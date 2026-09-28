package com.osvin.aichallenge.report.mcp

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Имя файла по умолчанию: `report-2026-09-28-1037.md` — по моменту записи в UTC. */
private val REPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

/** С чего начинается имя по умолчанию: по нему файлы отчётов видно среди прочих в каталоге. */
private const val REPORT_PREFIX = "report-"

/**
 * Инструмент `saveReport`: записывает markdown отчёта в файл — второй шаг цепочки.
 *
 * Принимает текст целиком, а не заголовок с секциями: разбор отчёта на части — работа первого
 * шага ([createReportTool]), и повторять его здесь значило бы иметь два разбора одного документа,
 * которые разошлись бы при первой правке. Здесь документ — просто текст, и записывается он как
 * пришёл.
 *
 * Пишет только в свой каталог ([ReportMcpServer] получает его в `ReportToolsData`) и только
 * в файл с именем без каталогов: имя приходит от модели, а `../../` в нём означало бы, что
 * инструмент пишет куда угодно на машине. Проверка имени — отказом по входу (результат с `isError`),
 * а не молчаливым укорочением: человек, попросивший сохранить в подкаталог, должен узнать,
 * что сохранено в корне отчётов.
 *
 * Имя можно не называть: отчётов бывает много, и придумывать имя каждый раз — работа не модели,
 * а часов; имя по умолчанию называет момент записи, поэтому отчёты за разные минуты не затирают
 * друг друга. Отвергнуто «всегда требовать имя»: тогда инструмент отказывал бы на вызове, где
 * от человека не зависело ничего.
 *
 * Ответ — JSON с полем `path`, а не только слова: путь читает и агент, чтобы назвать файл
 * человеку, и проверка, чтобы открыть записанное. Каталог создаётся записью, а не запуском
 * сервера: сервер поднимается и для `--list-tools`, и от одного лишь запуска пустой каталог
 * отчётов был бы мусором рядом с процессом.
 */
fun saveReportTool(
    filename: DeclaredArgument,
    content: DeclaredArgument
): ServerTool<ReportToolsData> = ServerTool(
    name = ReportMcpServer.SAVE_REPORT_TOOL,
    description = "Записывает markdown отчёта в файл каталога отчётов и отвечает абсолютным " +
        "путём. Имя файла — без каталогов; ответ — JSON с полями success и path.",
    arguments = listOf(filename, content)
) { data, request ->
    val markdown = request.argument(content.name)
        ?: return@ServerTool CallToolResult(
            content = listOf(TextContent("Аргумент ${content.name} не назван: записывать нечего.")),
            isError = true
        )
    try {
        val fileName = request.argument(filename.name)?.let(::checkedFileName) ?: defaultReportFileName(data.clock)
        val file = writeReport(data.rootDir, fileName, markdown)
        CallToolResult(
            content = listOf(
                TextContent(
                    REPORT_JSON.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("success", true)
                            put("path", file.toString())
                        }
                    )
                )
            )
        )
    } catch (error: ReportInputException) {
        CallToolResult(content = listOf(TextContent(error.message.orEmpty())), isError = true)
    } catch (error: IOException) {
        CallToolResult(
            content = listOf(TextContent("Файл не сохранён: ${error.message ?: "запись не удалась"}")),
            isError = true
        )
    }
}

/**
 * Имя файла из вызова: имя, а не путь.
 *
 * Отвергается всё, что выводит запись за каталог отчётов: разделители каталогов, абсолютный путь,
 * переход вверх и пустая строка. Проверяется сравнением с последним элементом пути, а не поиском
 * подстрок: `a/b` и `/a` отсекаются одним правилом, а не списком запрещённых последовательностей,
 * который пришлось бы дополнять под каждую платформу.
 */
internal fun checkedFileName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) throw ReportInputException("Имя файла названо пусто: назовите имя без каталогов.")
    val asPath = try {
        Paths.get(trimmed)
    } catch (error: InvalidPathException) {
        throw ReportInputException("Имя файла не годится: ${error.message}")
    }
    if (asPath.isAbsolute || asPath.fileName?.toString() != trimmed || trimmed.contains("..")) {
        throw ReportInputException(
            "Имя файла должно быть именем без каталогов, а не путём: «$name». Файл ложится в каталог отчётов."
        )
    }
    return trimmed
}

/**
 * Имя по умолчанию: момент записи в UTC.
 *
 * По минутам, как у сервера пайплайна: отчёты за разные минуты не затирают друг друга, а секунды
 * в имени были бы точностью, которой в жизни отчёта нет. UTC, а не местное время: имя не должно
 * зависеть от часового пояса машины, на которой поднят сервер.
 */
internal fun defaultReportFileName(clock: Clock): String =
    REPORT_PREFIX + REPORT_TIME.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) + ".md"

/**
 * Запись отчёта: файл в каталоге отчётов, путь записанного — ответ.
 *
 * Каталог создаётся здесь, а файл перезаписывается: отчёт за ту же минуту — это тот же отчёт,
 * и отказ вместо перезаписи заставлял бы придумывать имя заново. Путь приводится к абсолютному
 * (каталог из настроек и так абсолютен, но инструмент отвечает путём, а не настройкой, и обещать
 * абсолютный путь в ответе — его дело). Путь проверяется ещё раз после сборки: имя уже проверено,
 * но проверка принадлежит записи — она и есть то место, где путь превращается в файл.
 */
internal fun writeReport(rootDir: Path, fileName: String, markdown: String): Path {
    Files.createDirectories(rootDir)
    val directory = rootDir.toAbsolutePath().normalize()
    val file = directory.resolve(fileName).normalize()
    if (file.parent != directory) {
        throw ReportInputException("Файл «$fileName» выходит за каталог отчётов: сохранение отменено.")
    }
    Files.write(file, markdown.toByteArray(Charsets.UTF_8))
    return file
}
