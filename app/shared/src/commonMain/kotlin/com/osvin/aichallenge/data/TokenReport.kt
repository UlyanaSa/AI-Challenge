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
 * @param historyRawTokens Токены всей истории, которую прислал клиент, без сжатия.
 * @param summaryTokens Токены сводки, которой сжали историю.
 * @param foldedMessages Сколько сообщений свёрнуто в сводку.
 * @param compressionTokens Токены вызова, которым строилась сводка (запрос + ответ).
 * @param compressionCostUsd Цена этого вызова в USD; null, если тариф не опубликован.
 * @param strategy Стратегия управления контекстом этого ответа (см. [ContextStrategy]).
 * @param windowMessages Размер окна стратегий «скользящее окно» и «память агента»;
 *        0 — стратегия окно не использует.
 * @param droppedMessages Сколько сообщений отброшено окном (стратегия «скользящее окно»).
 * @param excludedMessages Сколько сообщений диалога не попало в путь активной ветки.
 * @param branchId Активная ветка диалога; null — основная линия.
 * @param memory Память агента: записи по слоям, токены слоёв, цена обновления.
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
    @SerialName("cost_usd") val costUsd: Double? = null,
    @SerialName("history_raw_tokens") val historyRawTokens: Int = 0,
    @SerialName("summary_tokens") val summaryTokens: Int = 0,
    @SerialName("folded_messages") val foldedMessages: Int = 0,
    @SerialName("compression_tokens") val compressionTokens: Int = 0,
    @SerialName("compression_cost_usd") val compressionCostUsd: Double? = null,
    @SerialName("strategy") val strategy: String = ContextStrategy.DEFAULT.wire,
    @SerialName("window_messages") val windowMessages: Int = 0,
    @SerialName("dropped_messages") val droppedMessages: Int = 0,
    @SerialName("excluded_messages") val excludedMessages: Int = 0,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("memory") val memory: MemoryReport = MemoryReport()
) {
    /**
     * Одна запись лога на весь отчёт: характеристики идут отдельными строками,
     * но запись одна — так блок читается целиком, а не рассыпается по logcat.
     * Строки о сжатии истории появляются только когда сервер действительно
     * свернул сообщения, строки об окне, ветке и памяти — когда их применила
     * выбранная стратегия: без этого запись остаётся прежней.
     */
    fun logEntry(model: String): String = buildList {
        add("[agent] Запрос → $model")
        add("[agent] стратегия: ${ContextStrategy.fromWire(strategy).title}")
        add("[agent] system prompt: $systemPrompt ток.")
        add("[agent] история: $history ток.")
        if (foldedMessages > 0) {
            add("[agent] сжатие истории: свёрнуто $foldedMessages сообщ. в сводку $summaryTokens ток.")
            add(
                "[agent] история к отправке: $history ток. вместо $historyRawTokens ток. " +
                    "(экономия ${historyRawTokens - history} ток., " +
                    "${fixed(100.0 * (historyRawTokens - history) / historyRawTokens, 1)}%)"
            )
        }
        if (windowMessages > 0) {
            add("[agent] окно: последние $windowMessages сообщ., отброшено $droppedMessages")
        }
        if (branchId != null) {
            add("[agent] активная ветка: $branchId (вне её пути: $excludedMessages сообщ.)")
        }
        add("[agent] текущий вопрос: $request ток.")
        add("[agent] всего (оценка): $promptEstimate ток.")
        add("[agent] окно модели: $contextWindow ток.")
        add("[agent] Ответ ← $model")
        add("[agent] токенов запроса: $promptTokens (факт), $promptEstimate (оценка)")
        add("[agent] токенов ответа: $replyTokens (рассуждения: $replyReasoningTokens)")
        add("[agent] finish: ${replyFinishReason ?: "неизвестно"}")
        add("[agent] окно занято: ${fixed(promptWindowShare * 100, 4)}%")
        add("[agent] цена: ${costUsd?.let { "$" + fixed(it, 6) } ?: "тариф не опубликован"}")
        if (memory.working.isNotEmpty() || memory.longTerm.isNotEmpty()) {
            add(
                "[agent] память: рабочая ${memory.working.size} шт. (${memory.workingTokens} ток.), " +
                    "долговременная ${memory.longTerm.size} шт. (${memory.longTermTokens} ток.)"
            )
            // Подписи и пояснения типов живут только в каталоге снимка, отчёт его
            // не несёт: в логе тип печатается как есть, без клиентской таблицы названий
            memory.working.forEach { record ->
                add("[agent] - ${record.layer} | ${record.value}")
            }
            memory.longTerm.forEach { record ->
                add("[agent] - ${record.layer} | ${record.value}")
            }
            add(
                "[agent] краткосрочная: ${memory.shortTermMessages} сообщ. в запросе, " +
                    "не ушло ${memory.shortTermDropped}"
            )
            if (memory.rejected > 0) {
                add("[agent] отклонено записей: ${memory.rejected}")
            }
            if (memory.evicted > 0) {
                add("[agent] вытеснено записей: ${memory.evicted}")
            }
            if (memory.updateTokens > 0) {
                add(
                    "[agent] обновление памяти: ${memory.updateTokens} ток., " +
                        "цена ${memory.updateCostUsd?.let { "$" + fixed(it, 6) } ?: "тариф не опубликован"}"
                )
            }
        }
    }.joinToString("\n")
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
