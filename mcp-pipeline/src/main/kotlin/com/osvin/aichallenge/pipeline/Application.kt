package com.osvin.aichallenge.pipeline

import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.storage.SqliteCurrencyRateRepository
import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import com.osvin.aichallenge.mcp.mcpServer
import com.osvin.aichallenge.mcp.runStdioServer
import com.osvin.aichallenge.pipeline.mcp.PipelineMcpServer
import com.osvin.aichallenge.pipeline.mcp.PipelineToolsData
import kotlin.system.exitProcess

/**
 * Точка входа MCP-сервера пайплайна: сервер на stdio и список инструментов в консоли.
 *
 * Флага режима службы (`--mcp`) здесь нет, в отличие от сервиса курсов: у пайплайна нет работы
 * без клиента — он ничего не собирает и никого не ждёт, а только отвечает на вызовы. Второе имя
 * для того же поведения («поднимайся и говори по протоколу») означало бы выбор, которого нет,
 * поэтому запуск без аргументов и есть запуск сервера — как у сервера инструментов проекта.
 *
 * База открывается лениво, внутри сборки сервера: в консольном режиме список инструментов берётся
 * из объявлений, и открывать ради него историю курсов значило бы требовать базу для справки.
 * Недоступная база — выход с кодом 2 и причиной в потоке ошибок: сервер, у которого нет данных,
 * отвечал бы на каждый вызов отказом, а причина отказа уже названа.
 */
fun main(args: Array<String>) {
    val mode = args.singleOrNull()
    if (mode != null && mode != LIST_TOOLS_FLAG) {
        System.err.println(
            "Режимы сервера пайплайна: без аргументов — сервер на stdio, $LIST_TOOLS_FLAG — список инструментов"
        )
        exitProcess(2)
    }

    val config = PipelineConfig.fromEnvironment()
    val tools = PipelineMcpServer.tools()
    runStdioServer(args, PipelineMcpServer.NAME, PipelineMcpServer.VERSION, tools) {
        val repository = try {
            SqliteCurrencyRateRepository(config.databasePath)
        } catch (error: CurrencyStorageException) {
            System.err.println("История курсов недоступна: ${error.message}")
            exitProcess(2)
        }
        mcpServer(
            PipelineMcpServer.NAME,
            PipelineMcpServer.VERSION,
            PipelineToolsData(repository, config.outputDir),
            tools
        )
    }
}
