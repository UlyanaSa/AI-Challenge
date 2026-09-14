package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Диалог заданной длины: пары «вопрос — ответ» с узнаваемым текстом. */
private fun dialog(messages: Int): List<ChatMessage> = (1..messages).map { index ->
    ChatMessage(if (index % 2 == 1) "user" else "assistant", "сообщение $index")
}

/** Компрессор из условия дня: последние 10 сообщений как есть, сводка — по 10 старых. */
private val compressor = HistoryCompressor()

/** Подменный транспорт: отдаёт заготовленные ответы по порядку вызовов. */
private class ScriptedClient(private val answers: List<String>) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", answers[minOf(requests.size - 1, answers.lastIndex)]), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = 100,
                completionTokens = 50,
                totalTokens = 150,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(0)
            )
        )
    }
}

/** Транспорт, у которого служебный вызов сводки падает: диалог от этого страдать не должен. */
private class FailingSummaryClient : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        if (requests.size == 1) throw LlmApiException(500, "DeepSeek API error: 500 - сводка недоступна")
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", "ответ"), "stop")),
            usage = DeepSeekResponse.Usage(10, 20, 30, DeepSeekResponse.Usage.CompletionTokensDetails(0))
        )
    }
}

class HistoryCompressorTest {

    /** Пока старых сообщений меньше шага, история уходит целиком — ничего не теряется. */
    @Test
    fun shortDialogGoesAsIs() {
        val plan = compressor.plan(dialog(12), null)
        assertEquals(12, plan.recent.size)
        assertTrue(plan.toFold.isEmpty())
        assertNull(plan.summary)
    }

    /** Накопился шаг старых сообщений — они уходят в сводку, последние остаются как есть. */
    @Test
    fun olderMessagesFoldWhenStepAccumulated() {
        val plan = compressor.plan(dialog(25), null)
        assertEquals(15, plan.toFold.size)
        assertEquals("сообщение 1", plan.toFold.first().content)
        assertEquals("сообщение 16", plan.recent.first().content)
        assertEquals(10, plan.recent.size)
    }

    /** Уже свёрнутые сообщения в запрос не попадают: их заменяет сводка. */
    @Test
    fun foldedMessagesAreReplacedBySummary() {
        val stored = StoredSummary(text = "бюджет 5 000 рублей", foldedMessages = 15, tokens = 20)
        val plan = compressor.plan(dialog(25), stored)
        assertEquals(10, plan.recent.size)
        assertEquals("сообщение 16", plan.recent.first().content)
        assertTrue(plan.toFold.isEmpty())
        assertEquals(stored, plan.summary)
    }

    /** Диалог начали заново: сводка от прошлого разговора не подставляется. */
    @Test
    fun summaryIsDroppedWhenDialogRestarts() {
        val stored = StoredSummary(text = "прошлый диалог", foldedMessages = 15, tokens = 20)
        val plan = compressor.plan(dialog(3), stored)
        assertNull(plan.summary)
        assertEquals(3, plan.recent.size)
    }

    /** Служебный запрос несёт прежнюю сводку и новые сообщения, а не всю историю. */
    @Test
    fun summaryRequestCarriesPreviousSummaryAndNewMessages() {
        val previous = StoredSummary("бюджет 5 000 рублей", 15, 20)
        val request = compressor.summaryRequest(DEMO_MODEL, previous, dialog(2))
        assertEquals(HistoryCompressor.SUMMARY_MAX_TOKENS, request.maxTokens)
        val text = request.messages.last().content
        assertTrue(text.contains("бюджет 5 000 рублей"))
        assertTrue(text.contains("сообщение 1") && text.contains("сообщение 2"))
    }

    /** Сводка подставляется служебным сообщением с ролью system. */
    @Test
    fun summaryIsSentAsSystemMessage() {
        val message = compressor.summaryMessage(StoredSummary("бюджет 5 000 рублей", 15, 20))
        assertEquals("system", message.role)
        assertTrue(message.content.contains("бюджет 5 000 рублей"))
    }

    /** Хранилище держит сводки по сессиям и умеет их забывать. */
    @Test
    fun storeKeepsSummariesPerSession() {
        val store = InMemorySummaryStore()
        store.put("a", StoredSummary("сводка a", 10, 5))
        store.put("b", StoredSummary("сводка b", 12, 6))
        assertEquals("сводка a", store.get("a")?.text)
        assertEquals("сводка b", store.get("b")?.text)
        store.clear("a")
        assertNull(store.get("a"))
        assertEquals("сводка b", store.get("b")?.text)
    }

    /** Со сжатием в модель уходят сводка и последние сообщения, а свёрнутые — нет. */
    @Test
    fun agentSendsSummaryInsteadOfFoldedMessages() = runBlocking {
        val client = ScriptedClient(listOf("бюджет 5 000 рублей", "ответ модели"))
        val result = LlmAgent(client).run(
            "Какой бюджет я называл?",
            AgentOptions(history = dialog(25), sessionId = "session-1", strategy = ContextStrategy.SUMMARY)
        )

        val sent = client.requests.last().messages
        assertEquals(13, sent.size, "system prompt + сводка + последние 10 сообщений + вопрос")
        assertEquals(
            ChatMessage("system", "сообщение 1"), sent.first(),
            "свой system prompt не задан, поэтому его роль играет первое сообщение диалога"
        )
        assertTrue(sent[1].content.startsWith("Сводка предыдущего диалога"))
        // Свёрнутые сообщения в запрос не уходят — кроме первого: оно несёт system prompt
        val folded = (2..15).map { "сообщение $it" }
        assertTrue(sent.none { it.content in folded }, "свёрнутые сообщения не должны уходить в модель: $sent")
        assertEquals(15, result.tokens.foldedMessages)
        assertTrue(result.tokens.historyRawTokens > result.tokens.history)
        assertEquals(150, result.tokens.compressionTokens)
    }

    /** Сбой служебного вызова не теряет диалог: уходит полная история, сжатия нет. */
    @Test
    fun agentFallsBackToFullHistoryWhenSummaryFails() = runBlocking {
        val client = FailingSummaryClient()
        val result = LlmAgent(client).run(
            "Какой бюджет я называл?",
            AgentOptions(history = dialog(25), sessionId = "session-1", strategy = ContextStrategy.SUMMARY)
        )

        val sent = client.requests.last().messages
        assertEquals(27, sent.size, "25 сообщений истории + system prompt из первого + вопрос")
        assertTrue(sent.any { it.content == "сообщение 1" })
        assertEquals(0, result.tokens.foldedMessages)
        assertEquals(result.tokens.historyRawTokens, result.tokens.history)
    }

    /** Без сессии сжатия нет: агенту негде хранить сводку между запросами. */
    @Test
    fun agentWithoutSessionGoesUncompressed() = runBlocking {
        val client = ScriptedClient(listOf("ответ модели"))
        val result = LlmAgent(client).run(
            "Какой бюджет я называл?",
            AgentOptions(history = dialog(25), strategy = ContextStrategy.SUMMARY)
        )

        assertEquals(1, client.requests.size)
        assertEquals(27, client.requests.first().messages.size)
        assertEquals(0, result.tokens.compressionTokens)
        assertEquals(result.tokens.historyRawTokens, result.tokens.history)
    }
}
