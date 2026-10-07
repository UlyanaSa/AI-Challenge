package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.ResponseFormat
import com.osvin.aichallenge.rag.Citations
import com.osvin.aichallenge.rag.Claim
import com.osvin.aichallenge.rag.ConfidenceCheck
import com.osvin.aichallenge.rag.QuoteCheck
import com.osvin.aichallenge.rag.Refusal
import com.osvin.aichallenge.rag.Retrieval
import com.osvin.aichallenge.rag.SourceRef

/**
 * Ответ чата: то же, что grounded-ответ дня 24, но с историей, памятью задачи и двумя видами ответа.
 *
 * Механика ответа взята из дня 24 целиком, и это главное решение класса. Модель возвращает JSON
 * с утверждениями и цитатами, разбор — [Citations.parse], проверка — [Citations.validate] по тексту
 * фрагментов, источники собирает система из метаданных ([SourceRef.of]). Придумать источник
 * или подтвердить утверждение пересказом здесь так же невозможно, как в дне 24, — и не потому,
 * что так строже, а потому, что мера дня («можно ли проверить ответ») в разговоре не меняется.
 * Меняется только то, что модель видит ещё и историю с памятью задачи ([ChatPrompt.chat]).
 *
 * Две вещи отличают этот ответ от одиночного. Первая: слабый контекст. День 24 при недостаточном
 * контексте не спрашивал модель вовсе, и это правильно для вопроса о книге — но в чате тот же
 * вопрос может быть о самом разговоре, и молчаливый отказ на «что мы решаем?» был бы ответом
 * не на заданный вопрос. Поэтому модель всё-таки спрашивается, но **без фрагментов**: ей говорится,
 * что отбор не оставил ничего, и разрешается ответить только по памяти задачи. Вторая: цитаты
 * при таком ответе не засчитываются (см. [answer]) — опираться на фрагменты, которых модели
 * не давали, она не может по построению, а номер из старых ответов в истории означал бы совсем
 * другой фрагмент.
 *
 * Ошибка API не превращается в отказ: отказ — это решение системы о нехватке сведений, а ошибка —
 * недошедший запрос, и [Answer.error] уносит её в отчёт отдельной строкой. Записать лимит или сеть
 * в «источников нет» значило бы испортить меру дня сбоем окружения.
 */
class ChatAnswerer(
    private val llm: LlmClient,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE
) {

    /**
     * Всё, что ответ даёт ходу: вид ответа, текст, источники, проверки и цена вызова.
     *
     * [kind] и [error] взаимоисключающие и оба могут быть `null` только у отказа: отказ — это
     * «ответа нет», и вид ответа у него отсутствует по смыслу, а не по недосмотру.
     */
    data class Answer(
        val kind: ChatAnswerKind?,
        val answer: String?,
        val sources: List<SourceRef>,
        val claims: List<Claim>,
        val checks: List<QuoteCheck>,
        val refusal: Refusal?,
        val refusalReason: String?,
        val error: String?,
        val messages: List<ChatMessage>,
        val raw: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val millis: Long,
        val note: String?
    )

    /**
     * Отвечает на вопрос по найденным фрагментам и памяти задачи.
     *
     * `confidence` приходит снаружи, а не считается здесь: решение о достаточности принимает сессия
     * тем же порогом, что и фильтр, — и в отчёте оно стоит рядом с ответом как отдельное решение,
     * а не как побочный эффект класса ответа.
     */
    suspend fun answer(
        question: String,
        found: Retrieval,
        memory: TaskMemory,
        window: List<DialogueMessage>,
        confidence: ConfidenceCheck
    ): Answer {
        // Контекста не хватило — фрагменты модели не показываются вовсе: показать слабые значило бы
        // получить ответ по ним с цитатами, которые проверку пройдут, а уверенности не дадут.
        val emptyContext = !confidence.sufficient
        val messages = ChatPrompt.chat(question, found.sources, memory, window, emptyContext)

        val started = System.nanoTime()
        val response = try {
            llm.complete(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    responseFormat = ResponseFormat.JSON_OBJECT
                )
            )
        } catch (error: LlmApiException) {
            return Answer(
                kind = null,
                answer = null,
                sources = emptyList(),
                claims = emptyList(),
                checks = emptyList(),
                refusal = null,
                refusalReason = null,
                error = "обращение к модели не удалось: ${error.message}",
                messages = messages,
                raw = null,
                promptTokens = null,
                completionTokens = null,
                millis = millisSince(started),
                note = null
            )
        }
        val millis = millisSince(started)
        val usage = response.usage
        val raw = response.choices.firstOrNull()?.message?.content

        val parsed = Citations.parse(raw.orEmpty(), found.sources)
        val checks = parsed.claims.map { claim -> Citations.validate(claim, found.sources) }
        // Утверждения принимаются только у ответа по фрагментам: у ответа о разговоре фрагментов
        // на входе не было, и любая «цитата» в нём — это номер из прошлых ответов, то есть чужой
        // источник, выданный за подтверждение.
        val confirmed = if (emptyContext) emptyList() else parsed.claims.filterIndexed { index, _ -> checks[index].found }

        if (DIALOGUE_KIND == ChatJson.field(raw, "kind") && parsed.answer != null) {
            return Answer(
                kind = ChatAnswerKind.DIALOGUE,
                answer = parsed.answer,
                sources = emptyList(),
                claims = emptyList(),
                checks = checks,
                refusal = null,
                refusalReason = null,
                error = null,
                messages = messages,
                raw = raw,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens,
                millis = millis,
                note = "ответ о разговоре: источники — память задачи"
            )
        }

        if (parsed.insufficient || confirmed.isEmpty()) {
            return Answer(
                kind = null,
                answer = null,
                sources = emptyList(),
                claims = emptyList(),
                checks = checks,
                refusal = if (emptyContext) Refusal.NO_CONTEXT else Refusal.NO_EVIDENCE,
                refusalReason = parsed.reason ?: when {
                    emptyContext -> "контекста для ответа не хватило: ${confidence.decision.title}"
                    else -> "ни одна цитата не подтвердилась в тексте фрагментов"
                },
                error = null,
                messages = messages,
                raw = raw,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens,
                millis = millis,
                note = null
            )
        }

        val dropped = checks.size - confirmed.size
        return Answer(
            kind = ChatAnswerKind.BASE,
            answer = parsed.answer,
            sources = SourceRef.of(confirmed, found),
            claims = confirmed,
            checks = checks,
            refusal = null,
            refusalReason = null,
            error = null,
            messages = messages,
            raw = raw,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            millis = millis,
            note = if (dropped > 0) "отброшено цитат: $dropped из ${checks.size}" else null
        )
    }

    private companion object {

        /** Вид ответа о разговоре в ответе модели; всё остальное считается ответом о книге. */
        const val DIALOGUE_KIND = "dialogue"

        /**
         * Бюджет ответа — 16384, вдвое больше, чем у [com.osvin.aichallenge.rag.GroundedAgent].
         *
         * Разница вынужденная и найдена живым прогоном: ответ чата длиннее одиночного ответа дня 24
         * (в промпте лежат история и память, а модель рассуждает перед ответом), и на первой реплике
         * сценария JSON оборвался на середине списка цитат — незакрытая скобка. Разбор такого ответа
         * честно даёт отказ («цитат в нём нет»), но это потерянный ход: человек поставил цель,
         * а получил «не знаю» из-за того, что модель не договорила. Ограничение это потолок, а не
         * расход: платим только за сказанное, а от обрезки JSON избавляемся как от класса отказов.
         * Предел модели проверен живым запросом: 16384 принимается и не отвергается сервером.
         */
        const val DEFAULT_MAX_TOKENS = 16384

        /** Ноль: ответы сравниваются между прогонами, как и в днях 22–24. */
        const val DEFAULT_TEMPERATURE = 0.0

        private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000
    }
}
