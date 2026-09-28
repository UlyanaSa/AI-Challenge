package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.mcp.ServerTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Первый шаг цепочки: `getExchangeRates` отдаёт документ, который целиком принимает `summarizeRates`.
 *
 * Проверяется то, на что опирается следующий шаг: имена полей (`base`, `updatedAt`, `rates`),
 * значения курсов и пересчёт к незнакомой базе. Успешные ответы читаются как JSON, а не
 * сравниваются строкой: строка закрепила бы отступы, а обещание — поля и числа.
 *
 * Отказы проверяются отдельно: недопустимая база, незнакомая валюта, пустая история и база,
 * которой в истории нет, — это разные причины, и модель должна получить их словами, а не
 * одинаковым «не получилось».
 */
class ExchangeRatesToolTest {

    private val reports = Files.createTempDirectory("pipeline-rates")

    @AfterTest
    fun removeReports() {
        reports.toFile().deleteRecursively()
    }

    @Test
    fun `курсы к рублю отдаются как записаны`() {
        val data = ratesData(
            rate(Currency.EUR, "95.8709", MOMENT),
            rate(Currency.USD, "84.3414", MOMENT.minusSeconds(60)),
            rate(Currency.GEL, "32.1693", MOMENT.minusSeconds(120))
        )

        val document = document(read(data))

        assertEquals("RUB", document["base"].text(), "база по умолчанию — рубль")
        assertEquals(MOMENT.toString(), document["updatedAt"].text(), "момент — самый свежий из курсов")
        val rates = document["rates"] as JsonObject
        assertEquals(listOf("EUR", "USD", "GEL"), rates.keys.toList(), "порядок валют — объявление перечисления")
        assertEquals("95.8709", rates["EUR"].text())
        assertEquals("84.3414", rates["USD"].text())
        assertEquals("32.1693", rates["GEL"].text())
        assertNull(document["missing"], "все три валюты есть — пропущенных быть не должно")
    }

    @Test
    fun `пересчёт к другой базе делит на курс базы`() {
        val data = ratesData(
            rate(Currency.EUR, "100", MOMENT),
            rate(Currency.USD, "80", MOMENT)
        )

        val document = document(read(data, "base" to "eur"))

        assertEquals("EUR", document["base"].text(), "код базы приводится к верхнему регистру")
        val rates = document["rates"] as JsonObject
        assertEquals("0.8", rates["USD"].text(), "80 рублей за доллар к 100 рублям за евро — это 0,8 евро за доллар")
        assertEquals("1", rates["EUR"].text(), "курс базы к самой себе — единица, и она не округляется")
    }

    @Test
    fun `пересчёт округляется до шести знаков`() {
        val data = ratesData(
            rate(Currency.EUR, "3", MOMENT),
            rate(Currency.USD, "1", MOMENT)
        )

        val rates = document(read(data, "base" to "EUR"))["rates"] as JsonObject

        assertEquals("0.333333", rates["USD"].text(), "одна треть в шести знаках, а не строка целиком")
    }

    @Test
    fun `валюта без истории названа пропущенной, а не отдана нулём`() {
        val data = ratesData(rate(Currency.EUR, "95.8709", MOMENT))

        val document = document(read(data, "currencies" to "EUR,USD"))

        val rates = document["rates"] as JsonObject
        assertEquals(listOf("EUR"), rates.keys.toList(), "в курсах только та валюта, что есть в истории")
        assertEquals(
            listOf("USD"),
            (document["missing"] as JsonArray).map { (it as JsonPrimitive).content },
            "про валюту без истории сказано отдельным полем"
        )
    }

    @Test
    fun `пустая история и база без курса отказывают словами`() {
        val empty = read(ratesData())
        assertTrue(empty.isError == true, "без истории инструмент должен отказать, а не отдать пустой документ")
        assertTrue(empty.text().contains("ещё не собраны"), "причина отказа не названа: ${empty.text()}")

        val data = ratesData(rate(Currency.EUR, "95.8709", MOMENT))
        val noBase = read(data, "base" to "USD")
        assertTrue(noBase.isError == true, "базы нет в истории — пересчитывать нечем")
        assertTrue(
            noBase.text().contains("USD") && noBase.text().contains("ещё не собран"),
            "причина не называет базу: ${noBase.text()}"
        )
    }

    @Test
    fun `недопустимые аргументы отказывают словами`() {
        val data = ratesData(rate(Currency.EUR, "95.8709", MOMENT))

        val unknownBase = read(data, "base" to "GBP")
        assertTrue(unknownBase.isError == true, "незнакомая база должна быть отказом")
        assertTrue(unknownBase.text().contains("GBP"), "причина не называет базу: ${unknownBase.text()}")

        val unknownCurrency = read(data, "currencies" to "EUR,GBP")
        assertTrue(unknownCurrency.isError == true, "незнакомая валюта в списке должна быть отказом")
        assertTrue(
            unknownCurrency.text().contains("GBP"),
            "причина не называет валюту: ${unknownCurrency.text()}"
        )
        assertFalse(
            unknownCurrency.text().contains("не собраны"),
            "незнакомая валюта выдана за отсутствие истории: ${unknownCurrency.text()}"
        )
    }

    private fun tool(): ServerTool<PipelineToolsData> =
        getExchangeRatesTool(PipelineMcpServer.baseArgument, PipelineMcpServer.currenciesArgument)

    private fun ratesData(vararg rates: CurrencyRate): PipelineToolsData =
        PipelineToolsData(
            repository = FakeRateRepository(rates.toList()),
            outputDir = reports,
            clock = Clock.fixed(MOMENT, ZoneOffset.UTC)
        )

    /** Вызов инструмента: проверки — обычные функции, поэтому корутина заводится здесь. */
    private fun read(data: PipelineToolsData, vararg arguments: Pair<String, String>): CallToolResult =
        runBlocking {
            tool().read(
                data,
                CallToolRequest(
                    params = CallToolRequestParams(
                        name = PipelineMcpServer.EXCHANGE_RATES_TOOL,
                        arguments = JsonObject(
                            arguments.associate { (key, value) -> key to JsonPrimitive(value) }
                        )
                    )
                )
            )
        }

    private fun document(result: CallToolResult): JsonObject {
        assertFalse(result.isError == true, "инструмент отказал: ${result.text()}")
        return Json.parseToJsonElement(result.text()) as JsonObject
    }

    private companion object {
        /** Момент курсов в проверках: он же момент в документе. */
        val MOMENT: Instant = Instant.parse("2026-09-28T10:37:55Z")
    }
}

/** Текст ответа инструмента: у документов курсов он один. */
internal fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

/** Значение поля документа: null, если поля нет или в нём не строка и не число. */
internal fun kotlinx.serialization.json.JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
