package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Модель запроса на обычный ответ в чате (без вариантов).
 * @param message Текущее сообщение пользователя.
 */
@Serializable
data class ChatRequest(
    val message: String
)
