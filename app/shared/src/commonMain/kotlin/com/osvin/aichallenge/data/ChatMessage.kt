package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Представляет отдельное сообщение в чате.
 * @param role Кто отправил сообщение (пользователь, ассистент или система).
 * @param content Текст сообщения.
 * @param timestamp Время отправки в миллисекундах.
 * @param branchId Ветка диалога, к которой относится сообщение; null — основная
 *        линия. Метка служебная: по ней собирается путь активной ветки
 *        (см. [DialogBranches]), а сам текст сообщения она не меняет.
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val branchId: String? = null
)
