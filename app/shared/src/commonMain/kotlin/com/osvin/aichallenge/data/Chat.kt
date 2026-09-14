package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Чат — отдельный диалог пользователя с агентом.
 *
 * @param id Идентификатор чата; он же — идентификатор сессии агента на сервере,
 * поэтому он хранится в БД устройства и не меняется, пока чат не удалён.
 * @param title Заголовок чата: берётся из первого сообщения пользователя.
 * @param createdAt Время создания в миллисекундах.
 * @param updatedAt Время последнего сообщения: по нему чаты сортируются в списке.
 */
@Serializable
data class Chat(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
