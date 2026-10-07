package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.rag.tidyQuery

/**
 * Запрос для поиска: неполный вопрос раскрывается по истории, полный уходит почти как есть.
 *
 * День 22 переписывал вопрос ради формулировки — разговорная просьба превращалась в запрос к базе
 * («Расскажи о Степане Трофимовиче» → «Степан Трофимович Верховенский»). В чате к этому добавляется
 * то, чего у одиночного вопроса нет: **неполный** вопрос, опирающийся на прошлые реплики. «А сколько
 * ему было лет?» не ищется вовсе — в нём нет ни одного имени, и поиск вернёт что попало, а ответ
 * будет выглядеть уверенно. Поэтому переписывание здесь идёт по истории (правило 2 задания
 * [ChatPrompt.REWRITE_SYSTEM]) и по значениям терминов из памяти задачи: «сын» человек мог закрепить
 * за одним персонажем, и поиск должен получить именно его.
 *
 * Конвейер дня 22 для этого не подходит как есть: его переписыватель
 * ([com.osvin.aichallenge.rag.LlmQueryRewriter]) видит один вопрос и истории не знает. Поэтому
 * в [com.osvin.aichallenge.rag.RagPipeline] переписывание отключено (`rewriter = null`), а запрос
 * готовит этот класс до вызова поиска — иначе один и тот же вопрос переписывался бы дважды, и второй
 * раз уже без истории. Чистка ответа модели общая ([tidyQuery]) — два разных правила чистки дали бы
 * два разных формата запроса к одному и тому же поиску.
 *
 * Ошибка API или непонятный ответ не останавливают диалог: в поиск уходит исходный вопрос с пометкой
 * в отчёте. Плохой запрос — это риск не найти нужное, а падение вызова — потерянный ход разговора,
 * и второе хуже первого.
 */
class ChatRewriter(
    private val llm: LlmClient,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE
) {

    /**
     * Запрос, ушедший в поиск, вместе с пометкой, откуда он взялся.
     *
     * [text] отличается от вопроса человека — и это то, что видно в отчёте рядом с самим вопросом;
     * без второй строки «нашлось не то» неотличимо от «спросили не то». [changed] отдельным полем,
     * а не сравнением на месте: ответ модели — тоже [text], и сравнивать строки в отчёте значило бы
     * повторять сравнение, которое уже сделано здесь.
     */
    data class Query(
        val text: String,
        val changed: Boolean,
        /** Строка для отчёта: что сделало переписывание или почему его не было. */
        val note: String?,
        val messages: List<ChatMessage>,
        val millis: Long
    )

    /** Готовит запрос по текущему вопросу, памяти задачи и последним репликам. */
    suspend fun rewrite(question: String, memory: TaskMemory, window: List<ChatTurn>): Query {
        val messages = ChatPrompt.rewrite(question, memory, window)
        val started = System.nanoTime()
        val response = try {
            llm.complete(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    reasoningEffort = REASONING_EFFORT
                )
            )
        } catch (error: LlmApiException) {
            return Query(
                text = question,
                changed = false,
                note = "запрос не переписан: модель ответила ошибкой (${error.message})",
                messages = messages,
                millis = millisSince(started)
            )
        }

        val millis = millisSince(started)
        val query = tidyQuery(response.choices.firstOrNull()?.message?.content)
        if (query == null) {
            return Query(
                text = question,
                changed = false,
                note = "запрос не переписан: ответ модели пуст",
                messages = messages,
                millis = millis
            )
        }
        return Query(
            text = query,
            changed = query != question,
            note = if (query == question) "запрос не изменился" else "запрос раскрыт по разговору",
            messages = messages,
            millis = millis
        )
    }

    private companion object {

        /** Бюджет на строку запроса — тот же, что у переписывателя дня 22 (там он и обоснован). */
        const val DEFAULT_MAX_TOKENS = 512

        /**
         * Режим без рассуждения — по уроку дня 22: извлечение ключевых слов мыслей не требует,
         * а мысли тратят бюджет ответа и могут вернуть пустую строку без всякой гарантии.
         */
        const val REASONING_EFFORT = "none"

        const val DEFAULT_TEMPERATURE = 0.0

        private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000
    }
}
