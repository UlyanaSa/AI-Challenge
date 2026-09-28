package com.osvin.aichallenge.currency.storage

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummary
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Часовые сводки в файле: что хранилище отдаёт и что в нём остаётся.
 *
 * Проверки идут на настоящем файле, а не на двойнике: здесь проверяются запросы, текстовые
 * представления денег и часов, замена строки по ключу «час и валюта» и переживание перезапуска —
 * всё то, чем настоящее хранилище отличается от памяти. Замена особенно: на ней держится
 * идемпотентность закрытия часа, а двойник с его списком показал бы замену и там, где
 * в SQLite не было бы уникального индекса.
 */
class SqliteCurrencyHourlySummaryRepositoryTest {

    private val directory: Path = Files.createTempDirectory("currency-summaries")
    private val file: Path = directory.resolve("summaries.db")

    private val hour: Instant = Instant.parse("2026-09-28T11:00:00Z")
    private val nextHour: Instant = Instant.parse("2026-09-28T12:00:00Z")
    private val previousHour: Instant = Instant.parse("2026-09-28T10:00:00Z")

    /** Файл базы и её журнал удаляются за проверкой: временные каталоги не должны копиться. */
    @AfterTest
    fun removeDatabaseFiles() {
        directory.toFile().deleteRecursively()
    }

    /** Десятичные числа возвращаются теми же, а неизвестный процент остаётся неизвестным. */
    @Test
    fun savedSummaryKeepsDecimalDigitsAndUnknownPercent() = runBlocking {
        val database = CurrencyDatabase(file)
        try {
            val repository = SqliteCurrencyHourlySummaryRepository(database)

            assertEquals(1, repository.save(listOf(summary(Currency.EUR, hour, last = "96.0000"))))

            val stored = repository.between(hour, hour).single()
            assertEquals(Currency.EUR, stored.currency)
            assertEquals(hour, stored.hour)
            assertEquals(BigDecimal("95.5000"), stored.firstRate)
            assertEquals(4, stored.firstRate.scale(), "нули в конце потерялись: столбец, видимо, числовой")
            assertEquals(BigDecimal("0.5000"), stored.change)
            assertNull(
                stored.changePercent,
                "процента не было, и ноль на его месте читался бы как «курс не менялся»"
            )
            assertEquals(BigDecimal("95.7500"), stored.averageRate)
            assertEquals(2, stored.samples)
        } finally {
            database.close()
        }
    }

    /** Повторное закрытие часа переписывает его строку, а не добавляет вторую. */
    @Test
    fun closingTheSameHourAgainReplacesItsRow() = runBlocking {
        val database = CurrencyDatabase(file)
        try {
            val repository = SqliteCurrencyHourlySummaryRepository(database)
            repository.save(listOf(summary(Currency.EUR, hour, last = "96.0000")))

            assertEquals(1, repository.save(listOf(summary(Currency.EUR, hour, last = "97.0000"))))

            val stored = repository.between(hour, hour)
            assertEquals(1, stored.size, "повторная запись часа должна заменять строку, а не удваивать её")
            assertEquals(BigDecimal("97.0000"), stored.single().lastRate)
        } finally {
            database.close()
        }
    }

    /** Окно входит обеими границами и отдаёт часы по возрастанию; чужие часы в него не попадают. */
    @Test
    fun windowReturnsItsHoursInChronologicalOrder() = runBlocking {
        val database = CurrencyDatabase(file)
        try {
            val repository = SqliteCurrencyHourlySummaryRepository(database)
            repository.save(
                Currency.entries.map { summary(it, hour, last = "96.0000") } +
                    summary(Currency.EUR, nextHour, last = "97.0000") +
                    summary(Currency.EUR, previousHour, last = "94.0000")
            )

            val window = repository.between(hour, nextHour)

            assertEquals(
                listOf(hour, hour, hour, nextHour),
                window.map { it.hour },
                "окно должно начинаться своей границей и не захватывать час до неё"
            )
            assertEquals(
                Currency.entries,
                window.take(Currency.entries.size).map { it.currency },
                "порядок валют внутри часа — порядок объявления перечисления"
            )
        } finally {
            database.close()
        }
    }

    /** Сводки переживают перезапуск: файл — единственное место, где они лежат. */
    @Test
    fun summariesSurviveReopeningTheFile() = runBlocking {
        val first = CurrencyDatabase(file)
        try {
            SqliteCurrencyHourlySummaryRepository(first).save(listOf(summary(Currency.EUR, hour, last = "96.0000")))
        } finally {
            first.close()
        }

        val second = CurrencyDatabase(file)
        try {
            val stored = SqliteCurrencyHourlySummaryRepository(second).between(hour, hour).single()
            assertEquals(BigDecimal("96.0000"), stored.lastRate)
        } finally {
            second.close()
        }
    }

    /** Сводка часа без разбора чисел: проверке хранения важны ключ строки и её крайние курсы. */
    private fun summary(
        currency: Currency,
        hour: Instant,
        last: String,
        first: String = "95.5000"
    ): CurrencyHourlySummary = CurrencyHourlySummary(
        currency = currency,
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
