package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.LlmClientFactory
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.agent.LlmProviderConfig
import com.osvin.aichallenge.agent.LlmUnavailableException
import com.osvin.aichallenge.models.OllamaTags
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ключ тестового клиента: он же настроенный ключ сервиса. */
private const val TEST_KEY = "тестовый-ключ"

/** Заголовок ключа: тот же, что читает сервис. */
private const val API_KEY_HEADER = "X-API-Key"

/** Модель подставного демона. */
private const val MODEL = "qwen3:1.7b"

/** Ответ подставной модели. */
private const val REPLY = "Ответ модели"

/** Обязательная инструкция тестового сервиса. */
private const val SYSTEM_PROMPT = "system rules"

/** Сводка прошлого диалога, которой отвечает подставная модель. */
private const val SUMMARY_TEXT = "Сводка прошлого: проект «Клевер», бюджет 120 000, срок 20 мая."

/** Разбор тел: сравниваются поля, а не текст. */
private val testJson = Json { ignoreUnknownKeys = true }

/** Заголовки заготовок подставного демона. */
private val testJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * Настройки тестового сервиса: лимиты задаются под проверку, а не по умолчанию.
 *
 * Умолчания здесь свои, а не продовые: проверка очереди требует параллельности в одну
 * генерацию, а проверка частоты — потолка в два запроса, и от значения `SERVICE_*`
 * на машине разработчика тест зависеть не должен.
 */
private fun testConfig(
    apiKeys: Set<String> = setOf(TEST_KEY),
    rateLimitPerMinute: Int = 10,
    maxConcurrentGenerations: Int = 2,
    queueLimit: Int = 4,
    queueWaitMillis: Long = 2_000,
    maxContextTokens: Int = 512,
    maxGenerationTokens: Int = 128,
    chatsListLimit: Int = ServiceConfig.DEFAULT_CHATS_LIST_LIMIT
): ServiceConfig = ServiceConfig(
    apiKeys = apiKeys,
    rateLimitPerMinute = rateLimitPerMinute,
    maxConcurrentGenerations = maxConcurrentGenerations,
    queueLimit = queueLimit,
    queueWaitMillis = queueWaitMillis,
    maxContextTokens = maxContextTokens,
    maxGenerationTokens = maxGenerationTokens,
    systemPrompt = SYSTEM_PROMPT,
    chatDbPath = "тест-не-используется.db",
    chatsListLimit = chatsListLimit
)

/**
 * Весь сервис на подставных зависимостях.
 *
 * Метрики читают счётчики занятости у лимитов, а размеры хранилища — у хранилища: копий
 * чисел нет, поэтому снимок показывает то же, что видят маршруты.
 */
private fun serviceDeps(
    config: ServiceConfig,
    store: ChatSessionStore,
    ollama: FakeOllama
): ServiceDeps {
    val limits = ServiceLimits(
        rateLimitPerMinute = config.rateLimitPerMinute,
        maxConcurrent = config.maxConcurrentGenerations,
        queueLimit = config.queueLimit,
        queueWaitMillis = config.queueWaitMillis
    )
    val metrics = ServiceMetrics(
        config = config,
        activeGenerations = limits::activeGenerations,
        queuedGenerations = limits::queuedGenerations,
        sessions = store::counts
    )
    val factory = LlmClientFactory(
        LlmProviderConfig(provider = LlmProvider.LOCAL, ollamaModel = MODEL),
        ollama.client(),
        AgentLogger.Silent
    )
    return ServiceDeps(
        config = config,
        store = store,
        metrics = metrics,
        limits = limits,
        auth = ServiceAuth(config.apiKeys, AgentLogger.Silent),
        llm = factory
    )
}

/** Сборка сервиса на подставных зависимостях. */
private class Fixture(
    val config: ServiceConfig = testConfig(),
    val ollama: FakeOllama = FakeOllama(),
    val store: FakeChatSessionStore = FakeChatSessionStore()
) {
    val deps: ServiceDeps = serviceDeps(config, store, ollama)
}

/**
 * Подставной демон Ollama: отвечает заготовками на чат и список моделей.
 *
 * Запросы к чату запоминаются целиком: по ним видно, что именно ушло модели, — а это
 * и есть проверка контекста, которую иначе пришлось бы смотреть в логе демона.
 */
private class FakeOllama(
    private val models: List<String> = listOf(MODEL),
    private val model: String = MODEL
) {

    /** Тела запросов к `/api/chat` в порядке поступления. */
    val prompts = CopyOnWriteArrayList<JsonObject>()

    /** Ответ модели. */
    var reply: String = REPLY

    /** Отказ вместо ответа: имитация недоступного демона. */
    var failure: Throwable? = null

    /** Задержка перед ответом; держит генерацию, пока идёт проверка очереди. */
    var delayMillis: Long = 0

    /** Заслонка: пока не открыта, генерация ждёт. */
    var gate: CompletableDeferred<Unit>? = null

    /** Демон не отвечает вовсе: проверка живости и список моделей получают отказ. */
    var broken: Boolean = false

    /** Движок для транспорта сервиса. */
    val engine = MockEngine { request ->
        if (broken) throw IllegalStateException("демон недоступен")
        when (request.url.encodedPath) {
            "/api/tags" -> respond(
                testJson.encodeToString(OllamaTags(models.map { OllamaTags.OllamaTag(it, it) })),
                HttpStatusCode.OK,
                testJsonHeaders
            )

            "/api/chat" -> {
                prompts += testJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                gate?.await()
                if (delayMillis > 0) delay(delayMillis)
                failure?.let { throw it }
                respond(
                    """
                    {"model":"$model","done":true,"done_reason":"stop",
                     "message":{"role":"assistant","content":"$reply"},
                     "prompt_eval_count":11,"eval_count":7}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    testJsonHeaders
                )
            }

            else -> respond("{}", HttpStatusCode.NotFound, testJsonHeaders)
        }
    }

    /** Клиент транспорта на подставном движке: тот же набор плагинов, что у сервера. */
    fun client(): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }
}

/** Подставное хранилище: сессии и история в памяти, без файла. */
private class FakeChatSessionStore : ChatSessionStore {

    private val sessions = LinkedHashMap<String, ChatSession>()
    private val history = HashMap<String, MutableList<StoredMessage>>()
    private var created = 0

    @Synchronized
    override fun create(title: String?, nowMillis: Long): ChatSession {
        val id = "chat%04d".format(created++)
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: ChatSession.DEFAULT_TITLE
        val session = ChatSession(id, name, nowMillis, nowMillis)
        sessions[id] = session
        history[id] = mutableListOf()
        return session
    }

    @Synchronized
    override fun find(id: String): ChatSession? = sessions[id]

    @Synchronized
    override fun append(chatId: String, role: String, content: String, nowMillis: Long): StoredMessage {
        val message = StoredMessage(role, content, nowMillis)
        history.getOrPut(chatId) { mutableListOf() }.add(message)
        sessions[chatId]?.let { sessions[chatId] = it.copy(updatedAt = nowMillis) }
        return message
    }

    @Synchronized
    override fun messages(chatId: String): List<StoredMessage> = history[chatId].orEmpty().toList()

    @Synchronized
    override fun list(limit: Int): List<ChatSession> = sessions.values
        .sortedByDescending { it.updatedAt }
        .take(limit)
        .map { it.copy(messageCount = history[it.id].orEmpty().size) }

    @Synchronized
    override fun latestWithMessages(excluding: String): ChatSession? = sessions.values
        .filter { it.id != excluding && history[it.id].orEmpty().isNotEmpty() }
        .maxByOrNull { it.updatedAt }

    @Synchronized
    override fun setSummary(chatId: String, previousChatId: String?, summary: String?, messages: Int) {
        sessions[chatId]?.let { session ->
            sessions[chatId] = session.copy(
                previousChatId = previousChatId,
                summary = summary,
                summaryMessages = messages
            )
        }
    }

    @Synchronized
    override fun delete(chatId: String): Int {
        if (sessions.remove(chatId) == null) return -1
        return history.remove(chatId)?.size ?: 0
    }

    @Synchronized
    override fun counts(): StoreCounts = StoreCounts(sessions.size, history.values.sumOf { it.size })

    override fun close() = Unit
}

/**
 * Поверхность `/api/…`: доступ, лимиты, контекст, история и метрики.
 *
 * Ollama подставной: движок отвечает заготовками, поэтому проверяется поведение сервиса —
 * коды, заголовки, тела и то, что ушло модели, — а не демон на машине разработчика.
 * Хранилище тоже подставное ([FakeChatSessionStore]): маршруты не зависят от файла,
 * а порядок сообщений виден прямо в памяти.
 */
class ServiceApiTest {

    @Test
    fun `чат без ключа отвечает 401, с чужим ключом тоже`() = testApplication {
        installService()

        val withoutKey = client.postService("/api/chat", """{"chatId":"chat0000","message":"привет"}""", key = null)
        assertEquals(HttpStatusCode.Unauthorized, withoutKey.status)
        assertEquals(ApiError.UNAUTHORIZED, withoutKey.jsonBody()["code"]?.jsonPrimitive?.content)

        val wrongKey = client.postService("/api/chat", """{"chatId":"chat0000","message":"привет"}""", key = "чужой")
        assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)
        assertEquals(ApiError.UNAUTHORIZED, wrongKey.jsonBody()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ключ принимается и заголовком X-API-Key, и схемой Bearer`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Тест", 1L).id

        val bearer = client.post("/api/chat") {
            header(HttpHeaders.Authorization, "Bearer $TEST_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"$chatId","message":"привет"}""")
        }

        assertEquals(HttpStatusCode.OK, bearer.status)
    }

    @Test
    fun `без настроенных ключей сервис отвечает 503 service_not_configured`() = testApplication {
        installService(Fixture(config = testConfig(apiKeys = emptySet())))

        val response = client.postService("/api/chat", """{"chatId":"chat0000","message":"привет"}""", key = "любой")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(ApiError.SERVICE_NOT_CONFIGURED, response.jsonBody()["code"]?.jsonPrimitive?.content)
        // Проверка живости — единственное исключение: клиент обязан отличать «сервис не настроен»
        // от «ключ неверный» ещё до того, как человек ввёл ключ.
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }

    @Test
    fun `сверх лимита частоты приходит 429 с Retry-After`() = testApplication {
        val fixture = installService(Fixture(config = testConfig(rateLimitPerMinute = 2)))
        val chatId = fixture.store.create("Тест", 1L).id

        repeat(2) {
            val ok = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")
            assertEquals(HttpStatusCode.OK, ok.status)
        }

        val limited = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(ApiError.RATE_LIMITED, limited.jsonBody()["code"]?.jsonPrimitive?.content)
        val retryAfter = limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        assertNotNull(retryAfter, "в ответе 429 нет Retry-After: ${limited.headers}")
        assertTrue(retryAfter >= 1, "Retry-After должен быть целым числом секунд: $retryAfter")
        // Отказы по частоте видны в метриках: панель показывает, что клиент упёрся в лимит.
        val metrics = client.getService("/api/metrics").jsonBody()
        assertEquals(1, metrics["rateLimit"]!!.jsonObject["rejections"]!!.jsonPrimitive.int)
    }

    @Test
    fun `заполненная очередь генераций даёт 503 overloaded`() = testApplication {
        val fixture = installService(
            Fixture(config = testConfig(maxConcurrentGenerations = 1, queueLimit = 0))
        )
        val first = fixture.store.create("Первый", 1L).id
        val second = fixture.store.create("Второй", 2L).id
        val gate = CompletableDeferred<Unit>()
        fixture.ollama.gate = gate

        coroutineScope {
            val slow = async { client.postService("/api/chat", """{"chatId":"$first","message":"медленный"}""") }
            awaitPrompts(fixture.ollama)
            val rejected = client.postService("/api/chat", """{"chatId":"$second","message":"лишний"}""")

            assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
            assertEquals(ApiError.OVERLOADED, rejected.jsonBody()["code"]?.jsonPrimitive?.content)
            assertNotNull(
                rejected.headers[HttpHeaders.RetryAfter],
                "отказ по перегрузке обязан звать повторить позже"
            )

            gate.complete(Unit)
            assertEquals(HttpStatusCode.OK, slow.await().status)
        }
    }

    @Test
    fun `лимит частоты ограничивает и маршруты без генерации`() = testApplication {
        val fixture = installService(Fixture(config = testConfig(rateLimitPerMinute = 2)))

        repeat(2) {
            assertEquals(
                HttpStatusCode.Created,
                client.postService("/api/chats", """{"title":"Чат"}""").status
            )
        }

        val limited = client.postService("/api/chats", """{"title":"Лишний"}""")

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(ApiError.RATE_LIMITED, limited.jsonBody()["code"]?.jsonPrimitive?.content)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter], "429 обязан звать повторить позже")
        // Проверка живости и метрики остаются без лимита: панель не должна исчерпывать окно.
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals(
            1,
            client.getService("/api/metrics").jsonBody()["rateLimit"]!!.jsonObject["rejections"]!!.jsonPrimitive.int
        )
        // Демона лишний запрос не достиг: лимит стоит до обращения к Ollama.
        assertTrue(fixture.ollama.prompts.isEmpty())
    }

    @Test
    fun `обязательная часть больше окна — 400 context_overflow`() = testApplication {
        val fixture = installService(
            Fixture(config = testConfig(maxContextTokens = 64, maxGenerationTokens = 32))
        )
        val chatId = fixture.store.create("Тест", 1L).id

        val response = client.postService(
            "/api/chat",
            """{"chatId":"$chatId","message":"${"почему ".repeat(30)}"}"""
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ApiError.CONTEXT_OVERFLOW, response.jsonBody()["code"]?.jsonPrimitive?.content)
        assertTrue(fixture.store.messages(chatId).isEmpty(), "отказ не должен оставлять вопрос в истории")
    }

    @Test
    fun `история усекается, но система и текущий вопрос остаются`() = testApplication {
        val fixture = installService(
            Fixture(config = testConfig(maxContextTokens = 300, maxGenerationTokens = 100))
        )
        val chatId = fixture.store.create("Тест", 1L).id
        repeat(12) { index ->
            fixture.store.append(chatId, "user", "$index".padEnd(60, 'a'), index.toLong())
            fixture.store.append(chatId, "assistant", "$index".padEnd(60, 'b'), index.toLong())
        }

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"question"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val trimmed = response.jsonBody()["trimmedMessages"]!!.jsonPrimitive.int
        assertEquals(15, trimmed, "отброшены должны быть самые старые пары")

        val sent = fixture.ollama.prompts.single()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(11, sent.size, "инструкция, девять сообщений истории и вопрос: $sent")
        assertEquals(
            "system",
            sent.first()["role"]?.jsonPrimitive?.content,
            "системная инструкция обязана идти первой: $sent"
        )
        assertEquals(SYSTEM_PROMPT, sent.first()["content"]?.jsonPrimitive?.content)
        assertEquals("user", sent.last()["role"]?.jsonPrimitive?.content)
        assertEquals("question", sent.last()["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `два запроса к одной сессии не чередуют сообщения`() = testApplication {
        val fixture = installService(
            Fixture(config = testConfig(maxConcurrentGenerations = 2, queueLimit = 4))
        )
        val chatId = fixture.store.create("Тест", 1L).id
        val second = createClient { }
        val gate = CompletableDeferred<Unit>()
        fixture.ollama.gate = gate

        coroutineScope {
            val firstRequest = async {
                client.postService("/api/chat", """{"chatId":"$chatId","message":"первый"}""")
            }
            awaitPrompts(fixture.ollama)
            val secondRequest = async {
                second.postService("/api/chat", """{"chatId":"$chatId","message":"второй"}""")
            }
            // Второй запрос обязан ждать первый: он ещё не должен был дойти до модели.
            delay(100)
            assertEquals(1, fixture.ollama.prompts.size, "второй запрос начал генерацию до первого")

            gate.complete(Unit)
            assertEquals(HttpStatusCode.OK, firstRequest.await().status)
            assertEquals(HttpStatusCode.OK, secondRequest.await().status)
        }

        assertEquals(
            listOf(
                "user" to "первый",
                "assistant" to REPLY,
                "user" to "второй",
                "assistant" to REPLY
            ),
            fixture.store.messages(chatId).map { it.role to it.content }
        )
    }

    @Test
    fun `после успешного ответа история получает вопрос и ответ`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Тест", 1L).id

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.jsonBody()
        assertEquals(REPLY, body["reply"]?.jsonPrimitive?.content)
        assertEquals(11, body["promptTokens"]?.jsonPrimitive?.int)
        assertEquals(7, body["completionTokens"]?.jsonPrimitive?.int)
        assertEquals(18, body["totalTokens"]?.jsonPrimitive?.int)
        assertEquals(chatId, body["chatId"]?.jsonPrimitive?.content)
        assertEquals(MODEL, body["model"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("user" to "вопрос", "assistant" to REPLY),
            fixture.store.messages(chatId).map { it.role to it.content }
        )
    }

    @Test
    fun `отказ модели не оставляет в истории вопрос без ответа`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Тест", 1L).id
        fixture.ollama.failure = LlmUnavailableException("демон Ollama не отвечает")

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(ApiError.OLLAMA_UNAVAILABLE, response.jsonBody()["code"]?.jsonPrimitive?.content)
        assertTrue(fixture.store.messages(chatId).isEmpty(), "отказ модели не пишется в историю")
    }

    @Test
    fun `пустой ответ модели — отказ 502, а не ответ с пустой строкой`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Тест", 1L).id
        fixture.ollama.reply = "   "

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.jsonBody()
        assertEquals(ApiError.EMPTY_REPLY, body["code"]?.jsonPrimitive?.content)
        // Подсказка называет действие: пустой ответ у думающей модели — это израсходованный бюджет.
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("maxTokens"), body.toString())
        assertTrue(fixture.store.messages(chatId).isEmpty(), "пустой ответ не пишется в историю")
        val requests = client.getService("/api/metrics").jsonBody()["requests"]!!.jsonObject
        assertEquals(0, requests["success"]!!.jsonPrimitive.int)
        assertEquals(1, requests["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `неизвестная сессия отвечает 404 на чтение, удаление и вопрос`() = testApplication {
        installService()

        val detail = client.getService("/api/chats/нет-такой")
        assertEquals(HttpStatusCode.NotFound, detail.status)
        assertEquals(ApiError.CHAT_NOT_FOUND, detail.jsonBody()["code"]?.jsonPrimitive?.content)
        assertEquals(HttpStatusCode.NotFound, client.deleteService("/api/chats/нет-такой").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.postService("/api/chat", """{"chatId":"нет-такой","message":"привет"}""").status
        )
    }

    @Test
    fun `запрос без chatId или с пустым сообщением — 400`() = testApplication {
        installService()

        val noChat = client.postService("/api/chat", """{"message":"привет"}""")
        assertEquals(HttpStatusCode.BadRequest, noChat.status)
        assertEquals(ApiError.INVALID_REQUEST, noChat.jsonBody()["code"]?.jsonPrimitive?.content)

        val emptyMessage = client.postService("/api/chat", """{"chatId":"chat0000","message":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, emptyMessage.status)

        val unreadable = client.postService("/api/chat", """не json""")
        assertEquals(HttpStatusCode.BadRequest, unreadable.status)
    }

    @Test
    fun `сессия создаётся, читается и удаляется вместе с историей`() = testApplication {
        installService()

        val created = client.postService("/api/chats", """{"title":"Мой чат"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val chatId = created.jsonBody()["chatId"]!!.jsonPrimitive.content
        assertEquals("Мой чат", created.jsonBody()["title"]?.jsonPrimitive?.content)

        assertEquals(
            HttpStatusCode.OK,
            client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""").status
        )

        val detail = client.getService("/api/chats/$chatId").jsonBody()
        assertEquals(chatId, detail["chatId"]?.jsonPrimitive?.content)
        assertEquals(2, detail["messages"]!!.jsonArray.size)
        assertEquals("user", detail["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)

        val deleted = client.deleteService("/api/chats/$chatId").jsonBody()
        assertEquals(true, deleted["deleted"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(2, deleted["messages"]?.jsonPrimitive?.int)
        assertEquals(HttpStatusCode.NotFound, client.getService("/api/chats/$chatId").status)
    }

    @Test
    fun `здоровье отвечает без ключа и называет модель`() = testApplication {
        installService()

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.jsonBody()
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("local", body["provider"]?.jsonPrimitive?.content)
        assertEquals(MODEL, body["model"]?.jsonPrimitive?.content)
        assertEquals("ok", body["ollama"]?.jsonPrimitive?.content)
        assertEquals(true, body["modelLoaded"]?.jsonPrimitive?.content?.toBoolean())
        assertNotNull(body["uptimeMs"])
        assertNull(body["message"], "у живого сервиса причины отказа нет: $body")
    }

    @Test
    fun `здоровье без загруженной модели называет действие`() = testApplication {
        installService(Fixture(ollama = FakeOllama(models = listOf("bge-m3:latest"))))

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.jsonBody()
        assertEquals("error", body["status"]?.jsonPrimitive?.content)
        assertEquals("ok", body["ollama"]?.jsonPrimitive?.content)
        assertEquals(false, body["modelLoaded"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("ollama pull"), body.toString())
    }

    @Test
    fun `список моделей берётся у демона, а его молчание — отказ сервиса`() = testApplication {
        val fixture = installService()

        val models = client.getService("/api/models")
        assertEquals(HttpStatusCode.OK, models.status)
        assertEquals(
            MODEL,
            models.jsonBody()["models"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
        )

        fixture.ollama.broken = true
        val unreachable = client.getService("/api/models")
        assertEquals(HttpStatusCode.ServiceUnavailable, unreachable.status)
        assertEquals(ApiError.OLLAMA_UNAVAILABLE, unreachable.jsonBody()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `метрики под ключом показывают работу сервиса`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Тест", 1L).id
        assertEquals(
            HttpStatusCode.OK,
            client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""").status
        )

        val metrics = client.getService("/api/metrics")

        assertEquals(HttpStatusCode.OK, metrics.status)
        val body = metrics.jsonBody()
        assertEquals(1, body["requests"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertEquals(1, body["requests"]!!.jsonObject["success"]!!.jsonPrimitive.int)
        assertEquals(0, body["requests"]!!.jsonObject["failed"]!!.jsonPrimitive.int)
        assertEquals(1, body["latency"]!!.jsonObject["samples"]!!.jsonPrimitive.int)
        assertEquals(2, body["generations"]!!.jsonObject["maxConcurrent"]!!.jsonPrimitive.int)
        assertEquals(4, body["generations"]!!.jsonObject["queueLimit"]!!.jsonPrimitive.int)
        assertEquals(11, body["tokens"]!!.jsonObject["prompt"]!!.jsonPrimitive.int)
        assertEquals(7, body["tokens"]!!.jsonObject["completion"]!!.jsonPrimitive.int)
        assertEquals(1, body["sessions"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(2, body["sessions"]!!.jsonObject["messages"]!!.jsonPrimitive.int)
        assertTrue(body["resources"]!!.jsonObject.containsKey("heapMaxBytes"), body.toString())
        assertTrue(body["resources"]!!.jsonObject.containsKey("processors"), body.toString())
        // Метрики тоже под ключом: незакрытая панель не должна показывать чужую работу.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/metrics").status)
    }

    @Test
    fun `конфигурация читается из окружения по правилам умолчаний`() {
        val defaults = ServiceConfig.fromEnv(emptyMap())
        assertEquals(ServiceConfig.DEFAULT_HOST, defaults.host)
        assertEquals(ServiceConfig.DEFAULT_PORT, defaults.port)
        assertEquals(ServiceConfig.DEFAULT_MAX_CONTEXT_TOKENS, defaults.maxContextTokens)
        assertEquals(true, defaults.summaryEnabled)
        assertEquals(ServiceConfig.DEFAULT_SUMMARY_MAX_TOKENS, defaults.summaryMaxTokens)
        assertEquals(ServiceConfig.DEFAULT_SUMMARY_MAX_MESSAGES, defaults.summaryMaxMessages)
        assertEquals(ServiceConfig.DEFAULT_CHATS_LIST_LIMIT, defaults.chatsListLimit)
        assertEquals(false, defaults.configured)

        val configured = ServiceConfig.fromEnv(
            mapOf(
                "SERVICE_API_KEY" to " один ",
                "SERVICE_API_KEYS" to "два, ,три",
                "SERVICE_PORT" to "0",
                "SERVICE_RATE_LIMIT_PER_MINUTE" to "не число",
                "SERVICE_QUEUE_LIMIT" to "0",
                "SERVICE_MAX_CONTEXT_TOKENS" to "1024",
                "SERVICE_MAX_GENERATION_TOKENS" to "9999",
                "SERVICE_SUMMARY_ENABLED" to "false",
                "SERVICE_SUMMARY_MAX_TOKENS" to "9999",
                "SERVICE_SUMMARY_MAX_MESSAGES" to "0",
                "SERVICE_CHATS_LIST_LIMIT" to "5"
            )
        )
        assertEquals(setOf("один", "два", "три"), configured.apiKeys)
        assertEquals(ServiceConfig.DEFAULT_PORT, configured.port)
        assertEquals(ServiceConfig.DEFAULT_RATE_LIMIT_PER_MINUTE, configured.rateLimitPerMinute)
        // Ноль — значение, а не опечатка: «ждать негде» бывает осознанным.
        assertEquals(0, configured.queueLimit)
        // Потолок ответа обрезан окном: с большим резервом сервис отвечал бы только отказами.
        assertEquals(1024, configured.maxGenerationTokens)
        // Сводка — такой же ответ модели: её бюджет тоже не поднимается выше потолка ответа.
        assertEquals(1024, configured.summaryMaxTokens)
        assertEquals(false, configured.summaryEnabled)
        // Неположительное число — «не задано»: сворачивать нуль сообщений нельзя.
        assertEquals(ServiceConfig.DEFAULT_SUMMARY_MAX_MESSAGES, configured.summaryMaxMessages)
        assertEquals(5, configured.chatsListLimit)
    }

    @Test
    fun `список чатов отдаёт свежие первыми, с числом сообщений и сводкой`() = testApplication {
        val fixture = installService(Fixture(config = testConfig(chatsListLimit = 2)))

        val empty = client.getService("/api/chats").jsonBody()
        assertEquals(0, empty["count"]!!.jsonPrimitive.int)
        assertEquals(0, empty["chats"]!!.jsonArray.size, "пустая база — пустой список, а не 404")

        val older = fixture.store.create("Старый", 1_000)
        fixture.store.append(older.id, "user", "вопрос", 2_000)
        fixture.store.append(older.id, "assistant", "ответ", 3_000)
        val fresh = fixture.store.create("Свежий", 4_000)
        fixture.store.append(fresh.id, "user", "ещё", 5_000)
        fixture.store.create("Самый старый", 100)
        fixture.store.setSummary(older.id, previousChatId = "chat0000", summary = SUMMARY_TEXT, messages = 2)

        val single = client.getService("/api/chats?limit=1").jsonBody()
        assertEquals(1, single["count"]!!.jsonPrimitive.int)
        assertEquals(
            fresh.id,
            single["chats"]!!.jsonArray.single().jsonObject["chatId"]!!.jsonPrimitive.content
        )

        // Потолок из настроек: третий чат в ответ не входит, но списка это не отменяет.
        val listed = client.getService("/api/chats").jsonBody()
        assertEquals(2, listed["count"]!!.jsonPrimitive.int)
        val chats = listed["chats"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(fresh.id, older.id), chats.map { it["chatId"]!!.jsonPrimitive.content })
        assertEquals(listOf(1, 2), chats.map { it["messages"]!!.jsonPrimitive.int })
        assertEquals(SUMMARY_TEXT, chats[1]["summary"]?.jsonPrimitive?.content)
        // Без сводки её нет: ни выдуманного текста, ни пустой строки вместо неё.
        assertNull(chats[0]["summary"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `создание чата сворачивает самый свежий другой чат с сообщениями`() = testApplication {
        val fixture = installService()
        fixture.store.create("Пустой", 9_000)
        val previous = fixture.store.create("С историей", 1_000)
        fixture.store.append(previous.id, "user", "первый вопрос", 2_000)
        fixture.store.append(previous.id, "assistant", "первый ответ", 3_000)
        fixture.ollama.reply = SUMMARY_TEXT

        val created = client.postService("/api/chats", """{"title":"Продолжение"}""")

        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.jsonBody()
        // Свежая, но пустая сессия прошлым не считается: сворачивать в ней нечего.
        assertEquals(previous.id, body["previousChatId"]?.jsonPrimitive?.content)
        assertEquals(SUMMARY_TEXT, body["summary"]?.jsonPrimitive?.content)
        assertEquals(2, body["summaryMessages"]!!.jsonPrimitive.int)
        assertNull(body["summaryError"]?.jsonPrimitive?.contentOrNull)

        // Модель получила расшифровку диалога с ролями, а не сам последний вопрос.
        val sent = fixture.ollama.prompts.single()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, sent.size, "инструкция сводки и диалог: $sent")
        assertEquals(ServiceConfig.DEFAULT_SUMMARY_PROMPT, sent.first()["content"]?.jsonPrimitive?.content)
        val transcript = sent.last()["content"]!!.jsonPrimitive.content
        assertTrue(transcript.startsWith("user: первый вопрос"), transcript)
        assertTrue(transcript.contains("assistant: первый ответ"), transcript)

        val plain = client.postService("/api/chats", """{"title":"Без сводки","summarize":false}""").jsonBody()
        assertNull(plain["previousChatId"]?.jsonPrimitive?.contentOrNull)
        assertNull(plain["summary"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, plain["summaryMessages"]!!.jsonPrimitive.int)
        // Выключенная сводка не зовёт модель вовсе: к демону ушёл только первый запрос.
        assertEquals(1, fixture.ollama.prompts.size)

        val summaries = client.getService("/api/metrics").jsonBody()["summaries"]!!.jsonObject
        assertEquals(1, summaries["built"]!!.jsonPrimitive.int)
        assertEquals(0, summaries["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `отказ модели при сводке не мешает создать чат`() = testApplication {
        val fixture = installService()
        val previous = fixture.store.create("Прошлый", 1_000)
        fixture.store.append(previous.id, "user", "вопрос", 2_000)
        fixture.ollama.failure = LlmUnavailableException("демон Ollama не отвечает")

        val response = client.postService("/api/chats", """{"title":"После отказа"}""")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.jsonBody()
        val chatId = body["chatId"]!!.jsonPrimitive.content
        assertEquals(previous.id, body["previousChatId"]?.jsonPrimitive?.content)
        assertNull(body["summary"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, body["summaryMessages"]!!.jsonPrimitive.int)
        assertTrue(
            body["summaryError"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true,
            "причина отказа обязана быть названа словами: $body"
        )
        // Чат заведён: недоступная модель — не повод остаться без разговора.
        assertEquals(HttpStatusCode.OK, client.getService("/api/chats/$chatId").status)
        val summaries = client.getService("/api/metrics").jsonBody()["summaries"]!!.jsonObject
        assertEquals(0, summaries["built"]!!.jsonPrimitive.int)
        assertEquals(1, summaries["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `сводка прошлого чата уходит в системную часть запроса`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Сводный", 1L).id
        fixture.store.setSummary(chatId, previousChatId = "chat0000", summary = SUMMARY_TEXT, messages = 14)

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"новый вопрос"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val sent = fixture.ollama.prompts.single()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, sent.size, "инструкция и вопрос: $sent")
        assertEquals("system", sent.first()["role"]?.jsonPrimitive?.content)
        val system = sent.first()["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith(SYSTEM_PROMPT), "инструкция сервиса обязана быть первой: $system")
        assertTrue(system.contains(SUMMARY_TEXT), system)
        assertTrue(
            system.indexOf(SYSTEM_PROMPT) < system.indexOf(SUMMARY_TEXT),
            "сводка идёт после инструкции сервиса: $system"
        )
        assertEquals("user", sent.last()["role"]?.jsonPrimitive?.content)
        // Сводка — часть инструкции, а не сообщение: в истории только вопрос и ответ.
        assertEquals(
            listOf("user" to "новый вопрос", "assistant" to REPLY),
            fixture.store.messages(chatId).map { it.role to it.content }
        )
    }

    @Test
    fun `без сводки системное сообщение остаётся как в дне 30`() = testApplication {
        val fixture = installService()
        val chatId = fixture.store.create("Обычный", 1L).id

        val response = client.postService("/api/chat", """{"chatId":"$chatId","message":"вопрос"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val sent = fixture.ollama.prompts.single()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, sent.size, "инструкция и вопрос: $sent")
        assertEquals(SYSTEM_PROMPT, sent.first()["content"]?.jsonPrimitive?.content)
    }

    private fun ApplicationTestBuilder.installService(fixture: Fixture = Fixture()): Fixture {
        val deps = fixture.deps
        application {
            routing { serviceApi(deps) }
        }
        return fixture
    }

    /** Ждёт, пока подставный демон получит запрос: только тогда ответ на него есть смысл проверять. */
    private suspend fun awaitPrompts(ollama: FakeOllama) {
        withTimeout(5_000) {
            while (ollama.prompts.isEmpty()) delay(10)
        }
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject =
        testJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.postService(path: String, body: String, key: String? = TEST_KEY): HttpResponse =
        post(path) {
            if (key != null) header(API_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.getService(path: String): HttpResponse =
        get(path) { header(API_KEY_HEADER, TEST_KEY) }

    private suspend fun HttpClient.deleteService(path: String): HttpResponse =
        delete(path) { header(API_KEY_HEADER, TEST_KEY) }
}
