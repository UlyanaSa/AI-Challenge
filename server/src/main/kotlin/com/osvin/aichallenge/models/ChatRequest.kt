package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Запрос от клиента к серверу.
 * Позволяет настраивать параметры модели DeepSeek.
 *
 * @param message Текущее сообщение пользователя.
 * @param history История диалога.
 * @param model Имя используемой модели (например, "deepseek-chat").
 * @param temperature Параметр случайности ответа (0.0 - 2.0).
 * @param maxTokens Максимальное количество токенов в ответе.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val history: List<ChatMessage> = emptyList(),
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)
