package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateRepository
import java.time.Instant

/**
 * История курсов, заданная списком: инструментам пайплайна нужен только `latest()`.
 *
 * Заглушка, а не временная база, потому что проверяется не чтение истории (оно принадлежит
 * службе курсов и проверено у неё), а то, что инструмент делает с прочитанным: пересчёт,
 * отбор валют и отказ, когда данных нет. Записывать при этом нечего — пайплайн только читает,
 * и `save` у него не вызывается вовсе.
 */
class FakeRateRepository(private val rates: List<CurrencyRate>) : CurrencyRateRepository {

    override suspend fun save(rates: List<CurrencyRate>): Int = 0

    override suspend fun latest(): List<CurrencyRate> = rates

    override suspend fun between(currency: Currency, from: Instant, to: Instant): List<CurrencyRate> = emptyList()

    override suspend fun previous(currency: Currency, before: Instant): CurrencyRate? = null

    override fun close() = Unit
}

/** Курс валюты на заданный момент: короче, чем полный конструктор, в каждом наборе данных. */
fun rate(currency: Currency, value: String, at: Instant): CurrencyRate =
    CurrencyRate(currency = currency, rateToRub = value.toBigDecimal(), receivedAt = at)
