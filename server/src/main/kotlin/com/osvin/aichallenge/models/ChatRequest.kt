package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Запрос от клиента к серверу.
 * Позволяет настраивать параметры модели DeepSeek.
 * История диалога не передаётся: каждый вопрос проверяется изолированно
 * (заданное число прогонов одного и того же вопроса).
 *
 * @param message Текущее сообщение пользователя (вопрос о породе собаки).
 * @param model Имя используемой модели (например, "deepseek-chat").
 * @param maxTokens Максимальное количество токенов в ответе.
 * @param stop Стоп-слова завершения генерации: модель останавливается при их появлении.
 * @param runs Количество прогонов одного и того же вопроса для сверки формата ответа.
 * @param format Канонический ключ формата ответа (см. GenerationFormat).
 * @param dogsOnly Отвечать только на вопросы о собаках (иначе — вежливый отказ).
 */
@Serializable
data class ChatRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val runs: Int? = null,
    val format: String? = null,
    val dogsOnly: Boolean? = null
)
