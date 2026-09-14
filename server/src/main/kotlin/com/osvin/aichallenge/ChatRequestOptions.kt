package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.ContextStrategy
import com.osvin.aichallenge.models.ChatRequest

/**
 * Отображение запроса клиента в настройки агента.
 *
 * Единственное место, где поля запроса превращаются в [AgentOptions]: имена полей
 * запроса и стратегию видно здесь, и здесь же задано поведение по умолчанию —
 * клиент, который ничего не прислал, получает сжатие истории, как в дне 9.
 */
fun ChatRequest.toAgentOptions(): AgentOptions = AgentOptions(
    model = model,
    maxTokens = maxTokens,
    stop = stop,
    temperature = temperature,
    systemPrompt = systemPrompt,
    history = history ?: emptyList(),
    sessionId = sessionId,
    strategy = ContextStrategy.fromWire(strategy),
    windowMessages = windowMessages,
    branches = branches ?: emptyList(),
    activeBranchId = branchId
)
