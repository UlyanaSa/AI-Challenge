package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.FakeCurrencyHourlySummaryRepository
import com.osvin.aichallenge.currency.FakeCurrencyRateProvider
import com.osvin.aichallenge.currency.FakeCurrencyRateRepository
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummary
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryService
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
    fun `change tool answers with the change per currency from the stored hours`() {
        val summaries = FakeCurrencyHourlySummaryRepository(
            listOf(
                hour("2026-09-28T10:00:00Z", first = "95.0000", last = "95.5000", average = "95.2500"),
                hour("2026-09-28T11:00:00Z", first = "95.5000", last = "96.5000", average = "96.0000")
            )
        )

        val body = call(
            CurrencyMcpServer.tools().change(),
            data(FakeCurrencyRateRepository(), emptyMap(), summaries),
            mapOf("hours" to "24")
        ).json()

        assertEquals(24, body.getValue("hours").jsonPrimitive.int)
        assertEquals(2, body.getValue("hoursCovered").jsonPrimitive.int)
        assertEquals("2026-09-28T10:00:00Z", body.getValue("from").jsonPrimitive.content)
        assertEquals("2026-09-28T12:00:00Z", body.getValue("to").jsonPrimitive.content)
        val currencies = body.getValue("currencies").jsonArray.map { it.jsonObject }
        assertEquals(listOf("EUR", "USD", "GEL"), currencies.map { it.getValue("currency").jsonPrimitive.content })
        val eur = currencies.first()
        // Число в JSON — значение, а не текст: конечные нули масштаба оно теряет, и «1.5000»
        // уезжает как «1.5». Для модели это то же число, а строкой его отдавать нельзя.
        assertEquals(BigDecimal("1.5"), eur.getValue("change").jsonPrimitive.content.toBigDecimal())
        assertEquals(BigDecimal("1.5789"), eur.getValue("changePercent").jsonPrimitive.content.toBigDecimal())
        assertEquals(2, eur.getValue("hours").jsonPrimitive.int)
        assertEquals(120, eur.getValue("samples").jsonPrimitive.int)
        // Валюта без сводок остаётся в ответе с null: пропуск читался бы как «валюта не отслеживается».
        assertEquals(JsonNull, currencies[1]["change"])
        assertEquals(0, currencies[1].getValue("hours").jsonPrimitive.int)
        assertNull(body["note"], "окно покрыто не полностью, но сводки есть — объясняться не о чем")
    }

    @Test
    fun `change tool explains an empty window instead of answering with zeros`() {
        val result = call(
            CurrencyMcpServer.tools().change(),
            data(FakeCurrencyRateRepository(), emptyMap()),
            mapOf("hours" to "24")
        )
        val body = result.json()

        assertEquals(0, body.getValue("hoursCovered").jsonPrimitive.int)
        assertEquals(JsonNull, body["from"])
        assertNotNull(body["note"], "пустое окно без объяснения читается как сбой службы")
    }

    @Test
    fun `change tool refuses a missing or non-numeric hours and names the bounds`() {
        val tool = CurrencyMcpServer.tools().change()
        val data = data(FakeCurrencyRateRepository(), emptyMap())

        listOf(null, "сутки").forEach { value ->
            val result = call(tool, data, if (value == null) emptyMap() else mapOf("hours" to value))

            assertEquals(true, result.isError, "hours=$value должен быть отвергнут")
            assertTrue(result.text().contains("от 1 до 720"), result.text())
        }
    }

    @Test
    fun `change tool refuses hours outside the allowed range`() {
        val tool = CurrencyMcpServer.tools().change()
        val data = data(FakeCurrencyRateRepository(), emptyMap())

        listOf("0", "-3", "721").forEach { value ->
            val result = call(tool, data, mapOf("hours" to value))

            assertEquals(true, result.isError, "hours=$value должен быть отвергнут")
            assertTrue(result.text().contains("вне допустимого"), result.text())
        }
    }

    @Test
    fun `change tool reports storage failure as a tool refusal`() {
        val summaries = FakeCurrencyHourlySummaryRepository().apply {
            failure = CurrencyStorageException("сводки недоступны")
        }

        val result = call(
            CurrencyMcpServer.tools().change(),
            data(FakeCurrencyRateRepository(), emptyMap(), summaries),
            mapOf("hours" to "24")
        )

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("сводки недоступны"))
    }

    @Test
    fun `server declares all three tools and the required arguments`() {
        val tools = CurrencyMcpServer.tools()

        assertEquals(
            listOf("get_currency_rates", "get_currency_summary", "get_currency_change"),
            tools.map { it.name }
        )
        assertEquals(emptyList(), tools.first { it.name == "get_currency_rates" }.arguments)
        val period = tools.first { it.name == "get_currency_summary" }.arguments.single()
        assertEquals("period", period.name)
        assertTrue(period.required)
        assertEquals(listOf("DAY", "WEEK", "MONTH"), period.values)
        // Часы объявлены без списка значений: их слишком много для схемы, и пределы проверяет
        // обработчик — тем же числом, которым объявлено описание.
        val hours = tools.first { it.name == "get_currency_change" }.arguments.single()
        assertEquals("hours", hours.name)
        assertTrue(hours.required)
        assertEquals(emptyList(), hours.values)
        assertTrue(hours.description.contains("от 1 до 720"), hours.description)
    }

    /** Инструмент по имени из объявленного списка: проверка не повторяет его объявление у себя. */
    private fun List<ServerTool<CurrencyToolsData>>.rates() = first { it.name == "get_currency_rates" }

    private fun List<ServerTool<CurrencyToolsData>>.summary() = first { it.name == "get_currency_summary" }

    private fun List<ServerTool<CurrencyToolsData>>.change() = first { it.name == "get_currency_change" }

    /** Сводка закрытого часа для проверок окна: числа — те, что проверка хочет увидеть в ответе. */
    private fun hour(at: String, first: String, last: String, average: String): CurrencyHourlySummary =
        CurrencyHourlySummary(
            currency = Currency.EUR,
            hour = Instant.parse(at),
            firstRate = BigDecimal(first),
            lastRate = BigDecimal(last),
            change = BigDecimal(last).subtract(BigDecimal(first)),
            changePercent = null,
            minRate = BigDecimal(first),
            maxRate = BigDecimal(last),
            averageRate = BigDecimal(average),
            samples = 60
        )

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
    private fun data(
        repository: FakeCurrencyRateRepository,
        rates: Map<Currency, String>,
        summaries: FakeCurrencyHourlySummaryRepository = FakeCurrencyHourlySummaryRepository()
    ): CurrencyToolsData {
        val provider = FakeCurrencyRateProvider(
            rates = rates.map { (currency, value) ->
                CurrencyRate(currency, BigDecimal(value), now)
            }
        )
        val service = CurrencyService(provider, repository)
        runBlocking { service.update() }
        val clock = Clock.fixed(now, java.time.ZoneOffset.UTC)
        return CurrencyToolsData(
            service,
            CurrencySummaryService(repository, clock),
            CurrencyHourlySummaryService(repository, summaries, clock)
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
