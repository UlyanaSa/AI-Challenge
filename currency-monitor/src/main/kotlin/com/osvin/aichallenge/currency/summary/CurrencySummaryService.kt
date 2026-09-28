package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * Сводка по истории курсов за период — то, что отдаёт инструмент `get_currency_summary`.
 *
 * Окно сводки — `[сейчас минус период, сейчас]`, и обе границы входят в него: курс, полученный
 * ровно сутки назад, ещё принадлежит суткам, а не выпадает из них из-за строгого сравнения.
 * «Сейчас» берётся не из [System.currentTimeMillis], а из [clock]: без этого проверка сводки
 * зависела бы от даты запуска, а описание окна нельзя было бы повторить.
 *
 * Возврат устроен списком, а не картой по валютам: порядок ответа объявлен один раз —
 * [Currency.entries], и модель видит валюты в том же порядке, в каком их перечисляет домен.
 * Карта этот порядок теряла бы, и ответ инструмента пришлось бы сортировать заново.
 *
 * @param repository История, по которой считается сводка.
 * @param clock Часы сервиса: точка «сейчас» для окна.
 */
class CurrencySummaryService(
    private val repository: CurrencyRateRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Сводка за период, отсчитанный от текущего момента часов сервиса.
     */
    suspend fun summary(period: SummaryPeriod): List<CurrencySummary> =
        summary(period, clock.instant())

    /**
     * Сводка за период, отсчитанный от [now].
     *
     * Отдельная перегрузка нужна проверкам: они задают момент сами и потому не зависят
     * от системных часов, а окно с обеих сторон известно заранее.
     */
    suspend fun summary(period: SummaryPeriod, now: Instant): List<CurrencySummary> {
        val from = now.minus(period.duration)
        return Currency.entries.map { summaryOf(it, from, now) }
    }

    /**
     * Сводка одной валюты за окно `[from, to]`.
     *
     * Отказ хранилища ([com.osvin.aichallenge.currency.CurrencyStorageException]) здесь
     * намеренно не перехватывается: у сводки нет разумного «пустого» ответа взамен истории,
     * а вызывающий — инструмент — обязан показать отказ, а не пустые числа. Глотать его
     * значило бы выдать недоступную историю за период без данных.
     */
    private suspend fun summaryOf(currency: Currency, from: Instant, to: Instant): CurrencySummary {
        val window = repository.between(currency, from, to)
        // Пустое окно — это ответ «за период по валюте ничего не известно», а не повод
        // пропустить валюту: пропуск читается как «валюта не отслеживается».
        val current = window.lastOrNull() ?: return CurrencySummary(currency)
        // Последняя запись окна, а не последняя в истории: сводка отвечает про период, и курс
        // недельной давности не должен выдавать себя за текущий. Хранилище отдаёт окно
        // по возрастанию времени, поэтому последний элемент и есть самый свежий.
        val previous = repository.previous(currency, current.receivedAt)
        return CurrencySummary(
            currency = currency,
            currentRate = current.rateToRub.rounded(),
            previousRate = previous?.rateToRub?.rounded(),
            change = previous?.let { current.rateToRub.subtract(it.rateToRub) }?.rounded(),
            changePercent = previous?.rateToRub?.let { percentOf(it, current.rateToRub) },
            minRate = window.minOf { it.rateToRub }.rounded(),
            maxRate = window.maxOf { it.rateToRub }.rounded(),
            averageRate = averageOf(window),
            samples = window.size
        )
    }

    /** Среднее окна: сумма на число записей, с масштабом и режимом округления ответа. */
    private fun averageOf(rates: List<CurrencyRate>): BigDecimal = rates
        .fold(BigDecimal.ZERO) { sum, rate -> sum.add(rate.rateToRub) }
        .divide(BigDecimal(rates.size), SUMMARY_SCALE, RoundingMode.HALF_UP)

    /**
     * Изменение в процентах от [previous] до [current]; null, если процент не определён.
     *
     * Проценты считаются от нуля как «бесконечно много», и единственный честный ответ здесь —
     * «неизвестно»: одна испорченная запись (курс равен нулю) не должна ронять сводку
     * исключением деления на ноль. Сравнение именно по `signum`, а не по `equals`: знак
     * различает ноль с любым масштабом и отрицательный курс, который тоже делить нельзя.
     */
    private fun percentOf(previous: BigDecimal, current: BigDecimal): BigDecimal? =
        previous.takeIf { it.signum() != 0 }?.let {
            current.subtract(previous)
                .multiply(HUNDRED)
                .divide(it, SUMMARY_SCALE, RoundingMode.HALF_UP)
        }

    /**
     * Число сводки в объявленном масштабе.
     *
     * В истории курсы лежат с шестью знаками, и эту точность незачем тащить в ответ: сводка
     * описывает период, а не повторяет записи. Масштаб и режим задаются явно потому, что
     * `divide` без них бросает `ArithmeticException` на непредставимой дроби, а `setScale`
     * без режима — на сужении; умолчания здесь означали бы падение на живых данных.
     */
    private fun BigDecimal.rounded(): BigDecimal = setScale(SUMMARY_SCALE, RoundingMode.HALF_UP)

    private companion object {
        /** Масштаб чисел ответа: четыре знака после запятой. */
        const val SUMMARY_SCALE = 4

        /** Множитель для перевода доли в проценты. */
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}
