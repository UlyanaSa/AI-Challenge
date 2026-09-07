package com.osvin.aichallenge

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.measureTime

/**
 * Затраты токенов на один запуск варианта (суммарно по всем попыткам).
 */
internal data class TokenCost(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Результат завершённой генерации у DeepSeek.
 * @param text Текст ответа.
 * @param attempts Сколько запросов потребовалось (повторы при обрыве по лимиту).
 * @param finalBudget Итоговый лимит токенов после округления и повышения.
 * @param truncatedAtCap Ответ всё ещё обрывается по лимиту даже на максимальном бюджете.
 * @param usage Суммарное количество токенов по всем попыткам.
 * @param elapsedMillis Суммарное время всех попыток в миллисекундах.
 */
internal data class CallOutcome(
    val text: String,
    val attempts: Int,
    val finalBudget: Int,
    val truncatedAtCap: Boolean,
    val usage: TokenCost,
    val elapsedMillis: Long
)

/**
 * Один запуск варианта: запрос к DeepSeek с автоувеличением лимита токенов.
 * Если модель оборвала ответ по лимиту (finish_reason = length), бюджет округляется
 * вверх и увеличивается (2000 -> 3000 -> 4500 -> 6800 -> 8192), пока ответ не
 * завершится осмысленно или не будет достигнут максимальный лимит.
 */
internal suspend fun deepSeekComplete(
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    initialMaxTokens: Int = AppConfig.DEFAULT_MAX_TOKENS
): CallOutcome {
    var budget = initialMaxTokens.coerceIn(1, AppConfig.MAX_TOKEN_CEILING)
    var attempts = 0
    var reply: String? = null
    var finishReason: String? = null
    var promptTokens = 0
    var completionTokens = 0
    var totalTokens = 0
    var elapsedMillis = 0L

    while (true) {
        attempts++
        val attemptTime = measureTime {
            val response = client.post("https://api.deepseek.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(
                    DeepSeekRequest(
                        model = model,
                        messages = messages,
                        maxTokens = budget,
                        temperature = AppConfig.DEFAULT_TEMPERATURE,
                        stop = null,
                        responseFormat = null
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

            responseBody.usage?.let { usage ->
                promptTokens += usage.promptTokens
                completionTokens += usage.completionTokens
                totalTokens += usage.totalTokens
            }
        }
        elapsedMillis += attemptTime.inWholeMilliseconds

        // Ответ оборвался по лимиту и лимит ещё можно увеличить —
        // повторяем с округлённым вверх бюджетом до смыслового завершения
        if (finishReason == "length" && budget < AppConfig.MAX_TOKEN_CEILING) {
            budget = minOf(AppConfig.MAX_TOKEN_CEILING, roundUpTokens(budget * 3 / 2))
            continue
        }
        break
    }

    return CallOutcome(
        text = reply ?: "",
        attempts = attempts,
        finalBudget = budget,
        truncatedAtCap = finishReason == "length" && budget >= AppConfig.MAX_TOKEN_CEILING,
        usage = TokenCost(promptTokens, completionTokens, totalTokens),
        elapsedMillis = elapsedMillis
    )
}
