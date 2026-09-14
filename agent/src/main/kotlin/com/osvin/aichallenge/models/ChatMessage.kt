package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Модель сообщения для внутреннего обмена сообщениями на сервере.
 *
 * @param role Роль автора сообщения: user, assistant или system.
 * @param content Текст сообщения.
 * @param branchId Ветка диалога, к которой относится сообщение: null — основная
 *        линия. Метка служебная: клиент помечает ею сообщения, чтобы агент собрал
 *        путь активной ветки, а в API сообщения уходят без неё.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val branchId: String? = null
)
