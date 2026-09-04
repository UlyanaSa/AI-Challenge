package com.osvin.aichallenge

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val message: String,
    val history: List<ChatMessage> = emptyList()
)

@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null
)

@Serializable
data class ErrorResponse(
    val success: Boolean,
    val error: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val deepseek: String,
    val message: String? = null,
    val code: Int? = null
)

@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null
)

@Serializable
data class DeepSeekResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        val message: ChatMessage
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int,
        @SerialName("completion_tokens") val completionTokens: Int,
        @SerialName("total_tokens") val totalTokens: Int
    )
}

// Клиент для запросов к DeepSeek
val client = HttpClient(CIO) {
    install(ClientContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
}

// Основная функция
fun main() {
    embeddedServer(
        Netty,
        port = 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Internal Server Error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(success = false, error = cause.message ?: "Unknown error")
            )
        }
    }

    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    install(RateLimit) {
        register(RateLimitName("chat")) {
            rateLimiter(limit = 15, refillPeriod = 60.seconds)
        }
    }

    routing {
        get("/v1/health") {
            val apiKey = System.getenv("DEEPSEEK_API_KEY")

            if (apiKey.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HealthResponse(
                        status = "error",
                        deepseek = "unknown",
                        message = "DEEPSEEK_API_KEY is not set"
                    )
                )
                return@get
            }

            try {
                // Проверяем связь с DeepSeek, запрашивая список доступных моделей
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

        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val apiKey = System.getenv("DEEPSEEK_API_KEY")
                ?: error("API key not configured")

            // Формируем запрос к DeepSeek
            val messages = request.history + ChatMessage("user", request.message)

            val deepSeekResponse = client.post("https://api.deepseek.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(
                    DeepSeekRequest(
                        model = "deepseek-chat",
                        messages = messages,
                        maxTokens = 2000,
                        temperature = 0.7
                    )
                )
            }

            if (!deepSeekResponse.status.isSuccess()) {
                val errorBody = deepSeekResponse.body<String>()
                error("DeepSeek API error: ${deepSeekResponse.status} - $errorBody")
            }

            val responseBody = deepSeekResponse.body<DeepSeekResponse>()

            if (responseBody.choices.isEmpty()) {
                error("DeepSeek returned empty choices")
            }

            call.respond(
                ChatResponse(
                    success = true,
                    reply = responseBody.choices.first().message.content,
                    usage = responseBody.usage?.let {
                        mapOf(
                            "prompt_tokens" to it.promptTokens,
                            "completion_tokens" to it.completionTokens,
                            "total_tokens" to it.totalTokens
                        )
                    }
                )
            )
        }
    }
}
