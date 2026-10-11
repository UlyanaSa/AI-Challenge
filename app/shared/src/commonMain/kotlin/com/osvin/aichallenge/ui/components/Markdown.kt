package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Ответ модели напечатанным текстом, а не разметкой.
 *
 * Модели отвечают разметкой Markdown: в ответе qwen3 приходит `**Юпитер**`, списки
 * с дефисом и блоки кода в тройных апострофах. Раньше этот текст печатался как есть,
 * и в ленте были видны и звёздочки, и апострофы — текст выглядел испорченным, хотя модель
 * ответила верно. Здесь разметка разбирается и превращается в оформление: жирный, курсив,
 * код, заголовки и списки.
 *
 * Разбор намеренно неполный — только то, что пишут модели в чате: заголовки, списки,
 * блоки кода, `жирный`, `курсив`, `код`. Таблицы, ссылки, картинки и сноски остаются
 * текстом: полноценный разборщик Markdown — это библиотека на сотни килобайт ради
 * ответов, где их не бывает, и в ленте чата ссылка и так читается.
 *
 * @param text Текст сообщения с разметкой.
 * @param modifier Модификатор блока.
 */
@Composable
fun MessageText(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val blocks = markdownBlocks(text)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(block.text)
                is MarkdownBlock.ListItem -> ListItem(block)
                is MarkdownBlock.Heading -> Text(
                    text = styledInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = styledInline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface
                )
            }
        }
    }
}

/**
 * Блок разметки: строка ленты после разбора.
 *
 * Списком он потому, что разметку разбирает одна функция ([markdownBlocks]), а печатает
 * другой код: если бы разбор возвращал готовый [AnnotatedString], карточкам кода и пунктам
 * списков пришлось бы заново выяснять, чем они являются.
 */
internal sealed interface MarkdownBlock {
    /** Обычный абзац: строки подряд до пустой строки. */
    data class Paragraph(val text: String) : MarkdownBlock

    /** Заголовок: уровень от 1 до 6 — число решёток в начале строки. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /**
     * Пункт списка.
     *
     * @param number Номер пункта у нумерованного списка; null — список маркированный.
     */
    data class ListItem(val number: Int?, val text: String) : MarkdownBlock

    /** Блок кода: текст между тройными апострофами. */
    data class Code(val text: String) : MarkdownBlock
}

/**
 * Разбор разметки на блоки.
 *
 * Пустая строка разделяет абзацы, а внутри абзаца переносы сохраняются: модели пишут
 * строку за строкой, и склеивать их в один абзац значило бы менять то, что написал автор
 * ответа. Апостроф не закрыт — блок кода до конца текста: так делает и сам Markdown,
 * а ответ обрывают редко.
 *
 * @param text Текст сообщения.
 */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }

    fun flushCode() {
        if (inCode || code.isNotEmpty()) {
            blocks += MarkdownBlock.Code(code.joinToString("\n"))
            code.clear()
        }
    }

    text.lines().forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith(CODE_FENCE)) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            code += line
            return@forEach
        }
        if (line.isBlank()) {
            flushParagraph()
            return@forEach
        }

        val heading = HEADING.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(trimmed)
        val ordered = ORDERED.matchEntire(trimmed)
        when {
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim()
                )
            }

            bullet != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(number = null, text = bullet.groupValues[1].trim())
            }

            ordered != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(
                    number = ordered.groupValues[1].toIntOrNull(),
                    text = ordered.groupValues[2].trim()
                )
            }

            else -> paragraph += line.trim()
        }
    }
    // Незакрытый блок кода доходит сюда же: открыт и не закрыт — значит до конца текста
    if (inCode) {
        flushCode()
    } else {
        flushParagraph()
    }
    return blocks
}

/**
 * Строка с оформлением, собранная для текущей темы.
 *
 * Разбор держится в памяти по тексту строки: лента перерисовывается на каждый введённый
 * символ, а разбор одного и того же ответа давал бы тот же результат.
 */
@Composable
private fun styledInline(text: String): AnnotatedString {
    // Цвет читается до remember: внутри блока памяти обращение к теме невозможно —
    // это вызов composable-функции, а блок выполняется один раз и не при перерисовке
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, codeBackground) { inlineStyle(text, codeBackground) }
}

/**
 * Сборка строки с оформлением.
 *
 * Парных маркеров без пары не бывает: одинокая звёздочка остаётся звёздочкой — иначе
 * умножение в ответе (`2 * 3`) съедало бы половину строки как начало курсива.
 *
 * @param text Текст с разметкой оформления.
 * @param codeBackground Фон для кода внутри строки: он отличается от фона блока речи,
 *        иначе код в тексте не отличить от обычных слов.
 */
internal fun inlineStyle(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBackground
    )
    var index = 0
    while (index < text.length) {
        val rest = text.substring(index)
        val marker = when {
            rest.startsWith("`") -> "`"
            rest.startsWith(BOLD) -> BOLD
            rest.startsWith("__") -> "__"
            rest.startsWith("*") -> "*"
            rest.startsWith("_") -> "_"
            else -> null
        }
        if (marker == null) {
            append(text[index])
            index++
            continue
        }
        // Ищем закрывающий маркер после открывающего; его нет — маркер обычный символ
        val end = text.indexOf(marker, index + marker.length)
        if (end < 0) {
            append(text[index])
            index++
            continue
        }
        val content = text.substring(index + marker.length, end)
        withStyle(
            when (marker) {
                "`" -> codeStyle
                BOLD, "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            }
        ) {
            append(content)
        }
        index = end + marker.length
    }
}

/**
 * Пункт списка: маркер и текст в одну строку.
 *
 * Маркер печатается отдельным [Text]: у многострочного пункта продолжение строки должно
 * начинаться под текстом, а не под маркером.
 */
@Composable
private fun ListItem(item: MarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.number?.let { "$it." } ?: "•",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = styledInline(item.text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Блок кода: моноширинный текст на своём фоне.
 *
 * Отступ и прокрутка по горизонтали тут не нужны: длинные строки в ленте чата переносятся,
 * а копировать код человек будет выделением — оно идёт через [SelectionContainer] всей ленты.
 */
@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Открывающий и закрывающий апостроф блока кода. */
private const val CODE_FENCE = "```"

/** Жирный шрифт: две звёздочки. */
private const val BOLD = "**"

/** Заголовок: решётки в начале строки и текст после них. */
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

/** Пункт маркированного списка: дефис, звёздочка или плюс. */
private val BULLET = Regex("^[-*+]\\s+(.*)$")

/** Пункт нумерованного списка: номер с точкой или скобкой. */
private val ORDERED = Regex("^(\\d+)[.)]\\s+(.*)$")
