package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    completionTokens: Int = 20,
    reasoningTokens: Int = 0,
    finishReason: String = "stop"
) = DeepSeekResponse(
    choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", content), finishReason)),
    usage = DeepSeekResponse.Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
        completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(reasoningTokens)
    )
)

/** Агент с записью логов вместо печати в консоль. */
private fun testAgent(llm: LlmClient, logs: MutableList<String> = mutableListOf()) =
    LlmAgent(llm, logger = AgentLogger { logs += it })

class LlmAgentTest {

    /** Агент формулирует ровно один запрос к API: сообщение пользователя + настройки генерации. */
    @Test
    fun agentSendsUserMessageAndGenerationSettings() = runBlocking {
        val llm = FakeLlmClient(response("Привет"))
        val agent = testAgent(llm)

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
        // Первый запрос диалога: история пуста, поэтому system prompt — само
        // первое сообщение пользователя (свой system prompt не задан)
        assertEquals(
            listOf(
                ChatMessage("system", "Опиши породу"),
                ChatMessage("user", "Опиши породу")
            ),
            request.messages
        )
    }

    /** Заданный system prompt и история диалога идут перед текущим запросом. */
    @Test
    fun agentSendsSystemPromptAndHistoryBeforeUserMessage() = runBlocking {
        val llm = FakeLlmClient(response("ок"))

        testAgent(llm).run(
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

    /** System prompt не задан — его роль играет первое сообщение диалога. */
    @Test
    fun firstUserMessageBecomesSystemPromptWhenPromptIsNotSet() = runBlocking {
        val llm = FakeLlmClient(response("ок"))

        testAgent(llm).run(
            "А теперь переведи это",
            AgentOptions(
                history = listOf(
                    ChatMessage("user", "Ты переводчик, отвечай только переводом"),
                    ChatMessage("assistant", "Понял, жду текст")
                )
            )
        )

        assertEquals(
            listOf(
                ChatMessage("system", "Ты переводчик, отвечай только переводом"),
                ChatMessage("user", "Ты переводчик, отвечай только переводом"),
                ChatMessage("assistant", "Понял, жду текст"),
                ChatMessage("user", "А теперь переведи это")
            ),
            llm.requests.single().messages
        )
    }

    /** Заданный system prompt важнее первого сообщения диалога. */
    @Test
    fun settingsSystemPromptWinsOverFirstMessage() = runBlocking {
        val llm = FakeLlmClient(response("ок"))

        testAgent(llm).run(
            "Вопрос",
            AgentOptions(
                systemPrompt = "Отвечай кратко",
                history = listOf(ChatMessage("user", "Ты переводчик"))
            )
        )

        assertEquals(
            listOf(
                ChatMessage("system", "Отвечай кратко"),
                ChatMessage("user", "Ты переводчик"),
                ChatMessage("user", "Вопрос")
            ),
            llm.requests.single().messages
        )
    }

    /**
     * Первое сообщение остаётся system prompt и когда история сжата: свёрнутые
     * сообщения уезжают в сводку, а инструкция из первого сообщения — нет.
     */
    @Test
    fun firstMessageStaysSystemPromptWhenHistoryIsCompressed() = runBlocking {
        val llm = FakeLlmClient(response("сводка"))
        val agent = LlmAgent(
            llm,
            logger = AgentLogger { },
            compressor = HistoryCompressor(keepLastMessages = 1, compressStep = 2)
        )

        agent.run(
            "Что дальше?",
            AgentOptions(
                sessionId = "session",
                strategy = ContextStrategy.SUMMARY,
                history = listOf(
                    ChatMessage("user", "Ты строгий редактор"),
                    ChatMessage("assistant", "Хорошо"),
                    ChatMessage("user", "Проверь текст"),
                    ChatMessage("assistant", "Нашёл две ошибки")
                )
            )
        )

        val messages = llm.requests.last().messages
        assertEquals(ChatMessage("system", "Ты строгий редактор"), messages.first())
        assertEquals(
            1, messages.count { it.content == "Ты строгий редактор" },
            "первое сообщение несёт system prompt, в истории оно не повторяется"
        )
        assertTrue(
            messages.none { it.content == "Проверь текст" },
            "свёрнутые сообщения уходят в сводку, а не в запрос: $messages"
        )
    }

    /** Агент возвращает ответ модели как есть и расход токенов запроса. */
    @Test
    fun agentReturnsModelReplyAndUsage() = runBlocking {
        val llm = FakeLlmClient(response("Париж", promptTokens = 42, completionTokens = 3))

        val result = testAgent(llm).run("Столица Франции?")

        assertEquals("Париж", result.reply)
        assertEquals(42, result.promptTokens)
        assertEquals(3, result.completionTokens)
        assertEquals(45, result.usage["total_tokens"])
    }

    /** Токены разложены по частям запроса, ответ считан из usage, стоимость — по тарифу модели. */
    @Test
    fun agentReportsTokenBreakdownAndCost() = runBlocking {
        val llm = FakeLlmClient(
            response(
                "ок",
                promptTokens = 1_000_000,
                completionTokens = 1_000_000,
                reasoningTokens = 800_000,
                finishReason = "length"
            )
        )

        val result = testAgent(llm).run(
            "Сколько стоит этот запрос?",
            AgentOptions(
                model = "deepseek-v4-flash",
                systemPrompt = "Отвечай кратко и по делу",
                history = listOf(
                    ChatMessage("user", "Привет, расскажи про токены"),
                    ChatMessage("assistant", "Токен — это часть слова")
                )
            )
        )

        val tokens = result.tokens
        assertTrue(tokens.systemPrompt > 0, "system prompt должен попасть в разбивку")
        assertTrue(tokens.history > tokens.systemPrompt, "история длиннее system prompt")
        assertTrue(tokens.request > 0, "текущий запрос должен попасть в разбивку")
        assertTrue(
            tokens.promptEstimate < tokens.promptTokens,
            "итоговые токены запроса берутся из usage API, а не из локальной оценки"
        )
        assertEquals(1_000_000, tokens.promptTokens)
        assertEquals(1_000_000, tokens.replyTokens)
        assertEquals(800_000, tokens.replyReasoningTokens)
        assertEquals("length", tokens.replyFinishReason)
        assertEquals(0.22 + 0.66, tokens.costUsd!!, 1e-9)
        assertEquals(ModelCatalog.CONTEXT_WINDOW, tokens.contextWindow)
        assertEquals(ModelCatalog.MAX_OUTPUT_TOKENS, tokens.maxOutputTokens)
    }

    /** У модели без опубликованного тарифа стоимость не считается, но границы известны. */
    @Test
    fun agentHasNoCostForModelWithoutPublishedTariff() = runBlocking {
        val llm = FakeLlmClient(response("ок", promptTokens = 100, completionTokens = 10))

        val tokens = testAgent(llm).run(
            "Что на картинке?",
            AgentOptions(model = "deepseek-v4-flash-vision-exp")
        ).tokens

        assertNull(tokens.costUsd)
        assertEquals(ModelCatalog.CONTEXT_WINDOW, tokens.contextWindow)
    }

    /** Переполнение контекста агент ловит до обращения к API и пишет причину в лог. */
    @Test
    fun agentRefusesOverflowingContextWithoutCallingApi() = runBlocking {
        val llm = FakeLlmClient(response("ок"))
        val logs = mutableListOf<String>()
        // ~4 млн символов кириллицы — заметно больше окна в 1 048 576 токенов
        val hugeHistory = listOf(ChatMessage("user", "я".repeat(4_000_000)))

        val failure = assertFailsWith<ContextOverflowException> {
            testAgent(llm, logs).run("И что дальше?", AgentOptions(history = hugeHistory, maxTokens = 2000))
        }

        assertEquals(2000, failure.maxOutputTokens)
        assertEquals(ModelCatalog.CONTEXT_WINDOW, failure.contextWindow)
        assertTrue(
            failure.promptTokens + failure.maxOutputTokens > failure.contextWindow,
            "исключение должно нести числа, по которым видно переполнение"
        )
        assertTrue(llm.requests.isEmpty(), "заведомо неуспешный запрос не должен уходить в API")
        assertTrue(
            logs.any { it.startsWith("Переполнение контекста") && it.contains("${failure.promptTokens} ток.") },
            "в логе должно быть видно, чего не хватило: $logs"
        )
    }

    /** Логи показывают разбивку запроса по частям и ответ модели вместе с его текстом. */
    @Test
    fun agentLogsRequestBreakdownAndReply() = runBlocking {
        val logs = mutableListOf<String>()
        val llm = FakeLlmClient(
            response("Париж", promptTokens = 42, completionTokens = 7, reasoningTokens = 5)
        )

        testAgent(llm, logs).run(
            "Столица Франции?",
            AgentOptions(
                systemPrompt = "Отвечай кратко",
                history = listOf(ChatMessage("user", "Привет"))
            )
        )

        val request = logs.first { it.startsWith("Запрос") }
        assertTrue(request.contains("system prompt: ") && request.contains("история: "))
        assertTrue(request.contains("текущий вопрос: "))
        assertTrue(request.contains("окно модели: ${ModelCatalog.CONTEXT_WINDOW} ток."))

        val reply = logs.first { it.startsWith("Ответ ←") }
        assertTrue(reply.contains("токенов ответа: 7"))
        assertTrue(reply.contains("рассуждения: 5"))
        assertTrue(reply.contains("finish: stop"))
        assertTrue(logs.any { it == "Текст ответа: Париж" })
    }

    /** Ошибку провайдера агент печатает целиком: в логе виден реальный ответ API. */
    @Test
    fun agentLogsRealApiResponseOnError() = runBlocking {
        val logs = mutableListOf<String>()
        val providerResponse =
            """{"error":{"message":"This model's maximum context length is 1048576 tokens. """ +
                """However, you requested 1430334 tokens"}}"""
        val llm = object : LlmClient {
            override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse =
                throw LlmApiException(400, "DeepSeek API error: 400 Bad Request - $providerResponse")
        }

        val failure = assertFailsWith<LlmApiException> {
            testAgent(llm, logs).run(
                "Скажи ок",
                AgentOptions(history = listOf(ChatMessage("user", "длинная история")))
            )
        }

        assertEquals(400, failure.status)
        val logged = logs.single { it.startsWith("Ошибка API") }
        assertTrue(logged.contains("HTTP 400"))
        assertTrue(logged.contains("maximum context length is 1048576"), logged)
        assertTrue(logged.contains("1430334"), logged)
    }

    /**
     * Пустой ответ модели — не успех: клиент получает исключение с причиной
     * и подсказкой, а не пустую строку (в чате из неё выходил пузырь без текста).
     */
    @Test
    fun agentRefusesEmptyReplyAndExplainsReasoningBudget() = runBlocking {
        val logs = mutableListOf<String>()
        val llm = FakeLlmClient(
            response("", promptTokens = 63, completionTokens = 600, reasoningTokens = 600, finishReason = "length")
        )

        val failure = assertFailsWith<EmptyReplyException> {
            testAgent(llm, logs).run("Почему небо синее?", AgentOptions(maxTokens = 600))
        }

        assertEquals(600, failure.reasoningTokens)
        assertEquals(600, failure.completionTokens)
        assertEquals("length", failure.finishReason)
        assertTrue(failure.message!!.contains("Увеличьте max_tokens"), failure.message!!)
        assertTrue(logs.any { it.startsWith("Ответ ←") && it.contains("рассуждения: 600") }, logs.toString())
        assertTrue(logs.any { it == "Текст ответа: пусто — бюджет ответа израсходован на рассуждения" }, logs.toString())
    }
}
