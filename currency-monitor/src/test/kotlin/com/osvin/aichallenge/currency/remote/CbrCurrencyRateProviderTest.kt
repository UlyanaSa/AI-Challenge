package com.osvin.aichallenge.currency.remote

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyConfig
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Разбор ответа ЦБ: адрес запроса, деление на номинал, неполный ответ и отказы.
 *
 * Сеть заменена движком MockEngine: проверяется разбор и тексты ошибок, а не связь с ЦБ —
 * настоящий ответ уже лежит в теле проверки ([CBR_RESPONSE]), и повторять его по сети значило бы
 * зависеть от чужого сервера и его расписания. Часы фиксированные ([CLOCK]): момент получения —
 * часть разбора, и он не должен меняться между запусками.
 *
 * Курсы сравниваются по значению ([assertRate]), а не через `BigDecimal.equals`: разбор округляет
 * до шести знаков, и 32.1693 против 32.169300 — один и тот же курс, тогда как `equals` счёл бы
 * их разными. Проверяется то, что важно читателю курса, — сколько рублей стоит единица валюты.
 */
class CbrCurrencyRateProviderTest {

    @Test
    fun `ответ ЦБ разбирается в курсы трёх валют`() = runBlocking {
        lateinit var asked: String
        val provider = provider { request ->
            asked = request.url.toString()
            respond(CBR_RESPONSE)
        }

        val rates = provider.getRates()

        assertEquals("$API_BASE/daily_json.js", asked, "запрос ушёл не за файлом курсов ЦБ")
        assertEquals(listOf(Currency.EUR, Currency.USD, Currency.GEL), rates.map { it.currency })
        assertRate("95.8709", rates.rate(Currency.EUR))
        assertRate("84.3414", rates.rate(Currency.USD))
        assertRate("32.1693", rates.rate(Currency.GEL))
    }

    @Test
    fun `номинал превращается в цену одной единицы`() = runBlocking {
        val rates = provider { respond("""{"Valute":{"GEL":{"Nominal":10,"Value":321.693}}}""") }.getRates()

        assertEquals(listOf(Currency.GEL), rates.map { it.currency })
        assertRate("32.1693", rates.rate(Currency.GEL))
        assertEquals(6, rates.rate(Currency.GEL).scale(), "курс округлён не до шести знаков")
    }

    @Test
    fun `ответ без валюты не ошибка`() = runBlocking {
        val body = """
            {"Valute":{"EUR":{"Nominal":1,"Value":95.8709},"GEL":{"Nominal":1,"Value":32.1693}}}
        """.trimIndent()

        val rates = provider { respond(body) }.getRates()

        assertEquals(listOf(Currency.EUR, Currency.GEL), rates.map { it.currency })
    }

    @Test
    fun `отслеживаемые валюты берутся из ответа, остальные ключи игнорируются`() = runBlocking {
        val body = """
            {"Valute":{"JPY":{"Nominal":100,"Value":55.0},"EUR":{"Nominal":1,"Value":95.8709}}}
        """.trimIndent()

        val rates = provider { respond(body) }.getRates()

        assertEquals(listOf(Currency.EUR), rates.map { it.currency })
    }

    @Test
    fun `непригодный курс в результат не попадает`() = runBlocking {
        val missingValue = """{"Valute":{"EUR":{"Nominal":1,"Value":95.8709},"USD":{"Nominal":1}}}"""
        assertEquals(
            listOf(Currency.EUR),
            provider { respond(missingValue) }.getRates().map { it.currency },
            "валюта без Value попала в результат"
        )

        val unparsable = """{"Valute":{"EUR":{"Nominal":1,"Value":95.8709},"USD":{"Nominal":1,"Value":"нет"}}}"""
        assertEquals(
            listOf(Currency.EUR),
            provider { respond(unparsable) }.getRates().map { it.currency },
            "неразобранное число принято за курс"
        )

        val zeroNominal = """{"Valute":{"EUR":{"Nominal":1,"Value":95.8709},"GEL":{"Nominal":0,"Value":321.693}}}"""
        assertEquals(
            listOf(Currency.EUR),
            provider { respond(zeroNominal) }.getRates().map { it.currency },
            "деление на нулевой номинал прошло как курс"
        )
    }

    @Test
    fun `ноль и отрицательное значение курсом не считаются`() = runBlocking {
        val body = """
            {"Valute":{
              "EUR":{"Value":-1},
              "USD":{"Value":0},
              "GEL":{"Value":32.1693}}}
        """.trimIndent()

        val rates = provider { respond(body) }.getRates()

        assertEquals(listOf(Currency.GEL), rates.map { it.currency })
    }

    @Test
    fun `валюта без номинала считается по номиналу один`() = runBlocking {
        val rates = provider { respond("""{"Valute":{"USD":{"Value":84.3414}}}""") }.getRates()

        assertRate("84.3414", rates.rate(Currency.USD))
    }

    @Test
    fun `момент получения берётся из часов и усечён до секунд`() = runBlocking {
        val moments = provider { respond(CBR_RESPONSE) }.getRates().map { it.receivedAt }.distinct()

        assertEquals(listOf(Instant.parse("2026-09-28T09:15:30Z")), moments)
    }

    @Test
    fun `отказ ЦБ объясняется кодом, а не телом`() = runBlocking {
        val failure = assertFailsWith<CurrencyRateException> {
            provider { respond("Сервис недоступен", HttpStatusCode.ServiceUnavailable) }.getRates()
        }

        assertEquals("ЦБ ответил 503", failure.message)
        assertTrue("Сервис недоступен" !in failure.message.orEmpty(), "тело ответа попало в текст ошибки")
    }

    @Test
    fun `неразобранный ответ — это ошибка разбора`() = runBlocking {
        val text = assertFailsWith<CurrencyRateException> {
            provider { respond("<html>вместо файла курсов</html>") }.getRates()
        }
        assertTrue("не разобран" in text.message.orEmpty(), "причина не названа: ${text.message}")

        val shape = assertFailsWith<CurrencyRateException> {
            provider { respond("[1,2,3]") }.getRates()
        }
        assertTrue("не разобран" in shape.message.orEmpty(), "чужая форма ответа разобрана как курсы")

        val withoutValute = assertFailsWith<CurrencyRateException> {
            provider { respond("""{"Date":"2026-09-26T11:30:00+03:00"}""") }.getRates()
        }
        assertTrue("не разобран" in withoutValute.message.orEmpty())
    }

    @Test
    fun `сбой связи — это «ЦБ не ответил»`() = runBlocking {
        val failure = assertFailsWith<CurrencyRateException> {
            provider { throw IOException("обрыв соединения") }.getRates()
        }

        assertTrue("не ответил" in failure.message.orEmpty(), "причина не названа: ${failure.message}")
    }

    @Test
    fun `формат ответа берётся из тела, а не из заголовка`() = runBlocking {
        val provider = provider {
            respond(CBR_RESPONSE, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        }

        assertEquals(3, provider.getRates().size, "разбор отказал из-за заголовка Content-Type")
    }

    /** Провайдер на подставном движке: адрес настоящий, сеть — нет. */
    private fun provider(handler: MockRequestHandler): CbrCurrencyRateProvider =
        CbrCurrencyRateProvider(
            config = CurrencyConfig(apiBase = API_BASE),
            clock = CLOCK,
            client = HttpClient(MockEngine(handler))
        )

    /** Курс валюты из набора; отсутствие курса — отказ проверки, а не null в сравнении. */
    private fun List<CurrencyRate>.rate(currency: Currency): BigDecimal {
        val rate = firstOrNull { it.currency == currency }
        assertNotNull(rate, "в наборе нет курса $currency")
        return rate.rateToRub
    }

    /** Сравнение денег по значению: 32.1693 и 32.169300 — один курс, а `equals` различает длину. */
    private fun assertRate(expected: String, actual: BigDecimal) {
        assertEquals(0, expected.toBigDecimal().compareTo(actual), "получили $actual вместо $expected")
    }

    private companion object {

        const val API_BASE = "https://www.cbr-xml-daily.ru"

        /**
         * Момент с миллисекундами: разбор обязан усечь его до секунды, и по ответу видно,
         * что время берётся из этих часов, а не из системных.
         */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-28T09:15:30.750Z"), ZoneOffset.UTC)

        /**
         * Ответ ЦБ вживую: дата публикации, раздел валют и у каждой валюты идентификаторы,
         * название и предыдущее значение, которых разбор не касается.
         */
        val CBR_RESPONSE = """
            {"Date":"2026-09-26T11:30:00+03:00","PreviousDate":"2026-09-25T11:30:00+03:00","Valute":{
              "EUR":{"ID":"R01239","NumCode":"978","CharCode":"EUR","Nominal":1,"Name":"Евро","Value":95.8709,"Previous":96.8859},
              "USD":{"ID":"R01235","NumCode":"840","CharCode":"USD","Nominal":1,"Name":"Доллар США","Value":84.3414,"Previous":85.1234},
              "GEL":{"ID":"R01204","NumCode":"981","CharCode":"GEL","Nominal":1,"Name":"Лари","Value":32.1693,"Previous":32.5}}}
        """.trimIndent()
    }
}
