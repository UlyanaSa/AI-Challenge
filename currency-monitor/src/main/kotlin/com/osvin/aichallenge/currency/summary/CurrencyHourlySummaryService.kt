package com.osvin.aichallenge.currency.summary

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRateRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Часовые сводки: закрыть прошедший час и собрать изменение за окно из закрытых часов.
 *
 * Две роли в одном месте, потому что это две стороны одного ряда: сводку пишут затем, чтобы
 * потом читать изменение по ней. Разнести их значило бы завести две сущности, которым друг
 * без друга нечего делать.
 *
 * Час закрывается на каждом обходе, а не по границе часа: записи по закрытому часу уже
 * не меняются, и повторный пересчёт даёт то же число — зато пропущенного часа не будет,
 * если обход задержался или служба перезапустилась в середине часа. Плата за это — чтение
 * окна часа и запись трёх строк на каждом обходе; при минутном сборе это шестьдесят записей
 * на валюту, то есть работа, которая всё равно дешевле одного запроса к источнику.
 *
 * Окно изменения измеряется целыми часами и кончается последним **закрытым** часом: текущий
 * час ещё не закрыт, и включать его значило бы отвечать про незаконченный промежуток.
 *
 * @param rates Минутная история: источник чисел для сводки часа.
 * @param summaries Сохранённые сводки: куда писать час и откуда читать окно.
 * @param clock Часы сервиса; параметр, чтобы проверки не зависели от системного времени.
 */
class CurrencyHourlySummaryService(
    private val rates: CurrencyRateRepository,
    private val summaries: CurrencyHourlySummaryRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Закрывает прошедший час: считает по нему сводки и сохраняет их.
     *
     * Возвращает, сколько строк записано. Ноль означает «по этому часу записей не было» —
     * не отказ: служба могла стоять, и пустая сводка вместо отсутствующей выглядела бы как
     * «курс не менялся».
     */
    suspend fun closeHour(now: Instant = clock.instant()): Int {
        val hour = hourStart(now).minus(1, ChronoUnit.HOURS)
        val closed = Currency.entries.mapNotNull { summaryOfHour(it, hour) }
        return if (closed.isEmpty()) 0 else summaries.save(closed)
    }

    /**
     * Изменение за [hours] часов по сохранённым сводкам.
     *
     * Окно — [hours] последних закрытых часов, кончая предыдущим: `[последний закрытый − hours + 1, последний закрытый]`.
     * Спрошенное окно больше сохранённого не подменяется меньшим молча: вызывающий видит
     * и [CurrencyChangeWindow.hours], и [CurrencyChangeWindow.hoursCovered].
     *
     * @throws IllegalArgumentException [hours] не больше нуля.
     */
    suspend fun change(hours: Int, now: Instant = clock.instant()): CurrencyChangeWindow {
        require(hours > 0) { "окно изменения считается целыми часами и не меньше часа, а не $hours" }
        val lastClosed = hourStart(now).minus(1, ChronoUnit.HOURS)
        val first = lastClosed.minus((hours - 1).toLong(), ChronoUnit.HOURS)
        val stored = summaries.between(first, lastClosed)
        return CurrencyChangeWindow(
            hours = hours,
            from = stored.minOfOrNull { it.hour },
            // Конец последней сводки — её час плюс час: окно кончается там, где кончается
            // последний закрытый час, а не там, где он начинается.
            to = stored.maxOfOrNull { it.hour }?.plus(1, ChronoUnit.HOURS),
            hoursCovered = stored.map { it.hour }.distinct().size,
            changes = Currency.entries.map { currency ->
                changeOf(currency, stored.filter { it.currency == currency })
            }
        )
    }

    /**
     * Сводка одной валюты за час; null — записей в этом часу не было.
     *
     * Окно записи берётся с обеих сторон, и верхняя граница сдвинута на секунду назад: история
     * курсов отдаёт окно включительно с обеих сторон, а запись ровно на границе часов
     * принадлежит уже следующему часу.
     */
    private suspend fun summaryOfHour(currency: Currency, hour: Instant): CurrencyHourlySummary? {
        val window = rates.between(currency, hour, hour.plus(1, ChronoUnit.HOURS).minusSeconds(1))
        val first = window.firstOrNull() ?: return null
        val last = window.last()
        return CurrencyHourlySummary(
            currency = currency,
            hour = hour,
            firstRate = first.rateToRub.rounded(),
            lastRate = last.rateToRub.rounded(),
            change = last.rateToRub.subtract(first.rateToRub).rounded(),
            changePercent = percentOf(first.rateToRub, last.rateToRub),
            minRate = window.minOf { it.rateToRub }.rounded(),
            maxRate = window.maxOf { it.rateToRub }.rounded(),
            averageRate = averageOfNotEmpty(window.map { it.rateToRub }),
            samples = window.size
        )
    }

    /**
     * Изменение одной валюты по её сохранённым часам.
     *
     * Числа складываются из сводок, а не пересчитываются по минутам: так ответ за сутки
     * не зависит от того, сколько записей в минуте, и совпадает с тем, что видно в ленте.
     * Среднее взвешивается числом записей — час с шестьюдесятью записями весит больше часа
     * с одной, иначе редкий час перевешивал бы частый.
     */
    private fun changeOf(currency: Currency, hours: List<CurrencyHourlySummary>): CurrencyChange {
        val first = hours.minByOrNull { it.hour } ?: return CurrencyChange(currency)
        val last = hours.maxByOrNull { it.hour } ?: return CurrencyChange(currency)
        return CurrencyChange(
            currency = currency,
            firstRate = first.firstRate,
            lastRate = last.lastRate,
            change = last.lastRate.subtract(first.firstRate).rounded(),
            changePercent = percentOf(first.firstRate, last.lastRate),
            minRate = hours.minOf { it.minRate },
            maxRate = hours.maxOf { it.maxRate },
            averageRate = weightedAverageOf(hours.map { it.averageRate }, hours.map { it.samples }),
            samples = hours.sumOf { it.samples },
            hours = hours.size
        )
    }
}

/** Начало часа по UTC: время в [Instant] абсолютное, поэтому усечение всегда по Гринвичу. */
private fun hourStart(moment: Instant): Instant = moment.truncatedTo(ChronoUnit.HOURS)
