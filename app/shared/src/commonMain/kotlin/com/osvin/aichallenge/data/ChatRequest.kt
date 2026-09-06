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
 * @param systemPrompt Содержимое дополнительного системного сообщения модели.
 *                     Сейчас содержит только инструкцию «отвечать о собаках».
 */
@Serializable
data class ChatRequest(
    val message: String,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val runs: Int? = null,
    val format: String? = null,
    val systemPrompt: String? = null
)
