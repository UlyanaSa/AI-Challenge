package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * Расход токенов одного ответа — то, что посчитал агент на сервере.
 * Приходит в поле `tokens` ответа `/v1/chat/completions`; приложение печатает
 * его в лог платформы (на Android — в logcat), поэтому числа видно там, где
 * их смотрят, даже когда сервер запущен не в этом терминале.
 *
 * @param systemPrompt Токены своего system prompt.
 * @param history Токены всей истории диалога.
 * @param request Токены текущего запроса пользователя.
 * @param promptEstimate Оценка всего запроса до отправки.
 * @param promptTokens Фактические токены запроса по данным API.
 * @param replyTokens Фактические токены ответа: у thinking-моделей сюда входят рассуждения.
 * @param replyReasoningTokens Из них токены рассуждений.
 * @param contextWindow Контекстное окно модели.
 * @param promptWindowShare Какую долю окна занял запрос: 0.0–1.0.
 * @param replyFinishReason Причина остановки: «stop» — модель договорила,
 *        «length» — кончился бюджет `max_tokens`.
 * @param costUsd Стоимость в USD; null, если тариф модели не опубликован.
 */
@Serializable
data class TokenReport(
    @SerialName("system_prompt_tokens") val systemPrompt: Int = 0,
    @SerialName("history_tokens") val history: Int = 0,
    @SerialName("request_tokens") val request: Int = 0,
    @SerialName("prompt_estimate") val promptEstimate: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("reply_tokens") val replyTokens: Int = 0,
    @SerialName("reply_reasoning_tokens") val replyReasoningTokens: Int = 0,
    @SerialName("context_window") val contextWindow: Int = 0,
    @SerialName("prompt_window_share") val promptWindowShare: Double = 0.0,
    @SerialName("reply_finish_reason") val replyFinishReason: String? = null,
    @SerialName("cost_usd") val costUsd: Double? = null
) {
    /**
     * Одна запись лога на весь отчёт: характеристики идут отдельными строками,
     * но запись одна — так блок читается целиком, а не рассыпается по logcat.
     */
    fun logEntry(model: String): String = listOf(
        "[agent] Запрос → $model",
        "[agent] system prompt: $systemPrompt ток.",
        "[agent] история: $history ток.",
        "[agent] текущий вопрос: $request ток.",
        "[agent] всего (оценка): $promptEstimate ток.",
        "[agent] окно модели: $contextWindow ток.",
        "[agent] Ответ ← $model",
        "[agent] токенов запроса: $promptTokens (факт), $promptEstimate (оценка)",
        "[agent] токенов ответа: $replyTokens (рассуждения: $replyReasoningTokens)",
        "[agent] finish: ${replyFinishReason ?: "неизвестно"}",
        "[agent] окно занято: ${fixed(promptWindowShare * 100, 4)}%",
        "[agent] цена: ${costUsd?.let { "$" + fixed(it, 6) } ?: "тариф не опубликован"}"
    ).joinToString("\n")
}

/**
 * Число с фиксированным количеством знаков после точки: в common-коде
 * нет `String.format`, а в логе нужны те же «0.0042%» и «$0.000059», что на сервере.
 */
private fun fixed(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (value * scale).roundToLong()
    return "${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
}
