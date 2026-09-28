package com.osvin.aichallenge.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Адаптер MCP → инструмент агента на живом протоколе.
 *
 * Проверяется не маппинг полей, а то, что за адаптером стоит настоящий вызов: инструмент,
 * полученный от сервера, вызывается агентом с разобранными аргументами, сервер отвечает
 * данными из своего хранилища, а отказ и текст ответа доходят в том виде, в каком их
 * понимает агент ([com.osvin.aichallenge.agent.ToolOutcome]).
 *
 * Сервер — сервер проекта: он поднимается процессом по stdio, как и любой сторонний
 * MCP-сервер, поэтому проверяется протокол целиком, а не вызов функции в одном процессе.
 */
class McpAgentToolsTest {

    @Test
    fun `инструменты MCP становятся инструментами агента`() = runBlocking {
        quietProtocolLogs()
        val rulesInStore = listOf(
            Invariant(InvariantKind.STACK.wire, "правило из файла: инструмент агента и вызов по протоколу — одно")
        )
        val session = openMcpSession(
            localMcpServerConfig(mainClass = "com.osvin.aichallenge.mcp.ProjectMcpServerKt")
                .copy(env = mapOf("INVARIANT_FILE" to invariantsFile(rulesInStore).absolutePath))
        )

        try {
            val agentTools = session.agentTools()
            val listed = session.listTools()

            // Имена и описания — слова сервера: агент показывает модели то, что объявил сервер.
            assertEquals(listed.map { it.name }, agentTools.map { it.name })
            assertEquals(listed.map { it.description.orEmpty() }, agentTools.map { it.description })
            // Схема аргументов уезжает как есть: иначе модель выбирала бы аргументы по копии,
            // которая с сервером не связана.
            assertEquals(listed.map { it.inputSchema }, agentTools.map { it.parameters })

            // Вызов через инструмент агента доходит до сервера и возвращает его данные:
            // аргумент разобран адаптером, ответ — текст инструмента.
            val listInvariants = agentTools.first { it.name == "list_invariants" }
            val outcome = listInvariants.call(JsonObject(mapOf("profile_id" to JsonPrimitive(DEFAULT_PROFILE))))

            assertFalse(outcome.isError, "инструмент ответил отказом: ${outcome.text}")
            assertEquals(rulesInStore, Json.decodeFromString<List<Invariant>>(outcome.text))
        } finally {
            session.close()
        }
    }

    /** Файл правил для серверного процесса: путь к хранилищу сервер берёт из окружения. */
    private fun invariantsFile(rules: List<Invariant>): File {
        val file = File.createTempFile("invariants", ".json")
        file.deleteOnExit()
        val stored: Map<String, Map<String, List<Invariant>>> =
            mapOf("profiles" to mapOf(DEFAULT_PROFILE to rules))
        file.writeText(Json.encodeToString(stored))
        return file
    }

    /** Разбор кадров JSON-RPC в этом тесте не нужен: проверяется адаптер, а не протокол. */
    private fun quietProtocolLogs() {
        (LoggerFactory.getLogger("io.modelcontextprotocol") as? Logger)?.level = Level.WARN
    }
}
