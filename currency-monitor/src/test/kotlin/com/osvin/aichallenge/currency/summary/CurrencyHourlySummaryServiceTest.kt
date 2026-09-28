package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.FakeCurrencyHourlySummaryRepository
import com.osvin.aichallenge.currency.FakeCurrencyRateRepository
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Часовые сводки: что попадает в час, что складывается в окно и как сообщается недобор.
 *
 * Проверки идут на двойниках истории и сводок, но с настоящими числами и настоящими границами
 * часов: правило округления, взвешивание среднего и конец окна — это и есть поведение сервиса,
 * и на подставных числах проверялось бы не оно. Файла базы здесь нет намеренно: хранение
 * проверяется отдельно ([com.osvin.aichallenge.currency.storage.SqliteCurrencyHourlySummaryRepositoryTest]),
 * а тут важно, что сервис считает, а не как он это кладёт.
 *
 * Часы сервиса подменены фиксированным моментом: «предыдущий час» иначе зависел бы от времени
 * запуска, и проверка не повторялась бы дважды одинаково.
 */
class CurrencyHourlySummaryServiceTest {

    private val rates = FakeCurrencyRateRepository()
    private val summaries = FakeCurrencyHourlySummaryRepository()

    /** Момент проверки: 20 минут двенадцатого — предыдущий закрытый час это 11:00. */
    private val now: Instant = Instant.parse("2026-09-28T12:20:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = CurrencyHourlySummaryService(rates, summaries, clock)

    @Test
    fun `closeHour summarises the previous hour and leaves the current one alone`() = runBlocking {
        rates.seed(
            listOf(
                rate("2026-09-28T11:00:00Z", "95.0000"),
                rate("2026-09-28T11:30:00Z", "95.2500"),
                rate("2026-09-28T11:59:59Z", "95.5000"),
                // Начало следующего часа принадлежит уже ему: сводка часа — окно, и граница
                // входит в него только слева.
                rate("2026-09-28T12:00:00Z", "99.0000")
            )
        )

        assertEquals(1, service.closeHour())

        val summary = summaries.stored.single()
        assertEquals(Currency.EUR, summary.currency)
        assertEquals(Instant.parse("2026-09-28T11:00:00Z"), summary.hour)
        assertEquals(BigDecimal("95.0000"), summary.firstRate)
        assertEquals(BigDecimal("95.5000"), summary.lastRate)
        assertEquals(BigDecimal("0.5000"), summary.change)
        // Проценты считаются от первого курса часа: 0.5 от 95 — это 0.5263%.
        assertEquals(BigDecimal("0.5263"), summary.changePercent)
        assertEquals(BigDecimal("95.0000"), summary.minRate)
        assertEquals(BigDecimal("95.5000"), summary.maxRate)
        assertEquals(BigDecimal("95.2500"), summary.averageRate)
        assertEquals(3, summary.samples)
    }

    @Test
    fun `closeHour writes nothing for an hour without records`() = runBlocking {
        assertEquals(0, service.closeHour())

        assertTrue(summaries.stored.isEmpty(), "час без записей не должен стать сводкой с нулями")
    }

    @Test
    fun `closing the hour again picks up a record added later`() = runBlocking {
        rates.seed(listOf(rate("2026-09-28T11:10:00Z", "95.0000")))
        service.closeHour()
        rates.seed(listOf(rate("2026-09-28T11:50:00Z", "96.0000")))

        service.closeHour()

        val summary = summaries.stored.single()
        assertEquals(BigDecimal("95.0000"), summary.firstRate)
        assertEquals(BigDecimal("96.0000"), summary.lastRate)
        assertEquals(2, summary.samples, "найденная позже запись того же часа обязана попасть в сводку")
    }

    @Test
    fun `change counts the saved hours and reports how much of the window is covered`() = runBlocking {
        summaries.seed(
            listOf(
                hour("2026-09-28T10:00:00Z", first = "95.0000", last = "95.5000", average = "95.2500"),
                hour("2026-09-28T11:00:00Z", first = "95.5000", last = "96.5000", average = "96.0000")
            )
        )

        val window = service.change(24)

        assertEquals(24, window.hours)
        assertEquals(Instant.parse("2026-09-28T10:00:00Z"), window.from)
        // Конец окна — конец последнего закрытого часа, а не его начало.
        assertEquals(Instant.parse("2026-09-28T12:00:00Z"), window.to)
        assertEquals(2, window.hoursCovered, "за сутки сохранено два часа, и это надо сказать честно")
        assertEquals(Currency.entries, window.changes.map { it.currency })

        val eur = window.changes.first()
        assertEquals(BigDecimal("95.0000"), eur.firstRate)
        assertEquals(BigDecimal("96.5000"), eur.lastRate)
        assertEquals(BigDecimal("1.5000"), eur.change)
        assertEquals(BigDecimal("1.5789"), eur.changePercent)
        assertEquals(BigDecimal("95.0000"), eur.minRate)
        assertEquals(BigDecimal("96.5000"), eur.maxRate)
        // Среднее взвешено числом записей часов: (95.25 * 60 + 96.0 * 60) / 120.
        assertEquals(BigDecimal("95.6250"), eur.averageRate)
        assertEquals(120, eur.samples)
        assertEquals(2, eur.hours)
    }

    @Test
    fun `change answers with nulls for every currency when nothing is stored`() = runBlocking {
        val window = service.change(24)

        assertEquals(0, window.hoursCovered)
        assertNull(window.from)
        assertNull(window.to)
        assertEquals(Currency.entries, window.changes.map { it.currency })
        window.changes.forEach { change ->
            assertNull(change.firstRate)
            assertNull(change.lastRate)
            assertNull(change.change)
            assertEquals(0, change.hours)
            assertEquals(0, change.samples)
        }
    }

    @Test
    fun `change refuses a window without hours`() {
        assertFailsWith<IllegalArgumentException> { runBlocking { service.change(0) } }
    }

    /** Курс с секундной точностью: время записи — тот же текст, что лёг бы в базу. */
    private fun rate(receivedAt: String, value: String): CurrencyRate =
        CurrencyRate(Currency.EUR, BigDecimal(value), Instant.parse(receivedAt))

    /** Сводка часа без разбора её чисел: проверке окна важны первый и последний курс и вес часа. */
    private fun hour(hour: String, first: String, last: String, average: String): CurrencyHourlySummary =
        CurrencyHourlySummary(
            currency = Currency.EUR,
            hour = Instant.parse(hour),
            firstRate = BigDecimal(first),
            lastRate = BigDecimal(last),
            change = BigDecimal(last).subtract(BigDecimal(first)),
            changePercent = null,
            minRate = BigDecimal(first),
            maxRate = BigDecimal(last),
            averageRate = BigDecimal(average),
            samples = 60
        )
}
