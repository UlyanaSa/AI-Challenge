package com.osvin.aichallenge.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Расход токенов одного запуска агента.
 *
 * Части запроса (system prompt, история, текущий запрос) и оценка ответа —
 * локальный счётчик; [promptTokens], [replyTokens] и [replyReasoningTokens] —
 * факт из `usage` ответа API. Стоимость считается по факту и тарифу модели.
 *
 * @param systemPrompt Токены своего system prompt.
 * @param profileTokens Токены блока профиля пользователя: он уходит системным сообщением
 *        при любой стратегии, поэтому считается отдельно от system prompt и истории.
 * @param history Токены всей истории диалога.
 * @param request Токены текущего запроса пользователя.
 * @param promptEstimate Оценка всего запроса до отправки (обвязка чата + части).
 * @param promptTokens Фактические токены запроса по данным API.
 * @param replyEstimate Оценка токенов ответа локальным счётчиком.
 * @param replyTokens Фактические токены ответа по данным API.
 * @param replyReasoningTokens Из них токены рассуждений: у thinking-моделей они
 *        входят в ответ и тратят бюджет `max_tokens` до появления текста.
 * @param contextWindow Контекстное окно модели.
 * @param maxOutputTokens Максимальное значение `max_tokens` у модели.
 * @param promptWindowShare Какую долю окна занял запрос: 0.0–1.0.
 * @param replyFinishReason Причина остановки ответа: «stop» — модель договорила,
 *        «length» — кончился бюджет `max_tokens` (у thinking-моделей его может
 *        съесть рассуждениями, и тогда текст ответа пустой).
 * @param costUsd Стоимость запуска в USD; null, если тариф не опубликован.
 * @param historyRawTokens Токены всей истории, которую прислал клиент: без сжатия.
 * @param summaryTokens Токены сводки, которая заменила свёрнутые сообщения.
 * @param foldedMessages Сколько сообщений свёрнуто в сводку.
 * @param compressionTokens Токены служебного вызова, которым строилась сводка (запрос + ответ).
 * @param compressionCostUsd Стоимость этого вызова в USD; null — тариф не опубликован.
 * @param strategy Стратегия управления контекстом этого запуска (см. [ContextStrategy]).
 * @param windowMessages Размер окна стратегий «скользящее окно» и «память агента»; 0 — не применялось.
 * @param droppedMessages Сколько сообщений отброшено окном (стратегия «скользящее окно»).
 * @param excludedMessages Сколько сообщений диалога не попало в путь активной ветки.
 * @param branchId Активная ветка диалога; null — основная линия.
 * @param memory Память агента: записи по слоям, токены слоёв, цена обновления.
 */
@Serializable
data class TokenReport(
    @SerialName("system_prompt_tokens") val systemPrompt: Int,
    @SerialName("profile_tokens") val profileTokens: Int = 0,
    @SerialName("history_tokens") val history: Int,
    @SerialName("request_tokens") val request: Int,
    @SerialName("prompt_estimate") val promptEstimate: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("reply_estimate") val replyEstimate: Int,
    @SerialName("reply_tokens") val replyTokens: Int,
    @SerialName("reply_reasoning_tokens") val replyReasoningTokens: Int,
    @SerialName("context_window") val contextWindow: Int,
    @SerialName("max_output_tokens") val maxOutputTokens: Int,
    @SerialName("prompt_window_share") val promptWindowShare: Double,
    @SerialName("reply_finish_reason") val replyFinishReason: String? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
    @SerialName("history_raw_tokens") val historyRawTokens: Int = history,
    @SerialName("summary_tokens") val summaryTokens: Int = 0,
    @SerialName("folded_messages") val foldedMessages: Int = 0,
    @SerialName("compression_tokens") val compressionTokens: Int = 0,
    @SerialName("compression_cost_usd") val compressionCostUsd: Double? = null,
    @SerialName("strategy") val strategy: String = ContextStrategy.FULL.wire,
    @SerialName("window_messages") val windowMessages: Int = 0,
    @SerialName("dropped_messages") val droppedMessages: Int = 0,
    @SerialName("excluded_messages") val excludedMessages: Int = 0,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("memory") val memory: MemoryReport = MemoryReport()
)

/**
 * Контекст не влезает в окно модели: запрос вместе с бюджетом ответа больше окна.
 * Агент проверяет это до обращения к API, чтобы не платить за заведомо
 * неуспешный запрос.
 */
class ContextOverflowException(
    val promptTokens: Int,
    val maxOutputTokens: Int,
    val contextWindow: Int
) : IllegalStateException(
    "Контекст переполнен: $promptTokens токенов запроса + $maxOutputTokens токенов ответа " +
        "больше окна модели в $contextWindow токенов. Сократите историю диалога или max_tokens."
)

/**
 * Модель не вернула текст ответа: у thinking-модели весь бюджет `max_tokens` ушёл
 * на рассуждения (`finish_reason = length`), либо модель ответила пустой строкой.
 * Пустой ответ бесполезен клиенту, поэтому агент не отдаёт его как успешный:
 * в чате вместо пустого сообщения появляется причина.
 */
class EmptyReplyException(
    val completionTokens: Int,
    val reasoningTokens: Int,
    val finishReason: String?
) : IllegalStateException(
    if (finishReason == "length") {
        "Модель не вернула текст: весь бюджет ответа ($completionTokens токенов) ушёл на рассуждения " +
            "($reasoningTokens токенов). Увеличьте max_tokens и отправьте запрос снова."
    } else {
        "Модель вернула пустой ответ (finish: ${finishReason ?: "неизвестно"}). Попробуйте отправить запрос снова."
    }
)
