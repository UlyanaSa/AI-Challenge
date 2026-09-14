package com.osvin.aichallenge

import com.osvin.aichallenge.agent.StoredSummary
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
}
