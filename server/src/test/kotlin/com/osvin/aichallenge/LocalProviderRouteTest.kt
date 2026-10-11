package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.LlmClientFactory
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.agent.LlmProviderConfig
import com.osvin.aichallenge.agent.LocalLlmClient
import com.osvin.aichallenge.models.OllamaTags
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.net.ConnectException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Локальный провайдер на маршруте чата и на проверке здоровья.
 *
 * Ollama подставной: движок отвечает заготовками, поэтому ни один тест не требует
 * ни демона, ни ключа облака, ни сети. Проверяется связка целиком — маршрут выбирает
 * транспорт по конфигурации, агент собирает запрос, локальный транспорт уходит на
 * `/api/chat` со своей моделью, а ответ возвращается клиенту с именем модели и
 * признаком провайдера.
 */
class LocalProviderRouteTest {

    /** Разбор ответов сервера: сравниваются поля, а не текст. */
    private val json = Json { ignoreUnknownKeys = true }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Тела запросов к `/api/chat`: по ним видно, что именно ушло локальной модели. */
    private val chatRequests = mutableListOf<JsonObject>()

    /** Модели, к которым обращался `/api/chat`: по ним видно, чьё имя ушло демону. */
    private val askedModels = mutableListOf<String>()

    /**
     * Подставной демон Ollama: отвечает на чат заготовкой, на список моделей — заданным
     * набором. Запросы к другим адресам не ожидаются, и отказ на них заметен сразу.
     */
    private fun ollama(reply: String, models: List<String> = listOf(LocalLlmClient.DEFAULT_MODEL)): HttpClient =
        HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/tags" -> respond(
                        content = json.encodeToString(OllamaTags(models.map { OllamaTags.OllamaTag(it, it) })),
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders
                    )

                    "/api/chat" -> {
                        val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        chatRequests += body
                        askedModels += body["model"]!!.jsonPrimitive.content
                        respond(
                            content = """
                                {"model":"qwen3:1.7b","done":true,"done_reason":"stop",
                                 "message":{"role":"assistant","content":"$reply"},
                                 "prompt_eval_count":12,"eval_count":5}
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders
                        )
                    }

                    else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                }
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        }

    private fun factory(http: HttpClient) = LlmClientFactory(
        LlmProviderConfig(
            provider = LlmProvider.LOCAL,
            ollamaUrl = "http://127.0.0.1:11434",
            ollamaModel = LocalLlmClient.DEFAULT_MODEL
        ),
        http,
        AgentLogger.Silent
    )

    @Test
    fun `чат в локальном режиме отвечает без ключа облака и называет свою модель`() = testApplication {
        application { module(llmClients = factory(ollama(reply = "Юпитер"))) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            // Клиент просит облачную модель: сервер обязан подставить модель Ollama.
            setBody("""{"message":"Какая планета самая большая? Ответь одним словом","model":"deepseek-v4-flash"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Юпитер", body["reply"]?.jsonPrimitive?.content)
        assertEquals("qwen3:1.7b", body["model"]?.jsonPrimitive?.content)
        assertEquals("local", body["provider"]?.jsonPrimitive?.content)
        assertTrue(askedModels.isNotEmpty(), "запрос к Ollama не ушёл")
        assertTrue(
            askedModels.all { it == LocalLlmClient.DEFAULT_MODEL },
            "демону ушло чужое имя модели: $askedModels"
        )
        // Локальному диалогу не уходят ни профиль разработчика, ни правила проекта: правила
        // маленькая модель принимает за задание и отвечает по ним, а не по вопросу.
        // Своей системной инструкции сервер тоже не подставляет — настройки контекста
        // у чата нет, и системным промтом становится первое сообщение диалога (правило
        // агента): оно же остаётся в истории обычным сообщением пользователя.
        val request = chatRequests.single()
        val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, messages.count { it["role"]?.jsonPrimitive?.content == "system" }, "$request")
        assertEquals(
            "user",
            messages.last()["role"]?.jsonPrimitive?.content,
            "вопрос должен быть последним сообщением: $request"
        )
        assertEquals(
            messages.first()["content"]?.jsonPrimitive?.content,
            messages.last()["content"]?.jsonPrimitive?.content,
            "роль системной инструкции играет первое сообщение диалога: $request"
        )
        // Рассуждение включено, температура транспорта — 0: с ней ответ воспроизводится
        // от запуска к запуску, а с температурой клиента (0.7) плывёт написание слов.
        assertEquals(true, request["think"]?.jsonPrimitive?.boolean, "$request")
        assertEquals(
            0.0,
            request["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.double,
            "$request"
        )
    }

    @Test
    fun `недоступный демон даёт 503 с причиной, а не пустой ответ`() = testApplication {
        val broken = HttpClient(MockEngine { throw ConnectException("Connection refused") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        }
        application { module(llmClients = factory(broken)) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Привет!"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("Ollama"), body.toString())
    }

    @Test
    fun `здоровье локального режима подтверждает загруженную модель`() = testApplication {
        application { module(llmClients = factory(ollama(reply = "ок"))) }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("local", body["provider"]?.jsonPrimitive?.content)
        assertEquals(LocalLlmClient.DEFAULT_MODEL, body["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `здоровье без модели называет действие, а не просто отказ`() = testApplication {
        application { module(llmClients = factory(ollama(reply = "ок", models = listOf("bge-m3:latest")))) }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("ollama pull"), body.toString())
    }
}
