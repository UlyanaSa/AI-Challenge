package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Ошибка от чат-сервера: так сервер отвечает на переполнение контекста,
 * пустой ответ модели и сбой провайдера.
 * @param success Всегда false: ответ пришёл с ошибкой.
 * @param error Текст ошибки от сервера с причиной и подсказкой, что делать.
 */
@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val error: String? = null
)
