package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Представляет отдельное сообщение в чате.
 * @param role Кто отправил сообщение (пользователь, ассистент или система).
 * @param content Текст сообщения.
 * @param timestamp Время отправки в миллисекундах.
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
