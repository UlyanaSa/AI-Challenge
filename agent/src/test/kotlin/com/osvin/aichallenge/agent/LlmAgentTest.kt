package com.osvin.aichallenge.agent

import com.osvin.aichallenge.GenerationFormat
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Подменный транспорт: записывает отправленный запрос и отдаёт заданный ответ.
 * Позволяет проверять агента без сети.
 */
private class FakeLlmClient(
    private val response: DeepSeekResponse
) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return response
    }
}

private fun response(
    content: String,
    promptTokens: Int = 10,
    completionTokens: Int = 20
) = DeepSeekResponse(
    choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", content))),
    usage = DeepSeekResponse.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
)

class LlmAgentTest {

    /** Агент формулирует ровно один запрос к API: сообщение пользователя + настройки генерации. */
    @Test
    fun agentSendsUserMessageAndGenerationSettings() = runBlocking {
        val llm = FakeLlmClient(response("1. Первый пункт.\n2. Второй пункт."))
        val agent = LlmAgent(llm)

        agent.run(
            "Опиши породу",
            AgentOptions(
                model = "deepseek-v4-pro",
                maxTokens = 512,
                temperature = 0.2,
                stop = listOf("  СТОП  ", "   "),
                format = GenerationFormat.BULLET_SENTENCE.key
            )
        )

        val request = llm.requests.single()
        assertEquals("deepseek-v4-pro", request.model)
        assertEquals(512, request.maxTokens)
        assertEquals(0.2, request.temperature)
        assertEquals(listOf("СТОП"), request.stop)
        assertEquals(
            listOf(
                ChatMessage("system", GenerationFormat.BULLET_SENTENCE.instruction!!),
                ChatMessage("user", "Опиши породу")
            ),
            request.messages
        )
    }

    /** Строгий JSON-формат требует от API ответа ровно одним JSON-объектом. */
    @Test
    fun strictJsonFormatEnablesJsonMode() = runBlocking {
        val llm = FakeLlmClient(response("{}"))

        LlmAgent(llm).run("Порода", AgentOptions(format = GenerationFormat.STRICT_JSON.key))

        assertEquals(GenerationFormat.STRICT_JSON_MODE, llm.requests.single().responseFormat)
    }

    /** Свой system prompt и история диалога идут перед текущим запросом. */
    @Test
    fun agentSendsSystemPromptAndHistoryBeforeUserMessage() = runBlocking {
        val llm = FakeLlmClient(response("ок"))

        LlmAgent(llm).run(
            "А сколько лет?",
            AgentOptions(
                systemPrompt = "Отвечай кратко",
                history = listOf(
                    ChatMessage("user", "Привет"),
                    ChatMessage("assistant", "Здравствуйте"),
                    ChatMessage("assistant", "   ")
                ),
                format = GenerationFormat.FREE_FORM.key
            )
        )

        assertEquals(
            listOf(
                ChatMessage("system", "Отвечай кратко"),
                ChatMessage("user", "Привет"),
                ChatMessage("assistant", "Здравствуйте"),
                ChatMessage("user", "А сколько лет?")
            ),
            llm.requests.single().messages
        )
    }

    /** Агент возвращает ответ модели как есть и расход токенов запроса. */
    @Test
    fun agentReturnsModelReplyAndUsage() = runBlocking {
        val llm = FakeLlmClient(response("Париж", promptTokens = 42, completionTokens = 3))

        val result = LlmAgent(llm).run("Столица Франции?", AgentOptions(format = GenerationFormat.FREE_FORM.key))

        assertEquals("Париж", result.reply)
        assertEquals(42, result.promptTokens)
        assertEquals(3, result.completionTokens)
        assertEquals(45, result.usage["total_tokens"])
    }
}
