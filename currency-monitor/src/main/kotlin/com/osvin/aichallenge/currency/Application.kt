package com.osvin.aichallenge.currency

import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import com.osvin.aichallenge.currency.mcp.CurrencyToolsData
import com.osvin.aichallenge.currency.remote.CbrCurrencyRateProvider
import com.osvin.aichallenge.currency.scheduler.CurrencyScheduler
import com.osvin.aichallenge.currency.storage.CurrencyDatabase
import com.osvin.aichallenge.currency.storage.SqliteCurrencyHourlySummaryRepository
import com.osvin.aichallenge.currency.storage.SqliteCurrencyRateRepository
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryService
import com.osvin.aichallenge.currency.summary.CurrencySummaryService
import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import com.osvin.aichallenge.mcp.mcpServer
import com.osvin.aichallenge.mcp.runStdioServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Флаг разового обновления: сходить к поставщику, записать в историю и выйти.
 *
 * Нужен там, где расписание неудобно: проверить доступ к поставщику и запись в базу, наполнить
 * историю перед демонстрацией, дёрнуть обновление из планировщика заданий на VPS. Тот же путь,
 * что у планировщика, — иначе «одно обновление» проверяло бы не то, что работает по часам.
 */
const val ONCE_FLAG = "--once"

/**
 * Флаг режима процесса для клиента: сервер инструментов, который выходит вместе с клиентом.
 *
 * Отличие от запуска без аргументов ровно одно и оно про владельца процесса: служба остаётся
 * жить после конца ввода (её владелец — тот, кто её запустил на VPS, а не подключившийся),
 * а этот режим выходит, потому что клиент закрыл соединение — работа кончилась. Так его
 * поднимает сервер приложения, и локально ([localMcpServerConfig] с этим флагом), и по ssh
 * на VPS: `ssh vps java -jar /opt/currency-monitor/currency-monitor.jar --mcp`.
 */
const val MCP_FLAG = "--mcp"

/** Лог сервиса: имя то же, что у пакета, — его видно в потоке ошибок MCP-сервера. */
private const val SERVICE_LOGGER = "com.osvin.aichallenge.currency"

/**
 * Точка входа сервиса курсов: планировщик и MCP-сервер в одном процессе.
 *
 * Один процесс на всё — и на сбор по расписанию, и на инструменты. Это следует из того, как
 * устроен MCP на stdio: сервер — процесс, которым владеет подключившийся клиент, и живёт он,
 * пока клиент держит его ввод. Разнеси мы сбор и инструменты по процессам, история курсов
 * писалась бы одним, а читалась другим, и шаг «независимо от клиента» потребовал бы связи
 * между процессами там, где сейчас достаточно одной корутины. Сбор при этом действительно
 * не зависит от клиента: он идёт по расписанию, а не по вызову инструмента, поэтому отсутствие
 * клиента его не останавливает.
 *
 * Режимы:
 * - без аргументов — служба: планировщик работает, и тот же процесс отвечает по протоколу,
 *   пока его читают. По концу ввода служба не выходит: на VPS клиента может не быть вовсе,
 *   а сбор обязан идти дальше — это и есть «независимо от клиентского приложения»;
 * - `--mcp` — процесс для клиента: тот же сбор, но по концу ввода процесс выходит, потому что
 *   владелец такого процесса — подключившийся клиент, а не служба (так его поднимает сервер
 *   приложения, локально или по ssh);
 * - `--once` — одно обновление и выход (код 1, если обновления не было);
 * - `--list-tools` — объявления инструментов и выход, без базы и без сети: список берётся
 *   из объявлений, а не из данных, поэтому печатается и там, где истории ещё нет.
 *
 * База и поставщик закрываются в `finally`: службу на VPS останавливают сигналом, и незакрытая
 * база — это не сброшенные на диск записи и занятый файл при следующем запуске.
 */
fun main(args: Array<String>) {
    val mode = args.singleOrNull()
    // Консольный режим обслуживается до всего остального: он не открывает ни базу, ни сеть,
    // и делать это ради списка объявлений значило бы создавать файл истории на ровном месте.
    if (mode == LIST_TOOLS_FLAG) {
        runStdioServer(args, CurrencyMcpServer.NAME, CurrencyMcpServer.VERSION, CurrencyMcpServer.tools()) {
            error("в консольном режиме сервер не собирается: список берётся из объявлений")
        }
        return
    }
    if (mode != null && mode != MCP_FLAG && mode != ONCE_FLAG) {
        // Опечатка не должна поднимать службу: она молча ждала бы кадров из ввода, а человек
        // решил бы, что команда зависла.
        System.err.println(
            "неизвестный режим: $mode; поддерживается $MCP_FLAG (процесс клиента), $ONCE_FLAG " +
                "(одно обновление), $LIST_TOOLS_FLAG (список инструментов) или запуск " +
                "без аргументов (служба)"
        )
        exitProcess(2)
    }

    val config = CurrencyConfig.fromEnvironment()
    val log = LoggerFactory.getLogger(SERVICE_LOGGER)
    val database = openDatabase(config) ?: exitProcess(2)
    // Оба хранилища — на одной базе: часовые сводки считаются по минутной истории того же часа,
    // и два соединения к одному файлу дали бы вопрос «а не отстал ли один из двух» там, где
    // нужен один согласованный взгляд на данные. Закрывает базу владелец файла — здесь.
    val repository = SqliteCurrencyRateRepository(database)
    val summaries = SqliteCurrencyHourlySummaryRepository(database)
    val provider = CbrCurrencyRateProvider(config)
    val service = CurrencyService(provider, repository)
    val hourly = CurrencyHourlySummaryService(repository, summaries)

    try {
        if (mode == ONCE_FLAG) exitProcess(updateOnce(service, log))
        val data = CurrencyToolsData(service, CurrencySummaryService(repository), hourly)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runStdioServer(
                emptyArray(),
                CurrencyMcpServer.NAME,
                CurrencyMcpServer.VERSION,
                CurrencyMcpServer.tools()
            ) {
                // Планировщик стартует здесь, а не до вызова сервера: до него стандартный вывод
                // ещё не забран под протокол, и первые строки лога (у logback по умолчанию это
                // поток вывода) уехали бы клиенту вместо кадров — клиент потерял бы рукопожатие.
                // Тем же порядком первое обновление не начинается раньше, чем сервер готов
                // отвечать, — иначе сбор шёл бы, пока сервер ещё собирается.
                // Настройки — в лог до первого обновления: на VPS по одной строке видно, куда
                // служба ходит, где лежит история и как часто обновляется, а иначе это
                // выяснялось бы по переменным окружения того, кто её запускал.
                log.info(
                    "Сервис курсов: поставщик {}, история {}, обновление каждые {}",
                    config.apiBase,
                    config.databasePath,
                    config.interval
                )
                CurrencyScheduler(service, hourly, config.interval, log).start(scope)
                mcpServer(
                    CurrencyMcpServer.NAME,
                    CurrencyMcpServer.VERSION,
                    data,
                    CurrencyMcpServer.tools()
                )
            }
            // Дальше идут только от клиента закрытый ввод и режим службы. Процесс клиента на этом
            // заканчивается: его работа кончилась вместе с соединением. Служба продолжает жить —
            // сбор идёт по расписанию, и клиента рядом может не быть ни одного; ждём сигнала
            // остановки и ничего больше не делаем.
            if (mode == MCP_FLAG) return
            log.info("Соединение закрыто: сбор курсов продолжается по расписанию")
            runBlocking { awaitCancellation() }
        } finally {
            // Планировщик живёт в своей корутине: и конец ввода, и сигнал остановки должны
            // останавливать цикл, а не оставлять его ждать своего часа в умирающем процессе.
            scope.cancel()
        }
    } finally {
        provider.close()
        database.close()
    }
}

/**
 * Открывает базу курсов или объясняет, почему не вышло.
 *
 * Отказ открытия — единственная ошибка запуска, при которой работать нечем: без истории нет
 * ни сбора, ни инструментов, ни сводок. Поэтому сообщение печатается в поток ошибок и процесс
 * завершается кодом 2, а не продолжается с полурабочим сервисом: «сервер поднялся, но ничего
 * не может» — худший исход для того, кто на VPS смотрит на `systemctl status`.
 */
private fun openDatabase(config: CurrencyConfig): CurrencyDatabase? = try {
    CurrencyDatabase(config.databasePath)
} catch (error: CurrencyStorageException) {
    // В поток ошибок, а не логгером: логгер у logback по умолчанию пишет в стандартный вывод,
    // который в рабочем режиме отдан протоколу. Ошибка открытия базы случается до того, как
    // вывод забран под кадры, и попала бы клиенту мусором вместо рукопожатия.
    System.err.println("$SERVICE_LOGGER: история курсов не открыта (${config.databasePath}): ${error.message}")
    null
}

/**
 * Одно обновление и код возврата: 0 — курсы записаны, 1 — обновления не было.
 *
 * Код возврата, а не всегда успех: `--once` зовут из скриптов и из планировщика заданий, и им
 * нужно знать, получилось ли. Отказ поставщика — это не сбой сервиса, но и не успех.
 *
 * Печатается то же, что пишет планировщик в лог: строки по валютам и итог. Логом сервиса это
 * не считается (`logger` берётся тот же, но формат короче): в разовом режиме нет расписания,
 * и сообщать «следующее обновление через час» было бы неправдой.
 */
private fun updateOnce(service: CurrencyService, log: Logger): Int = runBlocking {
    when (val update = service.update()) {
        is CurrencyUpdate.Saved -> {
            update.rates.forEach { log.info("{}/RUB = {}", it.currency.code, it.rateToRub.toPlainString()) }
            if (update.skipped.isNotEmpty()) {
                log.warn("курса нет: {}", update.skipped.joinToString(", ") { it.code })
            }
            log.info("курсы сохранены: {} строк", update.rows)
            0
        }

        is CurrencyUpdate.Failed -> {
            log.error("обновление не удалось: {}", update.reason)
            1
        }
    }
}
