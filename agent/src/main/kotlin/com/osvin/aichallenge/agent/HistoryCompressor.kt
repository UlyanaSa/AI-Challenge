package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest

/**
 * План отправки истории диалога.
 *
 * @param recent Сообщения, которые уходят в модель как есть.
 * @param toFold Сообщения, которые пора свернуть в сводку (пусто, если ещё рано).
 * @param summary Сводка, которая уйдёт в запрос вместо свёрнутых сообщений.
 * @param foldedMessages Сколько сообщений уже свёрнуто в [summary].
 */
data class HistoryPlan(
    val recent: List<ChatMessage>,
    val toFold: List<ChatMessage>,
    val summary: StoredSummary?,
    val foldedMessages: Int
)

/**
 * Управление контекстом диалога.
 *
 * Последние [keepLastMessages] сообщений уходят в модель как есть — по ним виден
 * текущий разговор. Всё, что старше, сводится в сводку: она строится, когда
 * накопилось [compressStep] таких сообщений, и дальше заменяет их в запросе.
 * Сводка живёт в [SummaryStore] отдельно от сообщений.
 *
 * @param keepLastMessages Сколько последних сообщений не сжимаем.
 * @param compressStep Сколько старых сообщений накапливаем до построения сводки.
 */
class HistoryCompressor(
    private val keepLastMessages: Int = DEFAULT_KEEP_LAST_MESSAGES,
    private val compressStep: Int = DEFAULT_COMPRESS_STEP
) {

    /** Что отправляем как есть и что сворачиваем в сводку. */
    fun plan(history: List<ChatMessage>, stored: StoredSummary?): HistoryPlan {
        // Клиент очистил диалог: сообщений меньше, чем свёрнуто в сводке, — сводка от прошлого разговора.
        val summary = stored?.takeIf { it.foldedMessages <= history.size }
        val foldedMessages = summary?.foldedMessages ?: 0
        val unfolded = history.drop(foldedMessages)

        if (unfolded.size <= keepLastMessages) {
            return HistoryPlan(unfolded, emptyList(), summary, foldedMessages)
        }

        val older = unfolded.dropLast(keepLastMessages)
        return if (older.size >= compressStep) {
            HistoryPlan(unfolded.takeLast(keepLastMessages), older, summary, foldedMessages)
        } else {
            // Сводку строить рано: отправляем всё как есть, ничего не теряя.
            HistoryPlan(unfolded, emptyList(), summary, foldedMessages)
        }
    }

    /** Сообщение со сводкой: уходит в модель вместо свёрнутых сообщений. */
    fun summaryMessage(summary: StoredSummary): ChatMessage =
        ChatMessage(SYSTEM_ROLE, "Сводка предыдущего диалога:\n${summary.text}")

    /**
     * Служебный запрос к модели: обновить сводку по новым сообщениям.
     * Прежняя сводка уходит в него как вход, а не как часть запроса пользователя.
     */
    fun summaryRequest(
        model: String,
        previous: StoredSummary?,
        messages: List<ChatMessage>,
        maxTokens: Int = SUMMARY_MAX_TOKENS
    ): DeepSeekRequest = DeepSeekRequest(
        model = model,
        messages = listOf(
            ChatMessage(SYSTEM_ROLE, SUMMARY_SYSTEM_PROMPT),
            ChatMessage("user", buildString {
                previous?.let { append("Прежняя сводка:\n${it.text}\n\n") }
                append("Новые сообщения диалога:\n")
                messages.forEach { append("${it.role}: ${it.content}\n") }
            })
        ),
        maxTokens = maxTokens
    )

    companion object {
        /** Сколько последних сообщений отправляем без сжатия. */
        const val DEFAULT_KEEP_LAST_MESSAGES = 10

        /** Через сколько накопившихся старых сообщений строим сводку. */
        const val DEFAULT_COMPRESS_STEP = 10

        /**
         * Бюджет ответа на построение сводки. У thinking-модели рассуждения тратят
         * тот же бюджет, поэтому он взят с запасом: маленький бюджет даёт пустой ответ.
         */
        const val SUMMARY_MAX_TOKENS = 2000

        /** Роль служебных сообщений: сводка и задание для модели. */
        private const val SYSTEM_ROLE = "system"

        /** Инструкция для служебного запроса сводки. */
        private const val SUMMARY_SYSTEM_PROMPT =
            "Ты ведёшь конспект диалога для передачи контекста. Сожми сообщения в короткую сводку: " +
                "факты, числа, договорённости, принятые решения и открытые вопросы. " +
                "Пиши тезисами, без вступлений и обращений, не длиннее 200 слов. " +
                "Если дана прежняя сводка — объедини её с новыми сообщениями и ничего важного не теряй."
    }
}
