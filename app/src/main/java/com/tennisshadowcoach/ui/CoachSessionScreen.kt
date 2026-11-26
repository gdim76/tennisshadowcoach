package com.tennisshadowcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tennisshadowcoach.coach.CoachSessionDraft
import com.tennisshadowcoach.coach.FocusMetric
import com.tennisshadowcoach.coach.FocusRule
import com.tennisshadowcoach.coach.SwingPhase
import com.tennisshadowcoach.coach.WeeklyFocus
import java.util.UUID

@Composable
fun CoachSessionScreen(
    draft: CoachSessionDraft,
    onChange: (CoachSessionDraft) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Text("Занятие", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = draft.dateIso,
            onValueChange = { onChange(draft.copy(dateIso = it)) },
            label = { Text("Дата (ГГГГ-ММ-ДД)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.coachNote,
            onValueChange = { onChange(draft.copy(coachNote = it)) },
            label = { Text("Заметки тренера") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Text("Фокусы для отработки (${draft.focuses.size}/3)", style = MaterialTheme.typography.titleMedium)
        draft.focuses.forEach { focus ->
            FocusEditor(
                focus = focus,
                onChange = { updated -> onChange(draft.copy(focuses = draft.focuses.map { if (it.id == focus.id) updated else it })) },
                onRemove = { onChange(draft.copy(focuses = draft.focuses.filterNot { it.id == focus.id })) }
            )
        }
        if (draft.focuses.size < 3) {
            Button(
                onClick = {
                    val focus = WeeklyFocus(
                        id = UUID.randomUUID().toString(),
                        title = "Новый фокус",
                        active = true,
                        rule = null
                    )
                    onChange(draft.copy(focuses = draft.focuses + focus))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Добавить фокус от тренера") }
        }
        Text(
            "Сформулируйте коротко: «ниже колени», «выше завершение», «устойчивее опора». Числовая проверка необязательна.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusEditor(focus: WeeklyFocus, onChange: (WeeklyFocus) -> Unit, onRemove: () -> Unit) {
    val rule = focus.rule
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = focus.title,
                    onValueChange = { onChange(focus.copy(title = it)) },
                    label = { Text("Задание тренера") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Switch(checked = focus.active, onCheckedChange = { onChange(focus.copy(active = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Проверять по данным", modifier = Modifier.weight(1f))
                Switch(
                    checked = rule != null,
                    onCheckedChange = { enabled ->
                        onChange(focus.copy(rule = if (enabled) {
                            FocusRule(FocusMetric.LEADING_KNEE, SwingPhase.PEAK_SPEED)
                        } else null))
                    }
                )
            }
            if (rule != null) {
                Text(
                    "Дополнительно: если тренер задал числовой диапазон, приложение покажет его для каждого замаха.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Метрика", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusMetric.entries.forEach { metric ->
                        FilterChip(rule.metric == metric, { onChange(focus.copy(rule = rule.copy(metric = metric))) }, label = { Text(metric.label) })
                    }
                }
                Text("Момент", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SwingPhase.entries.forEach { phase ->
                        FilterChip(rule.phase == phase, { onChange(focus.copy(rule = rule.copy(phase = phase))) }, label = { Text(phase.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rule.minimum?.toString().orEmpty(),
                        onValueChange = { onChange(focus.copy(rule = rule.copy(minimum = it.toFloatOrNull()))) },
                        label = { Text("Минимум, °") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rule.maximum?.toString().orEmpty(),
                        onValueChange = { onChange(focus.copy(rule = rule.copy(maximum = it.toFloatOrNull()))) },
                        label = { Text("Максимум, °") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = rule.hint,
                    onValueChange = { onChange(focus.copy(rule = rule.copy(hint = it))) },
                    label = { Text("Подсказка тренера") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Удалить фокус") }
        }
    }
}
