package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Обобщенный ответ сервера клиенту.
 */
@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null
)
