package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Модель запроса к чат-серверу.
 *
 * @param message Текущее сообщение пользователя.
 * @param model Идентификатор модели DeepSeek (см. [GenerationSettings.MODELS]).
 * @param maxTokens Максимальное количество токенов в ответе — ограничение длины ответа.
 * @param stop Стоп-слова завершения генерации.
 * @param temperature Температура генерации (0.0–2.0); по умолчанию 0.7.
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели.
 * @param history Предыдущие сообщения диалога (user/assistant), старые — первыми.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage>? = null
)
