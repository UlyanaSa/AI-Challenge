package com.osvin.aichallenge.currency

import java.math.BigDecimal
import java.time.Instant

/**
 * Поставщик курсов в памяти: сбор проверяется без сети и без чужого API.
 *
 * Двойник отдаёт ровно то, что положили, и умеет отказать: отказ поставщика — это состояние,
 * которое планировщик обязан пережить, и проверить это без отказа нельзя. Набор курсов задаётся
 * списком, поэтому проверка может дать и неполный ответ (не все валюты) — так проверяется
 * требование «отсутствие курса одной валюты не останавливает сбор».
 *
 * @param rates Что отдаёт поставщик.
 * @param failure Отказ вместо ответа; null — отвечает курсами.
 */
class FakeCurrencyRateProvider(
    private val rates: List<CurrencyRate> = allCurrencies(),
    private val failure: CurrencyRateException? = null
) : CurrencyRateProvider {

    /** Сколько раз спрашивали: по этому видно, что обновление действительно было. */
    var calls: Int = 0
        private set

    override suspend fun getRates(): List<CurrencyRate> {
        calls++
        failure?.let { throw it }
        return rates
    }

    companion object {
        /** Момент по умолчанию: проверкам важна последовательность, а не конкретная дата. */
        val DEFAULT_RECEIVED_AT: Instant = Instant.parse("2026-09-28T10:00:00Z")

        /** Все три валюты по одной цене — набор по умолчанию для проверок. */
        fun allCurrencies(
            receivedAt: Instant = DEFAULT_RECEIVED_AT,
            rate: String = "90.0000"
        ): List<CurrencyRate> = Currency.entries.map {
            CurrencyRate(it, BigDecimal(rate), receivedAt)
        }
    }
}
