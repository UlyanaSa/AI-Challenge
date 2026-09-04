package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,  // "user" или "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ChatRequest(
    val message: String,
    val history: List<ChatMessage> = emptyList()
)

@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null
)

sealed class ChatState {
    object Idle : ChatState()
    object Loading : ChatState()
    data class Success(val response: ChatResponse) : ChatState()
    data class Error(val message: String) : ChatState()
}