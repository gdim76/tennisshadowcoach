package com.tennisshadowcoach.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tennisshadowcoach.analysis.CaptureCamera
import com.tennisshadowcoach.analysis.RecordingMetadata
import java.io.File

@Composable
fun CameraRecordScreen(
    metadata: RecordingMetadata,
    onMetadataChange: (RecordingMetadata) -> Unit,
    onBack: () -> Unit,
    onVideoSaved: (Uri, String?, RecordingMetadata) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var recordingNow by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // A timed display lock must never stop a recording before the user can press Stop.
    // Restore the activity's previous preference as soon as this screen leaves composition.
    DisposableEffect(view) {
        val wasKeepingScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasKeepingScreenOn }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Запись упражнения", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Сначала явно укажи условия записи. Целевой режим — 30 кадров/с.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        RecordingMetadataEditor(metadata, onMetadataChange)

        val lensFacing = when (metadata.camera) {
            CaptureCamera.REAR -> CameraSelector.LENS_FACING_BACK
            CaptureCamera.SELFIE -> CameraSelector.LENS_FACING_FRONT
            CaptureCamera.UNSPECIFIED -> null
        }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text(
            "Поставь телефон неподвижно примерно в 2,5–3 м. В кадре должны быть голова, стопы и ракетка.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = videoCapture != null && lensFacing != null,
            onClick = {
                val capture = videoCapture ?: return@Button
                if (recordingNow) {
                    recording?.stop()
                } else {
                    val moviesDir = File(context.getExternalFilesDir(null), "TennisShadowCoach")
                    moviesDir.mkdirs()
                    val outputFile = File(moviesDir, "training_${System.currentTimeMillis()}.mp4")
                    recording = capture.output
                        .prepareRecording(
                            context,
                            FileOutputOptions.Builder(outputFile)
                                // Be explicit: recordings must not stop at a product-defined duration or size.
                                .setDurationLimitMillis(OutputOptions.DURATION_UNLIMITED.toLong())
                                .setFileSizeLimit(OutputOptions.FILE_SIZE_UNLIMITED.toLong())
                                .build()
                        )
                        .start(mainExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> recordingNow = true
                                is VideoRecordEvent.Finalize -> {
                                    recordingNow = false
                                    recording = null
                                    if (event.hasError()) {
                                        val reason = event.errorDescription()
                                        Log.w(
                                            CAMERA_LOG_TAG,
                                            "Recording finalized with ${event.error} ($reason), " +
                                                "bytes=${event.recordingStats.numBytesRecorded}, " +
                                                "durationMs=${event.recordingStats.recordedDurationNanos / 1_000_000}, " +
                                                "file=${outputFile.absolutePath}",
                                            event.cause
                                        )
                                        if (outputFile.isReadableVideo()) {
                                            onVideoSaved(
                                                Uri.fromFile(outputFile),
                                                "Камера завершила запись: $reason. Видеофайл сохранён и доступен для анализа.",
                                                metadata
                                            )
                                        } else {
                                            cameraError = "Запись не сохранена: $reason"
                                        }
                                    } else {
                                        onVideoSaved(Uri.fromFile(outputFile), null, metadata)
                                    }
                                }
                            }
                        }
                }
            }
        ) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (recordingNow) "Остановить запись" else "Начать запись")
        }

        if (lensFacing == null) {
            Text("Выбери камеру: иначе запись не начнётся, а metadata останется unspecified.")
        } else {
            Text("Предпросмотр камеры", style = MaterialTheme.typography.bodySmall)
            CameraPreview(
                lensFacing = lensFacing,
                onCaptureReady = { videoCapture = it },
                onError = { cameraError = it }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { recording?.stop() }
    }
}

private fun VideoRecordEvent.Finalize.errorDescription(): String = when (error) {
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "достигнут лимит размера файла"
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "недостаточно места в памяти"
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "камера перестала передавать кадры"
    VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "некорректные параметры сохранения"
    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "ошибка кодирования видео"
    VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "ошибка рекордера камеры"
    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "камера не передала корректные кадры"
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "достигнут лимит длительности"
    VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "объект записи был преждевременно освобождён"
    VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "неизвестная ошибка камеры"
    else -> "неизвестный код ошибки ($error)"
}

private fun File.isReadableVideo(): Boolean {
    if (!exists() || length() == 0L) return false
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.let { it > 0L }
            ?: false
    } catch (_: Throwable) {
        false
    } finally {
        retriever.release()
    }
}

private const val CAMERA_LOG_TAG = "TennisCamera"

@Composable
private fun CameraPreview(
    lensFacing: Int,
    onCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    key(lensFacing) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = surfaceProvider
                            }
                            val recorder = Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(Quality.HD))
                                .build()
                            val capture = VideoCapture.Builder(recorder)
                                .setTargetFrameRate(Range(30, 30))
                                .build()
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                                preview,
                                capture
                            )
                            onCaptureReady(capture)
                        } catch (error: Throwable) {
                            onError(error.message ?: "Не удалось запустить камеру.")
                        }
                    }, mainExecutor)
                }
            }
        )
    }
}
