package com.osvin.aichallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Объявление инструмента: то, что видит клиент, — без поведения и без данных процесса.
 *
 * Объявление отделено от обработчика намеренно: список инструментов нужен и там, где данных
 * сервера нет вовсе, — в консольном режиме (`--list-tools`) и в проверке, что клиент увидел
 * именно объявленное. Одна спецификация на всё: по ней сервер регистрирует инструмент,
 * строит его схему для клиента и печатает список человеку. Отдельного описания «для клиента»
 * и «для консоли» нет — они разошлись бы с тем, что сервер умеет, и модель получила бы вызов,
 * которого нет.
 */
interface ToolDeclaration {

    /** Имя для вызова. */
    val name: String

    /** Что инструмент отдаёт — словами, по которым его выбирает модель. */
    val description: String

    /** Аргументы вызова. */
    val arguments: List<DeclaredArgument>
}

/**
 * Аргумент инструмента.
 *
 * @param name Имя в вызове.
 * @param description Что аргумент значит — это описание уезжает в схему инструмента.
 * @param type Тип по JSON Schema.
 * @param required Аргумент обязателен: без него вызов не собирается.
 * @param values Допустимые значения, если их конечное число: уезжают в схему как `enum`,
 *        поэтому модель выбирает значение, а не угадывает его формулировку.
 */
data class DeclaredArgument(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = false,
    val values: List<String>? = null
)

/**
 * Инструмент сервера: объявление и поведение вместе, данные процесса — параметр поведения.
 *
 * Данные приходят в обработчик, а не захватываются им при объявлении: объявление — общее
 * для сервера и для консоли, а хранилища, HTTP-клиенты и токены появляются только у
 * поднятого сервера. Так список инструментов можно показать, не поднимая ничего.
 *
 * @param name Имя для вызова.
 * @param description Что инструмент отдаёт.
 * @param arguments Аргументы вызова.
 * @param read Что вернуть клиенту: готовый результат вызова.
 */
data class ServerTool<Data>(
    override val name: String,
    override val description: String,
    override val arguments: List<DeclaredArgument> = emptyList(),
    val read: suspend (Data, CallToolRequest) -> CallToolResult
) : ToolDeclaration

/** Схема аргументов инструмента: из неё клиент берёт имена, типы, значения и обязательность. */
fun ToolDeclaration.inputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        arguments.forEach { argument ->
            put(
                argument.name,
                buildJsonObject {
                    put("type", argument.type)
                    put("description", argument.description)
                    argument.values?.let { values ->
                        put(
                            "enum",
                            buildJsonArray { values.forEach { value -> add(JsonPrimitive(value)) } }
                        )
                    }
                }
            )
        }
    },
    required = arguments.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }
)

/**
 * Сервер с объявленными инструментами: осталось подключить транспорт ([serveStdio]).
 *
 * Уведомлений об изменении списка нет (`listChanged = false`): инструменты объявлены кодом
 * и на живом соединении не появляются, поэтому обещать клиенту уведомления было бы обещанием,
 * которого сервер не сдержит.
 *
 * Исключение инструмента — не падение сервера: оно возвращается результатом с `isError`, и
 * модель отвечает по причине. Другого пути у ошибки нет: ошибка протокола до модели не
 * доходит, а клиент получил бы отказ инструмента, которого не вызывал.
 */
fun <Data> mcpServer(name: String, version: String, data: Data, tools: List<ServerTool<Data>>): Server {
    val server = Server(
        serverInfo = Implementation(name, version),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )
    tools.forEach { tool ->
        server.addTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema()
        ) { request ->
            try {
                tool.read(data, request)
            } catch (cancelled: CancellationException) {
                // Отмена — не ошибка инструмента: превратив её в результат, сервер не заметил бы,
                // что сессию закрывают.
                throw cancelled
            } catch (error: Exception) {
                CallToolResult(
                    content = listOf(TextContent("инструмент ${tool.name} не смог ответить: ${error.message}")),
                    isError = true
                )
            }
        }
    }
    return server
}

/**
 * Инструменты словами сервера — то, что печатает консольный режим (`--list-tools`).
 *
 * Печатается то же, что сервер объявляет клиенту: имена, описания и аргументы берутся из
 * спецификации ([ToolDeclaration]), по которой инструменты и регистрируются.
 */
fun describeTools(name: String, version: String, tools: List<ToolDeclaration>): String = buildString {
    appendLine("$name $version — инструменты, которые объявляет сервер")
    appendLine(
        "инструментов: ${tools.size}; список объявлен кодом, поэтому изменения не рассылаются " +
            "(listChanged = false)"
    )
    tools.forEachIndexed { index, tool ->
        appendLine()
        appendLine("${index + 1}. ${tool.name}")
        appendLine("   ${tool.description}")
        if (tool.arguments.isEmpty()) {
            appendLine("   аргументов нет")
        } else {
            appendLine("   аргументы:")
            tool.arguments.forEach { argument -> appendLine("     ${argument.describe()}") }
        }
    }
}

/** Аргумент одной строкой: имя, тип, обязательность, значения и что он значит. */
private fun DeclaredArgument.describe(): String = buildString {
    append("$name: $type")
    append(if (required) ", обязательный" else ", необязательный")
    values?.let { append(", значения: ${it.joinToString(" | ")}") }
    append(" — $description")
}

/**
 * Аргумент вызова строкой: null — аргумента нет, это не строка или строка пуста.
 *
 * Инструменты проекта принимают строки (идентификатор профиля, значение из списка), поэтому
 * разбор один на всех: своя проверка в каждом инструменте означала бы разные правила для
 * одного и того же аргумента.
 */
fun CallToolRequest.argument(name: String): String? =
    (params.arguments?.get(name) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
