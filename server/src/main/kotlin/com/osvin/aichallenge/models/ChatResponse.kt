package com.osvin.aichallenge.models

import com.osvin.aichallenge.agent.TokenReport
import kotlinx.serialization.Serializable

/**
 * Обобщенный ответ сервера клиенту.
 *
 * @param usage Расход токенов в формате DeepSeek API.
 * @param tokens Расход токенов по частям запроса, ответу, границам модели и стоимости.
 */
@Serializable
data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val usage: Map<String, Int>? = null,
    val tokens: TokenReport? = null
)
