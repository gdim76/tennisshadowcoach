package com.tennisshadowcoach.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tennisshadowcoach.analysis.LeadingHand
import com.tennisshadowcoach.analysis.LivePoseAnalyzer
import com.tennisshadowcoach.analysis.LivePoseState

/** M3 preparation: pose preview and repeat count only.  It intentionally has no coaching cues. */
@Composable
fun LiveMirrorScreen(leadingHand: LeadingHand, onBack: () -> Unit) {
    val context = LocalContext.current
    var poseState by remember { mutableStateOf(LivePoseState()) }
    var mirrored by remember { mutableStateOf(true) }
    val analyzer = remember(context, leadingHand) {
        LivePoseAnalyzer(context, leadingHand) { poseState = it }
    }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { analyzer.finishSession(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Зеркало — подготовка M3", style = MaterialTheme.typography.titleLarge)
                Text("Подсказки и оценка правила здесь выключены.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Box(Modifier.fillMaxWidth().height(500.dp)) {
            LiveCameraPreview(analyzer, mirrored, Modifier.fillMaxSize())
            SkeletonOverlay(poseState, mirrored, Modifier.fillMaxSize())
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = mirrored, onCheckedChange = { mirrored = it })
            Spacer(Modifier.width(8.dp))
            Text(if (mirrored) "Зеркальное превью включено" else "Зеркальное превью выключено")
        }
        Text("Обработка: ${"%.1f".format(poseState.framesPerSecond)} кадр/с · кадров: ${poseState.analysedFrames}")
        Text("Счётчик замахов: ${poseState.swingCount}${if (!poseState.calibrated) " · выполни 2–3 замаха для калибровки" else ""}")
        Button(
            onClick = { analyzer.completeCalibration() },
            enabled = !poseState.calibrated && poseState.analysedFrames > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Завершить калибровку после 2–3 замахов") }
        Text(
            "Метка «П» показывает landmark 16 (правое запястье человека), «Л» — landmark 15. " +
                "Зеркальность меняет только превью и отрисовку, не стороны модели.",
            style = MaterialTheme.typography.bodySmall
        )
        poseState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LiveCameraPreview(analyzer: LivePoseAnalyzer, mirrored: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var provider: ProcessCameraProvider? = null
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    runCatching {
                        provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                        provider!!.unbindAll()
                        provider!!.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    }
                }, executor)
            }
        },
        update = { it.scaleX = if (mirrored) -1f else 1f }
    )
    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }
}

@Composable
private fun SkeletonOverlay(state: LivePoseState, mirrored: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        fun point(index: Int): Offset? = state.landmarks.getOrNull(index)?.let { landmark ->
            Offset((if (mirrored) 1f - landmark.x else landmark.x) * size.width, landmark.y * size.height)
        }
        SKELETON_CONNECTIONS.forEach { (from, to) ->
            val a = point(from); val b = point(to)
            if (a != null && b != null) drawLine(Color(0xFF00E5FF), a, b, strokeWidth = 4f)
        }
        state.landmarks.forEachIndexed { index, landmark ->
            if (landmark != null) point(index)?.let { drawCircle(Color(0xFFFFD740), 5f, it, style = Stroke(2f)) }
        }
        point(16)?.let { drawCircle(Color(0xFFFF1744), 10f, it) }
        point(15)?.let { drawCircle(Color(0xFF2979FF), 10f, it) }
    }
}

private val SKELETON_CONNECTIONS = listOf(
    11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
    11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28
)
