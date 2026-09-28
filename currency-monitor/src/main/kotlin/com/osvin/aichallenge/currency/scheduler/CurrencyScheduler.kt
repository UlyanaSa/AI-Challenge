package com.osvin.aichallenge.currency.scheduler

import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.CurrencyUpdate
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/** Имя логгера: по нему строки сбора отличимы от строк хранилища, сервера и точки входа. */
private const val LOGGER_NAME = "com.osvin.aichallenge.currency.scheduler"

/**
 * Сбор курсов по расписанию: одно обновление сразу и дальше каждые [interval].
 *
 * Первое обновление делается немедленно, а не по истечении [interval]: сервис запускают ради
 * данных сейчас, и ждать час до первой записи значило бы, что инструмент `get_currency_rates`
 * ответит «история пуста» всё это время. Пауза отсчитывается после завершения обновления, а не
 * по границе часа: выравнивание по чужому времени (у поставщика свои часы публикации) связало бы
 * расписание с моментом запуска процесса, ничего не давая взамен, — сбор всё равно упрётся
 * в доступность источника, а не в выбранную секунду.
 *
 * Итерация обёрнута в `try/catch` не потому, что [CurrencyService.update] бросает, — он
 * возвращает отказ значением, — а потому, что цикл обязан пережить то, чего от сервиса не
 * ждут: сбой хранилища, ошибку разбора, ошибку в самом планировщике. Упавший цикл молча
 * прекратил бы сбор, а заметно это стало бы только по устаревшей истории. [CancellationException]
 * при этом пробрасывается дальше: проглотить отмену — значит оставить корутину жить после
 * `cancel()`, и цикл либо зависнет на `delay`, который снова её бросит, либо будет крутиться
 * вхолостую, так и не завершившись.
 *
 * Своих потоков и `sleep` здесь нет намеренно: цикл живёт в переданном [scope], а ожидание —
 * `delay`, единственная точка, через которую корутина отменяется сразу. `Thread.sleep`
 * заблокировал бы поток и не заметил бы `cancel()` до конца паузы.
 *
 * После состоявшегося обновления закрывается прошедший час: часовые сводки — производное
 * от той же истории, и пишет их тот же цикл, что её собирает. Иначе за сводки отвечал бы кто-то
 * ещё, и «сводка есть не всегда» стало бы нормой, которую пришлось бы объяснять пользователю.
 *
 * @param service Единственный путь обновления: получить, проверить, сохранить.
 * @param summaries Часовые сводки: закрытие часа по уже записанной истории.
 * @param interval Промежуток между окончанием одного обновления и началом следующего.
 * @param log Лог планировщика; подменяем в проверках и на VPS, где нужен свой уровень.
 */
class CurrencyScheduler(
    private val service: CurrencyService,
    private val summaries: CurrencyHourlySummaryService,
    private val interval: Duration,
    private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME)
) {

    /**
     * Запускает цикл в [scope] и сразу возвращает управление: [Job] нужен вызывающему, чтобы
     * остановить сбор отменой, не дожидаясь следующего обновления.
     *
     * Вызов не блокирующий: тело корутины — цикл на всё время жизни сервиса, и ожидание его
     * в вызывающем означало бы, что до `runBlocking` или MCP-сессии не дойти.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        log.info("планировщик курсов запущен, интервал между обновлениями {}", interval)
        while (isActive) {
            try {
                // Сводка часа — только после состоявшегося обновления: закрывать час, в который
                // ничего не записалось, нечего, а отказ источника — не повод объявить час пустым.
                if (updateOnce()) closeHour()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.error("обновление курсов прервано непредвиденной ошибкой", error)
            }

            // Отмена во время обновления: пауза уже не нужна, и следующий сбор объявлять нечего.
            if (!isActive) break

            log.info("следующее обновление курсов через {}", interval)
            // Дробные миллисекунды теряются: у планировщика не бывает расписания мельче
            // миллисекунды, а `delay` принимает именно миллисекунды.
            delay(interval.toMillis())
        }
    }

    /**
     * Одно обновление и его лог.
     *
     * Отказ поставщика — строка уровня `error` с причиной, но не исключение: сервис уже вернул
     * его значением, и превращать его обратно в исключение значило бы перекладывать обработку
     * на цикл, который для этого и написан.
     *
     * @return Состоялось ли обновление: по нему решается, закрывать ли час сводкой.
     */
    private suspend fun updateOnce(): Boolean {
        log.info("обновление курсов началось")
        return when (val outcome = service.update()) {
            is CurrencyUpdate.Saved -> {
                // По строке на валюту с ценой ровно в том виде, в каком она уйдёт в историю:
                // `toPlainString` сохраняет масштаб и не переводит малые значения в степень.
                outcome.rates.forEach {
                    log.info("{}/RUB = {}", it.currency.code, it.rateToRub.toPlainString())
                }
                if (outcome.skipped.isNotEmpty()) {
                    log.warn(
                        "курсы не получены по валютам: {}",
                        outcome.skipped.joinToString(", ") { it.code }
                    )
                }
                log.info("в историю записано строк: {}", outcome.rows)
                true
            }

            is CurrencyUpdate.Failed -> {
                log.error("обновление курсов не удалось: {}", outcome.reason)
                false
            }
        }
    }

    /**
     * Закрывает прошедший час часовой сводкой.
     *
     * Отказ записи логируется и не прерывает сбор: сводка пересчитывается на следующем обороте,
     * пока по часу есть записи, а упавший из-за неё цикл остановил бы сбор курсов — потерю,
     * которую перезапуском уже не добрать. Поэтому `try/catch` здесь свой: общий на итерации
     * не отличал бы «отказал источник» от «не записалась сводка».
     */
    private suspend fun closeHour() {
        try {
            val rows = summaries.closeHour()
            if (rows > 0) log.info("сводка часа записана: строк {}", rows)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("часовые сводки не записаны", error)
        }
    }
}
