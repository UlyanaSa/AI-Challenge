package com.osvin.aichallenge.currency

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки сбора: что попадает в историю, а что нет.
 *
 * Сбор — единственное место, где данные попадают в историю, и ошибка здесь не видна ни в логе
 * (он говорит «сохранено»), ни в сборке: испорченная запись всплывёт потом в сводке, когда
 * минимум за сутки окажется нулём, а причина будет уже неочевидна. Поэтому проверяются обе
 * стороны обновления: что сохраняется и что при этом пропускается.
 */
class CurrencyServiceTest {

    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")

    @Test
    fun `full answer is saved and reported as saved`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val service = CurrencyService(FakeCurrencyRateProvider(rates = rates("95.8709")), repository)

        val update = service.update()

        val saved = update as CurrencyUpdate.Saved
        assertEquals(3, saved.rows)
        assertEquals(Currency.entries, saved.rates.map { it.currency })
        assertEquals(emptyList(), saved.skipped)
        assertEquals(3, repository.history.size)
    }

    @Test
    fun `currency missing from the answer is skipped and does not stop the others`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val provider = FakeCurrencyRateProvider(
            rates = listOf(CurrencyRate(Currency.EUR, BigDecimal("95.8709"), now))
        )

        val saved = CurrencyService(provider, repository).update() as CurrencyUpdate.Saved

        assertEquals(listOf(Currency.EUR), saved.rates.map { it.currency })
        assertEquals(listOf(Currency.USD, Currency.GEL), saved.skipped)
        assertEquals(1, repository.history.size)
    }

    @Test
    fun `non-positive rate is not saved`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val provider = FakeCurrencyRateProvider(
            rates = listOf(
                CurrencyRate(Currency.EUR, BigDecimal("95.8709"), now),
                CurrencyRate(Currency.USD, BigDecimal.ZERO, now),
                CurrencyRate(Currency.GEL, BigDecimal("-32.1693"), now)
            )
        )

        val saved = CurrencyService(provider, repository).update() as CurrencyUpdate.Saved

        assertEquals(listOf(Currency.EUR), saved.rates.map { it.currency })
        assertEquals(listOf(Currency.USD, Currency.GEL), saved.skipped)
        assertEquals(listOf(Currency.EUR), repository.history.map { it.currency })
    }

    @Test
    fun `provider failure keeps the history it had`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        repository.seed(listOf(CurrencyRate(Currency.EUR, BigDecimal("94.0000"), now.minusSeconds(3600))))
        val provider = FakeCurrencyRateProvider(failure = CurrencyRateException("ЦБ не ответил: timeout"))

        val update = CurrencyService(provider, repository).update()

        assertEquals("ЦБ не ответил: timeout", (update as CurrencyUpdate.Failed).reason)
        assertEquals(listOf(BigDecimal("94.0000")), repository.history.map { it.rateToRub })
    }

    @Test
    fun `storage failure is reported as a failed update rather than an exception`() = runBlocking {
        val repository = FakeCurrencyRateRepository().apply { failure = CurrencyStorageException("база занята") }

        val update = CurrencyService(FakeCurrencyRateProvider(), repository).update()

        assertTrue(update is CurrencyUpdate.Failed, "сбор не должен ронять цикл планировщика")
        assertEquals("база занята", update.reason)
    }

    @Test
    fun `latest returns what the history holds`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val service = CurrencyService(FakeCurrencyRateProvider(rates = rates("95.8709")), repository)

        service.update()

        assertEquals(3, service.latest().size)
        assertEquals(BigDecimal("95.8709"), service.latest().first { it.currency == Currency.EUR }.rateToRub)
    }

    /** Полный ответ поставщика: все три валюты по одной цене. */
    private fun rates(value: String): List<CurrencyRate> =
        Currency.entries.map { CurrencyRate(it, BigDecimal(value), now) }
}
