package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.MemoryLayers
import com.osvin.aichallenge.data.MemoryRecord
import com.osvin.aichallenge.data.MemoryReport
import com.osvin.aichallenge.data.MemoryType

/**
 * Шторка памяти чата: что агент помнит сейчас и форма явной записи.
 *
 * Разделы идут в порядке каталога ([MemoryLayers.types]) и подписаны его же
 * названиями: своей таблицы типов у клиента нет, поэтому каталог — единственный
 * источник и выбора типа, и его подписи. В краткосрочном разделе записей не бывает:
 * это сообщения диалога, а не хранилище, и руками в него не пишут — поэтому там
 * строка отчёта последнего ответа, а его чип в форме недоступен.
 *
 * Своего состояния у шторки нет: набранный текст и выбранный тип живут на экране
 * чата, иначе они терялись бы при перерисовке списка сообщений.
 *
 * Стратегия стоит первой: она решает, уйдут ли записи в запрос вообще, поэтому её
 * выбор объясняет, зачем остальные разделы этой шторки.
 *
 * @param layers Снимок памяти активного чата.
 * @param report Отчёт последнего ответа; null — ответа ещё не было.
 * @param error Отказ сервера на запись или удаление; null — отказа не было.
 * @param selectedLayer Выбранный тип записи; null — в каталоге нет писаемых типов.
 * @param valueText Текст записи.
 * @param strategy Стратегия контекста активного чата: что из памяти уходит в модель.
 * @param onLayerSelected Выбор типа из каталога.
 * @param onValueChange Ввод текста записи.
 * @param onRemember Сохранение введённой записи.
 * @param onForget Удаление записи: тип и текст.
 * @param onStrategySelected Смена стратегии контекста активного чата.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemorySheet(
    layers: MemoryLayers,
    report: MemoryReport?,
    error: String?,
    selectedLayer: String?,
    valueText: String,
    strategy: ContextStrategy,
    onLayerSelected: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemember: () -> Unit,
    onForget: (String, String) -> Unit,
    onStrategySelected: (ContextStrategy) -> Unit,
    modifier: Modifier = Modifier
) {
    // Что из наполненных слоёв чат сейчас не отправит модели: долговременную память
    // читает только «Память агента», рабочую — ещё и «Скользящее окно»
    val silentLongTerm = layers.recordsOf(LONG_TERM_LAYER).isNotEmpty() &&
        strategy != ContextStrategy.MEMORY
    val silentWorking = layers.recordsOf(WORKING_LAYER).isNotEmpty() &&
        strategy != ContextStrategy.MEMORY && strategy != ContextStrategy.SLIDING_WINDOW

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Память агента",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Что чат отправляет модели",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Вид чипов тот же, что у типов памяти ниже; подписи — из каталога стратегий:
        // название в чипе и пояснение выбранной, как в форме создания чата
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContextStrategy.entries.forEach { option ->
                FilterChip(
                    selected = option == strategy,
                    onClick = { onStrategySelected(option) },
                    label = { Text(option.title) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        memoryText(strategy.hint)
        // О записях, которые уже лежат на сервере, говорим только там, где они есть и
        // где текущая стратегия их не читает: без записей строка пугала бы зря, а про
        // рабочую память при «Скользящем окне» она была бы неправдой
        if (silentLongTerm) {
            memoryText("Долговременную память отдаёт модели только стратегия «Память агента»: " +
                "записи лежат на сервере, но в запрос не уходят")
        }
        if (silentWorking) {
            memoryText("Рабочую память отдают модели «Память агента» и «Скользящее окно»: " +
                "записи лежат на сервере, но в этот запрос не уходят")
        }

        layers.types.forEach { type ->
            MemoryTypeSection(
                type = type,
                records = layers.recordsOf(type.layer),
                report = report,
                onForget = onForget
            )
        }

        Spacer(Modifier.height(16.dp))
        memoryText("Новая запись")
        // Пустой каталог — единственная причина, по которой запись недоступна не из-за
        // текста: без каталога нечего выбрать, и кнопка «Запомнить» выглядела бы сломанной
        if (layers.types.none { it.writable }) {
            memoryText("Каталог типов памяти не пришёл с сервера: записать некуда, пока нет снимка")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            layers.types.forEach { type ->
                FilterChip(
                    // Краткосрочную память пишет сам чат: в каталоге она помечена
                    // недоступной для записи, поэтому и чип недоступен
                    enabled = type.writable,
                    selected = type.writable && type.layer == selectedLayer,
                    onClick = { onLayerSelected(type.layer) },
                    label = { Text(type.label()) }
                )
            }
        }
        if (layers.types.any { !it.writable }) {
            Spacer(Modifier.height(4.dp))
            memoryText("Краткосрочную память пишет сам чат: это сообщения диалога, вручную в неё не записывают")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = valueText,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Запись") },
            supportingText = { Text("Одна фраза, которую надо запомнить") },
            maxLines = 3
        )
        // Записать можно только в писаемый тип каталога, и текст не должен быть пустым:
        // остальное сервер отвергнет, поэтому кнопка такое просто не отправляет
        val canWrite = layers.types.any { it.writable && it.layer == selectedLayer }
        Button(
            onClick = onRemember,
            enabled = canWrite && valueText.isNotBlank()
        ) {
            Text("Запомнить")
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Раздел типа памяти: подпись и пояснение из каталога, записи слоя и «✕» у каждой. */
@Composable
private fun MemoryTypeSection(
    type: MemoryType,
    records: List<MemoryRecord>,
    report: MemoryReport?,
    onForget: (String, String) -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = type.label(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (type.layer == SHORT_TERM_LAYER) {
        // Записей у краткосрочной памяти нет: это сообщения текущего запроса,
        // о них говорит только отчёт последнего ответа
        memoryText(
            report?.let { "${it.shortTermMessages} сообщ. в запросе, не ушло ${it.shortTermDropped}" }
                ?: "ответа агента ещё не было"
        )
        return
    }
    if (records.isEmpty()) {
        memoryText("пока пусто")
        return
    }
    records.forEach { record ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            memoryText(record.value, Modifier.weight(1f))
            TextButton(
                onClick = { onForget(record.layer, record.value) },
                modifier = Modifier.semantics {
                    contentDescription = "Забыть запись: ${record.value}"
                }
            ) {
                Text("✕", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Строка шторки памяти: тот же стиль, что у остальных подписей блока. */
@Composable
private fun memoryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Подпись типа памяти: название из каталога вместе с его пояснением из того же каталога. */
private fun MemoryType.label(): String = "${title.replaceFirstChar { it.uppercase() }} ($hint)"

/** Записи слоя снимка: краткосрочная память живёт сообщениями чата, в слоях её нет. */
private fun MemoryLayers.recordsOf(layer: String): List<MemoryRecord> = when (layer) {
    WORKING_LAYER -> working
    LONG_TERM_LAYER -> longTerm
    else -> emptyList()
}

/** Краткосрочная память: сообщения текущего диалога. */
private const val SHORT_TERM_LAYER = "short_term"

/** Рабочая память: данные текущей задачи, общие для всех чатов. */
private const val WORKING_LAYER = "working"

/** Долговременная память: профиль, решения, знания. */
private const val LONG_TERM_LAYER = "long_term"
