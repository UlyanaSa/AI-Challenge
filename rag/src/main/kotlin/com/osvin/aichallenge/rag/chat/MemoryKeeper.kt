package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.ResponseFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Обновление памяти задачи служебным вызовом модели: разговор → цель, уточнения, правила, термины.
 *
 * Память задачи обновляет модель, а не код, и это то же решение, что в днях 10–13: цель и термины
 * человек называет живой речью («давай разберём, кем был Степан Трофимович»), и вытащить их из этой
 * речи правилами — значит написать разбор естественного языка. Модель уже читает разговор ради
 * ответа, и второй её роли — прочитать тот же разговор ради памяти — нужен отдельный вызов: ответ
 * занят вопросом дня, а память — состоянием разговора, и смешивать их в одном ответе значило бы
 * доверять памяти модели, которая в этот момент формулирует ответ, то есть оценивать себя самого.
 *
 * Вызовы идут **перед** ответом, а не после: ограничение, названное в этой реплике («дальше только
 * по первой главе»), должно действовать уже на неё, — а не со следующего хода. Плата за это — три
 * обращения к модели на реплику вместо одного; в отчёте время каждого видно отдельно, и цена
 * разговора не прячется за общим временем ответа.
 *
 * Ломать разговор неудачное обновление памяти не должно: неразобранный ответ или ошибка API — это
 * прежняя память и пометка в отчёте ([Update.note]), а не исключение наружу. Тот же порядок, что
 * у служебных вызовов `:agent` ([com.osvin.aichallenge.agent.MemoryExtractor]): служебный шаг
 * деградирует, диалог продолжается.
 */
class MemoryKeeper(
    private val llm: LlmClient,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE
) {

    /**
     * Что стало с памятью на этом ходу: новое состояние и всё, что нужно отчёту и логу.
     *
     * [memory] — память после слияния ([TaskMemory.merge]), а не то, что вернула модель: слияние
     * защищает от потери, и видеть в отчёте сырой ответ модели было бы неправдой о том, что поедет
     * в следующий запрос. [raw] хранится рядом именно поэтому — по нему видно, что модель сказала
     * сама, а что осталось от прежнего состояния.
     */
    data class Update(
        val memory: TaskMemory,
        val raw: String?,
        /** Строка для отчёта: обновлена, без изменений или почему не обновлена. */
        val note: String,
        val messages: List<ChatMessage>,
        val millis: Long,
        val promptTokens: Int?,
        val completionTokens: Int?
    )

    /** Читает разговор и возвращает память задачи после этого хода. */
    suspend fun update(memory: TaskMemory, window: List<ChatTurn>, question: String): Update {
        val messages = ChatPrompt.memory(memory, window, question)
        val started = System.nanoTime()
        val response = try {
            llm.complete(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    reasoningEffort = REASONING_EFFORT,
                    responseFormat = ResponseFormat.JSON_OBJECT
                )
            )
        } catch (error: LlmApiException) {
            return Update(
                memory = memory,
                raw = null,
                note = "память не обновлена: модель ответила ошибкой (${error.message})",
                messages = messages,
                millis = millisSince(started),
                promptTokens = null,
                completionTokens = null
            )
        }

        val millis = millisSince(started)
        val usage = response.usage
        val raw = response.choices.firstOrNull()?.message?.content
        val parsed = parse(raw)
        if (parsed == null) {
            return Update(
                memory = memory,
                raw = raw,
                note = "память не обновлена: ответ модели не разобрался",
                messages = messages,
                millis = millis,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens
            )
        }

        val merged = memory.merge(parsed)
        return Update(
            memory = merged,
            raw = raw,
            note = if (merged == memory) "память без изменений" else "память обновлена",
            messages = messages,
            millis = millis,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens
        )
    }

    /**
     * Разбор ответа модели; `null` — ответ не разобрался, и память надо оставить прежней.
     *
     * Разбирается тем же терпимым способом, что и служебные ответы `:agent`
     * ([com.osvin.aichallenge.agent.MemoryExtractor.parse]): объект вырезается из текста
     * ([ChatJson]), неизвестные поля игнорируются, отсутствующие получают значения по умолчанию.
     * Пустой объект `{}` — это валидный ответ и «в память ничего не добавилось»: он ничем
     * не отличается от честного «нового важного нет», и запрещать его незачем.
     */
    fun parse(raw: String?): TaskMemoryUpdate? {
        val body = ChatJson.objectOf(raw) ?: return null
        val reply = try {
            JSON.decodeFromString<Reply>(body.toString())
        } catch (error: Exception) {
            return null
        }
        return TaskMemoryUpdate(
            goal = reply.goal,
            clarifications = reply.clarifications,
            constraints = reply.constraints,
            terms = reply.terms.mapNotNull { term ->
                term.term.takeIf { it.isNotBlank() }?.let { TaskTerm(it, term.meaning) }
            }
        )
    }

    /** Форма ответа модели: поля названы так же, как в задании [ChatPrompt.MEMORY_SYSTEM]. */
    @Serializable
    private data class Reply(
        val goal: String? = null,
        val clarifications: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val terms: List<Term> = emptyList()
    )

    @Serializable
    private data class Term(val term: String = "", val meaning: String = "")

    private companion object {

        /**
         * Бюджет ответа памяти — 4000, как у извлечения памяти в `:agent`.
         *
         * Память задачи — маленький JSON, и своего бюджета ей хватило бы в разы меньше. Но бюджет
         * здесь ограничивает весь ответ целиком, а рассуждение отключено только на время этого
         * вызова; при включённом оно съело бы его и вернуло пустую строку — тот самый отказ, из-за
         * которого в дне 22 переписывание запроса молча перестало работать. Запас дешевле, чем
         * память, которая перестала обновляться и об этом не сказала.
         */
        const val DEFAULT_MAX_TOKENS = 4000

        /** Ноль: память не должна меняться от прогона к прогону, как и ответы дня 22. */
        const val DEFAULT_TEMPERATURE = 0.0

        /**
         * Режим без рассуждения: извлечение памяти — пересказ уже сказанного, и мысли здесь
         * не нужны. Урок дня 22 ([com.osvin.aichallenge.rag.LlmQueryRewriter]): у рассуждающей модели
         * рассуждение тратит бюджет ответа и возвращает пустой результат без гарантии.
         */
        const val REASONING_EFFORT = "none"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000
    }
}
