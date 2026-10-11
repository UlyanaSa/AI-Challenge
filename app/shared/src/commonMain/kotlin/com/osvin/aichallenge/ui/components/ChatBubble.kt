package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.ToolCallRecord

/**
 * Сообщение ленты чата.
 *
 * Текст печатается разобранной разметкой ([MessageText]), а не как есть: модели отвечают
 * Markdown, и звёздочки жирного шрифта в ленте выглядели бы испорченным текстом.
 *
 * Печатается блоком во всю ширину строки, а не пузырём по размеру текста: пузырь —
 * язык мобильного мессенджера, где лента узкая, а сообщения короткие. В браузере окно
 * широкое и ответ модели длинный: пузырь шириной 300 dp оставлял бы справа пустое поле
 * и рвал бы абзац на короткие строки. Поэтому роль участника названа строкой над текстом,
 * а сам текст занимает строку целиком и выделяется мышью ([SelectionContainer]) — в браузере
 * ответ копируют.
 *
 * Вызовы инструментов печатаются карточками рядом с речью, а не строкой в блоке: у ответа
 * инструмента текста нет вовсе ([MessageRole.TOOL] приходит с пустым `content`), и блок
 * на его месте был бы пустым. Заодно видно, откуда взяты данные ответа ассистента.
 *
 * Строки монитора ([MessageRole.MONITOR]) печатаются той же служебной карточкой: их тоже
 * писало приложение, а не человек, и подпись «Вы» на их месте приписала бы курс человеку.
 * Свой стиль у них разошёлся бы с соседними служебными блоками на первой же правке
 * оформления.
 *
 * @param message Данные сообщения.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    // Роль подписывается словом: в веб-ленте у сообщений нет «своей» стороны, по которой
    // читатель узнавал бы автора, — а подпись называет его прямо
    val isUser = message.role == MessageRole.USER
    val blockColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Ответ на вызов инструмента — не речь участника, а след работы: вместо блока
        // с пустым текстом печатается карточка вызова
        if (message.role == MessageRole.TOOL) {
            message.tools.forEach { call ->
                ToolCallCard(call)
            }
            return@Column
        }

        // Строка монитора — тоже не речь участника, а показание прибора: её напечатало
        // приложение по расписанию ([MessageRole.MONITOR]). Печатается она служебной
        // карточкой, а не блоком речи: человек её не писал
        if (message.role == MessageRole.MONITOR) {
            MonitorCard(message.content)
            return@Column
        }

        Text(
            text = if (isUser) "Вы" else "Ассистент",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        Surface(
            color = blockColor,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer {
                MessageText(
                    text = message.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }

        // Вызовы модели идут под её ответом: по ним видно, откуда взяты данные, и что
        // инструмент на них ответил — это часть ответа, а не служебная запись в логе
        message.tools.forEach { call ->
            Spacer(Modifier.height(4.dp))
            ToolCallCard(call)
        }
    }
}

/**
 * Карточка вызова инструмента: команда, ответ и пометка отказа.
 *
 * Одна на оба места — под ответом ассистента и вместо пузыря ответа инструмента: это один
 * и тот же след работы, и вторая отрисовка разошлась бы с первой на первой же правке
 * формата. Команда печатается моноширинно, потому что это имя и аргументы вызова, а не
 * речь: так её видно как команду, а не как часть ответа.
 *
 * @param call Вызов инструмента из сообщения.
 */
@Composable
private fun ToolCallCard(call: ToolCallRecord, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = call.command(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Ответа может не быть: инструмент мог не дойти до данных, и тогда пустой
            // строки на его месте достаточно, чтобы это не выглядело потерянным ответом
            val result = call.result
            if (result != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (call.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            // Пометка обязательна: отказ инструмента — это ответ модели, которая дальше
            // отвечала без данных, и по одному тексту этого не отличить от данных
            if (call.failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "инструмент ответил отказом: данных нет",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Карточка строки монитора: курсы валют, напечатанные приложением по расписанию.
 *
 * Тот же служебный блок, что у записи о вызове инструмента ([ToolCallCard]): лента курсов
 * состоит из таких строк, и свой стиль разошёлся бы с соседними служебными блоками
 * на первой же правке оформления.
 *
 * Текст приходит готовым и многострочным у сводки часа: собирает его тот, кто знает
 * формат ленты ([com.osvin.aichallenge.repository.ChatRepository]), а карточка только
 * печатает. Разбирать текст по строкам здесь значило бы держать второй разборщик того же
 * формата — и он разошёлся бы с первым.
 *
 * @param text Строка ленты: время с курсами или сводка прошедшего часа.
 */
@Composable
private fun MonitorCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
