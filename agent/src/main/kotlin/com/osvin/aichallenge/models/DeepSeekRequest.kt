package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Запрос, отправляемый напрямую в DeepSeek API.
 *
 * @param tools Инструменты, которые предлагаются модели: пустой список и `null` —
 *        одно и то же, ключа `tools` в запросе нет (значение по умолчанию не пишется).
 */
@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
    val tools: List<ToolDeclaration>? = null
)
