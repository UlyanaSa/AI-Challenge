package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живая проверка транспорта: настоящий демон Ollama и настоящая модель.
 *
 * Прогон включается явно — `./gradlew :agent:test -Pollama.live=1` (или `OLLAMA_LIVE=1`).
 * Без флага тест ничего не проверяет и печатает, как его запустить: он требует не только
 * демона, но и загруженной модели, а в обычной сборке ни того, ни другого может не быть.
 * Здесь проверяется то, чего не видят тесты на подставном движке: что формат запроса
 * принят настоящим Ollama и что ответ настоящей модели доходит до внутренней модели
 * приложения — вместе с числами токенов.
 */
class LocalLlmClientLiveTest {

    /** Живой режим включается явно: по умолчанию демона может не быть. */
    private val live = System.getProperty("ollama.live") == "1" || System.getenv("OLLAMA_LIVE") == "1"

    /** Транспорт к настоящему демону: тот же стек, что у сервера. */
    private fun client(): LocalLlmClient {
        val http = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = LocalLlmClient.DEFAULT_TIMEOUT_MILLIS
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = LocalLlmClient.DEFAULT_TIMEOUT_MILLIS
            }
        }
        return LocalLlmClient(http = http)
    }

    @Test
    fun `живой демон отвечает на простой вопрос`() {
        if (!live) {
            println("[agent] LocalLlmClientLiveTest пропущен: запустите с -Pollama.live=1 и поднятым Ollama")
            return
        }

        val response = runBlocking {
            client().complete(
                DeepSeekRequest(
                    model = LocalLlmClient.DEFAULT_MODEL,
                    messages = listOf(
                        ChatMessage("system", "Отвечай одним словом."),
                        ChatMessage("user", "Какая планета Солнечной системы самая большая? Ответь одним словом.")
                    ),
                    maxTokens = 64,
                    temperature = 0.0
                )
            )
        }

        val reply = response.choices.single().message.content
        println("[agent] живой ответ ${LocalLlmClient.DEFAULT_MODEL}: $reply")
        assertTrue(reply.contains("Юпитер", ignoreCase = true), "неожиданный ответ: $reply")
        assertTrue((response.usage?.completionTokens ?: 0) > 0, "демон не назвал расход токенов")
    }
}
