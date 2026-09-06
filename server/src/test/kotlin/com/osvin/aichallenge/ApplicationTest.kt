package com.osvin.aichallenge

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
}
