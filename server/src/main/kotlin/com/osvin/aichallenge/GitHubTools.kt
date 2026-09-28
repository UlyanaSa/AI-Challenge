package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.github.GitHubMcpServer
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Инструменты агента из MCP-сервера GitHub: одна долгая сессия на всё время работы сервера.
 *
 * Сессия общая на все запросы, а не своя у каждого: MCP-сервер — это отдельный процесс на
 * JVM, и поднимать его на каждый вопрос пользователя значило бы платить за его запуск и
 * рукопожатие в каждом ответе. Инструменты запоминаются вместе с сессией: у подключённого
 * сервера набор неизменен, а переспрашивается он только после переподключения — то есть
 * ровно тогда, когда мог измениться.
 *
 * Недоступный сервер инструментов не отменяет ответ: инструменты дополняют ответ, а не
 * являются его условием, поэтому причина отказа уходит в лог, а вопрос — модели без
 * инструментов. Попытка повторяется на следующем запросе: причина обычно временная
 * (процесс не поднялся, не сошёлся classpath, кончился лимит процессов), и выключать
 * инструменты до перезапуска сервера из-за одного сбоя на старте было бы хуже всего.
 *
 * Токен GitHub уезжает серверу инструментов окружением процесса, а не кодом: сервер
 * приложения его не читает вовсе, поэтому он не может попасть ни в лог, ни в ответ.
 * Токена нет — сервер инструментов откажет с причиной, и модель скажет об этом человеку,
 * вместо того чтобы выдумать данные.
 *
 * @param config Как запустить сервер инструментов: по умолчанию — класс из своего classpath,
 *        как и у любого локального MCP-сервера проекта.
 * @param onLog Куда писать строки о подключении и о логах сервера инструментов.
 */
class GitHubTools(
    private val config: McpServerConfig = localMcpServerConfig(GITHUB_SERVER_MAIN),
    private val onLog: (String) -> Unit = { println("[agent] $it") }
) {

    private val mutex = Mutex()
    private var session: McpSession? = null
    private var tools: List<AgentTool> = emptyList()

    /**
     * Инструменты на этот запрос: пустой список — сервер инструментов недоступен.
     *
     * Под замком, потому что запросы идут параллельно: без него два одновременных вопроса
     * подняли бы два процесса сервера, и один из них остался бы сиротой.
     */
    suspend fun available(): List<AgentTool> = mutex.withLock {
        if (session?.isRunning == true) return tools
        try {
            val opened = openMcpSession(config, onServerStderr = { onLog("github: $it") })
            val listed = opened.agentTools()
            session = opened
            tools = listed
            onLog(
                "инструменты GitHub (${opened.serverName} ${opened.serverVersion}): " +
                    listed.joinToString { it.name }
            )
            listed
        } catch (cancelled: CancellationException) {
            // Отмена — не отказ сервера инструментов: запрос пользователя оборван,
            // и запоминать это как «GitHub недоступен» было бы неправдой.
            throw cancelled
        } catch (error: Exception) {
            session = null
            tools = emptyList()
            onLog("MCP-сервер GitHub не поднялся: ${error.message}; отвечаю без инструментов")
            emptyList()
        }
    }

    /**
     * Закрывает сессию инструментов: сервер приложения зовёт это при остановке.
     *
     * Без этого процесс MCP-сервера пережил бы остановку приложения: транспорт закрывается
     * вместе с процессом-родителем не всегда, и в системе оставался бы висеть сервер,
     * которому больше некому отвечать.
     */
    suspend fun close() {
        mutex.withLock {
            session?.close()
            session = null
            tools = emptyList()
        }
    }

    companion object {
        /**
         * Точка входа сервера инструментов — та же, что названа в манифесте его JAR.
         *
         * Имя берётся у самого модуля ([GitHubMcpServer.MAIN_CLASS]), а не пишется здесь
         * вторым литералом: переименование файла иначе разошлось бы с тем, что поднимает
         * сервер приложения, и падало бы это только на живом запуске.
         */
        const val GITHUB_SERVER_MAIN = GitHubMcpServer.MAIN_CLASS
    }
}
