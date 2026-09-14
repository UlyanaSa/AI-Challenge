package com.osvin.aichallenge

import com.osvin.aichallenge.agent.ContextStrategy
import com.osvin.aichallenge.agent.StoredSummary
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.ChatRequest
import com.osvin.aichallenge.models.DialogBranch
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    /**
     * Эндпоинт здоровья всегда отвечает валидным HealthResponse, а статус зависит
     * от окружения: 200 — ключ задан и API доступен, 500 — ключ не задан,
     * 503 — ключ задан, но внешний API недоступен. Тест не зависит от сети.
     */
    @Test
    fun testHealthRouteResponds() = testApplication {
        application {
            module()
        }
        val response = client.get("/v1/health")
        assertTrue(
            response.status in setOf(
                HttpStatusCode.OK,
                HttpStatusCode.InternalServerError,
                HttpStatusCode.ServiceUnavailable
            ),
            "Unexpected status: ${response.status}"
        )
        assertTrue(response.bodyAsText().contains("\"status\""))
    }

    /**
     * Удаление чата на клиенте забывает и сессию агента на сервере: сводка истории
     * удалённой сессии исчезает, а сводка соседнего диалога остаётся. Тест не зависит
     * от сети и ключа API.
     */
    @Test
    fun testDeleteChatSessionClearsSummary() = testApplication {
        application {
            module()
        }
        val sessionId = "a".repeat(32)
        val otherSessionId = "b".repeat(32)
        summaryStore.put(sessionId, StoredSummary("сводка удаляемого диалога", 10, 5))
        summaryStore.put(otherSessionId, StoredSummary("сводка соседнего диалога", 12, 6))

        val response = client.delete("/v1/chats/$sessionId")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(summaryStore.get(sessionId), "Сводка удалённой сессии должна быть забыта")
        assertEquals("сводка соседнего диалога", summaryStore.get(otherSessionId)?.text)
    }

    /**
     * Запрос клиента отображается в настройки агента целиком: стратегия, окно,
     * активная ветка и её структура. Клиент, который ничего не прислал, получает
     * сжатие истории — поведение дня 9.
     */
    @Test
    fun testChatRequestMapsStrategyAndBranches() {
        val request = ChatRequest(
            message = "Что дальше?",
            sessionId = "session",
            strategy = "branches",
            windowMessages = 6,
            branchId = "a",
            branches = listOf(DialogBranch("a", forkedAfter = 4)),
            history = listOf(ChatMessage("user", "первое"))
        )

        val options = request.toAgentOptions()
        assertEquals(ContextStrategy.BRANCHES, options.strategy)
        assertEquals(6, options.windowMessages)
        assertEquals("a", options.activeBranchId)
        assertEquals(listOf(DialogBranch("a", forkedAfter = 4)), options.branches)
        assertEquals(1, options.history.size)

        val defaulted = ChatRequest(message = "Привет").toAgentOptions()
        assertEquals(ContextStrategy.SUMMARY, defaulted.strategy, "без стратегии клиент получает сжатие истории")
        assertTrue(defaulted.branches.isEmpty())
        assertNull(defaulted.activeBranchId)
    }
}
