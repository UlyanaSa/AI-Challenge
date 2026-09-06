package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Модель запроса к чат-серверу.
 * @param message Текущее сообщение пользователя.
 * @param history История переписки для контекста нейросети.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val history: List<ChatMessage> = emptyList()
)
