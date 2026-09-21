package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Модель запроса к чат-серверу.
 *
 * @param message Текущее сообщение пользователя.
 * @param model Идентификатор модели DeepSeek (см. [GenerationSettings.MODELS]).
 * @param maxTokens Максимальное количество токенов в ответе — ограничение длины ответа.
 * @param stop Стоп-слова завершения генерации.
 * @param temperature Температура генерации (0.0–2.0); по умолчанию 0.7.
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели.
 *        Null или пусто — его роль играет первое сообщение диалога: агент берёт его
 *        из [history] (на первом ходу диалога — из текущего сообщения).
 * @param history Предыдущие сообщения диалога (user/assistant), старые — первыми.
 * @param sessionId Идентификатор сессии диалога: по нему сервер держит сводку истории
 *        и слои памяти. Null — сервер обойдётся без сводки.
 * @param strategy Стратегия управления контекстом ([ContextStrategy.wire]): что из
 *        истории уходит в модель — `full`, `sliding_window`, `memory`, `branches`
 *        или `summary`. Null — сервер считает поведение дня 9 («сжатие в сводку»).
 * @param windowMessages Сколько последних сообщений отправляют стратегии со скользящим
 *        окном и памятью ([ContextStrategy.usesWindow]).
 * @param branchId Активная ветка диалога: её путь уходит в модель; null — основная линия.
 *        Уезжает только со стратегией «ветки диалога».
 * @param branches Ветки диалога: структура и точки ветвления; нужны той же стратегии.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage>? = null,
    val sessionId: String? = null,
    val strategy: String? = null,
    val windowMessages: Int? = null,
    val branchId: String? = null,
    val branches: List<DialogBranch>? = null
)
