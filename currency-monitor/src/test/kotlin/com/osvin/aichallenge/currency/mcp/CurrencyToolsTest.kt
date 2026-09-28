package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.FakeCurrencyRateProvider
import com.osvin.aichallenge.currency.FakeCurrencyRateRepository
import com.osvin.aichallenge.currency.summary.CurrencySummaryService
import com.osvin.aichallenge.currency.summary.SummaryPeriod
import com.osvin.aichallenge.mcp.ServerTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки ответов инструментов: тот JSON, который читает модель.
 *
 * Проверяется именно провод, а не внутренности: ответ инструмента — единственное, что видит
 * агент, и по нему он отвечает человеку. Ошибка в форме (курс строкой вместо числа, пустой
 * список вместо трёх валют, отсутствие пометки отказа) не видна ни в логах сервиса, ни
 * в сборке — её видно только в вопросе и ответе, то есть уже у человека.
 */
class CurrencyToolsTest {

    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")

    @Test
    fun `rates tool answers with numbers for every tracked currency`() {
        val repository = FakeCurrencyRateRepository()
        val data = data(repository, mapOf(Currency.EUR to "95.8709", Currency.USD to "84.3414", Currency.GEL to "32.1693"))

        val body = call(CurrencyMcpServer.tools().rates(), data).json()

        assertEquals("2026-09-28T12:00:00Z", body.getValue("updatedAt").jsonPrimitive.content)
        val rates = body.getValue("rates").jsonObject
        assertEquals(setOf("EUR", "USD", "GEL"), rates.keys)
        assertEquals(BigDecimal("95.8709"), rates.getValue("EUR").jsonPrimitive.content.toBigDecimal())
        // Число, а не строка: по строке агент не посчитает изменение, ему пришлось бы разбирать её самому.
        assertTrue(rates.getValue("EUR").jsonPrimitive.isString.not())
        assertNull(body["note"])
    }

    @Test
    fun `rates tool explains empty history instead of answering with an empty object`() {
        val body = call(CurrencyMcpServer.tools().rates(), data(FakeCurrencyRateRepository(), emptyMap())).json()

        assertEquals(JsonNull, body["updatedAt"])
        assertTrue(body.getValue("rates").jsonObject.isEmpty())
        assertNotNull(body["note"], "пустые курсы без объяснения читаются как сбой сервиса")
    }

    @Test
    fun `rates tool reports storage failure as a tool refusal`() {
        val repository = FakeCurrencyRateRepository().apply { failure = CurrencyStorageException("история недоступна") }
        val result = call(CurrencyMcpServer.tools().rates(), data(repository, emptyMap()))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("история недоступна"))
    }

    @Test
    fun `summary tool answers with a summary per currency for the asked period`() {
        val repository = FakeCurrencyRateRepository()
        seed(repository)
        val tool = CurrencyMcpServer.tools().summary()

        val body = call(tool, data(repository, emptyMap()), mapOf("period" to "DAY")).json()

        assertEquals("DAY", body.getValue("period").jsonPrimitive.content)
        val currencies = body.getValue("currencies").jsonArray.map { it.jsonObject }
        assertEquals(listOf("EUR", "USD", "GEL"), currencies.map { it.getValue("currency").jsonPrimitive.content })
        val eur = currencies.first()
        assertEquals(BigDecimal("95.8709"), eur.getValue("currentRate").jsonPrimitive.content.toBigDecimal())
        assertEquals(BigDecimal("94.8709"), eur.getValue("previousRate").jsonPrimitive.content.toBigDecimal())
        assertEquals(2, eur.getValue("samples").jsonPrimitive.int)
    }

    @Test
    fun `summary tool refuses an unknown period and names the allowed ones`() {
        val repository = FakeCurrencyRateRepository()
        val result = call(CurrencyMcpServer.tools().summary(), data(repository, emptyMap()), mapOf("period" to "YEAR"))

        assertEquals(true, result.isError)
        SummaryPeriod.entries.forEach { period -> assertTrue(result.text().contains(period.code), result.text()) }
    }

    @Test
    fun `summary tool refuses a missing required period`() {
        val repository = FakeCurrencyRateRepository()
        val result = call(CurrencyMcpServer.tools().summary(), data(repository, emptyMap()))

        assertEquals(true, result.isError)
    }

    @Test
    fun `server declares both tools and the period as a required choice`() {
        val tools = CurrencyMcpServer.tools()

        assertEquals(listOf("get_currency_rates", "get_currency_summary"), tools.map { it.name })
        assertEquals(emptyList(), tools.first { it.name == "get_currency_rates" }.arguments)
        val period = tools.first { it.name == "get_currency_summary" }.arguments.single()
        assertEquals("period", period.name)
        assertTrue(period.required)
        assertEquals(listOf("DAY", "WEEK", "MONTH"), period.values)
    }

    /** Инструмент по имени из объявленного списка: проверка не повторяет его объявление у себя. */
    private fun List<ServerTool<CurrencyToolsData>>.rates() = first { it.name == "get_currency_rates" }

    private fun List<ServerTool<CurrencyToolsData>>.summary() = first { it.name == "get_currency_summary" }

    /** Вызов инструмента так, как его зовёт клиент: через объявление, а не через внутренний метод. */
    private fun call(
        tool: ServerTool<CurrencyToolsData>,
        data: CurrencyToolsData,
        arguments: Map<String, String> = emptyMap()
    ): CallToolResult = runBlocking {
        tool.read(
            data,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = tool.name,
                    arguments = JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
                )
            )
        )
    }

    /**
     * Данные инструментов: служба сбора с заданным ответом поставщика и та же история для сводки.
     *
     * История наполняется обновлением, а не прямой записью в двойник: так проверка инструмента
     * идёт тем же путём, что и работа, — курсы попадают в историю через службу, а инструмент
     * читает то, что она сохранила.
     */
    private fun data(repository: FakeCurrencyRateRepository, rates: Map<Currency, String>): CurrencyToolsData {
        val provider = FakeCurrencyRateProvider(
            rates = rates.map { (currency, value) ->
                CurrencyRate(currency, BigDecimal(value), now)
            }
        )
        val service = CurrencyService(provider, repository)
        runBlocking { service.update() }
        return CurrencyToolsData(
            service,
            CurrencySummaryService(repository, Clock.fixed(now, java.time.ZoneOffset.UTC))
        )
    }

    /** История за сутки: два обновления евро — так у сводки есть с чем сравнить текущий курс. */
    private fun seed(repository: FakeCurrencyRateRepository) {
        repository.seed(
            listOf(
                CurrencyRate(Currency.EUR, BigDecimal("94.8709"), now.minusSeconds(6 * 3600)),
                CurrencyRate(Currency.USD, BigDecimal("84.0000"), now.minusSeconds(6 * 3600)),
                CurrencyRate(Currency.GEL, BigDecimal("32.0000"), now.minusSeconds(6 * 3600)),
                CurrencyRate(Currency.EUR, BigDecimal("95.8709"), now),
                CurrencyRate(Currency.USD, BigDecimal("84.3414"), now),
                CurrencyRate(Currency.GEL, BigDecimal("32.1693"), now)
            )
        )
    }

    private fun CallToolResult.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}
