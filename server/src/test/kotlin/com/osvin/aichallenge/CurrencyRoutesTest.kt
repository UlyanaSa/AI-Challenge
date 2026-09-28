package com.osvin.aichallenge

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import com.osvin.aichallenge.currency.storage.CurrencyDatabase
import com.osvin.aichallenge.currency.storage.SqliteCurrencyHourlySummaryRepository
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummary
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Маршруты курсов на настоящем MCP-процессе: текущие значения и изменение за сутки.
 *
 * Прогон живой в той части, которая принадлежит серверу: сервис курсов — настоящий отдельный
 * процесс по протоколу MCP, поднятый так же, как его поднимает приложение, и база у него
 * настоящая (файл во временном каталоге). Подставной только ЦБ — HTTP-сервер на свободном
 * порту в этом же тесте, как и у маршрутов GitHub. Код-путь от подстановки не меняется: сервис
 * берёт адрес источника из `CURRENCY_API_BASE` и ходит по нему так же, как ходил бы в ЦБ.
 *
 * Часовые сводки кладутся в базу до запуска процесса: суточное изменение считается по ним,
 * и проверять маршрут на пустом окне значило бы проверять только отказ. Часы берутся от
 * текущего момента, потому что окно отсчитывается от «сейчас»: вписанная дата выпала бы
 * из него на следующий день, и проверка стала бы хрупкой.
 *
 * Проверяется контракт маршрутов, а не внутренности: тело ответа — ответ инструмента с числами-
 * числами, покрытие окна и изменение по каждой валюте, 400 на мусорный `hours` (это ошибка
 * запроса, а не отказ службы) и 502, когда сессию поднять не удалось.
 *
 * Процесс сервиса и подставной ЦБ гасятся в `finally`: прогон не должен оставлять после себя
 * ни чужого процесса, ни занятого порта, ни временного каталога.
 */
class CurrencyRoutesTest {

    /** Разбор ответов: проверяется структура, а не точный текст. */
    private val json = Json { ignoreUnknownKeys = true }

    private val directory: Path = Files.createTempDirectory("currency-routes")

    /** Временный каталог с базой удаляется за проверкой: они не должны копиться. */
    @AfterTest
    fun removeDatabaseFiles() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `маршруты курсов — текущие значения и изменение за сутки`() {
        val cbr = StubCbrApi()
        val database = directory.resolve("currency.db")
        seedSummaries(database)
        val currencyTools = CurrencyTools(
            config = localMcpServerConfig(
                CurrencyMcpServer.MAIN_CLASS,
                env = mapOf(
                    "CURRENCY_API_BASE" to cbr.baseUrl,
                    "CURRENCY_DB" to database.toString(),
                    // Минутный сбор — тот же, что у приложения: маршрут спрашивает уже собранное,
                    // и частота сбора на ответ не влияет.
                    "CURRENCY_INTERVAL" to "PT1M"
                )
            ),
            onLog = { println("[agent] $it") }
        )

        try {
            testApplication {
                application { module(currencyTools = currencyTools) }

                val rates = client.get("/v1/currency")
                val ratesBody = rates.bodyAsText()
                assertEquals(HttpStatusCode.OK, rates.status, ratesBody)
                val snapshot = json.parseToJsonElement(ratesBody).jsonObject
                assertNotNull(snapshot["updatedAt"], "курсы собраны, и время сбора должно быть названо: $ratesBody")
                val values = snapshot.getValue("rates").jsonObject
                assertEquals(setOf("EUR", "USD", "GEL"), values.keys, ratesBody)
                val eur = values.getValue("EUR").jsonPrimitive
                assertEquals(BigDecimal("95.8709"), eur.content.toBigDecimal())
                assertFalse(eur.isString, "курс — число, а не строка: $ratesBody")

                val change = client.get("/v1/currency/change?hours=24")
                val changeBody = change.bodyAsText()
                assertEquals(HttpStatusCode.OK, change.status, changeBody)
                val window = json.parseToJsonElement(changeBody).jsonObject
                assertEquals(24, window.getValue("hours").jsonPrimitive.int, changeBody)
                assertEquals(
                    2,
                    window.getValue("hoursCovered").jsonPrimitive.int,
                    "в окне суток лежат два сохранённых часа: $changeBody"
                )
                val changes = window.getValue("currencies").jsonArray.map { it.jsonObject }
                assertEquals(
                    listOf("EUR", "USD", "GEL"),
                    changes.map { it.getValue("currency").jsonPrimitive.content },
                    changeBody
                )
                val eurChange = changes.first()
                assertEquals(BigDecimal("95.0"), eurChange.getValue("firstRate").jsonPrimitive.content.toBigDecimal())
                assertEquals(BigDecimal("96.5"), eurChange.getValue("lastRate").jsonPrimitive.content.toBigDecimal())
                assertEquals(BigDecimal("1.5"), eurChange.getValue("change").jsonPrimitive.content.toBigDecimal())
                assertEquals(
                    BigDecimal("1.5789"),
                    eurChange.getValue("changePercent").jsonPrimitive.content.toBigDecimal(),
                    changeBody
                )
                assertEquals(BigDecimal("95.625"), eurChange.getValue("averageRate").jsonPrimitive.content.toBigDecimal())
                assertEquals(4, eurChange.getValue("samples").jsonPrimitive.int, changeBody)
                assertNull(window["note"], "сводки есть — объясняться не о чем: $changeBody")

                // Окно проверяется на сервере до обращения к сервису: мусор в hours — ошибка
                // запроса, и 502 здесь выглядел бы как отказ службы курсов.
                listOf(
                    "/v1/currency/change",
                    "/v1/currency/change?hours=",
                    "/v1/currency/change?hours=сутки",
                    "/v1/currency/change?hours=0",
                    "/v1/currency/change?hours=721"
                ).forEach { path ->
                    val bad = client.get(path)

                    assertEquals(HttpStatusCode.BadRequest, bad.status, "$path → ${bad.bodyAsText()}")
                    assertTrue(bad.bodyAsText().contains("hours"), "$path → ${bad.bodyAsText()}")
                }
            }
        } finally {
            runBlocking { currencyTools.close() }
            cbr.stop()
        }
    }

    @Test
    fun `курсы без службы — отказ шлюза, а не пустая лента`() {
        // Процесс не поднимается вовсе: пустой лентой такой отказ показывать нельзя —
        // «курсы не менялись» и «служба не отвечает» разные вещи.
        val currencyTools = CurrencyTools(config = McpServerConfig(command = "/nonexistent/java"))

        try {
            testApplication {
                application { module(currencyTools = currencyTools) }

                val rates = client.get("/v1/currency")
                assertEquals(HttpStatusCode.BadGateway, rates.status, rates.bodyAsText())
                assertTrue(rates.bodyAsText().contains("error"), rates.bodyAsText())

                val change = client.get("/v1/currency/change?hours=24")
                assertEquals(HttpStatusCode.BadGateway, change.status, change.bodyAsText())
            }
        } finally {
            runBlocking { currencyTools.close() }
        }
    }

    /**
     * Два закрытых часа в базе сервиса: последний закрытый и час до него.
     *
     * Сводки кладутся напрямую, а не собираются сбором: собрать закрытый час в проверке можно
     * было бы только ожиданием границы часа, а проверке нужны не пути записи, а то, что маршрут
     * считает изменение по сохранённым часам.
     */
    private fun seedSummaries(database: Path) {
        val lastClosed = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS)
        val summaries = listOf(
            eurHour(lastClosed.minus(1, ChronoUnit.HOURS), first = "95.0000", last = "95.5000"),
            eurHour(lastClosed, first = "95.5000", last = "96.5000")
        )
        val db = CurrencyDatabase(database)
        try {
            runBlocking { SqliteCurrencyHourlySummaryRepository(db).save(summaries) }
        } finally {
            db.close()
        }
    }

    /** Сводка часа евро: проверке важны её первый и последний курс, а не середина. */
    private fun eurHour(hour: Instant, first: String, last: String): CurrencyHourlySummary =
        CurrencyHourlySummary(
            currency = Currency.EUR,
            hour = hour,
            firstRate = BigDecimal(first),
            lastRate = BigDecimal(last),
            change = BigDecimal(last).subtract(BigDecimal(first)),
            changePercent = null,
            minRate = BigDecimal(first),
            maxRate = BigDecimal(last),
            averageRate = BigDecimal(first).add(BigDecimal(last)).divide(BigDecimal(2), 4, java.math.RoundingMode.HALF_UP),
            samples = 2
        )
}

/**
 * Подставной ЦБ: `GET /daily_json.js` с тем же JSON, что отдаёт настоящий источник.
 *
 * Три отслеживаемые валюты с номиналом 1 — ровно те, за которыми следит сервис. Адрес
 * подставляется сервису переменной окружения: настоящего ЦБ здесь нет, но код-путь — тот же,
 * что с настоящим.
 */
private class StubCbrApi {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/daily_json.js") { exchange ->
            val body = RATES.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private companion object {

        /** Ответ ЦБ: раздел `Valute` с ценами за одну единицу валюты. */
        val RATES = """
            {
              "Date": "2026-09-28T11:30:00+03:00",
              "Valute": {
                "EUR": {"Value": 95.8709, "Nominal": 1},
                "USD": {"Value": 84.3414, "Nominal": 1},
                "GEL": {"Value": 32.1693, "Nominal": 1}
              }
            }
        """.trimIndent()
    }
}
