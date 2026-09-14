package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * HTTP-транспорт агента к API DeepSeek.
 *
 * Единственное место, где агент касается сети: агент формулирует [DeepSeekRequest],
 * транспорт доставляет его по HTTP и возвращает разобранный [DeepSeekResponse].
 * HTTP-клиент передаётся снаружи — сервер отдаёт общий клиент, тесты — подменный движок.
 */
class DeepSeekClient(
    private val apiKey: String,
    private val http: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT
) : LlmClient {

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        val response = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw LlmApiException(
                status = response.status.value,
                message = "DeepSeek API error: ${response.status} - ${response.bodyAsText()}"
            )
        }

        val body = response.body<DeepSeekResponse>()
        check(body.choices.isNotEmpty()) { "DeepSeek вернул пустой список ответов" }
        return body
    }

    companion object {
        /** Адрес эндпоинта chat/completions в API DeepSeek. */
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    }
}
