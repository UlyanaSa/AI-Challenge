package com.osvin.aichallenge.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.mcp.McpTool
import com.osvin.aichallenge.agent.mcp.McpToolArgument
import com.osvin.aichallenge.agent.mcp.localMcpServerConfig
import com.osvin.aichallenge.agent.mcp.openMcpSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Демонстрация дня 16: агент подключается к MCP-серверу и получает от него список инструментов.
 *
 * Сервер — локальный ([ProjectMcpServer]): клиент поднимает его процессом и говорит с ним
 * по stdio, как говорит с любым сторонним MCP-сервером. Поэтому проверяется не «мы умеем
 * вызывать свою же функцию», а протокол целиком: рукопожатие, объявленные возможности,
 * список инструментов со схемами — и то, что после закрытия сессии серверный процесс
 * не остаётся висеть.
 *
 * Ожидаемый список берётся из [ProjectMcpServer.tools] — той же спецификации, по которой
 * сервер регистрирует инструменты. Своего списка «как должно быть у клиента» здесь нет:
 * иначе проверка сравнивала бы копию с копией.
 */
class McpDemoTest {

    @Test
    fun `агент видит инструменты MCP-сервера`() = runBlocking {
        quietProtocolLogs()
        stage("MCP: подключение к серверу проекта")
        val session = openMcpSession(
            localMcpServerConfig(mainClass = "com.osvin.aichallenge.mcp.ProjectMcpServerKt"),
            onServerStderr = { log("сервер: $it") }
        )
        val tools = try {
            log("соединение установлено: ${session.serverName} ${session.serverVersion}")
            assertTrue(session.supportsTools, "сервер не объявил возможность работать с инструментами")
            log("сервер объявил инструменты: ${if (session.supportsTools) "да" else "нет"}")

            val listed = session.listTools()
            log("получено инструментов: ${listed.size}")
            listed.forEachIndexed { index, tool -> printTool(index + 1, listed.size, tool) }

            // Список — это объявление; вызов показывает, что за ним стоит настоящий инструмент:
            // ответ разбирается в правила проекта теми же типами, что и у сервера.
            val answer = session.client.callTool("list_invariants", mapOf("profile_id" to DEFAULT_PROFILE))
            val rules = Json.decodeFromString<List<Invariant>>(answer.text())
            assertTrue(rules.isNotEmpty(), "инструмент list_invariants вернул пустой набор правил")
            val first = rules.first()
            log("вызов list_invariants: правил ${rules.size}, первое — ${first.kind}: ${first.value.take(60)}…")
            listed
        } finally {
            session.close()
        }

        assertFalse(session.isRunning, "серверный процесс остался работать после закрытия сессии")
        log("сессия закрыта: серверный процесс остановлен")

        assertEquals(
            ProjectMcpServer.tools.map { it.name },
            tools.map { it.name },
            "список инструментов у клиента"
        )
        ProjectMcpServer.tools.forEach { spec ->
            val tool = tools.first { it.name == spec.name }
            assertEquals(spec.description, tool.description, "описание инструмента ${spec.name}")
            assertEquals(
                spec.arguments.map { McpToolArgument(it.name, it.type, it.required) },
                tool.arguments,
                "аргументы инструмента ${spec.name}"
            )
        }
        log("список сверен со спецификацией сервера: ${tools.size} инструмента, схемы совпали")
    }

    /** Инструмент глазами клиента: имя, описание и аргументы из схемы сервера. */
    private fun printTool(index: Int, total: Int, tool: McpTool) {
        log("инструмент $index/$total: ${tool.name}")
        log("  ${tool.description}")
        log("  аргументы: " + tool.arguments.joinToString { it.render() }.ifEmpty { "нет" })
    }

    /** Текст ответа инструмента: инструменты проекта отвечают текстом, а не блоками. */
    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun McpToolArgument.render(): String =
        "$name: ${type ?: "тип не назван"}, " + if (required) "обязательный" else "необязательный"

    /**
     * Протокольные логи SDK — на уровень WARN: демонстрация печатает список инструментов,
     * а разбор кадров JSON-RPC в этот вывод не помещается. Кадры проверяет сам протокол:
     * неразобранный кадр сорвал бы рукопожатие, и демонстрация упала бы до печати списка.
     */
    private fun quietProtocolLogs() {
        (LoggerFactory.getLogger("io.modelcontextprotocol") as? Logger)?.level = Level.WARN
    }

    /** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
    private fun log(line: String) = println("[agent] $line")

    /** Заголовок этапа: по нему видно, что печатает прогон. */
    private fun stage(title: String) = println("[agent] === $title ===")
}
