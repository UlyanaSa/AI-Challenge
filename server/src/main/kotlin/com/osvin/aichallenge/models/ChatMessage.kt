package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Модель сообщения для внутреннего обмена сообщениями на сервере.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)
