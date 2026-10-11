package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.ResponseFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.net.ConnectException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Транспорт к Ollama: преобразование запроса и ответа проверяется без демона.
 *
 * Подставной HTTP-движок отвечает заготовками, поэтому ни один тест не требует
 * запущенного Ollama и загруженной модели. Проверяется провод, а не сеть: что именно
 * уходит демону, как ответ ложится во внутреннюю модель приложения и во что
 * превращается каждый вид отказа.
 */
class LocalLlmClientTest {

    /** Разбор тела запроса и ответов движка: сравнение по полям, а не по тексту. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Клиент с подставным движком: JSON-режим и срок — те же, что у сервера. */
    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }

    /** Заголовки ответа демона: JSON, как у настоящего Ollama. */
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Тело запроса, которое транспорт отправил демону: движок ловит его и отвечает заготовкой. */
    private fun DeepSeekRequest.ollamaBody(model: String = "qwen3:1.7b"): JsonObject {
        val request = this
        lateinit var body: JsonObject
        val engine = MockEngine { sent ->
            body = json.parseToJsonElement((sent.body as TextContent).text).jsonObject
            respond(
                content = """{"message":{"role":"assistant","content":"ок"},"done_reason":"stop"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders
            )
        }
        runBlocking { LocalLlmClient(model, client(engine), logger = AgentLogger.Silent).complete(request) }
        return body
    }

    @Test
    fun `запрос уходит в api chat с моделью транспорта и с рассуждением`() {
        lateinit var url: String
        lateinit var sent: JsonObject
        val engine = MockEngine { request ->
            url = request.url.toString()
            sent = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(
                content = """{"message":{"role":"assistant","content":"Юпитер"},"done_reason":"stop"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders
            )
        }
        val request = DeepSeekRequest(
            model = "deepseek-v4-flash",
            messages = listOf(
                ChatMessage("system", "Ты ассистент"),
                ChatMessage("user", "Какая планета самая большая?")
            ),
            maxTokens = 256,
            temperature = 0.3,
            stop = listOf("\n\n")
        )

        val response = runBlocking {
            LocalLlmClient("qwen3:1.7b", client(engine), baseUrl = "http://127.0.0.1:11434", logger = AgentLogger.Silent)
                .complete(request)
        }

        assertEquals("http://127.0.0.1:11434/api/chat", url)
        // Модель транспорта, а не присланная облачная: демону имя облачной модели ничего не говорит.
        assertEquals("qwen3:1.7b", sent["model"]?.jsonPrimitive?.content)
        // Рассуждение названо явно: у thinking-модели умолчание зависит от модели,
        // а без рассуждения qwen3:1.7b не решает задачу, где нужен перебор.
        assertEquals(true, sent["think"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, sent["stream"]?.jsonPrimitive?.content?.toBoolean())
        val messages = sent["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        assertEquals("Ты ассистент", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        val options = sent["options"]!!.jsonObject
        assertEquals("256", options["num_predict"]?.jsonPrimitive?.content)
        // Температура транспорта, а не запроса: маленькая модель при 0.7 отвечает по-разному
        // от запуска к запуску, поэтому по умолчанию ответ детерминированный.
        assertEquals(0.0, options["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("Юпитер", response.choices.first().message.content)
    }

    @Test
    fun `рассуждение и температуру задаёт конфигурация транспорта`() {
        lateinit var sent: JsonObject
        val engine = MockEngine { request ->
            sent = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(
                content = """{"message":{"role":"assistant","content":"ок"},"done_reason":"stop"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders
            )
        }
        val request = DeepSeekRequest(
            model = "deepseek-v4-flash",
            messages = listOf(ChatMessage("user", "Привет")),
            temperature = 0.3
        )

        runBlocking {
            LocalLlmClient(
                model = "qwen3:1.7b",
                http = client(engine),
                logger = AgentLogger.Silent,
                think = false,
                temperature = 0.9
            ).complete(request)
        }

        assertEquals(false, sent["think"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(0.9, sent["options"]!!.jsonObject["temperature"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `ответ Ollama ложится во внутреннюю модель с расходом токенов`() {
        val engine = MockEngine {
            respond(
                content = """
                    {"model":"qwen3:1.7b","done":true,"done_reason":"stop",
                     "message":{"role":"assistant","content":"1080"},
                     "prompt_eval_count":42,"eval_count":17}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val response = runBlocking {
            LocalLlmClient("qwen3:1.7b", client(engine), logger = AgentLogger.Silent)
                .complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "1200*1.2*0.75?"))))
        }

        val choice = response.choices.single()
        assertEquals("1080", choice.message.content)
        assertEquals("stop", choice.finishReason)
        assertEquals(42, response.usage?.promptTokens)
        assertEquals(17, response.usage?.completionTokens)
        assertEquals(59, response.usage?.totalTokens)
    }

    @Test
    fun `служебный вызов просит json-формат`() {
        val body = DeepSeekRequest(
            model = "x",
            messages = listOf(ChatMessage("user", "верни json")),
            responseFormat = ResponseFormat.JSON_OBJECT
        ).ollamaBody()

        assertEquals("json", body["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `инструменты и аргументы вызова переводятся в формат Ollama`() {
        val engine = MockEngine {
            respond(
                content = """
                    {"message":{"role":"assistant","content":"","tool_calls":[
                      {"function":{"name":"get_currency_rates","arguments":{"codes":["USD"]}}}
                    ]},"done_reason":"stop"}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val response = runBlocking {
            LocalLlmClient("qwen3:1.7b", client(engine), logger = AgentLogger.Silent)
                .complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "курс"))))
        }

        val call = assertNotNull(response.choices.single().message.toolCalls).single()
        assertEquals("get_currency_rates", call.function.name)
        // Аргументы возвращаются строкой JSON — в таком виде их разбирает агент.
        assertEquals("USD", json.parseToJsonElement(call.function.arguments).jsonObject["codes"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `http-ошибка демона становится LlmApiException с его причиной`() {
        val engine = MockEngine {
            respond(
                content = """{"error":"model 'qwen3:1.7b' not found, try pulling it first"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val error = assertFailsWith<LlmApiException> {
            runBlocking {
                LocalLlmClient("qwen3:1.7b", client(engine), logger = AgentLogger.Silent)
                    .complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "привет"))))
            }
        }
        assertEquals(404, error.status)
        assertTrue(error.message!!.contains("not found"), error.message!!)
    }

    @Test
    fun `недоступный демон становится LlmUnavailableException`() {
        val engine = MockEngine { throw ConnectException("Connection refused") }

        val error = assertFailsWith<LlmUnavailableException> {
            runBlocking {
                LocalLlmClient("qwen3:1.7b", client(engine), baseUrl = "http://127.0.0.1:11434", logger = AgentLogger.Silent)
                    .complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "привет"))))
            }
        }
        assertTrue(error.message!!.contains("Ollama"), error.message!!)
        assertTrue(error.message!!.contains("qwen3:1.7b"), error.message!!)
    }

    @Test
    fun `превышенный срок становится LlmTimeoutException`() {
        val engine = MockEngine {
            delay(500)
            respond(
                content = """{"message":{"role":"assistant","content":"поздно"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        assertFailsWith<LlmTimeoutException> {
            runBlocking {
                LocalLlmClient(
                    "qwen3:1.7b",
                    client(engine),
                    timeoutMillis = 100,
                    logger = AgentLogger.Silent
                ).complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "привет"))))
            }
        }
    }

    @Test
    fun `ответ без сообщения не считается успешным`() {
        val engine = MockEngine {
            respond(
                content = """{"error":"broken response"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val error = assertFailsWith<LlmApiException> {
            runBlocking {
                LocalLlmClient("qwen3:1.7b", client(engine), logger = AgentLogger.Silent)
                    .complete(DeepSeekRequest("qwen3:1.7b", listOf(ChatMessage("user", "привет"))))
            }
        }
        assertTrue(error.message!!.contains("broken response"), error.message!!)
    }
}
