package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ от DeepSeek API.
 */
@Serializable
data class DeepSeekResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        val message: ChatMessage,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int,
        @SerialName("completion_tokens") val completionTokens: Int,
        @SerialName("total_tokens") val totalTokens: Int
    )
}
