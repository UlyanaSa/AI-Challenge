package com.osvin.aichallenge.agent

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
        val llm = FakeLlmClient(response("Привет"))
        val agent = LlmAgent(llm)

        agent.run(
            "Опиши породу",
            AgentOptions(
                model = "deepseek-v4-pro",
                maxTokens = 512,
                temperature = 0.2,
                stop = listOf("  СТОП  ", "   ")
            )
        )

        val request = llm.requests.single()
        assertEquals("deepseek-v4-pro", request.model)
        assertEquals(512, request.maxTokens)
        assertEquals(0.2, request.temperature)
        assertEquals(listOf("СТОП"), request.stop)
        assertEquals(listOf(ChatMessage("user", "Опиши породу")), request.messages)
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
                )
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

        val result = LlmAgent(llm).run("Столица Франции?")

        assertEquals("Париж", result.reply)
        assertEquals(42, result.promptTokens)
        assertEquals(3, result.completionTokens)
        assertEquals(45, result.usage["total_tokens"])
    }
}
