package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import java.math.BigDecimal

/**
 * Сводка по валюте за период: текущий и предыдущий курс, изменение, границы и среднее.
 *
 * Поля необязательные нарочно: окно может оказаться пустым (сервис только запустился, или
 * обновлений не было сутки, или валюта не приходила от поставщика), и тогда «среднего за сутки»
 * не существует. Null здесь — это «неизвестно», и он отличается от нуля: ноль значил бы «курс
 * не менялся», а это утверждение о данных, которых нет. Поэтому же в сводке есть [samples]:
 * по одному числу видно, на скольких записях она построена, иначе «среднее по одной точке»
 * выглядело бы как среднее по периоду.
 *
 * Предыдущий курс берётся из истории за пределами окна: у суток, у которых было одно
 * обновление, «предыдущий» — это то, что было до них, а не отсутствие данных. Иначе изменение
 * за сутки у сервиса, работающего второй день, всегда показывалось бы неизвестным.
 *
 * @param currency Валюта, к которой относится сводка.
 * @param currentRate Последний курс окна; null — в окне записей нет.
 * @param previousRate Курс, полученный до [currentRate]; null — сравнивать не с чем.
 * @param change Изменение в рублях: [currentRate] минус [previousRate].
 * @param changePercent Изменение в процентах от [previousRate].
 * @param minRate Минимум окна.
 * @param maxRate Максимум окна.
 * @param averageRate Среднее окна.
 * @param samples Сколько записей окна легло в минимум, максимум и среднее.
 */
data class CurrencySummary(
    val currency: Currency,
    val currentRate: BigDecimal? = null,
    val previousRate: BigDecimal? = null,
    val change: BigDecimal? = null,
    val changePercent: BigDecimal? = null,
    val minRate: BigDecimal? = null,
    val maxRate: BigDecimal? = null,
    val averageRate: BigDecimal? = null,
    val samples: Int = 0
)
