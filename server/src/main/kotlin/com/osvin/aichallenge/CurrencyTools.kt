package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.currency.MCP_FLAG
import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Инструменты агента из MCP-сервера курсов: сессия, которую открывает первый запрос к модели.
 *
 * Ленивый подъём здесь — решение, обратное инструментам GitHub, и это осознанно. У GitHub
 * сессию открывает человек кнопкой, потому что кнопке есть что показать: найден ли доступ,
 * кто вошёл, какие у токена права, — и есть что исправить, если доступа нет. У сервиса курсов
 * показывать нечего и исправлять нечего: он не спрашивает ни ключей, ни разрешений, а сбор
 * идёт по расписанию сам. Кнопка «подключить курсы» просила бы человека нажать её ради того,
 * что уже работает, а забытое нажатие выглядело бы как «инструментов нет» — при том что сервис
 * жив и данные собирает.
 *
 * Сессия переживает запросы: сервис на stdio — это процесс, и поднимать его заново на каждый
 * вопрос значило бы платить за запуск JVM и рукопожатие в каждом ответе. Недоступный сервис
 * ответ чата не отменяет: инструменты дополняют ответ, а не являются его условием, поэтому
 * при отказе список пуст, а причина уходит в лог.
 *
 * @param config Как запустить сервис: по умолчанию — класс из своего classpath (локальный
 *        запуск), а на VPS — команда из [CURRENCY_MCP_COMMAND_ENV]. `null` — сервиса в этом
 *        запуске нет вовсе, и приложение работает без инструментов курсов.
 * @param onLog Куда писать строки о подключении и о логах сервиса.
 */
class CurrencyTools(
    private val config: McpServerConfig? = currencyMcpServerConfig(),
    private val onLog: (String) -> Unit = { println("[agent] $it") }
) {

    private val mutex = Mutex()
    private var session: McpSession? = null
    private var modelTools: List<AgentTool> = emptyList()

    /**
     * Инструменты на этот запрос: недоступный сервис — пустой список, а не отказ ответа.
     *
     * Мёртвая сессия (процесс уже умер) закрывается и заменяется новой: транспорт, оставшийся
     * от неё, иначе копился бы на каждое обращение, а инструменты отвечали бы от имени процесса,
     * которого нет.
     */
    suspend fun tools(): List<AgentTool> = mutex.withLock {
        val server = config ?: return emptyList()
        session?.takeIf { it.isRunning }?.let { return modelTools }
        closeSession()
        try {
            val opened = openMcpSession(server, onServerStderr = { onLog("currency: $it") })
            val tools = opened.agentTools()
            session = opened
            modelTools = tools
            onLog("Курсы: сервис поднят, инструментов ${tools.size}")
            tools
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Причина в лог, а ответ — без инструментов: недоступный сервис курсов не должен
            // превращать вопрос человека в отказ ответа.
            onLog("Курсы: сервер инструментов недоступен: ${error.message}")
            emptyList()
        }
    }

    /**
     * Закрывает сессию: сервер приложения останавливается — процесс сервиса гасим сами.
     *
     * Без этого процесс сервиса курсов пережил бы остановку приложения: транспорт закрывается
     * вместе с родителем не всегда, и в системе остался бы висеть сервер, которому больше
     * некому отвечать.
     */
    suspend fun close() {
        mutex.withLock { closeSession() }
    }

    /** Гасит сессию, не превращая сбой закрытия в отказ операции: процесс уже получил конец ввода. */
    private suspend fun closeSession() {
        try {
            session?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onLog("Курсы: сессия закрылась с ошибкой: ${error.message}")
        }
        session = null
        modelTools = emptyList()
    }
}

/**
 * Переменная окружения с командой запуска сервиса курсов.
 *
 * Нужна ровно для одного случая — когда сервис живёт не рядом с приложением, а на VPS, как
 * того и требует задание:
 * `CURRENCY_MCP_COMMAND="ssh vps java -jar /opt/currency-monitor/currency-monitor.jar --mcp"`.
 * Флаг `--mcp` здесь обязателен: без него команда поднимет службу, которая живёт до остановки
 * и не отвечает клиенту по протоколу, — соединение зависло бы на рукопожатии.
 * Команда разбирается по пробелам, без оболочки: оболочка здесь значила бы, что настройка
 * приложения исполняет произвольный текст из окружения, а кавычки и подстановки — что
 * команду нельзя прочитать глазами.
 */
const val CURRENCY_MCP_COMMAND_ENV = "CURRENCY_MCP_COMMAND"

/**
 * Как запустить сервер инструментов курсов: локально классом из своего classpath или командой из окружения.
 *
 * Локально класс берётся тот же, что в манифесте fat JAR сервиса ([CurrencyMcpServer.MAIN_CLASS]),
 * а не пишется здесь вторым литералом: переименование файла иначе разошлось бы с тем, что
 * поднимает сервер приложения, и падало бы это только на живом запуске.
 *
 * Окружение сервису не перечисляется: дочерний процесс наследует окружение родителя, и
 * `CURRENCY_API_BASE`, `CURRENCY_DB`, `CURRENCY_INTERVAL`, заданные для приложения, доходят
 * до сервиса сами — иначе их пришлось бы повторять в двух местах и однажды забыть.
 */
internal fun currencyMcpServerConfig(env: Map<String, String> = System.getenv()): McpServerConfig =
    env[CURRENCY_MCP_COMMAND_ENV]?.takeIf { it.isNotBlank() }?.let { command ->
        val parts = command.trim().split(WHITESPACE)
        McpServerConfig(command = parts.first(), args = parts.drop(1))
    } ?: localMcpServerConfig(CurrencyMcpServer.MAIN_CLASS, args = listOf(MCP_FLAG))

/** Разделитель частей команды запуска: пробелы и переводы строк из окружения. */
private val WHITESPACE = Regex("\\s+")
