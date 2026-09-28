package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.FakeCurrencyRateRepository
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Проверки сводки: окно, границы, среднее, предыдущий курс и округление — на истории в памяти.
 *
 * Момент «сейчас» зафиксирован ([NOW]) и задан часам сервиса, поэтому проверка не зависит
 * от даты запуска: все записи расставлены относительно [NOW], а не относительно системных часов.
 */
class CurrencySummaryServiceTest {

    @Test
    fun windowGivesBoundsAverageAndSamples() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(
            listOf(
                rate("90.000000", NOW.minus(Duration.ofHours(2))),
                rate("95.000000", NOW.minus(Duration.ofHours(1))),
                rate("100.000000", NOW)
            )
        )

        val summary = summaryOf(repository)

        // Текущий — последняя точка окна, предыдущий — точка перед ней, из той же истории.
        assertEquals(BigDecimal("100.0000"), summary.currentRate)
        assertEquals(BigDecimal("95.0000"), summary.previousRate)
        assertEquals(BigDecimal("5.0000"), summary.change)
        assertEquals(BigDecimal("5.2632"), summary.changePercent)
        assertEquals(BigDecimal("90.0000"), summary.minRate)
        assertEquals(BigDecimal("100.0000"), summary.maxRate)
        assertEquals(BigDecimal("95.0000"), summary.averageRate)
        assertEquals(3, summary.samples)
    }

    @Test
    fun previousOutsideWindowGivesChangeAndPercent() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(
            listOf(
                rate("90.000000", NOW.minus(Duration.ofHours(26))),
                rate("99.000000", NOW.minus(Duration.ofHours(2)))
            )
        )

        val summary = summaryOf(repository)

        // Запись старше суток в окно не входит, но остаётся предыдущим курсом: 99 от 90 — рост 10 %.
        assertEquals(BigDecimal("99.0000"), summary.currentRate)
        assertEquals(BigDecimal("90.0000"), summary.previousRate)
        assertEquals(BigDecimal("9.0000"), summary.change)
        assertEquals(BigDecimal("10.0000"), summary.changePercent)
        assertEquals(1, summary.samples)
        assertEquals(BigDecimal("99.0000"), summary.minRate)
        assertEquals(BigDecimal("99.0000"), summary.maxRate)
        assertEquals(BigDecimal("99.0000"), summary.averageRate)
    }

    @Test
    fun emptyWindowKeepsCurrencyWithNulls() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val service = CurrencySummaryService(repository, Clock.fixed(NOW, ZoneOffset.UTC))

        val summaries = service.summary(SummaryPeriod.DAY, NOW)

        assertEquals(Currency.entries, summaries.map { it.currency })
        summaries.forEach { summary ->
            assertNull(summary.currentRate)
            assertNull(summary.previousRate)
            assertNull(summary.change)
            assertNull(summary.changePercent)
            assertNull(summary.minRate)
            assertNull(summary.maxRate)
            assertNull(summary.averageRate)
            assertEquals(0, summary.samples)
        }
    }

    @Test
    fun recordsOlderThanWindowAreIgnored() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(
            listOf(
                rate("50.000000", NOW.minus(Duration.ofHours(30))),
                rate("60.000000", NOW.minus(Duration.ofHours(24)).minusSeconds(1)),
                rate("100.000000", NOW.minus(Duration.ofHours(24))),
                rate("110.000000", NOW.minus(Duration.ofHours(1)))
            )
        )

        val summary = summaryOf(repository)

        // Ни запись полутора суток назад, ни запись на секунду старше границы в окно не попали;
        // попавшая ровно на границу — попала.
        assertEquals(2, summary.samples)
        assertEquals(BigDecimal("100.0000"), summary.minRate)
        assertEquals(BigDecimal("110.0000"), summary.maxRate)
        assertEquals(BigDecimal("105.0000"), summary.averageRate)
    }

    @Test
    fun ratesRoundedToFourPlaces() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(listOf(rate("95.870900", NOW.minus(Duration.ofHours(1)))))

        val summary = summaryOf(repository)

        assertEquals(BigDecimal("95.8709"), summary.currentRate)
        assertEquals(BigDecimal("95.8709"), summary.averageRate)
    }

    @Test
    fun percentRoundedHalfUpToFourPlaces() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(
            listOf(
                rate("3.000000", NOW.minus(Duration.ofHours(26))),
                rate("4.000000", NOW.minus(Duration.ofHours(1)))
            )
        )

        val summary = summaryOf(repository)

        // Изменение 1 при предыдущем 3 даёт 33.3333… %, округление HALF_UP до четырёх знаков.
        assertEquals(BigDecimal("1.0000"), summary.change)
        assertEquals(BigDecimal("33.3333"), summary.changePercent)
    }

    @Test
    fun zeroPreviousGivesNullPercent() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(
            listOf(
                rate("0.000000", NOW.minus(Duration.ofHours(26))),
                rate("95.000000", NOW.minus(Duration.ofHours(1)))
            )
        )

        val summary = summaryOf(repository)

        // Деления на ноль нет: испорченная запись не роняет сводку, процент становится неизвестным.
        assertEquals(BigDecimal("0.0000"), summary.previousRate)
        assertEquals(BigDecimal("95.0000"), summary.change)
        assertNull(summary.changePercent)
    }

    @Test
    fun storageFailurePropagates() {
        val repository = FakeCurrencyRateRepository()
        repository.failure = CurrencyStorageException("историю курсов прочитать не удалось")
        val service = CurrencySummaryService(repository, Clock.fixed(NOW, ZoneOffset.UTC))

        runBlocking {
            assertFailsWith<CurrencyStorageException> { service.summary(SummaryPeriod.DAY, NOW) }
        }
    }

    /** Сводка по евро за сутки: проверки смотрят на одну валюту, остальные в ответе не мешают. */
    private suspend fun summaryOf(repository: FakeCurrencyRateRepository): CurrencySummary {
        val service = CurrencySummaryService(repository, Clock.fixed(NOW, ZoneOffset.UTC))
        return service.summary(SummaryPeriod.DAY).single { it.currency == Currency.EUR }
    }

    /** Курс евро на момент: значения задаются строкой, чтобы масштаб не терялся в `Double`. */
    private fun rate(amount: String, at: Instant): CurrencyRate =
        CurrencyRate(Currency.EUR, BigDecimal(amount), at)

    private companion object {
        /** Единственный момент проверок: от него отсчитываются все записи. */
        val NOW: Instant = Instant.parse("2026-09-28T12:00:00Z")
    }
}
