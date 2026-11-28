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

data class LivePoseState(
    val landmarks: List<ImageLandmark?> = List(33) { null },
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
    private val tracker = OnlineLiveSwingTracker(leadingHand)
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

    fun completeCalibration() = synchronized(lock) {
        try {
            val trackerState = tracker.completeCalibration()
            notice = "Калибровка готова: найдено замахов — ${trackerState.completedCount}. Дальше счёт идёт сам."
            noticeIsError = false
            logEvent("calibration", trackerState.completedCount)
            publish(lastState.copy(calibrated = true, swingCount = trackerState.completedCount, notice = notice, noticeIsError = false))
        } catch (error: Throwable) {
            notice = error.message ?: "Не удалось завершить калибровку."
            noticeIsError = true
            logEvent("calibration_failed", 0)
            publish(lastState.copy(notice = notice, noticeIsError = true))
        }
    }

    fun finishSession() = synchronized(lock) {
        val trackerState = tracker.finish()
        logEvent("finish", trackerState.completedCount)
        publish(lastState.copy(calibrated = trackerState.calibrated, swingCount = trackerState.completedCount))
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
        val trackerState = synchronized(lock) {
            landmarkLog?.println(CsvLandmarkExporter.row(PoseFrame(resultCount, timestampUs, image, world)))
            lastTimestampUs = timestampUs
            tracker.add(resultCount, timestampUs, image, world).also { state ->
                resultCount++
                if (state.justCompleted) {
                    lastSwingNumber = state.completedCount
                    lastSwingCorrections = state.corrections
                    if (state.corrections.isNotEmpty()) correctedSwings++
                    logEvent(if (state.corrections.isEmpty()) "swing" else "swing_" + state.corrections.joinToString("+") { it.name.lowercase() }, state.completedCount)
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (firstResultAtMs == 0L) firstResultAtMs = now
        val fps = resultCount * 1_000f / (now - firstResultAtMs).coerceAtLeast(1L)
        publish(
            LivePoseState(
                landmarks = image,
                framesPerSecond = fps,
                analysedFrames = resultCount,
                calibrated = trackerState.calibrated,
                swingCount = trackerState.completedCount,
                justCompletedSwing = trackerState.justCompleted,
                framing = FramingCheck.evaluate(image),
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
