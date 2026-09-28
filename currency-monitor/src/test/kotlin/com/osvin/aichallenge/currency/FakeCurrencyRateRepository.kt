package com.osvin.aichallenge.currency

import java.time.Instant

/**
 * История курсов в памяти: проверки сводки, инструментов и планировщика идут без файла базы.
 *
 * Двойник ведёт себя как настоящая история в том, что важно потребителям: строки только
 * дописываются, порядок — по времени получения, а [previous] смотрит строго назад. Он не
 * копирует SQL и не проверяет то, что проверяет хранилище на SQLite: у настоящего хранилища
 * свои проверки ([SqliteCurrencyRateRepository]), и их подменять двойником значило бы проверять
 * двойника вместо хранилища.
 *
 * @param initial Начальная история: проверке обычно нужны уже собранные курсы.
 */
class FakeCurrencyRateRepository(initial: List<CurrencyRate> = emptyList()) : CurrencyRateRepository {

    /** История в порядке времени получения: так же её вернуло бы настоящее хранилище. */
    val history: List<CurrencyRate> get() = rows.toList()

    /** Что передали в [save] — по этому видно, дошли ли курсы до истории и в каком виде. */
    val saved: MutableList<List<CurrencyRate>> = mutableListOf()

    /** Отказ, которым проверяется поведение вызывающего при недоступной истории. */
    var failure: CurrencyStorageException? = null

    /** Закрыт ли двойник: планировщик и `main` обязаны закрывать историю. */
    var closed: Boolean = false
        private set

    private val rows = initial.sortedWith(ORDER).toMutableList()

    override suspend fun save(rates: List<CurrencyRate>): Int {
        failure?.let { throw it }
        saved += rates
        rows += rates
        rows.sortWith(ORDER)
        return rates.size
    }

    override suspend fun latest(): List<CurrencyRate> {
        failure?.let { throw it }
        return Currency.entries.mapNotNull { currency -> rows.lastOrNull { it.currency == currency } }
    }

    override suspend fun between(currency: Currency, from: Instant, to: Instant): List<CurrencyRate> {
        failure?.let { throw it }
        return rows.filter { it.currency == currency && it.receivedAt >= from && it.receivedAt <= to }
    }

    override suspend fun previous(currency: Currency, before: Instant): CurrencyRate? {
        failure?.let { throw it }
        return rows.lastOrNull { it.currency == currency && it.receivedAt < before }
    }

    override fun close() {
        closed = true
    }

    /** Дописывает историю, минуя [save]: так готовится начальное состояние проверки. */
    fun seed(rates: List<CurrencyRate>) {
        rows += rates
        rows.sortWith(ORDER)
    }

    private companion object {
        /** Тот же порядок, что у выборок хранилища: время, потом валюта. */
        val ORDER = compareBy<CurrencyRate>({ it.receivedAt }, { it.currency.ordinal })
    }
}
