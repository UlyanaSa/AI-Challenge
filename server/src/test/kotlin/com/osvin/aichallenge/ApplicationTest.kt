package com.osvin.aichallenge

import com.osvin.aichallenge.agent.ContextStrategy
import com.osvin.aichallenge.agent.MemoryLayer
import com.osvin.aichallenge.agent.MemoryLayers
import com.osvin.aichallenge.agent.MemoryWriter
import com.osvin.aichallenge.agent.StoredSummary
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.ChatRequest
import com.osvin.aichallenge.models.DialogBranch
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ApplicationTest {

    /** Разбор ответов памяти: проверяется структура снимка, а не текст ответа. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Снимок памяти из ответа маршрута: сессии у памяти нет, поэтому и параметров нет. */
    private suspend fun memorySnapshot(client: HttpClient): MemoryLayers =
        json.decodeFromString(client.get("/v1/memory").bodyAsText())

    /** Явная запись через маршрут: тело — тип и текст, сессии у памяти нет. */
    private suspend fun HttpClient.remember(layer: String, value: String) {
        val response = post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"$layer","value":"$value"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, "запись не принята: ${response.bodyAsText()}")
    }

    /** Забывание записи через маршрут: тип и текст уходят параметрами запроса. */
    private suspend fun HttpClient.forget(layer: String, value: String) {
        val response = delete("/v1/memory") {
            url {
                parameters.append("layer", layer)
                parameters.append("value", value)
            }
        }
        assertEquals(HttpStatusCode.OK, response.status, "запись не забыта: ${response.bodyAsText()}")
    }

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
     * Удаление чата забывает на сервере только его сессию: сводка истории удалённого
     * диалога исчезает, сводка соседнего диалога остаётся, а память профиля не трогается.
     * Рабочую память задачи чат не адресует: она общая для всех чатов, поэтому стирать
     * её вместе с одним диалогом значило бы уносить данные остальных. Свои записи тест
     * убирает тем же маршрутом — долговременная память переживает прогоны.
     * Тест не зависит от сети и ключа API.
     */
    @Test
    fun testDeleteChatForgetsSessionSummaryOnly() = testApplication {
        application {
            module()
        }
        val sessionId = "a".repeat(32)
        val otherSessionId = "b".repeat(32)
        summaryStore.put(sessionId, StoredSummary("сводка удаляемого диалога", 10, 5))
        summaryStore.put(otherSessionId, StoredSummary("сводка соседнего диалога", 12, 6))
        val goal = "цель-теста — удаление чата"
        val decision = "хранилище-теста — Room"
        client.remember("working", goal)
        client.remember("long_term", decision)

        val response = client.delete("/v1/chats/$sessionId")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(summaryStore.get(sessionId), "Сводка удалённой сессии должна быть забыта")
        assertEquals("сводка соседнего диалога", summaryStore.get(otherSessionId)?.text)
        val snapshot = memorySnapshot(client)
        assertTrue(
            snapshot.working.any { it.value == goal },
            "Рабочая память принадлежит профилю: удаление чата её не забывает: ${snapshot.working}"
        )
        assertTrue(
            snapshot.longTerm.any { it.value == decision },
            "Долговременная память профиля остаётся на месте: ${snapshot.longTerm}"
        )

        client.forget("working", goal)
        client.forget("long_term", decision)
    }

    /**
     * Явная запись через маршрут: тип памяти называет клиент, и это видно в снимке.
     * Сессии в записи нет: оба писаемых типа живут по профилю, поэтому запись рабочей
     * памяти видна в снимке, который читается без всяких параметров, — из любого чата.
     * Записи теста убираются тем же маршрутом — долговременная память переживает
     * прогоны, и тестовые данные в ней оставаться не должны.
     */
    @Test
    fun testExplicitMemoryWriteLandsInNamedType() = testApplication {
        application {
            module()
        }
        val decision = "хранилище-теста — Room"
        val goal = "цель-теста — собрать ТЗ"

        val longTermWrite = client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"long_term","value":"$decision"}""")
        }
        assertEquals(HttpStatusCode.OK, longTermWrite.status)
        val afterDecision = json.decodeFromString<MemoryLayers>(longTermWrite.bodyAsText())
        assertTrue(
            afterDecision.longTerm.any { it.value == decision },
            "запись уходит в названный тип памяти: ${afterDecision.longTerm}"
        )
        assertTrue(afterDecision.working.none { it.value == decision }, "в рабочей памяти её нет")

        val workingWrite = client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"working","value":"$goal"}""")
        }
        assertEquals(
            HttpStatusCode.OK,
            workingWrite.status,
            "в рабочую память пишут без сессии: она общая для профиля, а не для чата"
        )
        assertTrue(
            json.decodeFromString<MemoryLayers>(workingWrite.bodyAsText()).working.any { it.value == goal },
            "ответ на запись — тот же снимок, и запись в нём: ${workingWrite.bodyAsText()}"
        )

        val snapshot = memorySnapshot(client)
        assertTrue(
            snapshot.working.any { it.value == goal },
            "запись рабочей памяти видна из любого чата: запрос её не адресует: ${snapshot.working}"
        )
        assertTrue(snapshot.longTerm.any { it.value == decision }, "снимок отдаёт оба типа сразу")
        val body = client.get("/v1/memory").bodyAsText()
        assertTrue(
            body.contains("\"long_term\"") && body.contains("\"types\""),
            "поля снимка на проводе — snake_case, как их ждёт клиент: $body"
        )
        assertEquals(
            listOf("краткосрочная", "рабочая", "долговременная"),
            snapshot.types.map { it.title },
            "каталог типов уезжает вместе со снимком: по нему рисуется выбор в интерфейсе"
        )
        assertEquals(
            listOf("short_term", "working", "long_term"),
            snapshot.types.map { it.layer }
        )
        assertEquals(
            listOf(false, true, true),
            snapshot.types.map { it.writable },
            "в краткосрочную память писать нечего: это сообщения диалога"
        )

        client.forget("working", goal)
        client.forget("long_term", decision)
    }

    /** Отказ маршрута — ошибка запроса с причиной: память при этом остаётся прежней. */
    @Test
    fun testExplicitMemoryWriteRejectsForeignTypeAndBlankText() = testApplication {
        application {
            module()
        }

        val unknownType = client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"настроение","value":"боевой"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknownType.status)
        assertTrue(unknownType.bodyAsText().contains(MemoryWriter.UNKNOWN_LAYER))

        val shortTerm = client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"short_term","value":"пользователь поздоровался"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, shortTerm.status)
        assertTrue(shortTerm.bodyAsText().contains(MemoryWriter.NOT_WRITABLE))

        val blank = client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"working","value":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains(MemoryWriter.EMPTY_VALUE))

        val snapshot = memorySnapshot(client)
        assertTrue(
            snapshot.working.none { it.value.isBlank() } && snapshot.longTerm.none { it.value == "боевой" },
            "отклонённые записи в память не попали: $snapshot"
        )
    }

    /** Забывание: убирает одну запись, повторяется без ошибок, чужой тип — отказ. */
    @Test
    fun testForgetRemovesRecordAndRepeatsQuietly() = testApplication {
        application {
            module()
        }
        val decision = "хранилище-забыть — Room"
        client.post("/v1/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"layer":"long_term","value":"$decision"}""")
        }

        val forget = client.delete("/v1/memory") {
            url {
                parameters.append("layer", "long_term")
                parameters.append("value", decision)
            }
        }
        assertEquals(HttpStatusCode.OK, forget.status)
        assertTrue(
            json.decodeFromString<MemoryLayers>(forget.bodyAsText()).longTerm.none { it.value == decision },
            "запись забыта: ${forget.bodyAsText()}"
        )

        val repeated = client.delete("/v1/memory") {
            url {
                parameters.append("layer", "long_term")
                parameters.append("value", decision)
            }
        }
        assertEquals(HttpStatusCode.OK, repeated.status, "повторное удаление — не ошибка")

        val unknownType = client.delete("/v1/memory") {
            url {
                parameters.append("layer", "настроение")
                parameters.append("value", decision)
            }
        }
        assertEquals(HttpStatusCode.BadRequest, unknownType.status)
        assertTrue(unknownType.bodyAsText().contains(MemoryWriter.UNKNOWN_LAYER))

        val shortTerm = client.delete("/v1/memory") {
            url {
                parameters.append("layer", "short_term")
                parameters.append("value", "пользователь поздоровался")
            }
        }
        assertEquals(HttpStatusCode.BadRequest, shortTerm.status)
        assertTrue(shortTerm.bodyAsText().contains(MemoryWriter.NOT_WRITABLE))
    }

    /**
     * Запрос клиента отображается в настройки агента целиком: стратегия, окно,
     * активная ветка и её структура. Клиент, который ничего не прислал, получает
     * сжатие истории — поведение дня 9, а значение дня 10 «facts» читается как
     * «память агента», чтобы старое приложение не переключилось молча на сводку.
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

        assertEquals(
            ContextStrategy.MEMORY,
            ChatRequest(message = "Привет", strategy = "facts").toAgentOptions().strategy,
            "стратегия дня 10 читается как память агента"
        )
        assertEquals(
            setOf(MemoryLayer.WORKING, MemoryLayer.LONG_TERM),
            ChatRequest(message = "Привет", strategy = "memory").toAgentOptions().strategy.memory,
            "стратегия памяти ведёт рабочую и долговременную память"
        )
    }
}
