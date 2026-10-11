package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Ответ от чат-сервера.
 * @param success Статус успешности операции.
 * @param reply Текст ответа от нейросети.
 * @param usage Метаданные об использовании токенов (если передаются).
 * @param tokens Отчёт агента о расходе токенов: печатается в лог платформы.
 * @param model Имя модели, которой ответил сервер: в локальном режиме это загруженная
 *        модель Ollama, а не та, что выбрана в настройках, — показывать в шапке выбор
 *        клиента значило бы называть модель, которая на вопрос не отвечала.
 * @param provider Провайдер ответа (`local` или `cloud`): по нему видно, покидал ли
 *        запрос эту машину.
 */
@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null,
    val tokens: TokenReport? = null,
    val model: String? = null,
    val provider: String? = null
)
