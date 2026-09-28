package com.osvin.aichallenge.pipeline.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Второй шаг цепочки: `summarizeRates` читает документ первого шага и отдаёт текст сводки.
 *
 * Проверяется соединение шагов по данным, а не оформление: документ берётся тот же формы, что
 * отдаёт `getExchangeRates` (его составляет [exchangeRates] в проверках выше), а на выходе
 * сверяется текст целиком — потому что этот текст и есть то, что уезжает на третий шаг в файл.
 * Свободная формулировка тут была бы неотличима от потери данных: сводка — единственное место,
 * где видно, что курсы дошли до файла.
 *
 * Отказы — словами и с `isError`: второй шаг часто получает документ от модели, а не от первого
 * шага, и по причине отказа модель может позвать первый шаг заново.
 */
class SummarizeRatesToolTest {

    private val directory = Files.createTempDirectory("pipeline-summary")

    @AfterTest
    fun removeReports() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `сводка называет момент, базу, курсы и крайние`() {
        val document = (
            """{"base":"RUB","updatedAt":"2026-09-28T10:37:55Z",""" +
                """"rates":{"EUR":95.8709,"USD":84.3414,"GEL":32.1693}}"""
        )

        val summary = summary(call(SUMMARIZE_TOOL, "rates" to document))

        assertEquals(
            """
            Сводка курсов к RUB на 2026-09-28 10:37 UTC
            Валют: 3, размах между крайними: 63,7016
            EUR 95,8709 — дороже всех
            USD 84,3414
            GEL 32,1693 — дешевле всех
            """.trimIndent(),
            summary,
            "сводка не совпала с документом"
        )
    }

    @Test
    fun `строки идут в порядке документа`() {
        val document = ("""{"base":"RUB","rates":{"GEL":32.1693,"EUR":95.8709}}""")

        val lines = summary(call(SUMMARIZE_TOOL, "rates" to document)).lines().drop(2)

        assertEquals(
            listOf("GEL 32,1693 — дешевле всех", "EUR 95,8709 — дороже всех"),
            lines,
            "сводка переставила валюты: порядок в ней — порядок переданного документа"
        )
    }

    @Test
    fun `одна валюта — без пометок о крайних`() {
        val document = ("""{"base":"RUB","rates":{"USD":84.3414}}""")

        val summary = summary(call(SUMMARIZE_TOOL, "rates" to document))

        assertEquals(
            listOf("Валют: 1, размах между крайними: 0", "USD 84,3414"),
            summary.lines().drop(1),
            "у единственной валюты пометки о крайних — шум, а не сведение"
        )
        assertEquals("Сводка курсов к RUB", summary.lines().first(), "без момента времени строка остаётся той же")
    }

    @Test
    fun `курс строкой принимается наравне с числом`() {
        val asNumber = ("""{"base":"RUB","rates":{"USD":84.3414}}""")
        val asText = ("""{"base":"RUB","rates":{"USD":"84.3414"}}""")

        assertEquals(
            summary(call(SUMMARIZE_TOOL, "rates" to asNumber)),
            summary(call(SUMMARIZE_TOOL, "rates" to asText)),
            "документ с числом строкой дал другую сводку"
        )
    }

    @Test
    fun `испорченный документ отказывает словами`() {
        val cases = mapOf(
            "не JSON" to "курсы: EUR 95.87",
            "нет курсов" to """{"base":"RUB"}""",
            "курсы пусты" to """{"base":"RUB","rates":{}}""",
            "курс не число" to """{"base":"RUB","rates":{"USD":"много"}}""",
            "нет базы" to """{"rates":{"USD":84.3414}}"""
        )

        cases.forEach { (what, document) ->
            val result = call(SUMMARIZE_TOOL, "rates" to document)
            assertTrue(result.isError == true, "$what: сводка посчитана по негодному документу")
            assertTrue(
                result.text().isNotBlank(),
                "$what: отказ без причины — модель не узнает, что чинить"
            )
            assertFalse(
                result.text().contains("Валют:"),
                "$what: в ответе об отказе оказалась сводка: ${result.text()}"
            )
        }
    }

    @Test
    fun `пропущенная валюта в сводку не попадает, но документ читается`() {
        val document = (
            """{"base":"RUB","rates":{"EUR":95.8709},"missing":["USD","GEL"]}"""
        )

        val result = call(SUMMARIZE_TOOL, "rates" to document)

        assertFalse(result.isError == true, "пропущенные валюты — не повод отказать в сводке")
        assertTrue(result.text().contains("Валют: 1"), "посчитано не то число валют: ${result.text()}")
        assertFalse(result.text().contains("USD"), "валюта без истории попала в сводку")
    }

    private fun summary(result: CallToolResult): String {
        assertFalse(result.isError == true, "инструмент отказал: ${result.text()}")
        return result.text()
    }

    private fun call(name: String, vararg arguments: Pair<String, String>): CallToolResult = runBlocking {
        summarizeRatesTool(PipelineMcpServer.ratesArgument).read(
            PipelineToolsData(
                repository = FakeRateRepository(emptyList()),
                outputDir = directory,
                clock = Clock.fixed(MOMENT, ZoneOffset.UTC)
            ),
            CallToolRequest(
                params = CallToolRequestParams(
                    name = name,
                    arguments = JsonObject(arguments.associate { (key, value) -> key to JsonPrimitive(value) })
                )
            )
        )
    }

    private companion object {
        val MOMENT: Instant = Instant.parse("2026-09-28T10:37:55Z")

        const val SUMMARIZE_TOOL = PipelineMcpServer.SUMMARIZE_RATES_TOOL
    }
}
