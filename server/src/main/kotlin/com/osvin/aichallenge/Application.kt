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
 */
val client = HttpClient(CIO) {
    install(ClientContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
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
         * Основной endpoint для чата.
         * Гоняет один и тот же вопрос заданное число раз и сверяет формат каждого
         * ответа с заданным. В ответе сверху — текущие настройки и статус сверки
         * по каждому прогону, затем тела ответов: только неудачные прогоны,
         * либо последний успешный, если все прогоны прошли сверку.
         */
        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val apiKey = System.getenv("DEEPSEEK_API_KEY")
                ?: error("API ключ не настроен")

            val format = GenerationFormat.fromKey(request.format)
            val runs = (request.runs ?: AppConfig.DEFAULT_RUNS).coerceIn(1, AppConfig.MAX_RUNS)
            val maxTokens = (request.maxTokens ?: AppConfig.DEFAULT_MAX_TOKENS)
                .coerceIn(1, AppConfig.MAX_TOKEN_CEILING)
            val model = request.model ?: AppConfig.DEFAULT_MODEL
            val dogsOnly = request.dogsOnly ?: true
            val stopSequences = request.stop
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.take(AppConfig.MAX_STOP_SEQUENCES)
                ?.takeIf { it.isNotEmpty() }

            // Сообщения модели: при включённой настройке — инструкция эксперта
            // по породам собак, затем инструкция о формате ответа. История диалога
            // не передаётся — каждый вопрос (все его прогоны) проверяется изолированно.
            val messages = buildList {
                if (dogsOnly) add(ChatMessage("system", DogBreedChat.SYSTEM_INSTRUCTION))
                format.instruction?.let { add(ChatMessage("system", it)) }
                add(ChatMessage("user", request.message))
            }

            // Прогоны одного и того же вопроса
            val outcomes = (1..runs).map {
                runSingle(
                    apiKey = apiKey,
                    model = model,
                    messages = messages,
                    format = format,
                    initialMaxTokens = maxTokens,
                    stopSequences = stopSequences
                )
            }

            // Формируем итоговое сообщение: настройки и статус сверки — сверху
            val stopLine = stopSequences?.joinToString(", ") ?: "нет"
            val report = buildString {
                appendLine("=== Настройки ===")
                appendLine("Формат: ${format.label}")
                appendLine("Прогонов одного вопроса: $runs")
                appendLine("Максимум токенов: $maxTokens")
                appendLine("Стоп-слова: $stopLine")
                appendLine("Только вопросы о собаках: ${if (dogsOnly) "да" else "нет"}")
                appendLine()
                appendLine("=== Сверка формата с заданным ===")
                outcomes.forEachIndexed { index, outcome ->
                    val status = if (outcome.ok) "OK" else "ОШИБКА: ${outcome.reason}"
                    val suffix = when {
                        outcome.truncatedAtCap ->
                            " (обрыв по лимиту даже при ${outcome.finalBudget})"
                        outcome.attempts > 1 ->
                            " (лимит увеличен до ${outcome.finalBudget})"
                        else -> ""
                    }
                    appendLine("Прогон ${index + 1} — $status$suffix")
                }
                appendLine()
                appendLine("=== Ответ ===")
            }

            val failed = outcomes.mapIndexedNotNull { index, outcome ->
                if (outcome.ok) null else index + 1 to outcome
            }

            // Печатаем только неудачные прогоны; если все корректны — последний успешный
            val finalText = if (failed.isEmpty()) {
                val last = outcomes.last()
                report +
                    "--- Прогон ${outcomes.size} (последний успешный) ---\n" +
                    last.reply
            } else {
                report + buildString {
                    appendLine("--- Неудачные прогоны (формат не совпал) ---")
                    failed.forEachIndexed { index, (runNumber, outcome) ->
                        if (index > 0) appendLine()
                        appendLine("--- Прогон $runNumber ---")
                        appendLine(outcome.reply)
                    }
                }
            }

            // Отправляем ответ клиенту
            call.respond(
                ChatResponse(success = true, reply = finalText)
            )
        }
    }
}

/**
 * Результат одного прогона вопроса.
 * @param reply Текст ответа модели.
 * @param ok Прошёл ли ответ сверку формата.
 * @param reason Причина рассогласования формата (если не прошёл).
 * @param attempts Сколько запросов к DeepSeek потребовалось (повторы при обрыве по лимиту).
 * @param finalBudget Итоговый лимит токенов после округления и повышения.
 * @param truncatedAtCap Ответ всё ещё обрывается по лимиту даже на максимальном бюджете.
 */
private data class RunOutcome(
    val reply: String,
    val ok: Boolean,
    val reason: String?,
    val attempts: Int,
    val finalBudget: Int,
    val truncatedAtCap: Boolean
)

/**
 * Один прогон вопроса: запрос к DeepSeek с автоувеличением лимита токенов.
 * Если модель оборвала ответ по лимиту (finish_reason = length), бюджет округляется
 * вверх и увеличивается, пока ответ не завершится осмысленно или не будет достигнут
 * максимальный лимит.
 */
private suspend fun runSingle(
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    format: GenerationFormat,
    initialMaxTokens: Int,
    stopSequences: List<String>?
): RunOutcome {
    var budget = initialMaxTokens
    var attempts = 0
    var reply: String? = null
    var finishReason: String? = null

    while (true) {
        attempts++
        val response = client.post("https://api.deepseek.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = budget,
                    temperature = AppConfig.DEFAULT_TEMPERATURE,
                    stop = stopSequences,
                    responseFormat = if (format.jsonMode) GenerationFormat.STRICT_JSON_MODE else null
                )
            )
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.body<String>()
            error("DeepSeek API error: ${response.status} - $errorBody")
        }

        val responseBody = response.body<DeepSeekResponse>()
        if (responseBody.choices.isEmpty()) {
            error("DeepSeek вернул пустой список ответов")
        }

        reply = responseBody.choices.first().message.content
        finishReason = responseBody.choices.first().finishReason

        // Ответ оборвался по лимиту и лимит ещё можно увеличить —
        // повторяем с округлённым вверх бюджетом до смыслового завершения
        if (finishReason == "length" && budget < AppConfig.MAX_TOKEN_CEILING) {
            budget = minOf(AppConfig.MAX_TOKEN_CEILING, roundUpTokens(budget * 3 / 2))
            continue
        }
        break
    }

    val mismatchReason = format.verify(reply ?: "")
    return RunOutcome(
        reply = reply ?: "",
        ok = mismatchReason == null,
        reason = mismatchReason,
        attempts = attempts,
        finalBudget = budget,
        truncatedAtCap = finishReason == "length" && budget >= AppConfig.MAX_TOKEN_CEILING
    )
}
