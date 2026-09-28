package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import java.math.BigDecimal

/**
 * Как курс одной валюты изменился за окно, собранное из сохранённых часовых сводок.
 *
 * Числа не пересчитываются по минутной истории: они складываются из [CurrencyHourlySummary],
 * которые сервис уже посчитал и сохранил. Поэтому окно измеряется целыми часами, а не минутами:
 * час — наименьшая единица, про которую сохранённая сводка что-то знает. Считать «за 90 минут»
 * по таким данным можно было бы только приблизительно — и это приблизительно пришлось бы
 * выдавать за измерение.
 *
 * Первый и последний курс окна — это начало первой сводки окна и конец последней: так изменение
 * за сутки складывается из суток целиком, а не из последнего часа. Минимум и максимум берутся
 * по всем сводкам окна, среднее — взвешенное по числу записей в часах.
 *
 * Все числа, кроме [samples] и [hours], необязательные: у валюты без сохранённых сводок в окне
 * известно только то, что их нет, и ноль на месте курса был бы утверждением, которого сервис
 * не проверял.
 *
 * @param currency Валюта.
 * @param firstRate Курс на начало первой сохранённой сводки окна.
 * @param lastRate Курс на конец последней сохранённой сводки окна.
 * @param change Последний минус первый.
 * @param changePercent То же в процентах от [firstRate]; null, если процент не определён.
 * @param minRate Минимум по сводкам окна.
 * @param maxRate Максимум по сводкам окна.
 * @param averageRate Среднее по сводкам окна, взвешенное числом записей в часах.
 * @param samples Сколько минутных записей вошло в сводки окна.
 * @param hours Сколько часовых сводок окна есть по этой валюте.
 */
data class CurrencyChange(
    val currency: Currency,
    val firstRate: BigDecimal? = null,
    val lastRate: BigDecimal? = null,
    val change: BigDecimal? = null,
    val changePercent: BigDecimal? = null,
    val minRate: BigDecimal? = null,
    val maxRate: BigDecimal? = null,
    val averageRate: BigDecimal? = null,
    val samples: Int = 0,
    val hours: Int = 0
)
