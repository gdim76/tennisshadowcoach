package com.tennisshadowcoach.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import kotlin.math.max

enum class PoseModelVariant(val assetPath: String) {
    LITE("pose_landmarker_lite.task"),
    FULL("pose_landmarker_full.task")
}

/** Values used by the M0 data-capture pipeline. Change them in one place. */
data class AnalysisConfig(
    val modelVariant: PoseModelVariant = PoseModelVariant.LITE,
    val modelAssetPath: String = modelVariant.assetPath,
    val detectionConfidence: Float = 0.5f,
    val visibilityThreshold: Float = 0.5f,
    val outlierMedianWindowFrames: Int = 7,
    val outlierMedianDeviationTorsoLengths: Float = 0.35f,
    val maxInterpolationGapFrames: Int = 6,
    val speedSmoothingWindowFrames: Int = 5,
    val angleSmoothingWindowFrames: Int = 9,
    val swingCoordinateSmoothingWindowFrames: Int = 9,
    val frontFrac: Float = 0.6f,
    val backFrac: Float = 0.25f,
    val inPositionTorsoTolerance: Float = 0.25f,
    val minInPositionSeconds: Float = 1.0f,
    val minSwingSpeedFraction: Float = 0.3f,
    val leadingHand: LeadingHand = LeadingHand.RIGHT,
    val recordingAngle: RecordingAngle = RecordingAngle.SIDE,
    val recordingMetadata: RecordingMetadata = RecordingMetadata()
)

data class AnalysisProgress(
    val processedFrames: Int,
    val totalFrames: Int,
    val timestampUs: Long,
    val framesPerSecond: Float
)

data class VideoAnalysis(
    val frames: List<PoseFrame>,
    /** Compact previews captured from the exact frames passed to MediaPipe. */
    val previewJpegs: List<ByteArray?>,
    val csvFile: File,
    val metadataFile: File,
    val swingsCsvFile: File,
    val elapsedSeconds: Float,
    val processingFps: Float,
    val motion: MotionAnalysis
) {
    val processedFrames: Int get() = frames.size
    val detectedPoseFrames: Int get() = frames.count { frame -> frame.landmarks.any { it != null } }
}

/**
 * M0 offline analyser. MediaExtractor supplies timestamps for every encoded video frame;
 * those timestamps are also passed to MediaPipe VIDEO mode and written to the CSV.
 */
class VideoPoseAnalyzer(
    private val context: Context,
    private val config: AnalysisConfig = AnalysisConfig()
) {
    fun analyze(
        videoUri: Uri,
        onProgress: (AnalysisProgress) -> Unit = {}
    ): VideoAnalysis {
        val timestampsUs = readVideoSampleTimestamps(videoUri)
        require(timestampsUs.isNotEmpty()) { "В видео не найдено кадров." }

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(config.modelAssetPath).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(config.detectionConfidence)
            .setMinPosePresenceConfidence(config.detectionConfidence)
            .setMinTrackingConfidence(config.detectionConfidence)
            .build()

        val exportFiles = exportDestinations(videoUri)
        val csvFile = exportFiles.csvFile
        val startedAtNs = System.nanoTime()
        val frames = ArrayList<PoseFrame>(timestampsUs.size)
        val previewJpegs = ArrayList<ByteArray?>(timestampsUs.size)
        val retriever = MediaMetadataRetriever()
        val landmarker = PoseLandmarker.createFromOptions(context, options)
        var lastMediaPipeTimestampMs = -1L

        try {
            retriever.setDataSource(context, videoUri)
            val videoMetadata = VideoMetadata(
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            )
            PrintWriter(csvFile).use { csv ->
                csv.println(CsvLandmarkExporter.header())
                timestampsUs.forEachIndexed { index, timestampUs ->
                    val bitmap = retriever.getFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    val frame = if (bitmap == null) {
                        previewJpegs += null
                        emptyFrame(index, timestampUs)
                    } else {
                        try {
                            previewJpegs += bitmap.toDebugPreviewJpeg()
                            val argb = bitmap.asArgb8888()
                            try {
                                // VIDEO mode requires monotonic millisecond timestamps.
                                val timestampMs = max(timestampUs / 1_000L, lastMediaPipeTimestampMs + 1L)
                                lastMediaPipeTimestampMs = timestampMs
                                val result = landmarker.detectForVideo(
                                    BitmapImageBuilder(argb).build(),
                                    timestampMs
                                )
                                result.toPoseFrame(index, timestampUs)
                            } finally {
                                if (argb !== bitmap) argb.recycle()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    frames += frame
                    csv.println(CsvLandmarkExporter.row(frame))

                    val elapsedSeconds = (System.nanoTime() - startedAtNs) / NANOS_PER_SECOND
                    onProgress(
                        AnalysisProgress(
                            processedFrames = index + 1,
                            totalFrames = timestampsUs.size,
                            timestampUs = timestampUs,
                            framesPerSecond = if (elapsedSeconds == 0f) 0f else (index + 1) / elapsedSeconds
                        )
                    )
                }
            }
            AnalysisMetadataExporter.write(exportFiles.metadataFile, videoMetadata, config)
        } catch (error: Throwable) {
            csvFile.delete()
            exportFiles.metadataFile.delete()
            throw error
        } finally {
            retriever.release()
            landmarker.close()
        }

        val motion = MotionMetricsEngine.analyze(
            frames = frames,
            leadingHand = config.leadingHand,
            recordingAngle = config.recordingAngle,
            config = MetricsConfig(
                visibilityThreshold = config.visibilityThreshold,
                outlierMedianWindowFrames = config.outlierMedianWindowFrames,
                outlierMedianDeviationTorsoLengths = config.outlierMedianDeviationTorsoLengths,
                maxInterpolationGapFrames = config.maxInterpolationGapFrames,
                speedSmoothingWindowFrames = config.speedSmoothingWindowFrames,
                angleSmoothingWindowFrames = config.angleSmoothingWindowFrames,
                swingCoordinateSmoothingWindowFrames = config.swingCoordinateSmoothingWindowFrames,
                frontFrac = config.frontFrac,
                backFrac = config.backFrac,
                inPositionTorsoTolerance = config.inPositionTorsoTolerance,
                minInPositionSeconds = config.minInPositionSeconds,
                minSwingSpeedFraction = config.minSwingSpeedFraction
            )
        )
        val swingsCsvFile = File(csvFile.parentFile, "swings_${csvFile.nameWithoutExtension}.csv")
        SwingCsvExporter.write(swingsCsvFile, motion, frames)
        val elapsedSeconds = (System.nanoTime() - startedAtNs) / NANOS_PER_SECOND
        return VideoAnalysis(
            frames = frames,
            previewJpegs = previewJpegs,
            csvFile = csvFile,
            metadataFile = exportFiles.metadataFile,
            swingsCsvFile = swingsCsvFile,
            elapsedSeconds = elapsedSeconds,
            processingFps = if (elapsedSeconds == 0f) 0f else frames.size / elapsedSeconds,
            motion = motion
        )
    }

    private fun readVideoSampleTimestamps(videoUri: Uri): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("В файле нет видеодорожки.")
            extractor.selectTrack(trackIndex)

            return buildList {
                while (extractor.sampleTrackIndex == trackIndex) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }.filter { it >= 0L }
        } finally {
            extractor.release()
        }
    }

    private fun exportDestinations(videoUri: Uri): ExportFiles {
        val videoFile = videoUri.path?.let(::File)?.takeIf { videoUri.scheme == "file" }
        val directory = videoFile?.parentFile
            ?: File(context.getExternalFilesDir(null), "TennisShadowCoach")
        directory.mkdirs()
        val prefix = "landmarks_${System.currentTimeMillis()}"
        return ExportFiles(
            csvFile = File(directory, "$prefix.csv"),
            metadataFile = File(directory, "$prefix.metadata.json")
        )
    }

    private fun Bitmap.asArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

    /**
     * Keeps the debug viewer responsive while the slider moves. Full-resolution video
     * remains on disk; previews are intentionally small and exist only in memory.
     */
    private fun Bitmap.toDebugPreviewJpeg(): ByteArray {
        val previewWidth = DEBUG_PREVIEW_WIDTH_PX.coerceAtMost(width)
        val previewHeight = (height * (previewWidth.toFloat() / width)).toInt().coerceAtLeast(1)
        val preview = if (previewWidth == width) this else {
            Bitmap.createScaledBitmap(this, previewWidth, previewHeight, true)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(preview.compress(CompressFormat.JPEG, DEBUG_PREVIEW_JPEG_QUALITY, output)) {
                    "Не удалось создать предпросмотр кадра."
                }
                output.toByteArray()
            }
        } finally {
            if (preview !== this) preview.recycle()
        }
    }

    private fun emptyFrame(index: Int, timestampUs: Long) = PoseFrame(
        index = index,
        timestampUs = timestampUs,
        landmarks = List(LANDMARK_COUNT) { null },
        worldLandmarks = List(LANDMARK_COUNT) { null }
    )

    private fun com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult.toPoseFrame(
        index: Int,
        timestampUs: Long
    ): PoseFrame {
        val image = landmarks().firstOrNull().orEmpty()
        val world = worldLandmarks().firstOrNull().orEmpty()
        return PoseFrame(
            index = index,
            timestampUs = timestampUs,
            landmarks = image.toFixedSize { landmark -> landmark.toImageLandmark() },
            worldLandmarks = world.toFixedSize { landmark -> landmark.toWorldLandmark() }
        )
    }

    private fun NormalizedLandmark.toImageLandmark() = ImageLandmark(
        x = x(), y = y(), z = z(), visibility = visibility().orElse(null)
    )

    private fun Landmark.toWorldLandmark() = WorldLandmark(x = x(), y = y(), z = z())

    private fun <T, R> List<T>.toFixedSize(transform: (T) -> R): List<R?> =
        List(LANDMARK_COUNT) { index -> getOrNull(index)?.let(transform) }

    private companion object {
        const val LANDMARK_COUNT = 33
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val DEBUG_PREVIEW_WIDTH_PX = 320
        const val DEBUG_PREVIEW_JPEG_QUALITY = 70
    }
}

private data class ExportFiles(val csvFile: File, val metadataFile: File)

private data class VideoMetadata(
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int
)

/** Sidecar data makes a raw landmark CSV reproducible outside the app. */
private object AnalysisMetadataExporter {
    fun write(file: File, video: VideoMetadata, config: AnalysisConfig) {
        file.writeText(
            """
            {
              "video": {
                "width": ${video.width.jsonNumber()},
                "height": ${video.height.jsonNumber()},
                "rotation_degrees": ${video.rotationDegrees}
              },
              "pose_model": "${config.modelVariant.name.lowercase()}",
              "camera": "${config.recordingMetadata.camera.wireValue}",
              "recording_angle": "${config.recordingMetadata.recordingAngle.wireValue}",
              "phone_side": "${config.recordingMetadata.phoneSide.wireValue}",
              "leading_hand": "${config.recordingMetadata.leadingHand?.name?.lowercase() ?: "unspecified"}",
              "expected_swings": ${config.recordingMetadata.expectedSwings.jsonNumber()},
              "manual_counts": {
                "with_pauses": ${config.recordingMetadata.manualPausedSwings.jsonNumber()},
                "continuous": ${config.recordingMetadata.manualSeriesSwings.jsonNumber()}
              },
              "config": {
                "visibility_threshold": ${config.visibilityThreshold},
                "outlier_median_window_frames": ${config.outlierMedianWindowFrames},
                "outlier_median_deviation_torso_lengths": ${config.outlierMedianDeviationTorsoLengths},
                "max_interpolation_gap_frames": ${config.maxInterpolationGapFrames},
                "speed_smoothing_window_frames": ${config.speedSmoothingWindowFrames},
                "angle_smoothing_window_frames": ${config.angleSmoothingWindowFrames},
                "swing_coordinate_smoothing_window_frames": ${config.swingCoordinateSmoothingWindowFrames},
                "front_frac": ${config.frontFrac},
                "back_frac": ${config.backFrac},
                "in_position_torso_tolerance": ${config.inPositionTorsoTolerance},
                "min_in_position_seconds": ${config.minInPositionSeconds},
                "min_swing_speed_fraction": ${config.minSwingSpeedFraction},
                "leading_hand": "${config.recordingMetadata.leadingHand?.name?.lowercase() ?: "unspecified"}",
                "recording_angle": "${config.recordingMetadata.recordingAngle.wireValue}"
              }
            }
            """.trimIndent()
        )
    }

    private fun Int?.jsonNumber(): String = this?.toString() ?: "null"
}

/** CSV is intentionally flat so it can be opened directly in a spreadsheet. */
object CsvLandmarkExporter {
    private const val LANDMARK_COUNT = 33

    fun header(): String = buildList {
        add("frame_index")
        add("timestamp_us")
        repeat(LANDMARK_COUNT) { index ->
            addAll(listOf("image_${index}_x", "image_${index}_y", "image_${index}_z", "image_${index}_visibility"))
            addAll(listOf("world_${index}_x", "world_${index}_y", "world_${index}_z"))
        }
    }.joinToString(",")

    fun row(frame: PoseFrame): String = buildList {
        add(frame.index.toString())
        add(frame.timestampUs.toString())
        repeat(LANDMARK_COUNT) { index ->
            val image = frame.landmarks.getOrNull(index)
            add(image?.x.csv())
            add(image?.y.csv())
            add(image?.z.csv())
            add(image?.visibility.csv())
            val world = frame.worldLandmarks.getOrNull(index)
            add(world?.x.csv())
            add(world?.y.csv())
            add(world?.z.csv())
        }
    }.joinToString(",")

    private fun Float?.csv(): String = this?.toString().orEmpty()
}
