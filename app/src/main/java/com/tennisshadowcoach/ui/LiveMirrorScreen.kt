package com.tennisshadowcoach.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import com.tennisshadowcoach.analysis.MirrorPhase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.TextButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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

/**
 * The mirror: live pose, framing frame, swing count, a verdict per swing (M3) and voice prompts (M4).
 * The phone stands 2.5–3 m away, so feedback is mainly audible: a beep for a clean swing and a short
 * spoken correction for a wrong one.
 */
@Composable
fun LiveMirrorScreen(leadingHand: LeadingHand, onBack: () -> Unit) {
    val context = LocalContext.current
    var poseState by remember { mutableStateOf(LivePoseState()) }
    var mirrored by remember { mutableStateOf(true) }
    val preferences = remember(context) { CapturePreferences(context) }
    var feedback by remember { mutableStateOf(preferences.loadFeedback()) }
    fun updateFeedback(updated: MirrorFeedback) {
        feedback = updated
        preferences.saveFeedback(updated)
    }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var listenStatus by remember { mutableStateOf<VoiceCommandListener.Status?>(null) }
    var heard by remember { mutableStateOf<String?>(null) }
    var commandListener: VoiceCommandListener? by remember { mutableStateOf(null) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentVoice by remember { mutableStateOf<String?>(null) }
    val voice = remember(context) {
        VoiceCoach(
            context,
            preferredVoice = feedback.voiceName,
            onStatus = { status -> voiceStatus = status },
            onVoices = { names, current -> voices = names; currentVoice = current },
            onSpeaking = { speaking -> commandListener?.setPaused(speaking) }
        )
    }
    DisposableEffect(voice) { onDispose { voice.close() } }
    // A short tone on each counted swing: the screen is too small to read from 2.5–3 m.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    var lastCount by remember { mutableStateOf(0) }
    val analyzer = remember(context, leadingHand) {
        LivePoseAnalyzer(context, leadingHand) { poseState = it }
    }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    LaunchedEffect(analyzer, voice) { analyzer.onPrompt = voice::speak }
    val listener = remember(context, analyzer) {
        VoiceCommandListener(
            context,
            onCommand = analyzer::handle,
            onStatus = { status -> listenStatus = status },
            onHeard = { text, final ->
                heard = text
                if (final) analyzer.logHeard(text)
            }
        )
    }
    DisposableEffect(listener) {
        commandListener = listener
        onDispose { listener.close() }
    }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) listenStatus = VoiceCommandListener.Status("Без доступа к микрофону голосовые команды не работают — управляй кнопками.")
    }
    LaunchedEffect(feedback.listen, micGranted) {
        when {
            !feedback.listen -> listener.stop()
            !micGranted -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> listener.start()
        }
    }
    LaunchedEffect(feedback) {
        analyzer.voiceEnabled = feedback.voice
        analyzer.framingPromptsEnabled = feedback.framingPrompts
        if (!feedback.voice) voice.stop()
    }
    BackHandler { analyzer.finishSession(); onBack() }
    // A short beep for a good swing.  A swing with a correction gets a low double tone, unless the
    // voice is on: then the spoken correction replaces it instead of talking over it.
    LaunchedEffect(poseState.lastSwingNumber) {
        if (poseState.calibrated && poseState.lastSwingNumber > lastCount && feedback.sound) {
            if (poseState.lastSwingCorrections.isEmpty()) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else if (!feedback.voice) tone?.startTone(ToneGenerator.TONE_PROP_NACK, 400)
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

    var settingsOpen by remember { mutableStateOf(false) }
    // Green: whole body visible (and, during a set, at the calibrated distance).
    // Amber: visible but away from the calibrated spot, so swings are not counted.
    // Red: head or feet cut off, or nobody in the frame.
    val training = poseState.phase == MirrorPhase.TRAINING
    val frameColor = when {
        poseState.framing != null -> Color(0xFFE53935)
        training && !poseState.inPosition -> Color(0xFFFFB300)
        else -> Color(0xFF43A047)
    }
    val frameText = when {
        poseState.framing != null -> poseState.framing!!.label
        training && !poseState.inPosition -> "Встань на место, где проходила калибровка"
        else -> "В кадре целиком"
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { analyzer.finishSession(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text("Зеркало", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Скрыть настройки" else "Настройки") }
        }

        // The preview takes almost the whole screen; the information sits on top of it so the
        // picture stays large.  The whole camera frame is shown (FIT_CENTER), as the model sees it.
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .border(6.dp, frameColor)
                .padding(6.dp)
                .clipToBounds()
        ) {
            LiveCameraPreview(analyzer, mirrored, Modifier.fillMaxSize())
            SkeletonOverlay(poseState, mirrored, Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color(0x99000000)).padding(8.dp)
            ) {
                Text(frameText, color = frameColor, style = MaterialTheme.typography.titleMedium)
                Text(poseState.phase.label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (feedback.listen && listenStatus == null) {
                    Text("Слышу: ${heard ?: "…"}", color = Color(0xFFB3E5FC), style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0x99000000)).padding(8.dp)
            ) {
                Text("Замахов: ${poseState.swingCount}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                if (poseState.lastSwingNumber > 0) {
                    val good = poseState.lastSwingCorrections.isEmpty()
                    Text(
                        if (good) "Замах ${poseState.lastSwingNumber}: ✓ с разворотом плеч"
                        else "Замах ${poseState.lastSwingNumber}: ✗ " + poseState.lastSwingCorrections.joinToString { it.title.lowercase() },
                        color = if (good) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Без замечаний: ${poseState.swingCount - poseState.correctedSwings} из ${poseState.swingCount}",
                        color = Color.White, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (settingsOpen) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(feedback.listen, { updateFeedback(feedback.copy(listen = !feedback.listen)) }, label = { Text("Микрофон") })
                FilterChip(feedback.voice, { updateFeedback(feedback.copy(voice = !feedback.voice)) }, label = { Text("Голос") })
                FilterChip(
                    feedback.framingPrompts,
                    { updateFeedback(feedback.copy(framingPrompts = !feedback.framingPrompts)) },
                    label = { Text("Подсказки о кадре") },
                    enabled = feedback.voice
                )
                FilterChip(feedback.sound, { updateFeedback(feedback.copy(sound = !feedback.sound)) }, label = { Text("Звук") })
                FilterChip(mirrored, { mirrored = !mirrored }, label = { Text("Зеркально") })
            }
            if (voices.isNotEmpty()) {
                Text("Голос подсказок (нажми, чтобы послушать):", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    voices.forEachIndexed { index, name ->
                        FilterChip(
                            name == currentVoice,
                            {
                                voice.useVoice(name)
                                currentVoice = name
                                updateFeedback(feedback.copy(voiceName = name))
                            },
                            label = { Text("${index + 1}") }
                        )
                    }
                }
            }
            Text(
                "Голосовые команды: «start calibration» · «ready» (или «finish calibration») · «start» · «stop»",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Обработка: ${"%.1f".format(poseState.framesPerSecond)} кадр/с · кадров: ${poseState.analysedFrames}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        voiceStatus?.takeIf { feedback.voice }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        listenStatus?.takeIf { feedback.listen }?.let { status ->
            Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        poseState.notice?.let {
            Text(
                it,
                color = if (poseState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // The same actions as the voice commands, for when the phone is within reach.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (poseState.phase) {
                MirrorPhase.IDLE -> Button({ analyzer.startCalibration() }, Modifier.weight(1f)) { Text("Старт калибровки") }
                MirrorPhase.CALIBRATING -> Button({ analyzer.completeCalibration() }, Modifier.weight(1f)) { Text("Завершить калибровку") }
                MirrorPhase.READY -> {
                    Button({ analyzer.startTraining() }, Modifier.weight(1f)) { Text("Старт подхода") }
                    OutlinedButton({ analyzer.startCalibration() }, Modifier.weight(1f)) { Text("Калибровать заново") }
                }
                MirrorPhase.TRAINING -> Button({ analyzer.stopTraining() }, Modifier.weight(1f)) { Text("Стоп") }
            }
        }
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
                // Show the whole camera frame (no crop): what is on screen is what the model sees.
                // Preview and analysis share the 4:3 aspect so the overlay lines up exactly.
                previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                val sameAspect = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    runCatching {
                        provider = future.get()
                        val preview = Preview.Builder().setResolutionSelector(sameAspect).build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setResolutionSelector(sameAspect)
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
        // The preview is letterboxed (FIT_CENTER): draw inside the same centred rectangle.
        val aspect = if (state.imageWidth > 0 && state.imageHeight > 0) state.imageWidth.toFloat() / state.imageHeight else 3f / 4f
        val drawWidth = minOf(size.width, size.height * aspect)
        val drawHeight = drawWidth / aspect
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        fun point(index: Int): Offset? = state.landmarks.getOrNull(index)?.let { landmark ->
            Offset(left + (if (mirrored) 1f - landmark.x else landmark.x) * drawWidth, top + landmark.y * drawHeight)
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
