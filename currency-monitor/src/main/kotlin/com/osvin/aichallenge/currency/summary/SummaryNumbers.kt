package com.osvin.aichallenge.currency.summary

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Числа сводки: масштаб, проценты и среднее — одни на все сводки сервиса.
 *
 * Вынесены из [CurrencySummaryService] в отдельный файл, когда их стало двое: часовые сводки
 * считают изменение, проценты и среднее по тем же правилам, что сводка за период, и вторая
 * копия этих правил разошлась бы с первой при первой же правке. Правила короткие, но каждое
 * из них — решение, а не арифметика, и повторять решения нельзя.
 *
 * Масштаб ответа — четыре знака, меньше, чем шесть в истории: сводка описывает промежуток,
 * а не повторяет записи. Масштаб и режим округления задаются явно: `divide` без них бросает
 * `ArithmeticException` на непредставимой дроби, а `setScale` без режима — на сужении,
 * и умолчания означали бы падение на живых данных.
 */

/** Масштаб чисел ответа: четыре знака после запятой. */
internal const val SUMMARY_SCALE = 4

/** Множитель для перевода доли в проценты. */
internal val SUMMARY_HUNDRED: BigDecimal = BigDecimal(100)

/** Число сводки в объявленном масштабе. */
internal fun BigDecimal.rounded(): BigDecimal = setScale(SUMMARY_SCALE, RoundingMode.HALF_UP)

/**
 * Изменение в процентах от [previous] до [current]; null, если процент не определён.
 *
 * Проценты считаются от нуля как «бесконечно много», и единственный честный ответ здесь —
 * «неизвестно»: одна испорченная запись (курс равен нулю) не должна ронять сводку исключением
 * деления на ноль. Сравнение именно по `signum`, а не по `equals`: знак различает ноль с любым
 * масштабом и отрицательный курс, который тоже делить нельзя.
 */
internal fun percentOf(previous: BigDecimal, current: BigDecimal): BigDecimal? =
    previous.takeIf { it.signum() != 0 }?.let {
        current.subtract(previous)
            .multiply(SUMMARY_HUNDRED)
            .divide(it, SUMMARY_SCALE, RoundingMode.HALF_UP)
    }

/**
 * Среднее списка значений: сумма на их число; null — список пуст.
 *
 * Пустой список — не ошибка вызывающего, а «среднего нет»: у сводки за период окно бывает
 * пустым, и падать здесь значило бы превращать пустое окно в отказ. Там, где пустоты быть
 * не может (сводка часа без записей не создаётся вовсе), берут [averageOfNotEmpty] — он
 * об этом и говорит.
 */
internal fun averageOf(values: List<BigDecimal>): BigDecimal? =
    values.takeIf { it.isNotEmpty() }?.let { averageOfNotEmpty(it) }

/**
 * Среднее непустого списка значений: сумма на их число.
 *
 * Требование непустоты объявлено, а не подразумевается: деление на ноль записей — не «среднее
 * ноль», а ошибка в том, кто позвал, и она должна быть видна на месте, а не в виде `NaN`.
 */
internal fun averageOfNotEmpty(values: List<BigDecimal>): BigDecimal {
    require(values.isNotEmpty()) { "среднее по пустому списку не считается" }
    return values
        .fold(BigDecimal.ZERO) { sum, value -> sum.add(value) }
        .divide(BigDecimal(values.size), SUMMARY_SCALE, RoundingMode.HALF_UP)
}

/**
 * Среднее по часовым сводкам, взвешенное числом записей часа.
 *
 * Простое среднее средних дало бы разным часам один вес, хотя в одном могло быть шестьдесят
 * записей, а в другом одну: сводка за сутки отвечает про курс, а не про то, как часто его
 * спрашивали. Сумма записей нулевой быть не может — час без записей в сводку не попадает.
 */
internal fun weightedAverageOf(
    values: List<BigDecimal>,
    weights: List<Int>
): BigDecimal {
    val total = weights.sum()
    require(total > 0) { "среднее по сводкам без записей не считается" }
    val weighted = values.zip(weights).fold(BigDecimal.ZERO) { sum, (value, weight) ->
        sum.add(value.multiply(BigDecimal(weight)))
    }
    return weighted.divide(BigDecimal(total), SUMMARY_SCALE, RoundingMode.HALF_UP)
}
