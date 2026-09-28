package com.osvin.aichallenge.currency.scheduler

import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.CurrencyUpdate
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
 * @param service Единственный путь обновления: получить, проверить, сохранить.
 * @param interval Промежуток между окончанием одного обновления и началом следующего.
 * @param log Лог планировщика; подменяем в проверках и на VPS, где нужен свой уровень.
 */
class CurrencyScheduler(
    private val service: CurrencyService,
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
                updateOnce()
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
     */
    private suspend fun updateOnce() {
        log.info("обновление курсов началось")
        when (val outcome = service.update()) {
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
            }

            is CurrencyUpdate.Failed -> log.error("обновление курсов не удалось: {}", outcome.reason)
        }
    }
}
