package com.osvin.aichallenge.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка формата того, что уходит в API и приходит от него, — вокруг вызовов инструментов.
 *
 * Формат задаёт не наш код, а провайдер, и ошибка в нём не видна ни компилятору, ни разбору
 * ответа: запрос просто уходит без инструментов, а модель отвечает так, будто их у неё нет.
 * Поэтому проверяется текст сериализации — редкий случай, когда это осмысленно.
 */
class ToolCallWireTest {

    /** Json клиента: те же настройки, что у живого пути (см. `Application.client`). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun requestWithoutToolsHasNoToolsKey() {
        val body = json.encodeToString(
            DeepSeekRequest(model = "deepseek-v4-flash", messages = listOf(ChatMessage("user", "привет")))
        )

        assertFalse(body.contains("\"tools\""), body)
    }

    @Test
    fun requestWithToolsCarriesDeclaration() {
        val body = json.encodeToString(
            DeepSeekRequest(
                model = "deepseek-v4-flash",
                messages = listOf(ChatMessage("user", "покажи репозитории")),
                tools = listOf(
                    ToolDeclaration(
                        function = ToolFunction(
                            name = "get_repositories",
                            description = "Репозитории пользователя на GitHub",
                            parameters = buildJsonObject { put("type", "object") }
                        )
                    )
                )
            )
        )

        assertTrue(body.contains("\"tools\""), body)
        assertTrue(body.contains("\"type\":\"function\""), body)
        assertTrue(body.contains("\"name\":\"get_repositories\""), body)
    }

    /** Вызов в переписке и ответ инструмента: без пары «вызов — результат» API отклонит запрос. */
    @Test
    fun conversationCarriesToolCallsAndResultId() {
        val assistant = json.encodeToString(
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "call_1",
                        function = ToolCallFunction(name = "get_repositories", arguments = """{"visibility":"private"}""")
                    )
                )
            )
        )
        val tool = json.encodeToString(ChatMessage(role = "tool", content = "[]", toolCallId = "call_1"))

        assertTrue(assistant.contains("\"tool_calls\""), assistant)
        // Аргументы уезжают строкой: так их отдал провайдер, и так он их и ждёт обратно.
        assertTrue(assistant.contains("\"arguments\":\"{\\"), assistant)
        assertTrue(assistant.contains("visibility"), assistant)
        assertTrue(tool.contains("\"tool_call_id\":\"call_1\""), tool)
        assertFalse(tool.contains("tool_calls"), tool)
    }

    /** Ответ с вызовом приходит без текста (`content: null`) — это не ошибка разбора. */
    @Test
    fun responseWithToolCallsAndNullContentParses() {
        val response = json.decodeFromString<DeepSeekResponse>(
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"call_1","type":"function","function":{"name":"get_repositories","arguments":"{\"visibility\": \"private\"}"}}]},
            "finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """.trimIndent()
        )

        val message = response.choices.single().message
        assertEquals("", message.content)
        assertEquals("tool_calls", response.choices.single().finishReason)
        assertEquals("call_1", message.toolCalls?.single()?.id)
        assertEquals("""{"visibility": "private"}""", message.toolCalls?.single()?.function?.arguments)
    }
}
