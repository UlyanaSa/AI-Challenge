package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.ResponseFormat

/**
 * Шторка настроек генерации ответа модели.
 * Вызывается из панели ввода чата и содержит:
 *  - ограничение длины ответа (максимальное количество токенов),
 *  - стоп-слово завершения генерации,
 *  - формат ответа: набор чекбоксов, активен ровно один,
 *    по умолчанию — «Свободная форма»,
 *  - количество прогонов одного и того же вопроса для сверки формата.
 *
 * @param initial Текущие настройки для предзаполнения формы.
 * @param onApply Сохранение изменённых настроек.
 * @param onDismiss Закрытие шторки без сохранения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationSettingsSheet(
    initial: GenerationSettings,
    onApply: (GenerationSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Черновик формы; применяется только по кнопке «Применить»
    var maxTokensText by remember { mutableStateOf(initial.maxTokens.toString()) }
    var stopText by remember { mutableStateOf(initial.stopWords.joinToString(", ")) }
    var responseFormat by remember { mutableStateOf(initial.responseFormat) }
    var runsText by remember { mutableStateOf(initial.runs.toString()) }
    var dogsOnly by remember { mutableStateOf(initial.dogsOnly) }

    val maxTokens = maxTokensText.toIntOrNull()
    val isMaxTokensValid = maxTokens != null && maxTokens in 1..GenerationSettings.MAX_TOKENS_LIMIT

    val runs = runsText.toIntOrNull()
    val isRunsValid = runs != null && runs in GenerationSettings.MIN_RUNS..GenerationSettings.MAX_RUNS

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
                text = "Параметры генерации",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(16.dp))

            // Поведение чата: отвечать только на вопросы о собаках
            Text(
                text = "Поведение чата",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { dogsOnly = !dogsOnly },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Отвечать только на вопросы о собаках",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Вне темы модель вежливо откажется отвечать.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dogsOnly,
                    onCheckedChange = { dogsOnly = it }
                )
            }

            Spacer(Modifier.height(20.dp))

            // Ограничение длины ответа = максимальное количество токенов
            OutlinedTextField(
                value = maxTokensText,
                onValueChange = { maxTokensText = it.filter { char -> char.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Максимум токенов") },
                supportingText = {
                    if (isMaxTokensValid) {
                        Text("Ограничивает длину ответа. При обрыве по лимиту сервер сам увеличит бюджет.")
                    } else {
                        Text("Целое число от 1 до ${GenerationSettings.MAX_TOKENS_LIMIT}")
                    }
                },
                isError = maxTokensText.isNotEmpty() && !isMaxTokensValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(16.dp))

            // Стоп-слово завершения генерации
            OutlinedTextField(
                value = stopText,
                onValueChange = { stopText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Стоп-слово завершения") },
                placeholder = { Text("Например: Конец") },
                supportingText = {
                    Text(
                        "Генерация остановится, когда модель начнёт выдавать это слово. " +
                            "Несколько слов — через запятую (до ${GenerationSettings.MAX_STOP_WORDS})."
                    )
                },
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))

            // Формат ответа — набор чекбоксов, активен ровно один вариант
            Text(
                text = "Формат ответа",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Заданный формат сверяется по каждому прогону.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            ResponseFormat.entries.forEach { format ->
                FormatCheckboxRow(
                    label = format.label,
                    checked = responseFormat == format,
                    onCheckedChange = { checked ->
                        responseFormat = if (checked) format else ResponseFormat.FREE_FORM
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // Количество прогонов одного и того же вопроса
            OutlinedTextField(
                value = runsText,
                onValueChange = { runsText = it.filter { char -> char.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Прогонов одного вопроса") },
                supportingText = {
                    if (isRunsValid) {
                        Text("Вопрос повторится $runs раз(а), ответы сверяются по формату.")
                    } else {
                        Text(
                            "Целое число от ${GenerationSettings.MIN_RUNS} " +
                                "до ${GenerationSettings.MAX_RUNS}"
                        )
                    }
                },
                isError = runsText.isNotEmpty() && !isRunsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        maxTokensText = GenerationSettings.DEFAULT_MAX_TOKENS.toString()
                        stopText = ""
                        responseFormat = ResponseFormat.FREE_FORM
                        runsText = GenerationSettings.DEFAULT_RUNS.toString()
                        dogsOnly = true
                    }
                ) {
                    Text("Сбросить")
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        onApply(
                            GenerationSettings(
                                maxTokens = maxTokens ?: GenerationSettings.DEFAULT_MAX_TOKENS,
                                stopWords = parseStopWords(stopText),
                                responseFormat = responseFormat,
                                runs = runs ?: GenerationSettings.DEFAULT_RUNS,
                                dogsOnly = dogsOnly
                            )
                        )
                    },
                    enabled = isMaxTokensValid && isRunsValid
                ) {
                    Text("Применить")
                }
            }
        }
    }
}

/**
 * Строка чекбокса формата ответа: кликабельны и сам чекбокс, и подпись.
 */
@Composable
private fun FormatCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Разбор текстового поля стоп-слов в список: по запятой, без пустых и повторных слов.
 */
private fun parseStopWords(raw: String): List<String> =
    raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(GenerationSettings.MAX_STOP_WORDS)
