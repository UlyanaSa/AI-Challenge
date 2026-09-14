package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.ContextOverflowException
import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.EmptyReplyException
import com.osvin.aichallenge.agent.InMemorySummaryStore
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.models.*
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * HTTP-клиент для взаимодействия с внешним API DeepSeek.
 * Сконфигурирован с поддержкой JSON и игнорированием неизвестных полей.
 * Таймауты подняты: сильные модели (deepseek-v4-pro) на длинных ответах
 * отвечают дольше дефолтных 15 секунд движка CIO (день 5: сравнение моделей).
 */
val client = HttpClient(CIO) {
    install(ClientContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 300_000
    }
}

/**
 * Сводки историй диалогов: отдельно от сообщений, по сессии.
 *
 * Хранилище общее на все запросы сервера: агент создаётся на каждый запрос,
 * а сводка должна пережить запрос и заменить свёрнутые сообщения в следующем.
 */
val summaryStore = InMemorySummaryStore()

/**
 * Точка входа в приложение.
 * Запускает встроенный сервер Netty.
 */
fun main() {
    embeddedServer(
        Netty,
        port = AppConfig.DEFAULT_PORT,
        host = AppConfig.DEFAULT_HOST,
        module = Application::module
    ).start(wait = true)
}

/**
 * Ключ DeepSeek: сначала переменная окружения, затем `server/.env`.
 *
 * `.env` читает сам сервер, а не только Gradle-задача `runDev`: сервер запускают
 * и из IDE, и через `:server:run`, и из собранного jar — без этого ключа маршрут
 * падал до того, как агент успевал напечатать свои логи.
 */
private fun deepSeekApiKey(): String? =
    System.getenv("DEEPSEEK_API_KEY") ?: envFile()["DEEPSEEK_API_KEY"]

/** Содержимое `.env` рядом с проектом или в текущем каталоге; пусто, если файла нет. */
private fun envFile(): Map<String, String> {
    val file = listOf(File("server/.env"), File(".env")).firstOrNull { it.isFile } ?: return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

/**
 * Основной модуль сервера Ktor.
 * Настраивает плагины и маршрутизацию.
 */
fun Application.module() {
    // Поддержка JSON для входящих и исходящих данных
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    // Глобальная обработка исключений
    install(StatusPages) {
        // Переполнение контекста — ошибка запроса, а не сбой сервера
        exception<ContextOverflowException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(success = false, error = cause.message ?: "Контекст переполнен")
            )
        }

        // Пустой ответ модели: бюджета ответа не хватило на текст (thinking-модель
        // спустила его на рассуждения) — клиенту нужна причина, а не пустая строка
        exception<EmptyReplyException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(success = false, error = cause.message ?: "Модель не вернула текст ответа")
            )
        }

        // Ошибка запроса у провайдера: переполнение контекста, неверный max_tokens —
        // это вина запроса, а не сбой сервера. Сбой API провайдера — 502.
        exception<LlmApiException> { call, cause ->
            val status = if (cause.status in 400..499) {
                HttpStatusCode.BadRequest
            } else {
                HttpStatusCode.BadGateway
            }
            call.respond(
                status,
                ErrorResponse(success = false, error = cause.message ?: "Ошибка LLM API")
            )
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Internal Server Error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(success = false, error = cause.message ?: "Неизвестная ошибка сервера")
            )
        }
    }

    // Настройка CORS для возможности запросов с разных доменов (полезно для Web/JS)
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    // Ограничение частоты запросов для защиты API
    install(RateLimit) {
        register(RateLimitName("chat")) {
            rateLimiter(limit = 20, refillPeriod = 60.seconds)
        }
    }

    routing {
        /**
         * Проверка состояния сервера и API ключа.
         */
        get("/v1/health") {
            val apiKey = deepSeekApiKey()

            if (apiKey.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HealthResponse(
                        status = "error",
                        deepseek = "unknown",
                        message = "Ключ DEEPSEEK_API_KEY не задан в переменных окружения"
                    )
                )
                return@get
            }

            try {
                // Простая проверка связи с API через список моделей
                val response = client.get("https://api.deepseek.com/v1/models") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }

                if (response.status.isSuccess()) {
                    call.respond(HealthResponse(status = "ok", deepseek = "online"))
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthResponse(
                            status = "error",
                            deepseek = "auth_failed",
                            code = response.status.value
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthResponse(status = "error", deepseek = "offline", message = e.message)
                )
            }
        }

        /**
         * Основной endpoint для чата.
         * Транспортный адаптер: принимает HTTP-запрос, передаёт набор параметров
         * агенту ([LlmAgent]) и возвращает ответ клиенту. Вся работа с моделью
         * инкапсулирована в агенте.
         */
        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val apiKey = deepSeekApiKey()
                ?: error("API ключ не настроен")

            val agent = LlmAgent(DeepSeekClient(apiKey, client), summaryStore = summaryStore)
            val result = agent.run(
                userMessage = request.message,
                options = AgentOptions(
                    model = request.model,
                    maxTokens = request.maxTokens,
                    stop = request.stop,
                    temperature = request.temperature,
                    systemPrompt = request.systemPrompt,
                    history = request.history ?: emptyList(),
                    sessionId = request.sessionId,
                    compressHistory = request.compressHistory ?: true
                )
            )

            call.respond(
                ChatResponse(
                    success = true,
                    reply = result.reply,
                    usage = result.usage,
                    tokens = result.tokens
                )
            )
        }

        /**
         * Удаление сессии чата.
         * Клиент удалил чат — сводка его истории серверу больше не нужна:
         * без этого память росла бы на каждый удалённый диалог.
         */
        delete("/v1/chats/{sessionId}") {
            val sessionId = call.parameters["sessionId"].orEmpty()
            summaryStore.clear(sessionId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
