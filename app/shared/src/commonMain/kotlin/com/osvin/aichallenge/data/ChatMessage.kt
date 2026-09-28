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
 * @param tools Вызовы инструментов, показанные под ответом ассистента или в служебной
 *        записи ([MessageRole.TOOL]). Хранятся вместе с сообщением, а не отдельным списком
 *        на экране: вызов относится именно к этому ответу, и после перезапуска приложения
 *        видно, откуда взялись данные. В историю, уезжающую модели, они не входят
 *        (см. [MessageRole.TOOL]).
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val branchId: String? = null,
    val tools: List<ToolCallRecord> = emptyList()
)
