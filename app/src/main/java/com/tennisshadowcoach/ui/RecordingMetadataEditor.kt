package com.tennisshadowcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tennisshadowcoach.analysis.CaptureCamera
import com.tennisshadowcoach.analysis.CaptureRecordingAngle
import com.tennisshadowcoach.analysis.LeadingHand
import com.tennisshadowcoach.analysis.PhoneSide
import com.tennisshadowcoach.analysis.RecordingMetadata

/**
 * Shared editor used before capture and before analysing an existing recording.  Only the camera
 * and the leading hand are asked for; the view is measured from the pose.  The diagnostic block
 * lets the owner record the true view and manual counts so the measurements can be checked.
 */
@Composable
fun RecordingMetadataEditor(
    value: RecordingMetadata,
    onChange: (RecordingMetadata) -> Unit,
    cameraSelectable: Boolean = true
) {
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cameraSelectable) {
            MetadataChips("Камера", listOf(CaptureCamera.SELFIE, CaptureCamera.REAR), value.camera, { it.label }) {
                onChange(value.copy(camera = it))
            }
        }
        MetadataChips("Ведущая рука", listOf<LeadingHand?>(LeadingHand.RIGHT, LeadingHand.LEFT), value.leadingHand, {
            when (it) { LeadingHand.RIGHT -> "Правая"; LeadingHand.LEFT -> "Левая"; null -> "" }
        }) {
            onChange(value.copy(leadingHand = it))
        }
        Text(
            "Ракурс выбирать не нужно: поворот тела к камере приложение измерит само.",
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) {
            Text(if (diagnosticsOpen) "Скрыть поля для диагностики" else "Для диагностики (необязательно)")
        }
        if (diagnosticsOpen) {
            Text(
                "Эти поля нужны только для проверки автоматики: как стоял телефон на самом деле и сколько было замахов.",
                style = MaterialTheme.typography.bodySmall
            )
            MetadataChips("Ракурс на самом деле", CaptureRecordingAngle.entries, value.recordingAngle, { it.label }) {
                onChange(value.copy(recordingAngle = it))
            }
            MetadataChips("Телефон относительно человека", PhoneSide.entries, value.phoneSide, { it.label }) {
                onChange(value.copy(phoneSide = it))
            }
            Text("Ручной счёт: отдельно с паузами и серия подряд")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value.manualPausedSwings?.toString().orEmpty(),
                    onValueChange = { onChange(value.copy(manualPausedSwings = it.toIntOrNull())) },
                    label = { Text("С паузами") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = value.manualSeriesSwings?.toString().orEmpty(),
                    onValueChange = { onChange(value.copy(manualSeriesSwings = it.toIntOrNull())) },
                    label = { Text("Серия") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            value.expectedSwings?.let { Text("В metadata будет expected_swings: $it") }
        }
    }
}

@Composable
private fun <T> MetadataChips(
    title: String,
    choices: Iterable<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(selected == choice, { onSelect(choice) }, label = { Text(label(choice)) })
            }
        }
    }
}
