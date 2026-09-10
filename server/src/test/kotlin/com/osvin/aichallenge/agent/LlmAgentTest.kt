package com.osvin.aichallenge.agent

import com.osvin.aichallenge.GenerationFormat
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Подменный транспорт: записывает отправленные запросы и отдаёт ответы,
 * заданные функцией от номера запроса. Позволяет проверять агента без сети.
 */
private class FakeLlmClient(
    private val respond: (DeepSeekRequest, Int) -> DeepSeekResponse
) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return respond(request, requests.size)
    }
}

private fun reply(
    content: String,
    finishReason: String = "stop",
    promptTokens: Int = 10,
    completionTokens: Int = 20
) = DeepSeekResponse(
    choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", content), finishReason)),
    usage = DeepSeekResponse.Usage(promptTokens, completionTokens, promptTokens + completionTokens)
)

class LlmAgentTest {

    /** Агент формулирует запрос к API: сообщение пользователя + настройки генерации. */
    @Test
    fun agentSendsUserMessageAndGenerationSettings() = runBlocking {
        val llm = FakeLlmClient { _, _ -> reply("1. Первый пункт.\n2. Второй пункт.") }
        val agent = LlmAgent(llm)

        agent.run(
            "Опиши породу",
            AgentOptions(
                model = "deepseek-v4-pro",
                maxTokens = 512,
                temperature = 0.2,
                runs = 1,
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
        val llm = FakeLlmClient { _, _ -> reply("{}") }

        LlmAgent(llm).run("Порода", AgentOptions(runs = 1, format = GenerationFormat.STRICT_JSON.key))

        assertEquals(GenerationFormat.STRICT_JSON_MODE, llm.requests.single().responseFormat)
    }

    /** Обрыв ответа по лимиту токенов: агент повторяет запрос с увеличенным бюджетом. */
    @Test
    fun agentRepeatsRequestWhenReplyTruncated() = runBlocking {
        val llm = FakeLlmClient { _, attempt ->
            if (attempt == 1) {
                reply("оборванный ответ", finishReason = "length", completionTokens = 5)
            } else {
                reply("1. Раз.\n2. Два.")
            }
        }

        val result = LlmAgent(llm).run(
            "Порода",
            AgentOptions(runs = 1, maxTokens = 1000, format = GenerationFormat.BULLET_SENTENCE.key)
        )

        assertEquals(2, llm.requests.size)
        assertTrue(
            llm.requests[1].maxTokens!! > llm.requests[0].maxTokens!!,
            "повторный запрос должен увеличить лимит токенов"
        )
        assertTrue(result.reply.contains("1. Раз."), "в ответе должен быть текст второго прогона")
    }

    /** Ответ, не совпавший с заданным форматом, помечается ошибкой в тексте для интерфейса. */
    @Test
    fun agentReportsFormatMismatch() = runBlocking {
        val llm = FakeLlmClient { _, _ -> reply("Это не JSON") }

        val result = LlmAgent(llm).run(
            "Порода",
            AgentOptions(runs = 1, format = GenerationFormat.STRICT_JSON.key)
        )

        assertTrue(result.reply.contains("ОШИБКА"), "рассогласование формата должно быть видно в ответе")
        assertTrue(result.reply.contains("Это не JSON"), "в ответе должно быть тело ответа модели")
    }

    /** Агент выполняет заданное число прогонов и отдаёт расход токенов последнего. */
    @Test
    fun agentRunsQuestionAndReportsLastUsage() = runBlocking {
        val llm = FakeLlmClient { _, attempt ->
            reply("1. Прогон $attempt.\n2. Пункт.", promptTokens = 100, completionTokens = 7)
        }

        val result = LlmAgent(llm).run(
            "Порода",
            AgentOptions(runs = 3, format = GenerationFormat.BULLET_SENTENCE.key)
        )

        assertEquals(3, llm.requests.size)
        assertEquals(100, result.promptTokens)
        assertEquals(7, result.completionTokens)
        assertEquals(107, result.usage["total_tokens"])
    }
}
