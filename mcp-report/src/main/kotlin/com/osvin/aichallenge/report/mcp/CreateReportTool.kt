package com.osvin.aichallenge.report.mcp

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Что сказать, когда секции не разобраны: причину ищут в вызове, а не в сервере. */
private const val NOT_SECTIONS =
    "Секции не разобраны: ожидался JSON-массив объектов вида " +
        "[{\"title\": \"Введение\", \"content\": \"текст\"}] одной строкой. " +
        "Передайте описание отчёта целиком, без пересказа."

/** Что сказать, когда секций нет вовсе: заголовок без секций — не отчёт. */
private const val NO_SECTIONS =
    "Секции пусты: отчёт состоял бы из одного заголовка. Назовите хотя бы одну секцию."

/**
 * Инструмент `createReport`: собирает markdown-отчёт из заголовка и секций — первый шаг цепочки.
 *
 * Секции принимаются строкой JSON, а не готовым markdown: markdown составляет сервер, а не модель,
 * и от того, кто его составил, зависит, одинаково ли выглядят отчёты из разных вызовов. Строкой,
 * а не отдельным аргументом на секцию: число секций заранее не известно, и схема инструмента
 * не может объявить «секций столько, сколько нужно» — а пересказ секций аргументами «заголовок
 * и текст» превратил бы сборку в разбор модели, где сервер уже ничего не решает.
 *
 * Текст с описанием вокруг массива принимается наравне с чистым массивом: модель часто
 * предваряет JSON словами («вот секции:»), и отказ на такую строку заставлял бы её повторять
 * вызов, ничего не исправляя по существу. Отброшено послабление «любой текст считать одной
 * секцией»: тогда отчёт собирался бы из чего угодно, и «секции не разобраны» никогда бы
 * не случилось.
 *
 * Ответ — JSON с полем `content`, а не сам markdown: документ целиком принимает следующий шаг
 * ([saveReportTool]), и модель передаёт его дальше, а не пересказывает. Пересказ терял бы строки,
 * и в файл попадало бы не то, что собрал сервер.
 *
 * Отказ по входу — результат с `isError`: причина словами нужна модели, чтобы исправить вызов,
 * а сервер продолжает работать.
 */
fun createReportTool(
    title: DeclaredArgument,
    sections: DeclaredArgument
): ServerTool<ReportToolsData> = ServerTool(
    name = ReportMcpServer.CREATE_REPORT_TOOL,
    description = "Собирает markdown-отчёт из заголовка и секций. Ответ — JSON с полем content: " +
        "его целиком принимает saveReport.",
    arguments = listOf(title, sections)
) { _, request ->
    val heading = request.argument(title.name)
        ?: return@ServerTool CallToolResult(
            content = listOf(TextContent("Аргумент ${title.name} не назван: у отчёта должен быть заголовок.")),
            isError = true
        )
    val described = request.argument(sections.name)
        ?: return@ServerTool CallToolResult(
            content = listOf(TextContent("Аргумент ${sections.name} не назван: собирать не из чего.")),
            isError = true
        )
    try {
        val markdown = reportMarkdown(heading, reportSections(described))
        CallToolResult(
            content = listOf(
                TextContent(
                    REPORT_JSON.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject { put("content", markdown) }
                    )
                )
            )
        )
    } catch (error: ReportInputException) {
        CallToolResult(content = listOf(TextContent(error.message.orEmpty())), isError = true)
    }
}

/** Секция отчёта: заголовок второго уровня и текст под ним. */
internal data class ReportSection(val title: String, val content: String)

/**
 * Секции из аргумента: массив, объект с полем `sections` или текст вокруг массива.
 *
 * Объект с полем `sections` принимается потому, что модель иногда оборачивает массив в описание
 * («вот отчёт: {...}») — это то же значение, и отвергать его значило бы требовать формы, которой
 * контракт не устанавливает. Пустой массив — отказ, а не отчёт из одного заголовка: заголовок
 * без содержимого не несёт ничего, и молчаливое согласие сделало бы пустой файл нормальным итогом.
 */
internal fun reportSections(described: String): List<ReportSection> {
    val array = when (val document = parseJson(described)) {
        is JsonArray -> document
        is JsonObject -> document["sections"] as? JsonArray ?: throw ReportInputException(NOT_SECTIONS)
        else -> throw ReportInputException(NOT_SECTIONS)
    }
    if (array.isEmpty()) throw ReportInputException(NO_SECTIONS)
    return array.map { it.toSection() }
}

/**
 * Разбор значения: сам JSON, а при прозе вокруг — массив внутри текста.
 *
 * Второй попытки нет для чего-то, кроме массива: проза вокруг — единственное, что модель
 * добавляет к документу, и искать в тексте произвольные объекты значило бы угадывать, где
 * кончается описание и начинается значение.
 */
private fun parseJson(described: String): JsonElement? {
    val text = described.trim()
    parseOrNull(text)?.let { return it }
    // Проза вокруг: значение — то, что стоит между первой `[` и последней `]`.
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    return parseOrNull(text.substring(start, end + 1))
}

/** Значение или ничего: не разобранный JSON здесь не отказ, а повод попробовать иначе. */
private fun parseOrNull(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (error: SerializationException) {
        null
    }

/**
 * Секция из элемента массива: заголовок обязателен, текст — нет.
 *
 * Заголовок без текста допускается: секция-разделитель («Приложения», «Дальше») — это осмысленный
 * markdown, и требовать под каждым заголовком абзац значило бы придумывать правило, которого
 * у отчёта нет. А вот секция без заголовка — отказ: `##` без названия не читается ни человеком,
 * ни просмотрщиком, и догадываться, чем её назвать, сервер не должен.
 */
private fun JsonElement.toSection(): ReportSection {
    val fields = this as? JsonObject ?: throw ReportInputException(NOT_SECTIONS)
    val sectionTitle = (fields["title"] as? JsonPrimitive)?.contentOrNull
    if (sectionTitle.isNullOrBlank()) {
        throw ReportInputException("У секции не назван заголовок: назовите поле title у каждой секции.")
    }
    return ReportSection(
        title = sectionTitle.trim(),
        content = (fields["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    )
}

/**
 * Markdown отчёта: `# Заголовок` и по секции `## Заголовок секции` с её текстом.
 *
 * Заголовок первого уровня один — он у отчёта, поэтому и берётся из аргумента инструмента,
 * а не из секций: иначе у файла не было бы имени, по которому его узнают в списке. Секции
 * разделяются пустой строкой: markdown без неё склеил бы абзац соседней секции с предыдущим,
 * и текст уехал бы под чужой заголовок.
 */
internal fun reportMarkdown(title: String, sections: List<ReportSection>): String = buildString {
    append("# ").append(title.trim())
    sections.forEach { section ->
        append("\n\n## ").append(section.title)
        if (section.content.isNotEmpty()) append("\n\n").append(section.content)
    }
    append('\n')
}
