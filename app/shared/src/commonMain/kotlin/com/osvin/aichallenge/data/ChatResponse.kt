package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Ответ от чат-сервера.
 * @param success Статус успешности операции.
 * @param reply Текст ответа от нейросети.
 * @param usage Метаданные об использовании токенов (если передаются).
 */
@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null
)
