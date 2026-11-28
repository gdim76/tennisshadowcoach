package com.tennisshadowcoach.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    var soundOn by remember { mutableStateOf(true) }
    // A short tone on each counted swing: the screen is too small to read from 2.5–3 m.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    var lastCount by remember { mutableStateOf(0) }
    val analyzer = remember(context, leadingHand) {
        LivePoseAnalyzer(context, leadingHand) { poseState = it }
    }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    BackHandler { analyzer.finishSession(); onBack() }
    // One short beep for a good swing, a low double tone for a swing with a correction.
    LaunchedEffect(poseState.lastSwingNumber) {
        if (poseState.calibrated && poseState.lastSwingNumber > lastCount && soundOn) {
            if (poseState.lastSwingCorrections.isEmpty()) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else tone?.startTone(ToneGenerator.TONE_PROP_NACK, 400)
        }
        lastCount = poseState.lastSwingNumber
    }
    // The phone stands 2.5–3 m away during practice: it must not lock while the mirror is open.
    val view = LocalView.current
    DisposableEffect(view) {
        val wasKeepingScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasKeepingScreenOn }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { analyzer.finishSession(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Зеркало — подготовка M3", style = MaterialTheme.typography.titleLarge)
                Text("Счёт замахов. Подсказки по правилам пока выключены.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Green: whole body visible (and, after calibration, at the working distance).
        // Amber: visible but not at the calibrated distance, so swings are not counted.
        // Red: head or feet cut off, or nobody in the frame.
        val frameColor = when {
            poseState.framing != null -> Color(0xFFE53935)
            poseState.calibrated && !poseState.inPosition -> Color(0xFFFFB300)
            else -> Color(0xFF43A047)
        }
        val frameText = when {
            poseState.framing != null -> poseState.framing!!.label
            poseState.calibrated && !poseState.inPosition -> "Встань на место, где проходила калибровка"
            poseState.calibrated -> "В кадре целиком — замахи считаются"
            else -> "В кадре целиком — сделай 2–3 замаха и заверши калибровку"
        }

        // The preview takes the remaining height and clips the skeleton, so the counter and the
        // calibration button always stay on screen (the column does not scroll).
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .border(6.dp, frameColor)
                .padding(6.dp)
                .clipToBounds()
        ) {
            LiveCameraPreview(analyzer, mirrored, Modifier.fillMaxSize())
            SkeletonOverlay(poseState, mirrored, Modifier.fillMaxSize())
        }
        Text(frameText, color = frameColor, style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Замахов: ${poseState.swingCount}",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Звук", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = soundOn, onCheckedChange = { soundOn = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Зеркально", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = mirrored, onCheckedChange = { mirrored = it })
                }
            }
        }
        if (poseState.lastSwingNumber > 0) {
            val good = poseState.lastSwingCorrections.isEmpty()
            Text(
                if (good) "Замах ${poseState.lastSwingNumber}: ✓ с разворотом плеч"
                else "Замах ${poseState.lastSwingNumber}: ✗ " + poseState.lastSwingCorrections.joinToString { it.title.lowercase() },
                color = if (good) Color(0xFF43A047) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleLarge
            )
            if (!good) Text(poseState.lastSwingCorrections.first().advice)
            Text(
                "Без замечаний: ${poseState.swingCount - poseState.correctedSwings} из ${poseState.swingCount}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        poseState.notice?.let {
            Text(it, color = if (poseState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Text(
            "Обработка: ${"%.1f".format(poseState.framesPerSecond)} кадр/с · кадров: ${poseState.analysedFrames}",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = { analyzer.completeCalibration() },
            enabled = !poseState.calibrated && poseState.analysedFrames > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Завершить калибровку после 2–3 замахов") }
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
