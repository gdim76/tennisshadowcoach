package com.tennisshadowcoach.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.tennisshadowcoach.analysis.VoiceCommand
import com.tennisshadowcoach.analysis.VoiceCommandParser

/**
 * Listens for the mirror's spoken commands (M4) with the phone's on-device recogniser only: audio
 * never leaves the phone (project principle).  Commands are English: the phone has the English
 * on-device model, Russian is not installed, and nothing is downloaded by the app.  If on-device recognition is not available the
 * listener reports it and stays off; the buttons keep working.
 *
 * Android recognisers stop after each utterance, so listening is restarted in a loop.  It is
 * paused while the app itself speaks, otherwise it would hear its own prompts.
 */
class VoiceCommandListener(
    context: Context,
    private val onCommand: (VoiceCommand) -> Unit,
    private val onStatus: (Status?) -> Unit,
    /** What the recogniser heard (partial or final), shown on screen as feedback. */
    private val onHeard: (text: String, final: Boolean) -> Unit = { _, _ -> }
) : AutoCloseable {
    /** A problem to show on screen; the buttons always keep working. */
    data class Status(val message: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var paused = false
    private var listening = false
    private var lastCommand: VoiceCommand? = null
    private var lastCommandAt = 0L
    /** English variant asked from the recogniser; switched to the installed one (e.g. en-GB) if needed. */
    private var language = DEFAULT_LANGUAGE
    private var languageRetried = false

    /** Starts the loop; returns false when on-device recognition is not available. */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            onStatus(Status("Распознавание речи на телефоне недоступно — управляй кнопками."))
            return false
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { it.setRecognitionListener(listener) }
        }
        wanted = true
        onStatus(null)
        listen()
        return true
    }

    /**
     * After a language error: report which languages the on-device recogniser has, so the user sees
     * what is possible.  Nothing is downloaded.
     */
    private fun reportLanguages() {
        val target = recognizer
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onStatus(Status("На телефоне нет английского для офлайн-распознавания — управляй кнопками."))
            return
        }
        runCatching {
            target.checkRecognitionSupport(recognitionIntent(), { command -> main.post(command) }, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    // Use whichever English variant is installed (this phone has en-GB, not en-US).
                    val english = support.installedOnDeviceLanguages.firstOrNull { it.lowercase().startsWith(LANGUAGE_PREFIX) }
                    if (english != null && !languageRetried) {
                        languageRetried = true
                        language = english
                        wanted = true
                        onStatus(null)
                        listen()
                        return
                    }
                    val installed = support.installedOnDeviceLanguages.joinToString().ifEmpty { "нет" }
                    onStatus(Status("Офлайн-распознавание без английского (установлено: $installed) — управляй кнопками."))
                }

                override fun onError(error: Int) {
                    onStatus(Status("На телефоне нет английского для офлайн-распознавания (код $error) — управляй кнопками."))
                }
            })
        }.onFailure { onStatus(Status("На телефоне нет английского для офлайн-распознавания — управляй кнопками.")) }
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    fun stop() {
        wanted = false
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    /** Called while the app speaks (true) and when it has finished (false). */
    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            main.removeCallbacksAndMessages(null)
            if (listening) recognizer?.cancel()
            listening = false
        } else {
            restartSoon(300)
        }
    }

    override fun close() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!wanted || paused || listening) return
        val intent = recognitionIntent()
        listening = true
        runCatching { recognizer?.startListening(intent) }.onFailure {
            listening = false
            restartSoon(1_000)
        }
    }

    private fun restartSoon(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    /**
     * Only final results run commands: a partial "start" of "start calibration" would otherwise
     * start a set before the word "calibration" arrives.  Partial results are only shown.
     */
    private fun handle(results: Bundle?, final: Boolean) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        texts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { onHeard(it, final) }
        if (!final) return
        val command = VoiceCommandParser.parse(texts) ?: return
        val now = SystemClock.elapsedRealtime()
        // Partial and final results of one utterance carry the same command: act once.
        if (command == lastCommand && now - lastCommandAt < 2_000) return
        lastCommand = command
        lastCommandAt = now
        onCommand(command)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = handle(partialResults, final = false)

        override fun onResults(results: Bundle?) {
            handle(results, final = true)
            listening = false
            restartSoon(100)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wanted = false
                    onStatus(Status("Нет доступа к микрофону — разреши его в настройках приложения."))
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    wanted = false
                    reportLanguages()
                }
                // No speech, timeout, busy, client: normal in a listening loop.
                else -> restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_000 else 200)
            }
        }
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "en-GB"
        const val LANGUAGE_PREFIX = "en"
    }
}
