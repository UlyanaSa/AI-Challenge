package com.osvin.aichallenge.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Аргументы инструмента читаются из схемы, которую объявил сервер.
 *
 * Проверка отдельная от демонстрации не случайно: у инструментов сервера проекта аргумент
 * один и необязательный, поэтому ветку обязательного аргумента демонстрация не проходит —
 * а модель собирает вызов именно по ней. Схемы здесь такие же, какими их шлют серверы MCP,
 * поэтому проверяются и типы, и отметка об обязательности.
 */
class McpToolTest {

    @Test
    fun `обязательные аргументы отличаются от необязательных`() {
        val tool = McpTool(
            name = "sum",
            description = null,
            inputSchema = schemaOf(
                "a" to true,
                "b" to true,
                "note" to false
            )
        )

        assertEquals(
            listOf(
                McpToolArgument(name = "a", type = "number", required = true),
                McpToolArgument(name = "b", type = "number", required = true),
                McpToolArgument(name = "note", type = "string", required = false)
            ),
            tool.arguments
        )
    }

    @Test
    fun `инструмент без аргументов не выдумывает их`() {
        val tool = McpTool(
            name = "ping",
            description = "Инструмент без аргументов",
            inputSchema = buildJsonObject { put("type", "object") }
        )

        assertEquals(emptyList(), tool.arguments)
    }

    /** Схема с аргументами: имя, тип и обязательность — как их объявляет сервер. */
    private fun schemaOf(vararg arguments: Pair<String, Boolean>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arguments.forEach { (name, _) ->
                putJsonObject(name) { put("type", if (name == "note") "string" else "number") }
            }
        }
        put(
            "required",
            buildJsonArray {
                arguments.filter { (_, required) -> required }
                    .forEach { (name, _) -> add(JsonPrimitive(name)) }
            }
        )
    }
}
