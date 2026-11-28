package com.tennisshadowcoach.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirror phases (M4).  Nothing is counted or corrected outside [TRAINING]: walking to the phone and
 * pressing buttons must not produce swings.  Commands come from buttons or the voice.
 */
enum class MirrorPhase(val label: String) {
    IDLE("Скажи «start calibration» или нажми кнопку"),
    CALIBRATING("Калибровка: сделай 2–3 замаха и скажи «ready»"),
    READY("Калибровка готова. Скажи «start», чтобы начать подход"),
    TRAINING("Подход идёт. Скажи «stop», чтобы закончить")
}

data class LivePoseState(
    val phase: MirrorPhase = MirrorPhase.IDLE,
    val landmarks: List<ImageLandmark?> = List(33) { null },
    /** Size of the upright frame the model saw; the overlay uses it to sit exactly on the preview. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val framesPerSecond: Float = 0f,
    val analysedFrames: Int = 0,
    val calibrated: Boolean = false,
    val swingCount: Int = 0,
    val justCompletedSwing: Boolean = false,
    /** Why the frame is not usable (head/feet cut, nobody); null when the whole body is visible. */
    val framing: FramingIssue? = FramingIssue.NO_PERSON,
    /** After calibration: standing at the working distance, i.e. swings are being counted. */
    val inPosition: Boolean = false,
    /** Number of the last counted swing and what to correct in it (empty: done well). */
    val lastSwingNumber: Int = 0,
    val lastSwingCorrections: List<SwingCorrection> = emptyList(),
    /** Counted swings that had at least one correction. */
    val correctedSwings: Int = 0,
    /** Calibration outcome or an error; it stays on screen until the next calibration attempt. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val error: String? = null
)

/** Android adapter for MediaPipe LIVE_STREAM.  It only emits pose and a repeat count. */
class LivePoseAnalyzer(
    context: Context,
    leadingHand: LeadingHand,
    private val onState: (LivePoseState) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val leadingHand = leadingHand
    /** A fresh tracker per calibration: frames before "start calibration" never reach it. */
    private var tracker = OnlineLiveSwingTracker(leadingHand)
    @Volatile private var phase = MirrorPhase.IDLE
    @Volatile private var imageWidth = 0
    @Volatile private var imageHeight = 0
    private val bitmaps = ConcurrentHashMap<Long, android.graphics.Bitmap>()
    private var firstResultAtMs = 0L
    private var resultCount = 0
    private var lastState = LivePoseState()
    @Volatile private var lastSwingNumber = 0
    @Volatile private var lastSwingCorrections: List<SwingCorrection> = emptyList()
    @Volatile private var correctedSwings = 0
    @Volatile private var notice: String? = null
    @Volatile private var noticeIsError = false

    /**
     * Every live session is kept as a landmark CSV in the same format as the offline export, plus
     * an events file (calibration, counted swings), so it can be replayed on a computer.
     */
    private val sessionStem = "live_${System.currentTimeMillis()}"
    private val sessionDir = File(context.getExternalFilesDir(null), "TennisShadowCoach").apply { mkdirs() }
    private val landmarkLog: PrintWriter? = runCatching {
        PrintWriter(File(sessionDir, "$sessionStem.csv").bufferedWriter()).also { it.println(CsvLandmarkExporter.header()) }
    }.getOrNull()
    private val eventLog: PrintWriter? = runCatching {
        PrintWriter(File(sessionDir, "$sessionStem.events.csv").bufferedWriter()).also { it.println("frame_index,timestamp_us,event,count") }
    }.getOrNull()
    private var lastTimestampUs = 0L
    /** The tracker and the logs are used from the UI thread (buttons) and the MediaPipe thread. */
    private val lock = Any()

    /** Voice prompts (M4): the policy decides, [onPrompt] speaks.  Both switches come from the UI. */
    private val voicePolicy = VoicePromptPolicy()
    @Volatile var onPrompt: ((String) -> Unit)? = null
    @Volatile var voiceEnabled = true
    @Volatile var framingPromptsEnabled = true

    private val landmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(PoseModelVariant.LITE.assetPath).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(.5f)
            .setMinPosePresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { error -> publish(lastState.copy(error = error.message ?: "Ошибка поиска позы.")) }
            .build()
    )

    override fun analyze(image: ImageProxy) {
        val timestampMs = SystemClock.uptimeMillis()
        // CameraX presents the front camera upright in PreviewView, but the analysis
        // buffer keeps the sensor orientation.  MediaPipe returns landmark coordinates
        // in the bitmap coordinate system, so rotate the pixels before inference rather
        // than trying to rotate the Canvas overlay afterwards.
        val bitmap = try {
            image.toBitmap().rotatedForDisplay(image.imageInfo.rotationDegrees)
        } catch (error: Throwable) {
            image.close()
            publish(lastState.copy(error = error.message ?: "Не удалось прочитать кадр камеры."))
            return
        }
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        try {
            bitmaps.put(timestampMs, bitmap)?.recycle()
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
        } catch (error: Throwable) {
            bitmaps.remove(timestampMs)?.recycle()
            publish(lastState.copy(error = error.message ?: "Не удалось обработать кадр камеры."))
        } finally {
            image.close()
        }
    }

    /** Recognised speech, kept in the session log to check commands afterwards. */
    fun logHeard(text: String) = synchronized(lock) {
        logEvent("heard:" + text.replace(',', ';'), lastSwingNumber)
    }

    /** Buttons and voice commands both end up here. */
    fun handle(command: VoiceCommand) {
        // Commands are read in the context of the phase: during calibration a bare "finish" or
        // "stop" means "finish the calibration", during a set "finish calibration" means "stop".
        val current = phase
        when {
            command == VoiceCommand.STOP_TRAINING && current == MirrorPhase.CALIBRATING -> completeCalibration()
            command == VoiceCommand.FINISH_CALIBRATION && current == MirrorPhase.TRAINING -> stopTraining()
            command == VoiceCommand.STOP_TRAINING && current != MirrorPhase.TRAINING ->
                prompt(if (current == MirrorPhase.IDLE) "Сначала скажи start calibration" else "Подход не идёт. Скажи start")
            command == VoiceCommand.START_CALIBRATION -> startCalibration()
            command == VoiceCommand.FINISH_CALIBRATION -> completeCalibration()
            command == VoiceCommand.START_TRAINING -> startTraining()
            command == VoiceCommand.STOP_TRAINING -> stopTraining()
        }
    }

    fun startCalibration() = synchronized(lock) {
        if (phase == MirrorPhase.TRAINING) finishSet()
        tracker = OnlineLiveSwingTracker(leadingHand)
        resetSetCounters()
        phase = MirrorPhase.CALIBRATING
        notice = null
        logEvent("calibration_start", 0)
        prompt("Калибровка. Сделай три замаха.")
        publishPhase()
    }

    fun completeCalibration() = synchronized(lock) {
        if (phase != MirrorPhase.CALIBRATING) {
            prompt(if (phase == MirrorPhase.IDLE) "Сначала скажи start calibration" else "Калибровка уже готова")
            return@synchronized
        }
        try {
            val trackerState = tracker.completeCalibration()
            phase = MirrorPhase.READY
            notice = "Калибровка готова: найдено замахов — ${trackerState.completedCount}. Скажи «start»."
            noticeIsError = false
            logEvent("calibration", trackerState.completedCount)
            prompt(voicePolicy.onCalibration(SystemClock.elapsedRealtime(), success = true) + ". Скажи start.")
        } catch (error: Throwable) {
            // Stay in calibration: the user can make more swings and finish again.
            notice = error.message ?: "Не удалось завершить калибровку."
            noticeIsError = true
            logEvent("calibration_failed", 0)
            prompt(voicePolicy.onCalibration(SystemClock.elapsedRealtime(), success = false))
        }
        publishPhase()
    }

    fun startTraining() = synchronized(lock) {
        when (phase) {
            MirrorPhase.IDLE, MirrorPhase.CALIBRATING -> {
                prompt(if (phase == MirrorPhase.IDLE) "Сначала скажи start calibration" else "Сначала скажи ready")
                return@synchronized
            }
            MirrorPhase.TRAINING -> return@synchronized
            MirrorPhase.READY -> Unit
        }
        tracker.startNewSet()
        resetSetCounters()
        phase = MirrorPhase.TRAINING
        notice = null
        logEvent("set_start", 0)
        prompt("Старт")
        publishPhase()
    }

    fun stopTraining() = synchronized(lock) {
        if (phase != MirrorPhase.TRAINING) return@synchronized
        val total = finishSet()
        phase = MirrorPhase.READY
        val clean = total - correctedSwings
        notice = "Подход: замахов $total, без замечаний $clean."
        noticeIsError = false
        prompt("Стоп. Замахов $total, без замечаний $clean.")
        publishPhase()
    }

    /** Leaving the mirror: an open set is closed so its log is complete. */
    fun finishSession() = synchronized(lock) {
        val total = if (phase == MirrorPhase.TRAINING) finishSet() else lastState.swingCount
        logEvent("finish", total)
        publish(lastState.copy(swingCount = total))
    }

    private fun finishSet(): Int {
        val state = tracker.finish()
        if (state.justCompleted) {
            lastSwingNumber = state.completedCount
            if (state.corrections.isNotEmpty()) correctedSwings++
        }
        logEvent("set_stop", state.completedCount)
        return state.completedCount
    }

    private fun resetSetCounters() {
        lastSwingNumber = 0
        lastSwingCorrections = emptyList()
        correctedSwings = 0
    }

    private fun publishPhase() {
        publish(
            lastState.copy(
                phase = phase,
                calibrated = phase == MirrorPhase.READY || phase == MirrorPhase.TRAINING,
                swingCount = if (phase == MirrorPhase.TRAINING) 0 else lastState.swingCount,
                lastSwingNumber = lastSwingNumber,
                lastSwingCorrections = lastSwingCorrections,
                correctedSwings = correctedSwings,
                notice = notice,
                noticeIsError = noticeIsError
            )
        )
    }

    private fun onResult(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        bitmaps.remove(result.timestampMs())?.recycle()
        val image = result.landmarks().firstOrNull().orEmpty().toFixedSize { landmark ->
            ImageLandmark(landmark.x(), landmark.y(), landmark.z(), landmark.visibility().orElse(null))
        }
        val world = result.worldLandmarks().firstOrNull().orEmpty().toFixedSize { landmark ->
            WorldLandmark(landmark.x(), landmark.y(), landmark.z())
        }
        val timestampUs = result.timestampMs() * 1_000L
        val framing = FramingCheck.evaluate(image)
        val trackerState = synchronized(lock) {
            landmarkLog?.println(CsvLandmarkExporter.row(PoseFrame(resultCount, timestampUs, image, world)))
            lastTimestampUs = timestampUs
            val now = SystemClock.elapsedRealtime()
            // Only calibration and an active set feed the tracker; idle and between sets nothing
            // is counted.  Framing prompts are useful whenever a phase has been started.
            val counting = phase == MirrorPhase.CALIBRATING || phase == MirrorPhase.TRAINING
            if (!counting) {
                resultCount++
                return@synchronized OnlineLiveSwingTracker.State(phase == MirrorPhase.READY, lastState.swingCount, false)
            }
            tracker.add(resultCount, timestampUs, image, world).also { state ->
                resultCount++
                if (state.justCompleted && phase == MirrorPhase.TRAINING) {
                    lastSwingNumber = state.completedCount
                    lastSwingCorrections = state.corrections
                    if (state.corrections.isNotEmpty()) correctedSwings++
                    logEvent(if (state.corrections.isEmpty()) "swing" else "swing_" + state.corrections.joinToString("+") { it.name.lowercase() }, state.completedCount)
                    if (voiceEnabled) voicePolicy.onSwing(now, state.corrections)?.let(::prompt)
                } else if (phase == MirrorPhase.TRAINING && voiceEnabled && framingPromptsEnabled) {
                    // Framing is spoken only during a set; before it the owner is still setting up
                    // or walking to the phone, and the screen shows the reason anyway.
                    val phrase = VoicePromptPolicy.framingPhrase(framing, state.calibrated, state.inPosition)
                    voicePolicy.onFrame(now, phrase)?.let(::prompt)
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (firstResultAtMs == 0L) firstResultAtMs = now
        val fps = resultCount * 1_000f / (now - firstResultAtMs).coerceAtLeast(1L)
        publish(
            LivePoseState(
                phase = phase,
                landmarks = image,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                framesPerSecond = fps,
                analysedFrames = resultCount,
                calibrated = phase == MirrorPhase.READY || phase == MirrorPhase.TRAINING,
                swingCount = if (phase == MirrorPhase.TRAINING) trackerState.completedCount else lastState.swingCount,
                justCompletedSwing = trackerState.justCompleted,
                framing = framing,
                inPosition = trackerState.inPosition,
                lastSwingNumber = lastSwingNumber,
                lastSwingCorrections = lastSwingCorrections,
                correctedSwings = correctedSwings,
                notice = notice,
                noticeIsError = noticeIsError
            )
        )
    }

    private fun <T, R> List<T>.toFixedSize(transform: (T) -> R): List<R?> =
        List(33) { index -> getOrNull(index)?.let(transform) }

    private fun publish(state: LivePoseState) {
        lastState = state
        mainHandler.post { onState(state) }
    }

    private fun prompt(phrase: String) {
        if (!voiceEnabled) return
        voicePolicy.markSpoken(SystemClock.elapsedRealtime())
        // Commas would break the events CSV.
        logEvent("voice:" + phrase.replace(',', ';'), lastSwingNumber)
        onPrompt?.invoke(phrase)
    }

    private fun logEvent(event: String, count: Int) {
        eventLog?.println("${resultCount - 1},$lastTimestampUs,$event,$count")
        eventLog?.flush()
    }

    override fun close() {
        runCatching { landmarker.close() }
        synchronized(lock) {
            runCatching { landmarkLog?.close() }
            runCatching { eventLog?.close() }
        }
        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
    }

    private fun Bitmap.rotatedForDisplay(rotationDegrees: Int): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return this
        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        recycle()
        return rotated
    }
}
