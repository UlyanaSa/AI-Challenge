package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.openMcpSession
import com.osvin.aichallenge.report.mcp.ReportMcpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Инструменты агента из MCP-сервера отчётов: сборка markdown и запись файла.
 *
 * Сессия ленивая, как у инструментов курсов и пайплайна: сервер отчётов не спрашивает ни ключей,
 * ни разрешений, а поднимается он на первом обращении к модели. Каталог отчётов при этом не
 * трогается: папку создаёт запись, и от одного лишь запуска приложения пустой каталог отчётов
 * был бы мусором рядом с процессом.
 *
 * Сервер отчётов — отдельный сервер, а не инструмент внутри пайплайна: у него свой предмет
 * (отчёты как таковые, из любых данных), своя настройка каталога и своя точка входа. Совпадение
 * имён при этом возможно — записать файл умеют оба, — и различает их не код, а имя вызова
 * с признаком сервера в реестре (см. `McpToolRegistry`).
 *
 * @param config Как запустить сервер: по умолчанию — класс из своего classpath, а на машине,
 *        где отчёты хранятся отдельно, — команда из [REPORT_MCP_COMMAND_ENV]. `null` — сервера
 *        в этом запуске нет, и приложение работает без инструментов отчётов.
 */
class ReportTools(
    private val config: McpServerConfig? = reportMcpServerConfig(),
    private val onLog: (String) -> Unit = { println("[agent] $it") }
) {

    private val mutex = Mutex()
    private var session: McpSession? = null
    private var modelTools: List<AgentTool> = emptyList()

    /** Инструменты отчётов: недоступный сервер даёт пустой список, а ответ чата не отменяет. */
    suspend fun tools(): List<AgentTool> = mutex.withLock {
        val server = config ?: return emptyList()
        session?.takeIf { it.isRunning }?.let { return modelTools }
        closeSession()
        try {
            val opened = openMcpSession(server, onServerStderr = { onLog("отчёты: $it") })
            val tools = opened.agentTools()
            session = opened
            modelTools = tools
            onLog("Отчёты: сервер поднят, инструментов ${tools.size}")
            tools
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onLog("Отчёты: сервер инструментов недоступен: ${error.message}")
            emptyList()
        }
    }

    /** Гасит процесс сервера отчётов вместе с приложением: чужой процесс не должен переживать нас. */
    suspend fun close() {
        mutex.withLock { closeSession() }
    }

    /** Закрывает сессию, не превращая сбой закрытия в отказ операции. */
    private suspend fun closeSession() {
        try {
            session?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onLog("Отчёты: сессия закрылась с ошибкой: ${error.message}")
        }
        session = null
        modelTools = emptyList()
    }
}

/**
 * Переменная окружения с командой запуска сервера отчётов.
 *
 * Нужна для случая, когда отчёты пишутся не рядом с приложением: сервер поднимается на машине,
 * где лежит общий каталог, командой вида
 * `REPORT_MCP_COMMAND="ssh vps java -jar /opt/mcp-report/mcp-report.jar"`.
 */
const val REPORT_MCP_COMMAND_ENV = "REPORT_MCP_COMMAND"

/**
 * Как запустить сервер отчётов: командой из окружения или классом из своего classpath.
 *
 * Аргументов режима нет: у сервера отчётов один режим — говорить по протоколу.
 */
internal fun reportMcpServerConfig(env: Map<String, String> = System.getenv()): McpServerConfig =
    mcpServerConfigFromEnvironment(env, REPORT_MCP_COMMAND_ENV, ReportMcpServer.MAIN_CLASS)
