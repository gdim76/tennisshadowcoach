package com.tennisshadowcoach.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tennisshadowcoach.analysis.AnalysisConfig
import com.tennisshadowcoach.analysis.ImageLandmark
import com.tennisshadowcoach.analysis.VideoAnalysis
import com.tennisshadowcoach.analysis.smoothedLandmarks

@Composable
fun PoseDebugScreen(
    analysis: VideoAnalysis,
    onBack: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showSmoothed by remember { mutableStateOf(false) }
    val smoothed = remember(analysis) {
        smoothedLandmarks(analysis.frames, AnalysisConfig().smoothingWindowFrames)
    }
    val selectedFrame = analysis.frames[selectedIndex]
    val landmarks = if (showSmoothed) smoothed[selectedIndex] else selectedFrame.landmarks

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Column {
                Text("Отладка позы", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Кадр ${selectedFrame.index + 1} из ${analysis.frames.size} · ${selectedFrame.timestampUs / 1_000L} мс",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showSmoothed,
                onClick = { showSmoothed = false },
                label = { Text("Сырые точки") }
            )
            FilterChip(
                selected = showSmoothed,
                onClick = { showSmoothed = true },
                label = { Text("Сглаженные точки") }
            )
        }

        if (analysis.frames.size > 1) {
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = {
                    selectedIndex = it.toInt().coerceIn(0, analysis.frames.lastIndex)
                },
                valueRange = 0f..analysis.frames.lastIndex.toFloat()
            )
            Text(
                "Фон и скелет меняются синхронно при перемещении ползунка.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Кадр ${selectedFrame.index + 1}: ${selectedFrame.timestampUs / 1_000L} мс",
            style = MaterialTheme.typography.bodySmall
        )

        FrameWithPoseOverlay(
            previewJpeg = analysis.previewJpegs.getOrNull(selectedIndex),
            landmarks = landmarks,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            if (landmarks.any { it != null }) {
                "Зелёным показаны ландмарки с достаточной видимостью. Сглаживание — только для отладки; CSV всегда содержит сырые данные."
            } else {
                "В этом кадре поза не распознана. В CSV сохранена пустая строка координат для этого кадра."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FrameWithPoseOverlay(
    previewJpeg: ByteArray?,
    landmarks: List<ImageLandmark?>,
    modifier: Modifier = Modifier
) {
    val image = remember(previewJpeg) {
        previewJpeg?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    if (image == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Предпросмотр кадра недоступен") }
    } else {
        Box(
            modifier = modifier.aspectRatio(image.width.toFloat() / image.height),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Кадр записи",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
            PoseOverlay(landmarks, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PoseOverlay(landmarks: List<ImageLandmark?>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        SKELETON_CONNECTIONS.forEach { (start, end) ->
            val first = landmarks.getOrNull(start)?.takeIf { it.isVisibleForDebug() }
            val second = landmarks.getOrNull(end)?.takeIf { it.isVisibleForDebug() }
            if (first != null && second != null) {
                drawLine(
                    color = Color(0xFFE8FF4A),
                    start = androidx.compose.ui.geometry.Offset(first.x * size.width, first.y * size.height),
                    end = androidx.compose.ui.geometry.Offset(second.x * size.width, second.y * size.height),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
        landmarks.forEach { landmark ->
            if (landmark != null && landmark.isVisibleForDebug()) {
                drawCircle(
                    color = Color(0xFF00E676),
                    radius = 4.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        landmark.x * size.width,
                        landmark.y * size.height
                    )
                )
            }
        }
    }
}

private fun ImageLandmark.isVisibleForDebug(): Boolean = visibility == null || visibility >= 0.5f

private val SKELETON_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8,
    9 to 10, 11 to 12, 11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21,
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 11 to 23, 12 to 24,
    23 to 24, 23 to 25, 25 to 27, 27 to 29, 29 to 31, 24 to 26, 26 to 28,
    28 to 30, 30 to 32
)
