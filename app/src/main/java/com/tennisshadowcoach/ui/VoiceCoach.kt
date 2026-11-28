package com.tennisshadowcoach.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Speaks the mirror's prompts with the system speech engine in Russian (M4).  Works offline when a
 * Russian voice is installed (Samsung and Google engines ship one).  A new phrase interrupts the
 * current one: a late correction is worse than none.
 */
class VoiceCoach(
    context: Context,
    /** Voice saved by the user; null: the first offline male-looking voice, else the first Russian one. */
    private val preferredVoice: String?,
    private val onStatus: (String?) -> Unit,
    /** Offline Russian voices found, for the picker in the mirror settings. */
    private val onVoices: (List<String>, String?) -> Unit = { _, _ -> },
    /** True while a phrase is being spoken; the command listener pauses meanwhile. */
    private val onSpeaking: (Boolean) -> Unit = {}
) : AutoCloseable {
    @Volatile private var ready = false
    private val main = Handler(Looper.getMainLooper())
    /** Name of the voice in use, shown in the mirror settings. */
    @Volatile var voiceName: String? = null
        private set
    // The Samsung engine ships an offline male Russian voice (ru_ru_m00); if it is missing,
    // Android falls back to the default engine.
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        if (status != TextToSpeech.SUCCESS) {
            onStatus("Синтез речи недоступен на телефоне — голосовые подсказки выключены.")
            return@TextToSpeech
        }
        when (tts.setLanguage(Locale("ru", "RU"))) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                onStatus("Нет русского голоса: установи его в настройках «Синтез речи» телефона.")
            else -> {
                chooseMaleVoice()
                // A little slower and lower than the default: calmer for a coach.
                tts.setSpeechRate(0.95f)
                tts.setPitch(0.9f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { onSpeaking(true) } }
                    override fun onDone(utteranceId: String?) { main.post { onSpeaking(false) } }
                    @Deprecated("Required override")
                    override fun onError(utteranceId: String?) { main.post { onSpeaking(false) } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { onSpeaking(false) } }
                })
                ready = true
                onStatus(null)
            }
        }
    }, SAMSUNG_ENGINE)

    /**
     * Picks an offline Russian male voice.  Android does not expose a voice's gender, so it is
     * recognised by the engines' naming ("m00" in Samsung voices, "male" in some others).
     */
    private fun chooseMaleVoice() {
        val russian = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
            // The Google "star…" voices all sound the same (owner, 2026-11-28): one is enough to skip.
            .filterNot { it.name.contains("star") }
        Log.i(TAG, "Russian offline voices: " + russian.joinToString { it.name })
        val saved = russian.firstOrNull { it.name == preferredVoice }
        val male = russian.firstOrNull { voice -> MALE_MARKERS.any { voice.name.lowercase().contains(it) } }
        val chosen: Voice? = saved ?: male ?: russian.firstOrNull()
        if (chosen != null) {
            tts.voice = chosen
            voiceName = chosen.name
        }
        Log.i(TAG, "Voice in use: ${tts.voice?.name}")
        val names = russian.map { it.name }.sorted()
        main.post { onVoices(names, voiceName) }
    }

    /** Switches to [name] and says a sample phrase, so the user can hear it. */
    fun useVoice(name: String) {
        if (!ready) return
        val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == name } ?: return
        tts.voice = voice
        voiceName = voice.name
        speak("Нет замаха. Разверни плечи.")
    }

    fun speak(phrase: String) {
        if (!ready) return
        tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "prompt")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    private companion object {
        const val TAG = "VoiceCoach"
        const val SAMSUNG_ENGINE = "com.samsung.SMT"
        val MALE_MARKERS = listOf("m00", "m01", "male")
    }

    override fun close() {
        ready = false
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
