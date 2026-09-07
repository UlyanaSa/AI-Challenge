package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Модель запроса к чат-серверу.
 * История диалога не передаётся: каждый вопрос проверяется изолированно
 * (заданное число прогонов одного и того же вопроса).
 * @param message Текущее сообщение пользователя (вопрос о породе собаки).
 * @param maxTokens Максимальное количество токенов в ответе — ограничение длины ответа.
 * @param stop Стоп-слова завершения генерации.
 * @param runs Количество прогонов одного и того же вопроса для сверки формата ответа.
 * @param format Канонический ключ формата ответа (см. [ResponseFormat.key]).
 * @param temperature Температура генерации (0.0–2.0); по умолчанию 0.7.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val runs: Int? = null,
    val format: String? = null,
    val temperature: Double? = null
)
