package com.osvin.aichallenge.models

import com.osvin.aichallenge.agent.TokenReport
import kotlinx.serialization.Serializable

/**
 * Обобщенный ответ сервера клиенту.
 *
 * @param usage Расход токенов в формате DeepSeek API.
 * @param tokens Расход токенов по частям запроса, ответу, границам модели и стоимости.
 * @param model Имя модели, которая ответила: в локальном режиме сервер подставляет модель
 *        Ollama, поэтому интерфейс показывает то, чем ответили, а не то, что выбрал клиент.
 * @param provider Провайдер ответа: `local` или `cloud` — чтобы было видно, что генерация
 *        не уходила в облако.
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
