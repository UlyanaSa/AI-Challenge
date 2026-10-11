package com.osvin.aichallenge.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Выбор провайдера по конфигурации: адрес, модель и срок берутся из окружения,
 * а транспорт собирается без сети.
 *
 * Проверка не зависит от машины: карта переменных передаётся в [LlmProviderConfig.fromEnv]
 * явно, поэтому тест не читает ни настоящий `System.getenv()`, ни `.env` репозитория.
 */
class LlmProviderTest {

    @Test
    fun `без настройки провайдер облачный, как до этого дня`() {
        val config = LlmProviderConfig.fromEnv(emptyMap())

        assertEquals(LlmProvider.CLOUD, config.provider)
        assertEquals(LocalLlmClient.DEFAULT_URL, config.ollamaUrl)
        assertEquals(LocalLlmClient.DEFAULT_MODEL, config.ollamaModel)
        assertEquals(LocalLlmClient.DEFAULT_TIMEOUT_MILLIS, config.ollamaTimeoutMillis)
        // Облачный режим модель задаёт клиентом, поэтому транспорт её не навязывает.
        assertNull(config.modelOverride)
    }

    @Test
    fun `локальный режим читает адрес, модель и срок из окружения`() {
        val config = LlmProviderConfig.fromEnv(
            mapOf(
                LlmProviderConfig.ENV_PROVIDER to "LOCAL",
                LlmProviderConfig.ENV_OLLAMA_URL to "http://192.168.1.10:11434",
                LlmProviderConfig.ENV_OLLAMA_MODEL to "qwen3:1.7b",
                LlmProviderConfig.ENV_OLLAMA_TIMEOUT_MS to "45000"
            )
        )

        assertEquals(LlmProvider.LOCAL, config.provider)
        assertEquals("http://192.168.1.10:11434", config.ollamaUrl)
        assertEquals("qwen3:1.7b", config.ollamaModel)
        assertEquals(45_000L, config.ollamaTimeoutMillis)
        assertEquals("qwen3:1.7b", config.modelOverride)
    }

    @Test
    fun `незнакомое значение провайдера не включает локальный режим`() {
        // Незнакомая строка не должна молча уводить генерацию на другую машину.
        assertEquals(LlmProvider.CLOUD, LlmProviderConfig.fromEnv(mapOf(LlmProviderConfig.ENV_PROVIDER to "openai")).provider)
    }

    @Test
    fun `негодный срок отбрасывается в пользу умолчания`() {
        val config = LlmProviderConfig.fromEnv(
            mapOf(
                LlmProviderConfig.ENV_PROVIDER to "local",
                LlmProviderConfig.ENV_OLLAMA_TIMEOUT_MS to "0"
            )
        )

        assertEquals(LocalLlmClient.DEFAULT_TIMEOUT_MILLIS, config.ollamaTimeoutMillis)
    }

    @Test
    fun `локальный транспорт собирается без ключа облака`() {
        val factory = LlmClientFactory(
            LlmProviderConfig(provider = LlmProvider.LOCAL),
            HttpClient(MockEngine { respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
            AgentLogger.Silent
        )

        // Ключа нет, и это не ошибка: локальному провайдеру он не нужен.
        assertIs<LocalLlmClient>(factory.create(apiKey = null))
    }

    @Test
    fun `облачный транспорт без ключа отказывает, а не идёт в api`() {
        val factory = LlmClientFactory(
            LlmProviderConfig(provider = LlmProvider.CLOUD),
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            AgentLogger.Silent
        )

        val error = assertFailsWith<IllegalStateException> { factory.create(apiKey = null) }
        assertTrue(error.message!!.contains(LlmProviderConfig.ENV_PROVIDER), error.message!!)
    }

    @Test
    fun `локальная модель имеет своё окно и не имеет тарифа`() {
        val spec = ModelCatalog.spec(LocalLlmClient.DEFAULT_MODEL)

        assertEquals(40_960, spec.contextWindow)
        assertEquals(8_192, spec.maxOutputTokens)
        // Тариф не опубликован: цена не начисляется, и отчёт не показывает выдуманную.
        assertNull(spec.cost(promptTokens = 1_000, replyTokens = 500))
    }
}
