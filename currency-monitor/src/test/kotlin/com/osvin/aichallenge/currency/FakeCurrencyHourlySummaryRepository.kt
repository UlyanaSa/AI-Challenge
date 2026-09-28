package com.osvin.aichallenge.currency

import com.osvin.aichallenge.currency.summary.CurrencyHourlySummary
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryRepository
import java.time.Instant

/**
 * История часовых сводок в памяти: проверки сводок и инструментов идут без файла базы.
 *
 * Двойник повторяет то, что важно потребителям: сводка часа заменяется по ключу «час и валюта»,
 * а окно отдаётся по возрастанию часа. Замена здесь не украшение — час закрывается заново
 * на каждом обороте планировщика, и двойник, копивший бы строки, показывал бы поведение,
 * которого у настоящего хранилища нет. Чего двойник не повторяет — SQL и текстовые
 * представления чисел: это проверяется на настоящем файле ([com.osvin.aichallenge.currency.storage.SqliteCurrencyHourlySummaryRepository]),
 * и подменять там двойником значило бы проверять двойника вместо хранилища.
 *
 * @param initial Начальные сводки: проверке обычно нужны уже закрытые часы.
 */
class FakeCurrencyHourlySummaryRepository(
    initial: List<CurrencyHourlySummary> = emptyList()
) : CurrencyHourlySummaryRepository {

    /** Что передали в [save] — по этому видно, дошли ли сводки до хранилища и в каком виде. */
    val saved: MutableList<List<CurrencyHourlySummary>> = mutableListOf()

    /** Отказ, которым проверяется поведение вызывающего при недоступной истории сводок. */
    var failure: CurrencyStorageException? = null

    /** Сводки, лежащие сейчас: по ним видно и замену по ключу, и окно выборки. */
    val stored: List<CurrencyHourlySummary> get() = rows.toList()

    private val rows = initial.toMutableList()

    override suspend fun save(summaries: List<CurrencyHourlySummary>): Int {
        failure?.let { throw it }
        saved += summaries
        summaries.forEach { summary ->
            rows.removeAll { it.hour == summary.hour && it.currency == summary.currency }
            rows += summary
        }
        rows.sortWith(ORDER)
        return summaries.size
    }

    override suspend fun between(from: Instant, to: Instant): List<CurrencyHourlySummary> {
        failure?.let { throw it }
        return rows.filter { it.hour >= from && it.hour <= to }
    }

    /** Дописывает сводки, минуя [save]: так готовится начальное состояние проверки. */
    fun seed(summaries: List<CurrencyHourlySummary>) {
        rows += summaries
        rows.sortWith(ORDER)
    }

    private companion object {
        /** Тот же порядок, что у выборки хранилища: час, потом валюта. */
        val ORDER = compareBy<CurrencyHourlySummary>({ it.hour }, { it.currency.ordinal })
    }
}
