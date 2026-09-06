package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Запрос, отправляемый напрямую в DeepSeek API.
 */
@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stop: List<String>? = null,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    val stream: Boolean = false
)
