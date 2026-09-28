package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.openMcpSession
import com.osvin.aichallenge.pipeline.mcp.PipelineMcpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Инструменты агента из MCP-сервера пайплайна: три шага цепочки — курсы, сводка, файл.
 *
 * Сессия ленивая ([tools]): поднимает её первый вопрос к модели, как у инструментов курсов,
 * и по той же причине — спрашивать у человека разрешения тут не на что, серверу пайплайна
 * не нужны ни ключи, ни доступы, а кнопка «подключить» просила бы нажать её ради того, что
 * уже работает. Недоступный сервер не отменяет ответ чата: инструменты дополняют ответ,
 * а не являются его условием, поэтому при отказе список пуст, а причина уходит в лог.
 *
 * Порядок вызовов здесь не зашит: инструменты уезжают модели тремя отдельными объявлениями,
 * и цепочку собирает она — по описаниям, где сказано, чей ответ принимает следующий шаг.
 * Зашитый порядок означал бы один инструмент, делающий всё, и тогда проверять было бы нечего:
 * передача данных между инструментами существует ровно там, где их вызывают по отдельности.
 *
 * @param config Как запустить сервер: по умолчанию — класс из своего classpath, а на машине
 *        со службой курсов — команда из [PIPELINE_MCP_COMMAND_ENV]. `null` — сервера в этом
 *        запуске нет вовсе, и приложение работает без инструментов пайплайна.
 * @param onLog Куда писать строки о подключении и о логах сервера.
 */
class PipelineTools(
    private val config: McpServerConfig? = pipelineMcpServerConfig(),
    private val onLog: (String) -> Unit = { println("[agent] $it") }
) {

    private val mutex = Mutex()
    private var session: McpSession? = null
    private var modelTools: List<AgentTool> = emptyList()

    /**
     * Инструменты пайплайна на этот запрос: недоступный сервер — пустой список, а не отказ ответа.
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
            val opened = openMcpSession(server, onServerStderr = { onLog("пайплайн: $it") })
            val tools = opened.agentTools()
            session = opened
            modelTools = tools
            onLog("Пайплайн: сервер поднят, инструментов ${tools.size}")
            tools
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onLog("Пайплайн: сервер инструментов недоступен: ${error.message}")
            emptyList()
        }
    }

    /**
     * Закрывает сессию: сервер приложения останавливается — процесс сервера пайплайна гасим сами.
     *
     * Без этого процесс сервера инструментов пережил бы остановку приложения: транспорт
     * закрывается вместе с родителем не всегда, и в системе остался бы висеть сервер, которому
     * больше некому отвечать.
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
            onLog("Пайплайн: сессия закрылась с ошибкой: ${error.message}")
        }
        session = null
        modelTools = emptyList()
    }
}

/**
 * Переменная окружения с командой запуска сервера пайплайна.
 *
 * Нужна для случая, когда история курсов лежит не рядом с приложением: пайплайн читает ту же базу,
 * что служба курсов, и на машине со службой запускается командой вида
 * `PIPELINE_MCP_COMMAND="ssh vps java -jar /opt/mcp-pipeline/mcp-pipeline.jar"`.
 * Без переменной сервер поднимается классом из своего classpath — это локальный запуск,
 * и он берёт базу по `CURRENCY_DB`, как и остальные части проекта.
 */
const val PIPELINE_MCP_COMMAND_ENV = "PIPELINE_MCP_COMMAND"

/**
 * Как запустить сервер пайплайна: командой из окружения или классом из своего classpath.
 *
 * Аргументов режима нет, в отличие от службы курсов: у сервера пайплайна один режим — говорить
 * по протоколу, и второго имени для него не заводится.
 */
internal fun pipelineMcpServerConfig(env: Map<String, String> = System.getenv()): McpServerConfig =
    mcpServerConfigFromEnvironment(env, PIPELINE_MCP_COMMAND_ENV, PipelineMcpServer.MAIN_CLASS)
