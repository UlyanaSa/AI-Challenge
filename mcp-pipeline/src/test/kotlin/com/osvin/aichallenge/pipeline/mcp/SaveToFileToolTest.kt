package com.osvin.aichallenge.pipeline.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Третий шаг цепочки: `saveToFile` кладёт переданный текст в файл каталога отчётов.
 *
 * Проверяется то, чем цепочка заканчивается: содержимое файла на диске равно переданному тексту,
 * а названная в ответе контрольная сумма — это сумма записанного файла, а не обещание.
 * Сумма пересчитывается здесь из байтов файла: иначе проверка повторяла бы вычисление инструмента
 * и не заметила бы, например, записи в другой кодировке.
 *
 * Отдельно проверяются имена: имя приходит от модели, и всё, что выводит запись за каталог
 * отчётов, должно быть отказом. Отказ проверяется и по отсутствию файла на диске — сообщение
 * об отказе, после которого файл всё-таки появился, хуже отказа без сообщения.
 */
class SaveToFileToolTest {

    private val directory = Files.createTempDirectory("pipeline-reports")

    @AfterTest
    fun removeReports() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `текст ложится в файл, ответ называет путь, размер и сумму`() {
        val text = "Сводка курсов к RUB\nВалют: 3, размах между крайними: 63,7016"

        val result = call("name" to "rates.md", "content" to text)

        val file = directory.resolve("rates.md")
        assertFalse(result.isError == true, "инструмент отказал: ${result.text()}")
        assertEquals(text, Files.readString(file), "в файле не тот текст, что передали")
        assertTrue(result.text().contains(file.toString()), "ответ не называет путь: ${result.text()}")
        assertTrue(
            result.text().contains("Байт: ${Files.size(file)}"),
            "размер в ответе не сходится с файлом (${Files.size(file)}): ${result.text()}"
        )
        assertTrue(
            result.text().contains(hash(file)),
            "контрольная сумма в ответе — не от записанного файла: ${result.text()}"
        )
    }

    @Test
    fun `без имени файл называется по моменту записи`() {
        val result = call("content" to "сводка")

        assertFalse(result.isError == true, "инструмент отказал: ${result.text()}")
        val expected = directory.resolve("rates-2026-09-28-1037.md")
        assertTrue(Files.exists(expected), "имя по умолчанию не совпало с моментом: ${result.text()}")
        assertEquals("сводка", Files.readString(expected), "в файле не тот текст, что передали")
    }

    @Test
    fun `отчёт за ту же минуту заменяет прежний`() {
        call("name" to "rates.md", "content" to "первый")
        val second = call("name" to "rates.md", "content" to "второй")

        assertFalse(second.isError == true, "инструмент отказал: ${second.text()}")
        assertEquals("второй", Files.readString(directory.resolve("rates.md")), "старый отчёт не заменён")
    }

    @Test
    fun `каталог отчётов создаётся записью`() {
        val nested = directory.resolve("август")
        assertFalse(Files.exists(nested), "каталог отчётов существует до проверки")

        val data = data(outputDir = nested)
        val result = tool().let { runBlocking { it.read(data, request("name" to "rates.md", "content" to "сводка")) } }

        assertFalse(result.isError == true, "инструмент отказал: ${result.text()}")
        assertEquals("сводка", Files.readString(nested.resolve("rates.md")), "файл не появился в новом каталоге")
    }

    @Test
    fun `имя с каталогом отказывает, а файл не появляется`() {
        listOf("../rates.md", "подкаталог/rates.md", "/tmp/rates.md", "..", "  ").forEach { name ->
            val result = call("name" to name, "content" to "сводка")

            assertTrue(result.isError == true, "имя «$name» принято, хотя выводит за каталог отчётов")
            assertTrue(result.text().contains(name.trim()), "отказ не называет имя: ${result.text()}")
        }

        assertTrue(
            Files.list(directory).use { it.toList() }.isEmpty(),
            "после отказа в каталоге отчётов остались файлы"
        )
        assertFalse(Files.exists(directory.parent.resolve("rates.md")), "запись вышла из каталога отчётов")
    }

    @Test
    fun `без текста сохранять нечего`() {
        val result = call()

        assertTrue(result.isError == true, "вызов без текста должен быть отказом, а не пустым файлом")
        assertTrue(
            Files.list(directory).use { it.toList() }.isEmpty(),
            "после отказа в каталоге отчётов остались файлы"
        )
    }

    private fun call(vararg arguments: Pair<String, String>): CallToolResult =
        runBlocking { tool().read(data(), request(*arguments)) }

    private fun request(vararg arguments: Pair<String, String>) = CallToolRequest(
        params = CallToolRequestParams(
            name = PipelineMcpServer.SAVE_TO_FILE_TOOL,
            arguments = JsonObject(arguments.associate { (key, value) -> key to JsonPrimitive(value) })
        )
    )

    private fun data(outputDir: Path = directory) = PipelineToolsData(
        repository = FakeRateRepository(emptyList()),
        outputDir = outputDir,
        clock = Clock.fixed(MOMENT, ZoneOffset.UTC)
    )

    private fun tool() = saveToFileTool(PipelineMcpServer.nameArgument, PipelineMcpServer.contentArgument)

    /** Контрольная сумма файла: считается здесь, из байтов на диске. */
    private fun hash(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    private companion object {
        val MOMENT: Instant = Instant.parse("2026-09-28T10:37:55Z")
    }
}
