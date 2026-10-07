package com.osvin.aichallenge.indexing.ollama

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Провайдер Ollama: каким запросом он спрашивает вектор, откуда берёт размерность и что говорит,
 * когда демон отвечает ошибкой или не отвечает вовсе.
 *
 * Проверяется на подставном сервере, а не на живом демоне: у теста не должно быть внешних
 * условий — ни запущенной Ollama, ни скачанной модели. Живой прогон для этого есть отдельно
 * (страница дня), а здесь закреплён протокол: тело запроса, разбор ответа и текст ошибки.
 */
class OllamaEmbeddingProviderTest {

    @Test
    fun `размерность берётся у модели, а запрос несёт модель и текст`() = runBlocking {
        val server = stub { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val json = Json.parseToJsonElement(body).jsonObject
            lastModel = json["model"]?.jsonPrimitive?.content
            lastInput = json["input"]?.jsonPrimitive?.content
            exchange.respond("""{"embeddings":[[0.1,0.2,0.3]]}""")
        }
        try {
            val provider = OllamaEmbeddingProvider.connect(baseUrl(server), "test-model")

            assertEquals(3, provider.dimension, "размерность должна прийти от модели, а не из константы")
            assertEquals(listOf(0.1f, 0.2f, 0.3f), provider.embed("принцип подстановки"))
            assertEquals("test-model", lastModel, "в запросе должно быть имя модели")
            assertEquals("принцип подстановки", lastInput, "в запросе должен быть текст, а не его обрезка")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ошибка демона доходит до вызывающего вместе с подсказкой`() = runBlocking {
        val server = stub { exchange ->
            exchange.respond("""{"error":"model 'bge-m3' not found, try pulling it first"}""", status = 404)
        }
        try {
            val failure = assertFailsWith<OllamaException> {
                OllamaEmbeddingProvider.connect(baseUrl(server), "bge-m3", Duration.ofSeconds(5))
            }

            assertTrue(
                failure.message!!.contains("not found"),
                "в сообщении должна быть причина от демона: ${failure.message}"
            )
            assertTrue(
                failure.message!!.contains("bge-m3"),
                "и имя модели, о которой речь: ${failure.message}"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `закрытый порт — это недоступный демон, а не молчаливый пустой вектор`() = runBlocking {
        val server = stub { it.respond("""{"embeddings":[[0.1]]}""") }
        val url = baseUrl(server)
        server.stop(0)

        assertFalse(OllamaEmbeddingProvider.available(url), "закрытый порт — демона нет")
        val failure = assertFailsWith<OllamaException> {
            OllamaEmbeddingProvider.connect(url, "bge-m3", Duration.ofSeconds(5))
        }
        assertTrue(
            failure.message!!.contains(url),
            "в сообщении должен быть адрес, по которому не ответили: ${failure.message}"
        )
    }

    // Читаются из потока подставного сервера, проверяются в потоке теста.
    @Volatile private var lastModel: String? = null
    @Volatile private var lastInput: String? = null

    private fun baseUrl(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    /** Подставной демон: один маршрут `/api/embed` и ответ, который задаёт сам тест. */
    private fun stub(handler: (StubExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/embed") { exchange -> handler(StubExchange(exchange)) }
        server.createContext("/api/version") { exchange -> StubExchange(exchange).respond("""{"version":"0.0.0"}""") }
        server.executor = null
        server.start()
        return server
    }

    private inner class StubExchange(private val exchange: HttpExchange) {

        val requestBody get() = exchange.requestBody

        fun respond(body: String, status: Int = 200) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
