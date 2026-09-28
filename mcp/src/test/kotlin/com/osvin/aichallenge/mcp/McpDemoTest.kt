package com.osvin.aichallenge.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantKind
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
        val rulesInStore = listOf(
            Invariant(InvariantKind.STACK.wire, "правило из файла: версия MCP SDK одна на оба конца протокола"),
            Invariant(InvariantKind.BUSINESS.wire, "правило из файла: данные пользователя не уходят третьим лицам")
        )
        val invariantsFile = invariantsFile(rulesInStore)
        log("хранилище сервера: временный файл правил, правил ${rulesInStore.size}")
        val session = openMcpSession(
            localMcpServerConfig(mainClass = "com.osvin.aichallenge.mcp.ProjectMcpServerKt")
                .copy(env = mapOf("INVARIANT_FILE" to invariantsFile.absolutePath)),
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
            // ответ разбирается в правила проекта теми же типами, что и у сервера, и сверяется
            // с набором в хранилище — тест упал бы, ответь инструмент умолчаниями проекта.
            val answer = session.client.callTool("list_invariants", mapOf("profile_id" to DEFAULT_PROFILE))
            val rules = Json.decodeFromString<List<Invariant>>(answer.text())
            assertEquals(rulesInStore, rules, "инструмент вернул не то, что лежит в его хранилище")
            val first = rules.first()
            log("вызов list_invariants: правил ${rules.size}, первое — ${first.kind}: ${first.value}")
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
            // Сверяется всё, что клиент читает из схемы: имя, тип, обязательность, пояснение
            // и список допустимых значений. Пояснение и список — не украшение: из них шторка
            // подключения строит выбор аргумента, и потеря любого из них была бы молчаливой.
            assertEquals(
                spec.arguments.map {
                    McpToolArgument(
                        name = it.name,
                        type = it.type,
                        required = it.required,
                        description = it.description,
                        values = it.values.orEmpty()
                    )
                },
                tool.arguments,
                "аргументы инструмента ${spec.name}"
            )
        }
        log("список сверен со спецификацией сервера: ${tools.size} инструмента, схемы совпали")
    }

    /**
     * Файл правил для серверного процесса: путь к хранилищу сервер берёт из переменной
     * окружения (`INVARIANT_FILE`), как и при обычном запуске. Набор нарочно не похож
     * на умолчания проекта: ответ инструмента должен прийти из этого файла, а не из них.
     */
    private fun invariantsFile(rules: List<Invariant>): File {
        val file = File.createTempFile("invariants", ".json")
        file.deleteOnExit()
        val stored: Map<String, Map<String, List<Invariant>>> = mapOf("profiles" to mapOf(DEFAULT_PROFILE to rules))
        file.writeText(Json.encodeToString(stored))
        return file
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

    private fun McpToolArgument.render(): String = buildString {
        append("$name: ${type ?: "тип не назван"}, ")
        append(if (required) "обязательный" else "необязательный")
        if (values.isNotEmpty()) append(", значения: ${values.joinToString(" | ")}")
        description?.let { append(" — $it") }
    }

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
