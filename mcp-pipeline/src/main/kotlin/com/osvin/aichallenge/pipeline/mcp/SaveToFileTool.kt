package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import com.osvin.aichallenge.pipeline.PipelineConfig
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Имя файла по умолчанию: `rates-2026-09-28-1037.md` — по моменту записи в UTC. */
private val REPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

/** С чего начинается имя по умолчанию: по нему файлы отчётов видно среди прочих в каталоге. */
private const val REPORT_PREFIX = "rates-"

/**
 * Инструмент `saveToFile`: сохраняет текст в файл отчётов — третий шаг цепочки.
 *
 * Пишет только в свой каталог ([PipelineConfig.outputDir]) и только в файл с именем без каталогов:
 * имя приходит от модели, а `../../` в нём означало бы, что инструмент пишет куда угодно на машине.
 * Проверка имени — отказом по входу (результат с `isError`), а не молчаливым укорочением: человек,
 * попросивший сохранить в подкаталог, должен узнать, что сохранено в корне отчётов.
 *
 * Ответ называет путь, размер и SHA-256: размер и контрольная сумма — то, по чему видно, что
 * записан именно переданный текст. Цепочка заканчивается файлом, и «сохранилось ли то, что
 * передали» — единственный вопрос, на который этот шаг может ответить числами, а не словами.
 *
 * Каталог создаётся записью, а не запуском сервера: сервер поднимается и для `--list-tools`,
 * и от одного лишь запуска пустой каталог отчётов был бы мусором рядом с процессом.
 */
fun saveToFileTool(
    name: DeclaredArgument,
    content: DeclaredArgument
): ServerTool<PipelineToolsData> = ServerTool(
    name = PipelineMcpServer.SAVE_TO_FILE_TOOL,
    description = "Сохраняет текст в файл каталога отчётов и отвечает путём, размером " +
        "и контрольной суммой. Имя файла — без каталогов.",
    arguments = listOf(name, content)
) { data, request ->
    val text = request.argument(content.name)
        ?: return@ServerTool CallToolResult(
            content = listOf(TextContent("Аргумент ${content.name} не назван: сохранять нечего.")),
            isError = true
        )
    try {
        val fileName = request.argument(name.name)?.let(::checkedFileName) ?: defaultFileName(data.clock)
        CallToolResult(content = listOf(TextContent(writeReport(data.outputDir, fileName, text))))
    } catch (error: PipelineInputException) {
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
    if (trimmed.isEmpty()) throw PipelineInputException("Имя файла названо пусто: назовите имя без каталогов.")
    val asPath = try {
        Paths.get(trimmed)
    } catch (error: InvalidPathException) {
        throw PipelineInputException("Имя файла не годится: ${error.message}")
    }
    if (asPath.isAbsolute || asPath.fileName?.toString() != trimmed || trimmed.contains("..")) {
        throw PipelineInputException(
            "Имя файла должно быть именем без каталогов, а не путём: «$name». Файл ложится в каталог отчётов."
        )
    }
    return trimmed
}

/** Имя по умолчанию: момент записи в UTC — отчёты за разные минуты не затирают друг друга. */
internal fun defaultFileName(clock: Clock): String =
    REPORT_PREFIX + REPORT_TIME.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) + ".md"

/**
 * Запись отчёта: файл в каталоге отчётов, ответ о записи.
 *
 * Каталог создаётся здесь, а файл перезаписывается: отчёт за ту же минуту — это тот же отчёт,
 * и отказ вместо перезаписи заставлял бы придумывать имя заново. Путь проверяется ещё раз после
 * сборки: имя уже проверено, но проверка принадлежит записи — она и есть то место, где путь
 * превращается в файл.
 */
internal fun writeReport(outputDir: Path, fileName: String, content: String): String {
    Files.createDirectories(outputDir)
    val directory = outputDir.normalize()
    val file = directory.resolve(fileName).normalize()
    if (file.parent != directory) {
        throw PipelineInputException("Файл «$fileName» выходит за каталог отчётов: сохранение отменено.")
    }

    val bytes = content.toByteArray(Charsets.UTF_8)
    Files.write(file, bytes)
    return buildString {
        append("Файл сохранён: ").append(file)
        append('\n').append("Байт: ").append(bytes.size)
        append(", строк: ").append(content.lines().size)
        append('\n').append("SHA-256: ").append(bytes.sha256())
    }
}

/** Контрольная сумма записанного: по ней видно, что в файле именно переданный текст. */
private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }
