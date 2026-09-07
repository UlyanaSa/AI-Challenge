package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.AnswerVariant
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.RunMode

/**
 * Шторка настройки вариантов ответа (кнопка ⚖ в панели ввода).
 *
 * Позволяет выбрать, какими способами решать вопрос:
 *  1. прямой ответ без дополнительных инструкций;
 *  2. инструкция «решай пошагово»;
 *  3. сначала промпт для решения, затем решение по нему;
 *  4. группа экспертов (аналитик, инженер, критик) — решение каждого.
 *
 * И способ запуска:
 *  - все последовательно — один отчёт, у каждого варианта основные характеристики
 *    (токены, скорость, длина, стоимость, глубина, точность) и вердикт судьи;
 *  - по отдельности — каждый выбранный вариант запускается отдельным ответом в чате.
 *
 * Если не выбран ни один вариант — чат работает как обычный:
 * один прямой ответ модели без инструкций и характеристик.
 *
 * @param initial Текущие настройки.
 * @param onApply Сохранение изменений.
 * @param onDismiss Закрытие шторки без сохранения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerVariantsSheet(
    initial: GenerationSettings,
    onApply: (GenerationSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf(initial.variants.toSet()) }
    var runMode by remember { mutableStateOf(initial.runMode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Варианты ответа",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "У каждого запущенного варианта выводятся основные характеристики: " +
                    "затраченные токены, скорость ответа, длина ответа, стоимость ответа, " +
                    "глубина объяснения и точность ответа.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Варианты ответа",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))

            AnswerVariant.entries.forEach { variant ->
                VariantCheckboxRow(
                    title = variant.title,
                    checked = variant in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) {
                            selected + variant
                        } else {
                            selected - variant
                        }
                    }
                )
            }

            if (selected.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Ни один вариант не выбран — обычный чат с одним прямым ответом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Запуск",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            RunMode.entries.forEach { mode ->
                RunModeRow(
                    label = mode.label,
                    checked = runMode == mode,
                    onClick = { runMode = mode }
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        selected = emptySet()
                        runMode = RunMode.SEQUENTIAL
                    }
                ) {
                    Text("Сбросить")
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        onApply(
                            GenerationSettings(
                                variants = AnswerVariant.entries.filter { it in selected },
                                runMode = runMode
                            )
                        )
                    }
                ) {
                    Text("Применить")
                }
            }
        }
    }
}

/**
 * Строка с чекбоксом выбора варианта ответа.
 */
@Composable
private fun VariantCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * Строка с выбором способа запуска.
 */
@Composable
private fun RunModeRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = checked,
            onClick = onClick
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
