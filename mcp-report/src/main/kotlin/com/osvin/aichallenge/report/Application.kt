package com.osvin.aichallenge.report

import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import com.osvin.aichallenge.mcp.mcpServer
import com.osvin.aichallenge.mcp.runStdioServer
import com.osvin.aichallenge.report.mcp.ReportMcpServer
import com.osvin.aichallenge.report.mcp.ReportToolsData
import kotlin.system.exitProcess

/**
 * Точка входа MCP-сервера отчётов: сервер на stdio и список инструментов в консоли.
 *
 * Флага режима службы (`--mcp`) здесь нет, в отличие от сервиса курсов: у сервера отчётов нет
 * работы без клиента — он ничего не собирает и никого не ждёт, а только отвечает на вызовы.
 * Второе имя для того же поведения («поднимайся и говори по протоколу») означало бы выбор,
 * которого нет, поэтому запуск без аргументов и есть запуск сервера — как у сервера пайплайна.
 *
 * Ничего не открывается при старте: у сервера нет ни базы, ни соединения, а каталог отчётов
 * заводит запись. Поэтому сервер поднимается и на машине, где каталога ещё нет, и отказ на
 * старте был бы отказом из-за того, что появится при первом вызове. Сборка сервера идёт внутри
 * [runStdioServer] — в консольном режиме (`--list-tools`) инструменты берутся из объявлений,
 * и поднимать ради них транспорт незачем.
 */
fun main(args: Array<String>) {
    val mode = args.singleOrNull()
    if (mode != null && mode != LIST_TOOLS_FLAG) {
        System.err.println(
            "Режимы сервера отчётов: без аргументов — сервер на stdio, $LIST_TOOLS_FLAG — список инструментов"
        )
        exitProcess(2)
    }

    val config = ReportConfig.fromEnvironment()
    val tools = ReportMcpServer.tools()
    runStdioServer(args, ReportMcpServer.NAME, ReportMcpServer.VERSION, tools) {
        mcpServer(
            ReportMcpServer.NAME,
            ReportMcpServer.VERSION,
            ReportToolsData(config.reportsDir),
            tools
        )
    }
}
