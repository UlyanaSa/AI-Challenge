package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import java.math.BigDecimal
import java.time.Instant

/**
 * Сводка за один закрытый час: как курс шёл внутри этого часа.
 *
 * Часовые сводки — это второй, более грубый ряд истории, который сервис ведёт рядом с записями
 * по минутам. Нужен он затем, что по минутам вопрос «как курс менялся за сутки» не читается
 * глазами: за сутки их 1440 на валюту, и складывать их приходится каждый раз заново. Сводка
 * хранит уже посчитанное: с чего час начался, чем закончился, каким был минимум, максимум
 * и среднее. Из этих строк и собирается изменение за сутки — без обращения к минутной истории.
 *
 * Первый и последний курс часа, а не «предыдущий и текущий»: час — это окно, и внутри него
 * важно, с чего окно началось и чем кончилось. Заодно так считается и «изменение за час»
 * (последний минус первый), и «изменение за сутки» по цепочке сводок.
 *
 * @param currency Валюта сводки.
 * @param hour Начало часа по UTC; он же ключ строки в истории сводок и её время.
 * @param firstRate Курс первой записи часа в объявленном масштабе.
 * @param lastRate Курс последней записи часа.
 * @param change Последний минус первый: насколько курс изменился внутри часа.
 * @param changePercent То же в процентах от [firstRate]; null, если первый курс нулевой.
 * @param minRate Минимум часа.
 * @param maxRate Максимум часа.
 * @param averageRate Среднее часа.
 * @param samples Сколько записей часа в него вошло.
 */
data class CurrencyHourlySummary(
    val currency: Currency,
    val hour: Instant,
    val firstRate: BigDecimal,
    val lastRate: BigDecimal,
    val change: BigDecimal,
    val changePercent: BigDecimal?,
    val minRate: BigDecimal,
    val maxRate: BigDecimal,
    val averageRate: BigDecimal,
    val samples: Int
)
