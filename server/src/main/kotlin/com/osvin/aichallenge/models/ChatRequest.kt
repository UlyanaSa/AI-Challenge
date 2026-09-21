package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Запрос от клиента к серверу.
 * Позволяет настраивать параметры модели DeepSeek и передавать контекст диалога.
 *
 * @param message Текущее сообщение пользователя (вопрос о породе собаки).
 * @param model Имя используемой модели (например, "deepseek-chat").
 * @param maxTokens Максимальное количество токенов в ответе.
 * @param stop Стоп-слова завершения генерации: модель останавливается при их появлении.
 * @param temperature Температура генерации (0.0–2.0); по умолчанию 0.7.
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели.
 *        Null или пусто — его роль играет первое сообщение диалога (см. [history]).
 * @param history Предыдущие сообщения диалога (user/assistant), старые — первыми.
 * @param sessionId Идентификатор сессии диалога: по нему сервер хранит сводку истории
 *        и рабочую память задачи.
 * @param strategy Стратегия управления контекстом: `full`, `sliding_window`, `memory`,
 *        `branches` или `summary` (см. `ContextStrategy`). От неё же зависит, какие слои
 *        памяти ведёт диалог. Null — `summary`, как в дне 9.
 * @param windowMessages Сколько последних сообщений отправляют стратегии окна и памяти.
 * @param branchId Активная ветка диалога: её путь уходит в модель; null — основная линия.
 * @param branches Ветки диалога: точки ветвления (нужны стратегии «ветки»).
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
