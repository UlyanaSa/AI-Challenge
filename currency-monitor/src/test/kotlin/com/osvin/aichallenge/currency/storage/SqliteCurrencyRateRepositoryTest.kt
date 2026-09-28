package com.osvin.aichallenge.currency.storage

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyStorageException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * История курсов в файле: что хранилище отдаёт и что в нём остаётся.
 *
 * Проверки идут на настоящем файле во временном каталоге, а не на двойнике: у этого хранилища
 * проверяются запросы, текстовые представления денег и времени и переживание перезапуска —
 * всё то, чего двойник не повторяет и чем настоящее хранилище отличается от памяти. Ни байт
 * SQL, ни вызовы драйвера здесь не проверяются: наблюдаемое — это то, что вернула выборка
 * и что видно после повторного открытия того же файла.
 *
 * Моменты заданы секундами и лежат на разных часах: время в базе хранится текстом, и проверка
 * порядка записей должна опираться на тот же текст, который туда положен.
 */
class SqliteCurrencyRateRepositoryTest {

    private val directory: Path = Files.createTempDirectory("currency-rates")
    private val file: Path = directory.resolve("rates.db")

    /** Файл базы и её журнал удаляются за проверкой: временные каталоги не должны копиться. */
    @AfterTest
    fun removeDatabaseFiles() {
        directory.toFile().deleteRecursively()
    }

    /** Три валюты сохраняются целиком, и десятичный курс возвращается тем же числом. */
    @Test
    fun latestReturnsSavedRatesInDeclarationOrder() = runBlocking {
        val repository = SqliteCurrencyRateRepository(file)
        try {
            assertTrue(repository.latest().isEmpty(), "пустая история уже что-то отдала")

            assertEquals(3, repository.save(update(FIRST, eur = "95.42", usd = "78.1005", gel = "2.9200")))

            val latest = repository.latest()
            assertEquals(Currency.entries, latest.map { it.currency })
            assertEquals(FIRST, latest[0].receivedAt)
            assertEquals(BigDecimal("95.42"), latest[0].rateToRub)
            assertTrue(
                latest[0].rateToRub.compareTo(BigDecimal(95.42)) != 0,
                "курс вернулся как число с плавающей точкой, а не как десятичное"
            )
            assertEquals(BigDecimal("78.1005"), latest[1].rateToRub)
            // Нули в конце тоже часть значения: числовой столбец их бы потерял.
            assertEquals(BigDecimal("2.9200"), latest[2].rateToRub)
        } finally {
            repository.close()
        }
    }

    /** Второе обновление дописывает историю, а не заменяет прежнее значение. */
    @Test
    fun secondUpdateAddsPointsInsteadOfReplacingThem() = runBlocking {
        val repository = SqliteCurrencyRateRepository(file)
        try {
            repository.save(update(FIRST, eur = "95.42", usd = "78.1005", gel = "2.9200"))
            repository.save(update(SECOND, eur = "96.10", usd = "78.9000", gel = "2.9300"))

            val window = repository.between(Currency.EUR, FIRST, SECOND)
            assertEquals(listOf(FIRST, SECOND), window.map { it.receivedAt })
            assertEquals(listOf(BigDecimal("95.42"), BigDecimal("96.10")), window.map { it.rateToRub })
            Currency.entries.forEach { currency ->
                assertEquals(2, repository.between(currency, FIRST, SECOND).size, "у «$currency» потерялась точка")
            }
            assertEquals(
                BigDecimal("96.10"),
                repository.latest().first { it.currency == Currency.EUR }.rateToRub
            )
        } finally {
            repository.close()
        }
    }

    /** Предыдущий курс — строго раньше момента: текущее обновление им не считается. */
    @Test
    fun previousReturnsTheUpdateStrictlyBeforeTheMoment() = runBlocking {
        val repository = SqliteCurrencyRateRepository(file)
        try {
            assertNull(repository.previous(Currency.EUR, FIRST), "до первой записи предыдущего курса нет")

            repository.save(update(FIRST, eur = "95.42", usd = "78.1005", gel = "2.9200"))
            repository.save(update(SECOND, eur = "96.10", usd = "78.9000", gel = "2.9300"))

            val previous = repository.previous(Currency.EUR, SECOND)
            assertEquals(BigDecimal("95.42"), previous?.rateToRub)
            assertEquals(FIRST, previous?.receivedAt)
            assertNull(
                repository.previous(Currency.EUR, FIRST),
                "курс обновления, до которого ищут, вернулся как предыдущий"
            )
        } finally {
            repository.close()
        }
    }

    /** Окно отдаёт свои границы, только свою валюту и по возрастанию времени. */
    @Test
    fun betweenReturnsOnlyTheWindowInAscendingOrder() = runBlocking {
        val repository = SqliteCurrencyRateRepository(file)
        try {
            repository.save(update(FIRST, eur = "95.00", usd = "78.00", gel = "2.90"))
            repository.save(update(SECOND, eur = "96.00", usd = "79.00", gel = "2.91"))
            repository.save(update(THIRD, eur = "97.00", usd = "80.00", gel = "2.92"))

            val window = repository.between(Currency.EUR, SECOND, THIRD)
            assertEquals(listOf(SECOND, THIRD), window.map { it.receivedAt })
            assertEquals(listOf(BigDecimal("96.00"), BigDecimal("97.00")), window.map { it.rateToRub })
            // Окно из одного момента отдаёт свою запись, а не пустоту и не соседние.
            assertEquals(
                listOf(BigDecimal("79.00")),
                repository.between(Currency.USD, SECOND, SECOND).map { it.rateToRub }
            )
            assertTrue(
                repository.between(Currency.EUR, FOURTH, FIFTH).isEmpty(),
                "окно без записей вернуло записи"
            )
        } finally {
            repository.close()
        }
    }

    /** История переживает закрытие: тот же файл открывается заново и видит прежние записи. */
    @Test
    fun historySurvivesReopen() = runBlocking {
        val first = SqliteCurrencyRateRepository(file)
        first.save(update(FIRST, eur = "95.42", usd = "78.1005", gel = "2.9200"))
        first.save(update(SECOND, eur = "96.10", usd = "78.9000", gel = "2.9300"))
        first.close()

        val reopened = SqliteCurrencyRateRepository(file)
        try {
            assertEquals(
                BigDecimal("96.10"),
                reopened.latest().first { it.currency == Currency.EUR }.rateToRub
            )
            assertEquals(2, reopened.between(Currency.EUR, FIRST, SECOND).size)
        } finally {
            reopened.close()
        }
    }

    /** Закрытие идемпотентно, а закрытая история не открывается заново молча. */
    @Test
    fun closedHistoryRefusesInsteadOfReopening() = runBlocking {
        val repository = SqliteCurrencyRateRepository(file)
        repository.save(update(FIRST, eur = "95.42", usd = "78.1005", gel = "2.9200"))
        repository.close()
        repository.close()

        val failure = assertFailsWith<CurrencyStorageException> { repository.latest() }
        assertTrue("закрыта" in failure.message.orEmpty(), "отказ закрытой истории: ${failure.message}")
    }

    /** Обновление всех трёх валют одним моментом — так их и пишет планировщик. */
    private fun update(moment: Instant, eur: String, usd: String, gel: String): List<CurrencyRate> = listOf(
        CurrencyRate(Currency.EUR, BigDecimal(eur), moment),
        CurrencyRate(Currency.USD, BigDecimal(usd), moment),
        CurrencyRate(Currency.GEL, BigDecimal(gel), moment)
    )

    private companion object {
        val FIRST: Instant = Instant.parse("2026-09-28T10:00:00Z")
        val SECOND: Instant = Instant.parse("2026-09-28T11:00:00Z")
        val THIRD: Instant = Instant.parse("2026-09-28T12:00:00Z")

        /** Моменты заведомо после всех записей: на них окно пустое. */
        val FOURTH: Instant = Instant.parse("2027-01-01T00:00:00Z")
        val FIFTH: Instant = Instant.parse("2027-01-02T00:00:00Z")
    }
}
