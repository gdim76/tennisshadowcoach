package com.tennisshadowcoach.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tennisshadowcoach.analysis.AnalysisProgress
import com.tennisshadowcoach.analysis.AnalysisConfig
import com.tennisshadowcoach.analysis.CaptureRecordingAngle
import com.tennisshadowcoach.analysis.LeadingHand
import com.tennisshadowcoach.analysis.RecordingAngle
import com.tennisshadowcoach.analysis.RecordingMetadata
import com.tennisshadowcoach.analysis.VideoAnalysis
import com.tennisshadowcoach.analysis.VideoPoseAnalyzer
import com.tennisshadowcoach.coach.CoachSettingsStore
import com.tennisshadowcoach.coach.FocusRuleEvaluator
import com.tennisshadowcoach.coach.WeeklyFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TennisCoachApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var analysis by remember { mutableStateOf<VideoAnalysis?>(null) }
    var progress by remember { mutableStateOf<AnalysisProgress?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recordingNotice by remember { mutableStateOf<String?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var showLiveMirror by remember { mutableStateOf(false) }
    var openLiveAfterPermission by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showCoachSession by remember { mutableStateOf(false) }
    var recordingMetadata by remember { mutableStateOf(RecordingMetadata()) }
    val latestRecording = remember(context) { findLatestRecording(context) }
    val coachStore = remember(context) { CoachSettingsStore(context) }
    var coachDraft by remember { mutableStateOf(coachStore.load()) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && openLiveAfterPermission) {
            openLiveAfterPermission = false
            showLiveMirror = true
        } else if (granted) showCamera = true
        else errorMessage = "Для записи видео нужен доступ к камере."
    }

    if (showCamera) {
        CameraRecordScreen(
            metadata = recordingMetadata,
            onMetadataChange = { recordingMetadata = it },
            onBack = { showCamera = false },
            onVideoSaved = { uri, notice, metadata ->
                videoUri = uri
                recordingMetadata = metadata
                analysis = null
                progress = null
                recordingNotice = notice
                showCamera = false
            }
        )
        return
    }

    if (showLiveMirror) {
        val hand = recordingMetadata.leadingHand
        if (hand != null) {
            LiveMirrorScreen(leadingHand = hand, onBack = { showLiveMirror = false })
            return
        }
        showLiveMirror = false
    }

    if (showDebug && analysis != null) {
        PoseDebugScreen(
            analysis = analysis!!,
            onBack = { showDebug = false }
        )
        return
    }

    if (showCoachSession) {
        CoachSessionScreen(
            draft = coachDraft,
            onChange = { updated -> coachDraft = updated; coachStore.save(updated) },
            onBack = { showCoachSession = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tennis Shadow Coach", fontWeight = FontWeight.Bold)
                        Text("M2 · заметки и фокусы тренера", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Запись для разбора", style = MaterialTheme.typography.titleLarge)
                    Text("Сними 30–60 секунд. Камера должна видеть тело целиком; для бокового разбора ставь телефон перпендикулярно движению.")
                    Text(
                        "На этом этапе приложение сохраняет сырые точки тела и не оценивает технику.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(onClick = { showCoachSession = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть занятие и фокусы")
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Метаданные записи", style = MaterialTheme.typography.titleMedium)
                    Text("Не выбранные поля сохраняются как unspecified — приложение не подставляет ракурс или сторону молча.")
                    RecordingMetadataEditor(recordingMetadata, { recordingMetadata = it })
                }
            }

            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) showCamera = true else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Записать тренировку")
            }

            Button(
                enabled = recordingMetadata.leadingHand != null,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        showLiveMirror = true
                    } else {
                        openLiveAfterPermission = true
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Открыть зеркало (подготовка M3)") }
            if (recordingMetadata.leadingHand == null) {
                Text("Для зеркала сначала выбери ведущую руку в метаданных.", style = MaterialTheme.typography.bodySmall)
            }

            if (videoUri == null && latestRecording != null) {
                Button(
                    onClick = { videoUri = Uri.fromFile(latestRecording) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Открыть последнюю запись") }
            }

            videoUri?.let { selectedVideo ->
                val hand = recordingMetadata.leadingHand
                val analysisAngle = when (recordingMetadata.recordingAngle) {
                    CaptureRecordingAngle.SIDE -> RecordingAngle.SIDE
                    CaptureRecordingAngle.FRONT, CaptureRecordingAngle.ANGLE_45 -> RecordingAngle.FRONT_OR_45
                    CaptureRecordingAngle.UNSPECIFIED -> null
                }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text("Видео готово к анализу", fontWeight = FontWeight.SemiBold)
                        }
                        Text("Будет обработан каждый кадр видеодорожки с его исходной временной меткой.")
                        if (hand == null || analysisAngle == null) {
                            Text(
                                "Для разбора выбери ведущую руку и ракурс выше. До выбора metadata останется unspecified.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            enabled = !analyzing && hand != null && analysisAngle != null,
                            onClick = {
                                analyzing = true
                                progress = null
                                analysis = null
                                errorMessage = null
                                scope.launch {
                                    try {
                                        analysis = withContext(Dispatchers.IO) {
                                            VideoPoseAnalyzer(
                                                context,
                                                AnalysisConfig(
                                                    leadingHand = hand ?: LeadingHand.RIGHT,
                                                    recordingAngle = analysisAngle ?: RecordingAngle.SIDE,
                                                    recordingMetadata = recordingMetadata
                                                )
                                            ).analyze(selectedVideo) { update -> progress = update }
                                        }
                                    } catch (error: Throwable) {
                                        errorMessage = error.message ?: "Не удалось проанализировать видео."
                                    } finally {
                                        analyzing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Insights, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(if (analyzing) "Анализируем…" else "Анализировать каждый кадр")
                        }
                    }
                }
            }

            if (analyzing) {
                val currentProgress = progress
                LinearProgressIndicator(
                    progress = {
                        if (currentProgress == null || currentProgress.totalFrames == 0) 0f
                        else currentProgress.processedFrames.toFloat() / currentProgress.totalFrames
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    currentProgress?.let {
                        "${it.processedFrames} из ${it.totalFrames} кадров · ${"%.1f".format(it.framesPerSecond)} кадр/с"
                    } ?: "Подготавливаем видеодорожку…",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            recordingNotice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            analysis?.let { result ->
                AnalysisResultCard(
                    analysis = result,
                    focuses = coachDraft.focuses.filter { it.active },
                    onOpenDebug = { showDebug = true },
                    onShareCsv = { shareCsv(context, result.csvFile) },
                    onShareSwingsCsv = { shareCsv(context, result.swingsCsvFile) }
                )
            }
        }
    }
}

@Composable
private fun AnalysisResultCard(
    analysis: VideoAnalysis,
    focuses: List<WeeklyFocus>,
    onOpenDebug: () -> Unit,
    onShareCsv: () -> Unit,
    onShareSwingsCsv: () -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Результат анализа", style = MaterialTheme.typography.titleLarge)
            MetricRow("Обработано кадров", analysis.processedFrames.toString())
            MetricRow("Кадры с найденной позой", "${analysis.detectedPoseFrames} из ${analysis.processedFrames}")
            MetricRow("Скорость обработки", "${"%.1f".format(analysis.processingFps)} кадр/с")
            MetricRow("Найдено замахов", analysis.motion.swings.size.toString())
            analysis.motion.swings.forEachIndexed { index, swing ->
                Text(
                    "Замах ${index + 1}: ${swing.durationMs} мс · момент удара ${swing.impactFrame?.let { "кадр $it" } ?: "не найден"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (focuses.isNotEmpty()) {
                Text("Фокусы тренера", style = MaterialTheme.typography.titleMedium)
                focuses.forEach { focus ->
                    Text(focus.title, fontWeight = FontWeight.SemiBold)
                    val rule = focus.rule
                    if (rule == null) {
                        Text(
                            "Задание для отработки — без числовой проверки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FocusRuleEvaluator.evaluate(analysis.motion, rule).forEach { result ->
                            val value = result.value?.let { " · ${"%.0f".format(it)}°" }.orEmpty()
                            Text(
                                "Замах ${result.swingNumber}: ${result.status.label}$value",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.status.name == "OUT_OF_RANGE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Text(
                "Сырые точки и отдельный CSV замахов с метриками сохранены рядом с записью.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Открыть отладочный просмотр")
            }
            Button(onClick = onShareCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Экспортировать CSV")
            }
            Button(onClick = onShareSwingsCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Экспортировать CSV замахов")
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun shareCsv(context: android.content.Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(shareIntent, "Экспорт CSV"))
}

private fun findLatestRecording(context: android.content.Context): File? =
    File(context.getExternalFilesDir(null), "TennisShadowCoach")
        .listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }
        ?.maxByOrNull(File::lastModified)
