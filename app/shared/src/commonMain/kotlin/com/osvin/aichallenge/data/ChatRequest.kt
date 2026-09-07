package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Модель запроса обычного ответа в чате (без вариантов).
 * @param message Текущее сообщение пользователя.
 */
@Serializable
data class ChatRequest(
    val message: String
)
