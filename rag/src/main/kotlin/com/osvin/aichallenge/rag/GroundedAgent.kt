package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.ResponseFormat

/**
 * Проверка достаточности контекста перед обращением к модели (§8, §9 задания).
 *
 * Проверка стоит **до** модели, и в этом её смысл: если спросить модель при слабом контексте, она
 * ответит по памяти, и никакая проверка цитат потом этого не поймёт — цитат просто не будет,
 * а ответ будет. Поэтому решение об отказе принимается по числам поиска, а не по ответу модели.
 *
 * Порог берётся тот же, что у фильтрации (задание §8: «использовать threshold, подобранный
 * на предыдущем этапе»), и это не совпадение, а требование сравнения: два разных порога дали бы
 * систему, у которой «достаточно» и «прошло фильтр» — разные условия, и объяснить отказ было бы
 * нечем. Состояние [Confidence.BELOW_THRESHOLD] достижимо, когда фильтр выключен или стоит ниже
 * порога достаточности: тогда в контекст попадает фрагмент, которого для уверенного ответа мало.
 *
 * Близость берётся у лучшего фрагмента **контекста**, а не выдачи: модель видит контекст, и вопрос
 * «хватает ли того, что она увидит» решается по нему. У выдачи тот же максимум мог бы принадлежать
 * фрагменту, который порог отсеял.
 */
object Sufficiency {

    fun check(found: Retrieval, threshold: Double): ConfidenceCheck {
        val candidates = found.trace?.candidates.orEmpty()
        val context = if (found.trace == null) found.sources
        else candidates.filter { it.passed }.map { it.source }
        val best = context.maxOfOrNull { it.similarity }

        val decision = when {
            candidates.isEmpty() && found.sources.isEmpty() -> Confidence.NO_CHUNKS
            context.isEmpty() -> Confidence.ALL_FILTERED
            best == null || best < threshold -> Confidence.BELOW_THRESHOLD
            else -> Confidence.SUFFICIENT
        }
        return ConfidenceCheck(decision, context.size, best, threshold)
    }
}

/**
 * Grounded-агент: вопрос и найденный контекст → ответ с источниками и цитатами либо отказ.
 *
 * Агент отвечает за три вещи, и каждая — требование задания. **Достаточность**: слабый контекст
 * не доходит до модели вовсе ([Sufficiency]), и отказ не сопровождается источниками, потому что
 * задание запрещает добирать цитаты ради отчёта (§10). **Источники**: список источников собирается
 * здесь из метаданных чанков ([SourceRef.of]) — модель называет только номер фрагмента, и придумать
 * страницу или главу она не может, потому что этих полей в её ответе нет (§4). **Цитаты**: каждая
 * цитата проверяется по тексту чанка ([Citations.validate]), и утверждение с непроверенной цитатой
 * в ответ не попадает: показать его как подтверждённое значило бы выдать галлюцинацию за проверенный
 * ответ (§11).
 *
 * Поиск сюда приходит готовым ([Retrieval]): конвейер дня 23 уже сделал переписывание, фильтр
 * и второй этап, и grounded-режим работает **на той же выдаче**, что обычный режим. Это условие
 * сравнения дня 24: разница между «предыдущим» и «grounded» ответом должна быть разницей этапов
 * проверки, а не разницей поиска. Второй прогон поиска дал бы другую выдачу, и сравнение мерило бы
 * шум эмбеддингов.
 */
class GroundedAgent(
    private val llm: LlmClient,
    private val model: String,
    /** Порог достаточности: то же число, что у фильтра, — см. [Sufficiency]. */
    private val threshold: Double,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    /**
     * Просить ли у API ответ строго в виде JSON-объекта.
     *
     * Отдельный параметр, а не константа: прогон с ним и без него — это два разных условия одного
     * и того же режима, и если модель начнёт спотыкаться о формат, отключение покажет, в формате ли
     * дело. Разбор ответа от этого флага не зависит: непонятный ответ и с ним, и без него считается
     * отказом.
     */
    private val jsonFormat: Boolean = true
) {

    suspend fun answer(question: String, found: Retrieval): GroundedAnswer {
        val confidence = Sufficiency.check(found, threshold)
        if (!confidence.sufficient) {
            // Модель не спрашивается вовсе: ответить на этот же контекст она может только по памяти,
            // а это и есть то, что задание просит не делать.
            return GroundedAnswer(
                question = question,
                retrieval = found,
                confidence = confidence,
                answer = null,
                sources = emptyList(),
                claims = emptyList(),
                checks = emptyList(),
                refusal = Refusal.NO_CONTEXT,
                messages = emptyList(),
                raw = null,
                promptTokens = null,
                completionTokens = null,
                finishReason = null,
                millis = 0,
                note = confidence.decision.title
            )
        }

        val messages = Prompt.grounded(question, found.sources)
        val started = System.nanoTime()
        val response = llm.complete(
            DeepSeekRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
                responseFormat = if (jsonFormat) ResponseFormat.JSON_OBJECT else null
            )
        )
        val millis = (System.nanoTime() - started) / 1_000_000
        val choice = requireNotNull(response.choices.firstOrNull()) {
            "Модель вернула ответ без вариантов"
        }

        val raw = choice.message.content
        val parsed = Citations.parse(raw, found.sources)
        val checks = parsed.claims.map { claim -> Citations.validate(claim, found.sources) }
        val confirmed = parsed.claims.filterIndexed { index, _ -> checks[index].found }

        // Отказ после модели — это тоже корректный исход, и он не несёт ни источников, ни цитат:
        // подтверждённых утверждений нет, а показывать неподтверждённые нельзя.
        if (parsed.insufficient || confirmed.isEmpty()) {
            return GroundedAnswer(
                question = question,
                retrieval = found,
                confidence = confidence,
                answer = null,
                sources = emptyList(),
                claims = emptyList(),
                checks = checks,
                refusal = Refusal.NO_EVIDENCE,
                messages = messages,
                raw = raw,
                promptTokens = response.usage?.promptTokens,
                completionTokens = response.usage?.completionTokens,
                finishReason = choice.finishReason,
                millis = millis,
                note = parsed.reason ?: "ни одна цитата не подтвердилась в тексте фрагментов"
            )
        }

        val dropped = checks.size - confirmed.size
        return GroundedAnswer(
            question = question,
            retrieval = found,
            confidence = confidence,
            answer = parsed.answer,
            sources = SourceRef.of(confirmed, found),
            claims = confirmed,
            checks = checks,
            refusal = null,
            messages = messages,
            raw = raw,
            promptTokens = response.usage?.promptTokens,
            completionTokens = response.usage?.completionTokens,
            finishReason = choice.finishReason,
            millis = millis,
            note = if (dropped > 0) "отброшено цитат: $dropped из ${checks.size}" else null
        )
    }

    private companion object {

        /**
         * Бюджет ответа — 8192, как у [RagAgent]: два режима должны спрашивать модель одинаково.
         *
         * Меньший бюджет здесь был бы подменой условия сравнения: grounded-ответ длиннее обычного
         * (в нём ещё и утверждения с цитатами), и урезанный лимит дал бы отказ там, где обычный режим
         * ответил, — а разница приписывалась бы проверкам дня 24.
         */
        const val DEFAULT_MAX_TOKENS = 8192

        /** Ноль — условие контрольного сравнения, как и в дне 22. */
        const val DEFAULT_TEMPERATURE = 0.0
    }
}
