package com.osvin.aichallenge

import com.osvin.aichallenge.models.*
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * HTTP-клиент для взаимодействия с внешним API DeepSeek.
 * Сконфигурирован с поддержкой JSON и игнорированием неизвестных полей.
 * Таймауты увеличены: длинные генерации (группа экспертов, судья) могут
 * занимать заметно больше стандартных 15 секунд.
 */
val client = HttpClient(CIO) {
    install(ClientContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 180_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 180_000
    }
}

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
            val apiKey = System.getenv("DEEPSEEK_API_KEY")

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
         * Обычный ответ чата: один прямой запрос к модели без инструкций
         * и без характеристик.
         */
        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val apiKey = System.getenv("DEEPSEEK_API_KEY")
                ?: error("API ключ не настроен")

            val outcome = deepSeekComplete(
                apiKey = apiKey,
                model = AppConfig.DEFAULT_MODEL,
                messages = listOf(ChatMessage("user", request.message))
            )
            call.respond(ChatResponse(success = true, reply = outcome.text))
        }

        /**
         * Запуск одного варианта ответа: решение + основные характеристики
         * (токены, скорость, длина, стоимость, глубина, точность).
         */
        post("/v1/chat/variant") {
            val request = call.receive<VariantRequest>()
            val apiKey = System.getenv("DEEPSEEK_API_KEY")
                ?: error("API ключ не настроен")

            val solved = VariantRunner.solveVariant(
                apiKey = apiKey,
                model = AppConfig.DEFAULT_MODEL,
                question = request.message,
                variantKey = request.variant
            )
            call.respond(
                VariantResponse(
                    success = true,
                    title = solved.title,
                    content = solved.content,
                    metrics = solved.metrics
                )
            )
        }

        /**
         * Вердикт судьи по готовым решениям одной задачи.
         * Судья оценивает каждое решение по параметрам (правильность, полнота,
         * обоснованность, ясность) и называет наиболее точное.
         */
        post("/v1/chat/analyze") {
            val request = call.receive<AnalysisRequest>()
            val apiKey = System.getenv("DEEPSEEK_API_KEY")
                ?: error("API ключ не настроен")

            val verdict = VariantRunner.judgeSolutions(
                apiKey = apiKey,
                model = AppConfig.DEFAULT_MODEL,
                question = request.question,
                solutions = request.solutions
            )
            call.respond(AnalysisResponse(success = true, verdict = verdict))
        }
    }
}
