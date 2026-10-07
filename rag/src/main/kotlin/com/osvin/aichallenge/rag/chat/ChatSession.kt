package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.rag.HeuristicReranker
import com.osvin.aichallenge.rag.LlmReranker
import com.osvin.aichallenge.rag.RagPipeline
import com.osvin.aichallenge.rag.SimilarityFilter
import com.osvin.aichallenge.rag.SourceFinder
import com.osvin.aichallenge.rag.Sufficiency
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Настройки разговора: поиск, окно истории и порог достаточности — одним объектом.
 *
 * Значения по умолчанию повторяют настройки дня 24 ([com.osvin.aichallenge.rag.RagCli]): порог 0.40,
 * десять кандидатов из векторного поиска, три фрагмента в контекст, эвристический второй этап.
 * Это не копирование ради удобства, а условие сравнимости: если чат ищет иначе, чем день 24,
 * разницу в ответах нельзя списать ни на разговор, ни на память — она может быть целиком эффектом
 * поиска. Окно истории в пять реплик — те же десять сообщений, что окно генерации в `:agent`
 * (`GenerationSettings.DEFAULT_WINDOW_MESSAGES = 10`): держать историю длиннее память не помогает,
 * а стоит токенов, и долговременное состояние в дне 25 несёт не история, а память задачи.
 */
data class ChatSettings(
    /** Порог фильтра; он же порог достаточности — как в дне 24. */
    val threshold: Double = DEFAULT_THRESHOLD,
    val retrievalTopK: Int = DEFAULT_RETRIEVAL_TOP_K,
    val finalTopK: Int = DEFAULT_FINAL_TOP_K,
    /** Второй этап: `"heuristic"`, `"llm"` или `"none"`. */
    val rerank: String = DEFAULT_RERANK,
    /** Сколько последних реплик уходит в промпт; всё, что старше, держит память задачи. */
    val windowTurns: Int = DEFAULT_WINDOW_TURNS
) {

    /**
     * Конвейер поиска с переписыванием, **выключенным** внутри конвейера.
     *
     * `rewriter = null` — не упущение, а разделение труда: переписыватель дня 22 видит только вопрос
     * и не знает истории, а чату нужен запрос, раскрытый по прошлым репликам ([ChatRewriter]).
     * Оставить здесь переписыватель значило бы переписывать запрос дважды, и второй раз — затирая
     * раскрытые местоимения обратно.
     */
    fun pipeline(finder: SourceFinder, llm: LlmClient, model: String): RagPipeline = RagPipeline(
        finder = finder,
        rewriter = null,
        filter = SimilarityFilter(threshold),
        reranker = when (rerank) {
            RERANK_NONE -> null
            RERANK_LLM -> LlmReranker(llm, model)
            else -> HeuristicReranker()
        },
        finalTopK = finalTopK
    )

    /** Строка настроек для отчёта: по ней прогон воспроизводится, не заглядывая в код. */
    val description: String
        get() = "порог $threshold · второй этап $rerank · Top-$finalTopK из $retrievalTopK · " +
            "окно истории $windowTurns"

    companion object {

        const val RERANK_NONE = "none"
        const val RERANK_LLM = "llm"

        /** Те же числа, что у запуска дня 24: см. KDoc [ChatSettings]. */
        const val DEFAULT_THRESHOLD = 0.40
        const val DEFAULT_RETRIEVAL_TOP_K = 10
        const val DEFAULT_FINAL_TOP_K = 3
        const val DEFAULT_RERANK = "heuristic"
        const val DEFAULT_WINDOW_TURNS = 5
    }
}

/**
 * Мини-чат с RAG и памятью задачи: держит историю, ищет контекст на каждый вопрос, отвечает
 * с источниками и обновляет память задачи.
 *
 * Ход разговора устроен так, и порядок здесь — часть решения:
 *
 * 1. **Память обновляется первой.** Ограничение, названное человеком в этой же реплике, должно
 *    действовать на неё, а не со следующего хода; кроме того, память нужна переписывателю запроса.
 * 2. **Запрос готовится по разговору** ([ChatRewriter]) — неполный вопрос раскрывается по истории.
 * 3. **Поиск — один вызов** конвейера дня 24: фильтр, второй этап, Top-K, перенумерация с единицы.
 * 4. **Достаточность** проверяется до вопроса модели ([Sufficiency]) и уходит в ход отдельным
 *    решением: по ней видно, был отказ из-за поиска или из-за цитат.
 * 5. **Ответ** ([ChatAnswerer]) проверяется так же, как в дне 24: утверждения с дословными цитатами,
 *    источники — из метаданных чанков.
 * 6. **Ход записывается в историю** целиком, вместе с памятью до и после.
 *
 * История хранится здесь и обрезается окном ([ChatSettings.windowTurns]) при сборке промпта, а не
 * выбрасывается: полный список ходов нужен отчёту и странице, окно — только модели. Память задачи
 * не обрезается никогда: она и есть то, что не даёт длинному разговору потерять предмет.
 *
 * Разговор сериализован ([Mutex]): страница может получить две реплики подряд, и параллельные ходы
 * перемешали бы историю, память и нумерацию ходов. Живой прогон сценариев идёт шаг за шагом,
 * но защита нужна не для него, а для страницы, где два человека с клавиатурой — обычное дело.
 */
class ChatSession(
    private val llm: LlmClient,
    private val model: String,
    private val pipeline: RagPipeline,
    val settings: ChatSettings = ChatSettings(),
    private val keeper: MemoryKeeper = MemoryKeeper(llm, model),
    private val rewriter: ChatRewriter = ChatRewriter(llm, model),
    private val answerer: ChatAnswerer = ChatAnswerer(llm, model)
) {

    private val mutex = Mutex()
    private val turns = ArrayList<ChatTurn>()

    /** Память задачи: то, что разговор зафиксировал и что уходит в каждый запрос к модели. */
    var memory: TaskMemory = TaskMemory()
        private set

    /** Имя модели ответа: попадает в отчёт и в подпись страницы. */
    val modelName: String get() = model

    /** Полная история разговора — для отчёта и страницы. */
    fun turns(): List<ChatTurn> = turns.toList()

    /** Последний ход: странице он нужен, чтобы показать ответ до следующего опроса. */
    fun lastTurn(): ChatTurn? = turns.lastOrNull()

    /**
     * Очередная реплика человека: поиск, ответ, память.
     *
     * Метод целиком под замком, а не по частям: ход — это единица состояния (номер, память, история),
     * и промежуточное состояние между «память обновлена» и «ход записан» не должно быть видно никому.
     */
    suspend fun ask(question: String): ChatTurn = mutex.withLock {
        val before = memory
        val window = window()
        val update = keeper.update(before, window, question)
        val query = rewriter.rewrite(question, update.memory, window)
        val found = pipeline.find(query.text)
        val confidence = Sufficiency.check(found, settings.threshold)
        val answer = answerer.answer(question, found, update.memory, window, confidence)

        val turn = ChatTurn(
            index = turns.size + 1,
            question = question,
            query = query.text,
            queryNote = query.note,
            found = found,
            confidence = confidence,
            kind = answer.kind,
            answer = answer.answer,
            sources = answer.sources,
            claims = answer.claims,
            checks = answer.checks,
            refusal = answer.refusal,
            refusalReason = answer.refusalReason,
            error = answer.error,
            memoryBefore = before,
            memory = update.memory,
            memoryNote = update.note,
            answerMessages = answer.messages,
            memoryMessages = update.messages,
            rewriteMessages = query.messages,
            raw = answer.raw,
            memoryRaw = update.raw,
            promptTokens = answer.promptTokens,
            completionTokens = answer.completionTokens,
            millis = answer.millis,
            memoryMillis = update.millis,
            rewriteMillis = query.millis
        )
        memory = update.memory
        turns += turn
        turn
    }

    /**
     * Стирает разговор и память задачи: новый разговор начинается с чистого листа.
     *
     * Память стирается вместе с историей намеренно: память задачи описывает **этот** разговор, и,
     * оставшись от прошлого, она превратилась бы в правило, которого человек не устанавливал,
     * — а именно этого память и не должна делать.
     */
    suspend fun reset() = mutex.withLock {
        turns.clear()
        memory = TaskMemory()
    }

    /** Окно истории для промпта: последние реплики, не считая текущей (её добавляет промпт). */
    private fun window(): List<ChatTurn> =
        if (settings.windowTurns <= 0) emptyList() else turns.takeLast(settings.windowTurns)
}
