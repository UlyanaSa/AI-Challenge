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
 * @param interval Шаг сетки сбора: промежуток между началами двух обновлений.
 *        Не «пауза после работы»: удар сам занимает время (запрос к поставщику, запись
 *        истории), и пауза от конца работы сдвигала бы каждое следующее обновление
 *        на это время — за сутки сетка уезжала бы на минуты, а приложение, читающее
 *        курсы по своей сетке, показывало бы одну минуту дважды и пропускало следом.
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
        // Начало сетки — запуск службы: первое обновление идёт сразу, остальные — по её точкам
        val grid = System.currentTimeMillis()
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

            // Дробные миллисекунды теряются: у планировщика не бывает расписания мельче
            // миллисекунды, а `delay` принимает именно миллисекунды.
            val pause = pauseToNextTick(grid, System.currentTimeMillis(), interval.toMillis())
            log.info("следующее обновление курсов через {} мс", pause)
            delay(pause)
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

/**
 * Пауза до следующей точки сетки с началом [grid] и шагом [interval].
 *
 * Точки сетки — [grid] и дальше через [interval]. Обновление, занявшее больше шага, пропускает
 * свои точки, а не сдвигает сетку: пауза ведёт к первой точке, которая ещё не прошла, поэтому
 * долгий запрос к поставщику не утаскивает за собой расписание и не превращается в череду
 * немедленных повторов.
 *
 * Функция отдельная и с моментом параметром, а не вычислением по системным часам внутри цикла:
 * так правило проверяется без ожидания настоящего интервала. Общего места у этого правила
 * с таким же в клиентском мониторе (`ChatRepository.pauseToNextTick`) нет: там общий код KMP,
 * здесь JVM-служба, и складывать ради пяти строк ещё один модуль было бы дороже повтора.
 */
internal fun pauseToNextTick(grid: Long, moment: Long, interval: Long): Long {
    val passed = moment - grid
    val points = if (passed < 0) 1 else passed / interval + 1
    return grid + points * interval - moment
}
