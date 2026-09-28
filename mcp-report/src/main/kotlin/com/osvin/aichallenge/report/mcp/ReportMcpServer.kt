package com.osvin.aichallenge.report.mcp

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Clock

/**
 * Формат ответов инструментов отчётов: многострочный JSON.
 *
 * С отступами, как у инструментов проекта: ответ читают и модель, и человек в карточке вызова,
 * а одна строка на весь ответ в карточке не читается. Разбору отступы не мешают — это тот же
 * JSON, и ключи те же.
 */
internal val REPORT_JSON = Json { prettyPrint = true }

/**
 * Данные, без которых инструменты отчётов не работают: каталог отчётов и часы.
 *
 * Не инструменты и не сервер, а то, что приходит в обработчик вызова: объявление инструмента
 * общее для консольного режима и для поднятого сервера, а каталог есть только у поднятого.
 * Часы здесь по той же причине, что у сервера пайплайна: имя файла по умолчанию зависит
 * от момента записи, и в проверке этот момент задаётся, а не берётся из системных часов —
 * иначе проверка зависела бы от дня прогона.
 *
 * @param rootDir Каталог отчётов `saveReport`.
 * @param clock Часы сервера: по ним называется файл, если имя не назвали.
 */
data class ReportToolsData(
    val rootDir: Path,
    val clock: Clock = Clock.systemUTC()
)

/**
 * MCP-сервер отчётов: два инструмента, которые агент вызывает цепочкой.
 *
 * Инструментов два, а не один «собери отчёт и сохрани», потому что между сборкой и записью
 * стоит агент: собранный markdown он передаёт второму вызову сам. Одним инструментом передача
 * данных между шагами исчезла бы из ленты вызовов — остался бы один вызов с готовым файлом,
 * и «сервер собрал то, что передал агент» стало бы неотличимо от «сервер сделал всё сам».
 * День оркестрации проверяет именно передачу, поэтому шаг сборки отвечает документом
 * (`createReport` → JSON с полем `content`), а шаг записи принимает этот документ целиком
 * (`saveReport`), и связывает их агент, а не зашитый в сервер порядок.
 *
 * Markdown, а не свой формат: отчёт читает человек — в редакторе, в просмотрщике markdown,
 * в конце концов в выводе `cat`. Свой формат заставил бы писать для него читателя, а выигрыша
 * не дал бы: заголовки и текст — это всё, что в отчёте есть.
 *
 * Файл пишет только второй инструмент и только в свой каталог: сборка ничего не трогает на диске,
 * поэтому неудачная формулировка заголовка не оставляет после себя мусора, а записать чужой файл
 * сервер не может даже при ошибочном имени — имя проверяется ([checkedFileName]).
 *
 * Один и тот же набор увидят и наш агент, и любой сторонний MCP-клиент: устройство сервера общее
 * с остальными серверами проекта (объявление [ServerTool], сборка `mcpServer`, запуск
 * `runStdioServer`).
 */
object ReportMcpServer {

    /** Имя сервера в рукопожатии: по нему видно, чьи инструменты пришли. */
    const val NAME = "ai-challenge-report"

    /** Версия сервера в рукопожатии. */
    const val VERSION = "1.0.0"

    /**
     * Класс точки входа: им же поднимает сервер приложение (`:server`), поэтому имя вынесено
     * константой, а не написано в двух местах — переименование разошлось бы молча.
     */
    const val MAIN_CLASS = "com.osvin.aichallenge.report.ApplicationKt"

    /** Первый шаг: собрать markdown отчёта. */
    const val CREATE_REPORT_TOOL = "createReport"

    /** Второй шаг: записать markdown в файл. */
    const val SAVE_REPORT_TOOL = "saveReport"

    val titleArgument = DeclaredArgument(
        name = "title",
        description = "заголовок отчёта: он же первый заголовок markdown",
        required = true
    )

    val sectionsArgument = DeclaredArgument(
        name = "sections",
        description = "секции отчёта — JSON-массив объектов {\"title\": \"...\", \"content\": \"...\"} " +
            "одной строкой; каждая секция станет заголовком второго уровня",
        required = true
    )

    val filenameArgument = DeclaredArgument(
        name = "filename",
        description = "имя файла без каталогов; по умолчанию — report-ГГГГ-ММ-ДД-ЧЧММ.md",
        required = false
    )

    val contentArgument = DeclaredArgument(
        name = "content",
        description = "markdown отчёта: например, поле content из ответа createReport",
        required = true
    )

    /** Инструменты сервера: объявление цепочки целиком — в одном списке. */
    fun tools(): List<ServerTool<ReportToolsData>> = listOf(
        createReportTool(titleArgument, sectionsArgument),
        saveReportTool(filenameArgument, contentArgument)
    )
}
