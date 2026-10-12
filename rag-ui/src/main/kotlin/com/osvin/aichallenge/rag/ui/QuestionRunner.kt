package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.GroundedAgent
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.RagAgent
import com.osvin.aichallenge.rag.RagIndex
import com.osvin.aichallenge.rag.RagPipeline
import com.osvin.aichallenge.rag.Retrieval
import com.osvin.aichallenge.rag.SimilarityFilter
import com.osvin.aichallenge.rag.StageConfig
import kotlinx.coroutines.CancellationException

/**
 * Один клиент модели, собранный в конвейер дня 23: три режима ответа на контрольный вопрос.
 *
 * Место одно на всю страницу — его используют и прогон десяти вопросов ([RagSession]), и сравнение
 * моделей ([CompareSession]). Разница между ними только в том, чей клиент сюда попал и сколько раз
 * прогон повторяется; сама сборка агентов и порядок обращений внутри вопроса — общие, и это
 * условие сравнения, а не удобство: если у одной модели переписывание идёт до поиска, а у другой
 * после, разница в ответах будет разницей сборки, а не моделей.
 *
 * Порядок внутри вопроса зафиксирован и повторяет прогон в консоли: без базы → базовый RAG →
 * улучшенный RAG. Поиск улучшенного режима отдаётся наружу ([find]) отдельно от ответа ([improved]),
 * потому что им пользуется день 24: там grounded-ответ и предыдущий ответ обязаны считаться
 * **на одной выдаче**, а второй поиск дал бы другую.
 *
 * Этапы называются готовыми строками в [config]: отчёт печатает «модель qwen3:1.7b», и собирать
 * это имя второй раз из варианта значило бы завести второе место, где этап превращается в строку.
 */
internal class QuestionRunner(
    client: LlmClient,
    model: String,
    settings: RunSettings,
    index: RagIndex
) {

    private val rewriter = settings.rewriterOf(client, model)
    private val reranker = settings.rerankerOf(client, model)

    /** Базовый режим дня 22: Top-K по близости, без этапов. */
    private val baseAgent = RagAgent(client, index.retriever(settings.baselineTopK), model)

    /**
     * Конвейер дня 23: переписывание запроса, расширенная выдача, порог и второй этап.
     *
     * Отдаётся наружу целиком ([find] и [improved] разными методами, а не спрятанными в один):
     * дню 24 нужна ровно одна выдача на два ответа, и собрать её заново изнутри агента он не может.
     */
    private val pipeline = RagPipeline(
        finder = index.retriever(settings.retrievalTopK),
        rewriter = rewriter,
        filter = SimilarityFilter(settings.threshold),
        reranker = reranker,
        finalTopK = settings.finalTopK
    )

    private val improvedAgent = RagAgent(client, pipeline, model)

    /**
     * Grounded-ответ дня 24: отказ вместо догадки, когда подтверждать нечем.
     *
     * Порог достаточности — тот же, что у фильтра (§8): второе число для «хватает ли контекста»
     * разошлось бы с первым, и объяснить отказ разными порогами было бы нечем.
     */
    val groundedAgent = GroundedAgent(client, model, settings.threshold)

    /** Чем считали: имена реализаций этапов уходят в шапку отчёта. */
    val config = StageConfig(
        baselineTopK = settings.baselineTopK,
        retrievalTopK = settings.retrievalTopK,
        finalTopK = settings.finalTopK,
        threshold = settings.threshold,
        rewrite = rewriter?.name,
        rerank = reranker?.name
    )

    /** Ответ по памяти: поиска нет, и фрагментов в запросе тоже. */
    suspend fun without(control: ControlQuestion): ModeResult =
        ask(baseAgent, control, Mode.WITHOUT_RAG)

    /** Ответ по выдаче дня 22: Top-K ближайших фрагментов. */
    suspend fun baseline(control: ControlQuestion): ModeResult =
        ask(baseAgent, control, Mode.WITH_RAG)

    /** Единственная выдача конвейера на вопрос: её видят оба ответа дня 24. */
    suspend fun find(question: String): Retrieval = pipeline.find(question)

    /** Ответ улучшенного режима по уже найденной выдаче. */
    suspend fun improved(control: ControlQuestion, found: Retrieval): ModeResult =
        askWith(improvedAgent, control, found)

    /**
     * Один вопрос в одном режиме: ответ модели и его сверка с ожиданием.
     *
     * Отказ режима — не отказ прогона: второй режим всё ещё может ответить, и страница показывает
     * ошибку в том столбце, где она случилась. Отмена приходит снаружи и пробрасывается: проглотив
     * её здесь, страница продолжала бы прогон после нажатия «Остановить».
     */
    private suspend fun ask(agent: RagAgent, control: ControlQuestion, mode: Mode): ModeResult = try {
        val answer = agent.ask(control.question, mode)
        ModeResult(
            control = control,
            mode = mode,
            answer = answer,
            check = Check.evaluate(control, answer.text, answer.sources),
            error = null
        )
    } catch (cause: Exception) {
        if (cause is CancellationException) throw cause
        ModeResult(control, mode, answer = null, check = null, error = failureMessage(cause))
    }

    /** То же, но по готовой выдаче: поиск уже сделан, и второй раз его делать нельзя. */
    private suspend fun askWith(agent: RagAgent, control: ControlQuestion, found: Retrieval): ModeResult = try {
        val answer = agent.askWith(control.question, Mode.WITH_RAG, found)
        ModeResult(
            control = control,
            mode = Mode.WITH_RAG,
            answer = answer,
            check = Check.evaluate(control, answer.text, answer.sources),
            error = null
        )
    } catch (cause: Exception) {
        if (cause is CancellationException) throw cause
        ModeResult(control, Mode.WITH_RAG, answer = null, check = null, error = failureMessage(cause))
    }
}

/**
 * Сообщение об отказе: у отказа API ценен его ответ, у остальных — тип и текст.
 *
 * Общей функцией, потому что об одном и том же отказе сообщают и прогон, и сравнение моделей:
 * две формулировки одной причины разошлись бы, и одинаковый отказ выглядел бы по-разному.
 */
internal fun failureMessage(cause: Throwable): String = when (cause) {
    is LlmApiException -> "Модель отказала: статус ${cause.status}, ${cause.message}"
    else -> cause.message ?: cause::class.simpleName ?: "неизвестная ошибка"
}
