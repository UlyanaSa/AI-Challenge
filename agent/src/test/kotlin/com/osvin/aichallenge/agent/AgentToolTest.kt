package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.ToolCall
import com.osvin.aichallenge.models.ToolCallFunction
import com.osvin.aichallenge.models.config.AppConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Подменный транспорт с очередью ответов: сценарий диалога с моделью.
 *
 * Ответы заданы заранее, потому что проверяется агент, а не модель: что он отправил,
 * что сделал с вызовом и что вернул наверх. Когда сценарий кончился, повторяется
 * последний ответ — так видно запросы, которых сценарий не предполагал.
 */
private class ScriptedLlmClient(private val responses: List<DeepSeekResponse>) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return responses[minOf(requests.size, responses.size) - 1]
    }
}

/** Ответ модели, которая вместо текста просит вызов инструмента. */
private fun toolRequest(
    name: String,
    arguments: String,
    id: String = "call_1"
) = DeepSeekResponse(
    choices = listOf(
        DeepSeekResponse.Choice(
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ToolCall(id = id, function = ToolCallFunction(name = name, arguments = arguments))
                )
            ),
            finishReason = "tool_calls"
        )
    ),
    usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
)

/** Ответ модели текстом. */
private fun textResponse(content: String) = DeepSeekResponse(
    choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", content), "stop")),
    usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
)

/** Схема аргументов инструмента, как её объявляет владелец: enum значения перечисления. */
private val VISIBILITY_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            put(
                "visibility",
                buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("all"))
                        add(JsonPrimitive("public"))
                        add(JsonPrimitive("private"))
                    })
                }
            )
        }
    )
}

/** Следы вызова инструмента: с чем позвали и сколько раз. */
private class ToolCalls {
    val arguments = mutableListOf<JsonObject>()
    val count: Int get() = arguments.size
}

/** Инструмент для проверки: запоминает вызов и отдаёт заданный ответ. */
private fun recordingTool(
    calls: ToolCalls,
    name: String = "get_repositories",
    answer: (JsonObject) -> ToolOutcome = { ToolOutcome("""[{"name":"ai_challenge_task1"}]""") }
) = AgentTool(
    name = name,
    description = "Репозитории пользователя на GitHub",
    parameters = VISIBILITY_SCHEMA,
    call = { arguments ->
        calls.arguments += arguments
        answer(arguments)
    }
)

class AgentToolTest {

    /**
     * Главный путь: модель просит инструмент, агент выполняет его с разобранными аргументами,
     * отправляет результат следующим запросом и возвращает финальный текст.
     */
    @Test
    fun agentCallsToolAndAnswersFromItsResult() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            listOf(
                toolRequest("get_repositories", """{"visibility": "private"}"""),
                textResponse("У вас один приватный репозиторий.")
            )
        )
        val agent = LlmAgent(llm, logger = AgentLogger { })
        val result = agent.run(
            "Покажи мои приватные репозитории на GitHub",
            AgentOptions(tools = listOf(recordingTool(calls)))
        )

        assertEquals("У вас один приватный репозиторий.", result.reply)
        // Аргументы пришли строкой JSON и разобраны: инструмент видит объект, а не текст.
        assertEquals(listOf(JsonObject(mapOf("visibility" to JsonPrimitive("private")))), calls.arguments)

        assertEquals(2, llm.requests.size)
        // Первый запрос объявляет инструмент целиком: без объявления модель не может его выбрать.
        val declaration = llm.requests[0].tools?.single()
        assertEquals("get_repositories", declaration?.function?.name)
        assertEquals("Репозитории пользователя на GitHub", declaration?.function?.description)
        assertEquals(VISIBILITY_SCHEMA, declaration?.function?.parameters)

        // Второй запрос продолжает переписку: вызов модели повторяется как есть (иначе API
        // не примет результат), а сообщение с ролью tool закрывает его по идентификатору.
        val followUp = llm.requests[1].messages
        assertEquals(listOf("call_1"), followUp[followUp.size - 2].toolCalls?.map { it.id })
        val toolMessage = followUp.last()
        assertEquals("tool", toolMessage.role)
        assertEquals("call_1", toolMessage.toolCallId)
        assertEquals("""[{"name":"ai_challenge_task1"}]""", toolMessage.content)

        // Расход: два раунда, и второй состоялся из-за вызова — его токены и есть цена инструмента.
        assertEquals(20, result.promptTokens)
        assertEquals(40, result.completionTokens)
        assertEquals(1, result.tokens.tools.rounds)
        assertEquals(30, result.tokens.tools.tokens)
        // Отчёт несёт вызов целиком, а не одно имя: по нему лента чата печатает команду,
        // которой позвали инструмент, и ответ, который он вернул.
        assertEquals(
            listOf(
                ToolCallRecord(
                    name = "get_repositories",
                    arguments = """{"visibility": "private"}""",
                    result = """[{"name":"ai_challenge_task1"}]"""
                )
            ),
            result.tokens.tools.calls
        )
    }

    /** Без инструментов запрос уходит как раньше: ни объявлений, ни вызовов. */
    @Test
    fun agentWithoutToolsSendsNoDeclarations() = runBlocking {
        val llm = ScriptedLlmClient(listOf(textResponse("Привет")))
        val result = LlmAgent(llm, logger = AgentLogger { }).run("Привет")

        assertNull(llm.requests.single().tools)
        assertEquals(ToolsReport(), result.tokens.tools)
    }

    /** Пустые аргументы — это «аргументов нет», а не ошибка вызова: у инструмента есть умолчания. */
    @Test
    fun agentTreatsEmptyArgumentsAsNoArguments() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            listOf(toolRequest("get_repositories", ""), textResponse("Готово"))
        )
        LlmAgent(llm, logger = AgentLogger { }).run(
            "Покажи все репозитории",
            AgentOptions(tools = listOf(recordingTool(calls)))
        )

        assertEquals(listOf(JsonObject(emptyMap())), calls.arguments)
    }

    /** Незнакомый инструмент — ответ модели с причиной, а не отказ запроса. */
    @Test
    fun agentReportsUnknownToolToModel() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            listOf(
                toolRequest("get_weather", """{"city": "Москва"}"""),
                textResponse("Погоду не знаю, но репозитории показать могу.")
            )
        )
        val result = LlmAgent(llm, logger = AgentLogger { }).run(
            "Какая сегодня погода?",
            AgentOptions(tools = listOf(recordingTool(calls)))
        )

        assertEquals(0, calls.count)
        val toolMessage = llm.requests[1].messages.last()
        assertEquals("tool", toolMessage.role)
        assertEquals("call_1", toolMessage.toolCallId)
        assertTrue(
            toolMessage.content.contains("не объявлен") && toolMessage.content.contains("get_repositories"),
            "модель должна узнать и причину, и что доступно: ${toolMessage.content}"
        )
        assertEquals("Погоду не знаю, но репозитории показать могу.", result.reply)
        val rejected = result.tokens.tools.calls.single()
        assertEquals("get_weather", rejected.name)
        assertEquals("""{"city": "Москва"}""", rejected.arguments)
        assertEquals(true, rejected.failed)
        // Ответ есть и у отказа: в ленте видно, чем именно он объяснён.
        assertTrue(rejected.result.orEmpty().contains("не объявлен"), rejected.result)
    }

    /** Упавший инструмент — тоже ответ модели: причина уходит ей, запрос не падает. */
    @Test
    fun agentTurnsToolFailureIntoModelMessage() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            listOf(
                toolRequest("get_repositories", """{"visibility": "all"}"""),
                textResponse("GitHub недоступен, отвечу без данных.")
            )
        )
        val agent = LlmAgent(llm, logger = AgentLogger { })
        val result = agent.run(
            "Покажи мои репозитории",
            AgentOptions(tools = listOf(recordingTool(calls) { throw IllegalStateException("нет токена GITHUB_TOKEN") }))
        )

        val toolMessage = llm.requests[1].messages.last()
        assertEquals("tool", toolMessage.role)
        assertTrue(
            toolMessage.content.contains("не смог ответить") && toolMessage.content.contains("нет токена GITHUB_TOKEN"),
            "причина отказа должна дойти до модели: ${toolMessage.content}"
        )
        assertEquals("GitHub недоступен, отвечу без данных.", result.reply)
        assertTrue(result.tokens.tools.calls.single().failed)
    }

    /** Аргументы, которые не разбираются, до инструмента не доходят: об этом сообщается отказом. */
    @Test
    fun agentReportsUnparsableArgumentsWithoutCallingTool() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            listOf(
                toolRequest("get_repositories", "visibility=private"),
                textResponse("Уточните запрос.")
            )
        )
        LlmAgent(llm, logger = AgentLogger { }).run(
            "Покажи приватные",
            AgentOptions(tools = listOf(recordingTool(calls)))
        )

        assertEquals(0, calls.count)
        val toolMessage = llm.requests[1].messages.last()
        assertTrue(
            toolMessage.content.contains("не разобрались"),
            "модель должна узнать, что аргументы не поняты: ${toolMessage.content}"
        )
    }

    /**
     * Раунды кончаются: модель просит инструмент трижды (предел), на четвёртый запрос
     * инструменты ей уже не предлагаются, и она отвечает текстом.
     */
    @Test
    fun agentStopsOfferingToolsAfterRoundLimit() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            List(AppConfig.MAX_TOOL_ROUNDS) { index ->
                toolRequest("get_repositories", """{"visibility": "all"}""", id = "call_$index")
            } + textResponse("Отвечаю по тому, что уже получил.")
        )
        val result = LlmAgent(llm, logger = AgentLogger { }).run(
            "Покажи репозитории и посчитай их",
            AgentOptions(tools = listOf(recordingTool(calls)))
        )

        assertEquals(AppConfig.MAX_TOOL_ROUNDS + 1, llm.requests.size)
        assertEquals(AppConfig.MAX_TOOL_ROUNDS, calls.count)
        assertNotNull(llm.requests.first().tools)
        assertNull(llm.requests.last().tools)
        assertEquals("Отвечаю по тому, что уже получил.", result.reply)
        assertEquals(AppConfig.MAX_TOOL_ROUNDS, result.tokens.tools.rounds)
    }

    /**
     * Если модель продолжает просить вызовы и без объявлений, цикл всё равно кончается:
     * лишние вызовы не выполняются, а работа завершается её текстом (здесь — пустым,
     * поэтому запрос отклоняется понятной ошибкой, а не бесконечными вызовами).
     */
    @Test
    fun agentStopsWhenModelKeepsAskingWithoutTools() = runBlocking {
        val calls = ToolCalls()
        val llm = ScriptedLlmClient(
            List(AppConfig.MAX_TOOL_ROUNDS + 1) { index ->
                toolRequest("get_repositories", """{"visibility": "all"}""", id = "call_$index")
            }
        )
        val logs = mutableListOf<String>()
        val agent = LlmAgent(llm, logger = AgentLogger { logs += it })

        assertFailsWith<EmptyReplyException> {
            agent.run("Покажи репозитории", AgentOptions(tools = listOf(recordingTool(calls))))
        }

        assertEquals(AppConfig.MAX_TOOL_ROUNDS, calls.count)
        assertEquals(AppConfig.MAX_TOOL_ROUNDS + 1, llm.requests.size)
        assertTrue(
            logs.any { it.contains("раунды исчерпаны") },
            "в логе должно быть видно, почему вызовы пропущены: $logs"
        )
    }
}
