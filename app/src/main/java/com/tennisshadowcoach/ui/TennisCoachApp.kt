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
import com.tennisshadowcoach.analysis.LeadingHand
import com.tennisshadowcoach.analysis.RecordingAngle
import com.tennisshadowcoach.analysis.VideoAnalysis
import com.tennisshadowcoach.analysis.VideoPoseAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var showCamera by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var leadingHand by remember { mutableStateOf(LeadingHand.RIGHT) }
    var recordingAngle by remember { mutableStateOf(RecordingAngle.SIDE) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true
        else errorMessage = "Для записи видео нужен доступ к камере."
    }

    if (showCamera) {
        CameraRecordScreen(
            onBack = { showCamera = false },
            onVideoSaved = {
                videoUri = it
                analysis = null
                progress = null
                showCamera = false
            }
        )
        return
    }

    if (showDebug && analysis != null) {
        PoseDebugScreen(
            analysis = analysis!!,
            onBack = { showDebug = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tennis Shadow Coach", fontWeight = FontWeight.Bold)
                        Text("M0 · запись и сырые ландмарки", style = MaterialTheme.typography.labelSmall)
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

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Параметры M1", style = MaterialTheme.typography.titleMedium)
                    Text("Ведущая рука", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(leadingHand == LeadingHand.RIGHT, { leadingHand = LeadingHand.RIGHT }, label = { Text("Правая") })
                        FilterChip(leadingHand == LeadingHand.LEFT, { leadingHand = LeadingHand.LEFT }, label = { Text("Левая") })
                    }
                    Text("Ракурс", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(recordingAngle == RecordingAngle.SIDE, { recordingAngle = RecordingAngle.SIDE }, label = { Text("Сбоку") })
                        FilterChip(recordingAngle == RecordingAngle.FRONT_OR_45, { recordingAngle = RecordingAngle.FRONT_OR_45 }, label = { Text("Спереди / 45°") })
                    }
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

            videoUri?.let { selectedVideo ->
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
                        Button(
                            enabled = !analyzing,
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
                                                AnalysisConfig(leadingHand = leadingHand, recordingAngle = recordingAngle)
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

            analysis?.let { result ->
                AnalysisResultCard(
                    analysis = result,
                    onOpenDebug = { showDebug = true },
                    onShareCsv = { shareCsv(context, result.csvFile) }
                )
            }
        }
    }
}

@Composable
private fun AnalysisResultCard(
    analysis: VideoAnalysis,
    onOpenDebug: () -> Unit,
    onShareCsv: () -> Unit
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
                    "Замах ${index + 1}: ${swing.durationMs} мс · пик ${"%.2f".format(swing.peakSpeed)} торса/с",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Сырые image и world-координаты 33 точек сохранены в CSV рядом с записью.",
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
