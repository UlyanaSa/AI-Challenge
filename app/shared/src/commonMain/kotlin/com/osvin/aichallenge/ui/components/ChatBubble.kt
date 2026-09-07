package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osvin.aichallenge.data.AnswerMetrics
import com.osvin.aichallenge.data.AnalysisEntry
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.MessageRole
import kotlin.math.roundToInt

/**
 * Пузырек сообщения в списке чата.
 *
 * Умеет показывать три вида сообщений ассистента:
 *  - обычный текст;
 *  - вариант ответа: название варианта, текст решения и выделенный блок
 *    с характеристиками (токены, скорость, длина, стоимость, глубина, точность);
 *  - анализ: таблица характеристик всех запущенных вариантов + вердикт судьи.
 *
 * @param message Данные сообщения.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val isAnalysis = message.analysisEntries != null
    val maxWidth = if (isAnalysis) 380.dp else 320.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = maxWidth)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                message.variantTitle?.let { title ->
                    Text(
                        text = "Вариант: $title",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (isAnalysis) {
                    Text(
                        text = "Анализ: сравнение вариантов",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    message.analysisEntries?.let { entries ->
                        AnalysisTable(entries = entries)
                    }
                    Text(
                        text = "Оценка судьи по параметрам: правильность · полнота · " +
                            "обоснованность · ясность",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                    )
                }

                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }

                message.metrics?.let { metrics ->
                    MetricsCard(metrics = metrics, textColor = textColor)
                }
            }
        }
    }
}

/**
 * Выделенный блок с характеристиками запущенного варианта.
 */
@Composable
private fun MetricsCard(
    metrics: AnswerMetrics,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "Характеристики",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = metrics.toDetailedLines(),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Таблица характеристик вариантов в сообщении-анализе.
 * Строки — метрики, столбцы — варианты.
 */
@Composable
private fun AnalysisTable(
    entries: List<AnalysisEntry>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.width(118.dp).padding(horizontal = 3.dp, vertical = 3.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Метрика",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            entries.forEach { entry ->
                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.shortLabel(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        AnalysisTableRows.entries.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.width(118.dp).padding(horizontal = 3.dp, vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(text = row.label, fontSize = 10.sp, maxLines = 1)
                }
                entries.forEach { entry ->
                    Box(
                        modifier = Modifier.weight(1f).padding(horizontal = 3.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = row.format(entry.metrics),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Строки итоговой таблицы: метрика → значение. */
private enum class AnalysisTableRows(val label: String) {
    TOKENS("Токены") {
        override fun format(m: AnswerMetrics) = m.totalTokens.toString()
    },
    TIME("Время, с") {
        override fun format(m: AnswerMetrics) = formatSeconds(m.elapsedMillis)
    },
    SPEED("Скорость, ток/с") {
        override fun format(m: AnswerMetrics) = m.tokensPerSecond.roundToInt().toString()
    },
    LENGTH("Длина, симв") {
        override fun format(m: AnswerMetrics) = m.chars.toString()
    },
    COST("Стоимость, ₽") {
        override fun format(m: AnswerMetrics) = m.costRubText
    },
    ACCURACY("Точность") {
        override fun format(m: AnswerMetrics) = m.accuracy?.let { "$it/10" } ?: "—"
    },
    DEPTH("Глубина") {
        override fun format(m: AnswerMetrics) = m.depth?.let { "$it/10" } ?: "—"
    };

    abstract fun format(metrics: AnswerMetrics): String
}

/** Короткое название варианта для заголовка столбца таблицы. */
private fun AnalysisEntry.shortLabel(): String {
    val title = title.lowercase()
    return when {
        "пошагов" in title -> "Пошагово"
        "промпт" in title -> "Промпт"
        "эксперт" in title -> "Эксперты"
        else -> "Прямой"
    }
}

/** Развёрнутые строки характеристик для блока под решением. */
private fun AnswerMetrics.toDetailedLines(): String =
    buildString {
        append("Токены: вход $promptTokens · выход $completionTokens · всего $totalTokens\n")
        append("Скорость: ${formatSeconds(elapsedMillis)} с · ${tokensPerSecond.roundToInt()} токенов/с\n")
        append("Длина: $chars символов · $words слов\n")
        append("Стоимость: \$$costUsdText ≈ $costRubText ₽\n")
        append(
            "Точность: ${accuracy?.let { "$it/10" } ?: "нет данных"} · " +
                "Глубина: ${depth?.let { "$it/10" } ?: "нет данных"}"
        )
    }

/** Время в секундах с одним знаком после запятой. */
private fun formatSeconds(millis: Long): String {
    val tenths = (millis / 100.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
