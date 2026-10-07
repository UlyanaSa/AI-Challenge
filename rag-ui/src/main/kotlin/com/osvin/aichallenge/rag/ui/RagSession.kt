package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.rag.Api
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.Controls
import com.osvin.aichallenge.rag.Corpus
import com.osvin.aichallenge.rag.DayBase
import com.osvin.aichallenge.rag.HeuristicReranker
import com.osvin.aichallenge.rag.LlmQueryRewriter
import com.osvin.aichallenge.rag.LlmReranker
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.QueryRewriter
import com.osvin.aichallenge.rag.RagAgent
import com.osvin.aichallenge.rag.RagIndex
import com.osvin.aichallenge.rag.RagPipeline
import com.osvin.aichallenge.rag.Report
import com.osvin.aichallenge.rag.Reranker
import com.osvin.aichallenge.rag.Run
import com.osvin.aichallenge.rag.RunHeader
import com.osvin.aichallenge.rag.SimilarityFilter
import com.osvin.aichallenge.rag.StageConfig
import com.osvin.aichallenge.rag.StageReport
import com.osvin.aichallenge.rag.Stages
import com.osvin.aichallenge.rag.Summary
import com.osvin.aichallenge.rag.Trial
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Чем кончился запуск прогона: принято или отвергнуто с причиной. */
sealed interface StartOutcome {

    data object Accepted : StartOutcome

    data class Rejected(val message: String) : StartOutcome
}

/**
 * Условия прогона, которые задаёт человек на странице: те же, что у прогона в консоли.
 *
 * Варианты этапов идут строками, а не объектами, по той же причине, что и в консоли: клиент модели
 * появляется только после проверки ключа, а разбор варианта должен случиться до прогона — чтобы
 * неизвестное имя отвергло запуск с причиной, а не упало в фоне посреди прогона.
 *
 * Два Top-K независимы: [retrievalTopK] — сколько кандидатов просит поиск у улучшенного конвейера,
 * [finalTopK] — сколько фрагментов уходит в контекст после фильтра и второго этапа.
 */
data class RunSettings(
    /** Top-K базового режима дня 22: столько фрагментов берёт поиск по близости. */
    val baselineTopK: Int,
    val retrievalTopK: Int,
    val finalTopK: Int,
    /** Порог фильтрации; `0.0` — фильтра нет. */
    val threshold: Double,
    /** Вариант переписывания: `none` или `llm`. */
    val rewrite: String,
    /** Вариант второго этапа: `none`, `heuristic` или `llm`. */
    val rerank: String
) {

    /** Переписыватель по имени варианта: `null` — этапа нет. */
    fun rewriterOf(llm: LlmClient, model: String): QueryRewriter? = when (rewrite) {
        NONE -> null
        "llm" -> LlmQueryRewriter(llm, model)
        else -> error("Вариант переписывания не поддержан: $rewrite")
    }

    /** Второй этап по имени варианта: `null` — этапа нет. */
    fun rerankerOf(llm: LlmClient, model: String): Reranker? = when (rerank) {
        NONE -> null
        "heuristic" -> HeuristicReranker()
        "llm" -> LlmReranker(llm, model)
        else -> error("Вариант второго этапа не поддержан: $rerank")
    }

    /**
     * Проверка условий до запуска: причина отказа важнее, чем прогон, падающий на середине.
     *
     * Правила те же, что у разбора аргументов в консоли, и это не дублирование: страница — второй
     * вход в тот же прогон, и она обязана отвергать те же условия теми же словами, иначе прогон
     * со страницы и прогон в консоли начинали бы с разного.
     */
    fun validate(): String? = when {
        baselineTopK !in 1..RagSession.MAX_TOP_K ->
            "Top-K базового режима: от 1 до ${RagSession.MAX_TOP_K}, получено $baselineTopK"

        retrievalTopK !in 1..RagSession.MAX_TOP_K ->
            "retrievalTopK: от 1 до ${RagSession.MAX_TOP_K}, получено $retrievalTopK"

        finalTopK !in 1..retrievalTopK ->
            "finalTopK ($finalTopK) должен быть от 1 до retrievalTopK ($retrievalTopK)"

        threshold !in 0.0..1.0 -> "Порог: от 0 до 1, получено $threshold"
        rewrite !in REWRITE_VARIANTS -> "Вариант переписывания «$rewrite» неизвестен: допустимы $NONE, llm"
        rerank !in RERANK_VARIANTS -> "Вариант второго этапа «$rerank» неизвестен: допустимы $NONE, heuristic, llm"
        else -> null
    }

    companion object {

        /** Имя «этапа нет»: одно и то же в разборе условий и в подсказке. */
        const val NONE = "none"

        val REWRITE_VARIANTS = listOf(NONE, "llm")
        val RERANK_VARIANTS = listOf(NONE, "heuristic", "llm")

        /**
         * Значения по умолчанию — как у прогона в консоли.
         *
         * Десять кандидатов — пример из задания: их хватает фильтру и второму этапу, чтобы было
         * из чего выбирать, но каждый кандидат стоит токенов у LLM-реранкера. Финальные три — тот же
         * контекст, что в дне 22, иначе сравнение с базовым режимом было бы нечестным. Порог 0,40 —
         * значение, выбранное подбором на этом корпусе: 0,50 и 0,60 отсеивают правильные фрагменты
         * быстрее, чем шум (правильные близости здесь начинаются от 0,45). Переписывание моделью
         * включено — это главный этап дня 23, а эвристический второй этап детерминирован и не стоит
         * обращений к модели.
         */
        val DEFAULT = RunSettings(
            baselineTopK = RagSession.DEFAULT_TOP_K,
            retrievalTopK = 10,
            finalTopK = 3,
            threshold = 0.40,
            rewrite = "llm",
            rerank = "heuristic"
        )
    }
}

/**
 * Состояние страницы дня 23: индекс базы, прогон контрольных вопросов в трёх режимах и отчёты.
 *
 * Режимов три, и они повторяют прогон в консоли ([RagCli]): без базы, базовый RAG (Top-K дня 22)
 * и улучшенный RAG — конвейер с переписыванием запроса, расширенной выдачей, порогом и вторым
 * этапом. Все три идут по одному индексу и одной модели, и порядок вызовов внутри вопроса
 * фиксирован: иначе разница между колонками была бы разницей настроек, а не этапов.
 *
 * Сессия держит ровно то, чего нет в конвейере: индекс рабочего каталога, набор результатов
 * и состояние прогона. Сами ответы получает [RagAgent], сверка — [Check], метрики — [Report]
 * и [Stages]: страница не считает ни одного числа сама, иначе её числа разошлись бы с отчётом
 * прогона в консоли, а сверять одно с другим стало бы нечем.
 *
 * Прогон идёт в фоне ([scope]) и пишет состояние под замком: страница опрашивает [state] раз
 * в секунду, и сервер обязан отвечать, пока идёт обращение к модели. Прогон один на страницу:
 * два запуска писали бы одни файлы отчётов и путали результаты между собой, поэтому второе
 * нажатие получает отказ, а не второй прогон поверх первого.
 *
 * Индекс переиспользуется, если он уже собран: этого требует задание дня — поиск по индексу,
 * а не индексация при каждом запросе. Пересборка — отдельное действие на странице, и в состоянии
 * видно, был ли индекс собран заново или взят готовым: от этого зависит, к чему относятся числа.
 */
class RagSession(
    /** Рабочий каталог: корпус дня, индекс и отчёты страницы. */
    private val workDir: Path,
    private val scope: CoroutineScope,
    private val embedding: Embedding,
    private val strategy: ChunkingStrategyType,
    private val model: String,
    /** Ключ модели: `null` — ключа нет, и прогон вопросов не запускается. */
    private val apiKey: String?,
    /**
     * Как построить клиента модели.
     *
     * Фабрикой, а не готовым клиентом, потому что страница строит его один раз на прогон,
     * а прогон начинается позже запуска сервера. Умолчание — живой клиент ([Api.model]),
     * и это же место позволяет прогнать сессию без сети: подставной клиент отвечает заготовкой.
     */
    private val llm: (String) -> LlmClient = Api::model
) {

    private data class ResultPair(
        val without: ModeResult? = null,
        val baseline: ModeResult? = null,
        val improved: ModeResult? = null
    )

    private val lock = Any()

    /**
     * База дня: читается при запуске страницы, а не при первом вопросе.
     *
     * Чтение может не удаться (нет встроенного PDF и нет текста в рабочем каталоге), и это нужно
     * знать сразу: страница с пустой базой не сможет ни проиндексировать, ни искать, и человеку
     * лучше увидеть причину при запуске, чем после нажатия кнопки.
     */
    private val base: DayBase? = runBlocking { Corpus.read(workDir) }

    private val index = RagIndex(workDir, embedding, strategy)

    private var indexState = if (index.exists) IndexDto.READY else IndexDto.IDLE
    private var indexChunks = 0
    private var indexTotal = 0
    private var indexMillis = 0L

    /** Индекс взят готовым, а не собран этим прогоном: в отчёте это разные числа и разные слова. */
    private var indexReused = false

    /**
     * Настройки этапов последнего прогона: они попадают в отчёт, и без них числа не воспроизвести.
     *
     * Хранятся готовым [StageConfig] с именами реализаций, а не сырыми вариантами: отчёт печатает
     * «модель deepseek-v4-flash», и собирать это имя второй раз из варианта значило бы завести
     * второе место, где этап превращается в строку.
     */
    private var stageConfig: StageConfig? = null

    private var state = StateDto.IDLE
    private var stage: String? = null
    private var stageTitle: String? = null
    private var done = 0
    private var total = 0
    private var error: String? = null
    private var elapsedMs = 0L
    private val results = LinkedHashMap<String, ResultPair>()

    /** Прогнанные вопросы целиком: по ним считаются метрики этапов и метрики дня 22. */
    private val completed = ArrayList<Trial>()

    /** Метрики дня 22 (память против базового RAG): считаются [Report.summary] по паре каждого Trial. */
    private var summary: Summary? = null

    /** Метрики этапов дня 23: считаются [Stages.summary] по тем же вопросам. */
    private var stageSummary: StagesDto? = null

    private var reports = emptyList<ReportDto>()

    init {
        // Число чанков готового индекса нужно уже в шапке страницы: это первое, что человек
        // видит про индекс, и по нему понятно, построен ли он вообще.
        if (index.exists) indexChunks = runCatching { index.chunkCount() }.getOrDefault(0)
    }

    /** Что страница знает до нажатия кнопки. */
    fun setup(): SetupDto {
        val current = base
        return SetupDto(
            dir = workDir.toAbsolutePath().toString(),
            base = BaseDto(
                file = current?.document?.files?.firstOrNull()?.name ?: Corpus.FILE_NAME,
                chars = current?.chars ?: 0,
                tokens = current?.tokens ?: 0,
                pages = current?.pages?.size ?: 0
            ),
            index = indexDto(),
            provider = ProviderDto(
                kind = embedding.kind,
                name = embedding.name,
                model = embedding.model,
                dimension = embedding.provider.dimension,
                note = embedding.note
            ),
            model = model,
            topK = RunSettings.DEFAULT.baselineTopK,
            maxTopK = MAX_TOP_K,
            retrievalTopK = RunSettings.DEFAULT.retrievalTopK,
            finalTopK = RunSettings.DEFAULT.finalTopK,
            threshold = RunSettings.DEFAULT.threshold,
            rewrite = RunSettings.DEFAULT.rewrite,
            rerank = RunSettings.DEFAULT.rerank,
            keyNote = when {
                current == null -> Corpus.MISSING_MESSAGE
                apiKey == null -> NO_KEY_MESSAGE
                else -> null
            },
            questions = Controls.questions.map { control ->
                QuestionDto(
                    id = control.id,
                    question = control.question,
                    expected = control.expected,
                    facts = control.facts.map { it.text },
                    pages = control.pages,
                    section = control.section,
                    note = control.note,
                    absent = control.absent
                )
            }
        )
    }

    /**
     * Текущее состояние прогона: что идёт, что уже прогнано и с какими числами.
     *
     * Ответы отдаются целиком, вместе с текстами фрагментов и запросов к модели: страница
     * показывает путь запроса, и собирать его из нескольких запросов значило бы показывать
     * конвейер из разных моментов времени.
     */
    fun state(): StateDto = synchronized(lock) {
        StateDto(
            state = state,
            stage = stage,
            stageTitle = stageTitle,
            done = done,
            total = total,
            index = indexDto(),
            error = error,
            results = results.map { (id, pair) ->
                val control = Controls.byId(id)
                // Трейс и сверка этапов есть только у улучшенного режима: у базового поиска дня 22
                // этапов не было, и пустой трейс страница читает как «этапов нет», а не «потерялись».
                val trace = pair.improved?.answer?.retrieval?.trace
                ResultDto(
                    id = id,
                    question = control?.question.orEmpty(),
                    absent = control?.absent ?: false,
                    without = pair.without?.toDto("without", "без базы"),
                    baseline = pair.baseline?.toDto("baseline", "базовый RAG"),
                    improved = pair.improved?.toDto("improved", "улучшенный RAG"),
                    trace = trace?.toDto(),
                    stageCheck = if (control == null) null else Stages.evaluate(control, trace)?.toDto()
                )
            },
            summary = summary,
            stages = stageSummary,
            config = stageConfig?.toDto(),
            reports = reports,
            elapsedMs = elapsedMs
        )
    }

    /**
     * Пересобирает индекс по кнопке.
     *
     * Отдельное действие, а не часть прогона: сборка не требует ни ключа, ни модели, и её
     * результат — не числа сравнения, а готовый индекс. Прогон при отсутствии индекса соберёт
     * его сам, поэтому кнопка нужна ровно для одного случая: пересобрать то, что уже есть
     * (корпус в рабочем каталоге мог быть пересобран из PDF заново).
     */
    fun rebuildIndex(): StartOutcome {
        val current = base ?: return StartOutcome.Rejected(Corpus.MISSING_MESSAGE)
        synchronized(lock) {
            if (state == StateDto.RUNNING || indexState == IndexDto.BUILDING) {
                return StartOutcome.Rejected(BUSY_MESSAGE)
            }
            indexState = IndexDto.BUILDING
            indexChunks = 0
            indexTotal = 0
        }
        scope.launch {
            try {
                build(current)
            } catch (cause: Exception) {
                synchronized(lock) {
                    indexState = IndexDto.IDLE
                    error = "Индекс не собрался: ${message(cause)}"
                }
            }
        }
        return StartOutcome.Accepted
    }

    /**
     * Запускает прогон выбранных вопросов.
     *
     * Вопросы приходят номерами, а не текстом: набор — условие сравнения, и его не редактируют
     * со страницы. Настройки этапов приходят [RunSettings] и проверяются здесь до запуска: они
     * попадают в отчёт, и числа без них не воспроизводятся, а неизвестный вариант этапа должен
     * отвергнуть запуск, а не упасть в фоне посреди прогона.
     */
    fun start(ids: List<String>, settings: RunSettings): StartOutcome {
        val current = base ?: return StartOutcome.Rejected(Corpus.MISSING_MESSAGE)
        if (apiKey == null) return StartOutcome.Rejected(NO_KEY_MESSAGE)
        if (ids.isEmpty()) return StartOutcome.Rejected(NO_QUESTIONS_MESSAGE)
        settings.validate()?.let { return StartOutcome.Rejected(it) }
        val unknown = ids.filter { Controls.byId(it) == null }
        if (unknown.isNotEmpty()) {
            return StartOutcome.Rejected("В наборе нет вопросов: ${unknown.joinToString(", ")}")
        }

        synchronized(lock) {
            if (state == StateDto.RUNNING || indexState == IndexDto.BUILDING) {
                return StartOutcome.Rejected(BUSY_MESSAGE)
            }
            state = StateDto.RUNNING
            stage = StateDto.INDEX_STAGE
            stageTitle = "Подготовка индекса"
            done = 0
            total = ids.size
            error = null
            elapsedMs = 0L
        }
        scope.launch { runJob(ids, current, settings) }
        return StartOutcome.Accepted
    }

    /**
     * Прогон: индекс, вопросы по очереди, отчёты.
     *
     * Внутри вопроса режимы идут в фиксированном порядке — без базы, базовый, улучшенный, — и он
     * одинаков для всех вопросов: обращения к модели идут по очереди, и разный порядок для разных
     * вопросов сделал бы числа несравнимыми между собой.
     *
     * Результат режима показывается сразу, не дожидаясь следующего: обращение к модели идёт
     * секундами, и человеку видно, где прогон находится.
     *
     * Два поиска над одним индексом: базовому режиму нужны [RunSettings.baselineTopK] ближайших
     * чанков, конвейеру — [RunSettings.retrievalTopK] кандидатов. Индекс читается дважды, но
     * остаётся одним файлом: сравнение режимов не зависит от того, какой из них собрал хранилище.
     */
    private suspend fun runJob(ids: List<String>, current: DayBase, settings: RunSettings) {
        val started = System.nanoTime()
        try {
            val prepared = prepareIndex(current)
            val llm = llm(requireNotNull(apiKey))
            val rewriter = settings.rewriterOf(llm, model)
            val reranker = settings.rerankerOf(llm, model)
            // Имена реализаций попадают в отчёт: по ним видно, какой именно этап работал в этом прогоне.
            synchronized(lock) {
                stageConfig = StageConfig(
                    baselineTopK = settings.baselineTopK,
                    retrievalTopK = settings.retrievalTopK,
                    finalTopK = settings.finalTopK,
                    threshold = settings.threshold,
                    rewrite = rewriter?.name,
                    rerank = reranker?.name
                )
            }
            val baseAgent = RagAgent(llm, prepared.retriever(settings.baselineTopK), model)
            val pipeline = RagPipeline(
                finder = prepared.retriever(settings.retrievalTopK),
                rewriter = rewriter,
                filter = SimilarityFilter(settings.threshold),
                reranker = reranker,
                finalTopK = settings.finalTopK
            )
            val improvedAgent = RagAgent(llm, pipeline, model)
            for (id in ids) {
                val control = requireNotNull(Controls.byId(id))
                synchronized(lock) {
                    stage = id
                    stageTitle = control.question
                }
                val without = ask(baseAgent, control, Mode.WITHOUT_RAG)
                record(id, without = without)
                val baseline = ask(baseAgent, control, Mode.WITH_RAG)
                record(id, baseline = baseline)
                val improved = ask(improvedAgent, control, Mode.WITH_RAG)
                val pair = record(id, improved = improved)
                if (pair.without?.failed == false &&
                    pair.baseline?.failed == false &&
                    pair.improved?.failed == false
                ) {
                    completed += Trial(
                        control = control,
                        noBase = run(pair.without),
                        baseline = run(pair.baseline),
                        improved = run(pair.improved)
                    )
                    publish()
                }
            }
            synchronized(lock) { done = ids.size }
        } catch (cause: Exception) {
            synchronized(lock) {
                state = StateDto.FAILED
                error = message(cause)
            }
        } finally {
            synchronized(lock) {
                elapsedMs = (System.nanoTime() - started) / 1_000_000
                stage = null
                stageTitle = null
                if (state != StateDto.FAILED) state = StateDto.DONE
            }
        }
    }

    /** Один вопрос в одном режиме: ответ модели и его сверка с ожиданием. */
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
        // Отказ режима — не отказ прогона: второй режим всё ещё может ответить, и страница
        // показывает ошибку в том столбце, где она случилась.
        ModeResult(control, mode, answer = null, check = null, error = message(cause))
    }

    /** Прогон режима в том виде, в каком его принимает [Trial]: без ответа и сверки Trial не собрать. */
    private fun run(result: ModeResult): Run =
        Run(result.control, requireNotNull(result.answer), requireNotNull(result.check))

    /** Записывает результат режима и отдаёт пару целиком: по ней видно, чего ещё не было. */
    private fun record(
        id: String,
        without: ModeResult? = null,
        baseline: ModeResult? = null,
        improved: ModeResult? = null
    ): ResultPair = synchronized(lock) {
        val previous = results[id] ?: ResultPair()
        val pair = ResultPair(
            without = without ?: previous.without,
            baseline = baseline ?: previous.baseline,
            improved = improved ?: previous.improved
        )
        results[id] = pair
        if (improved != null) done++
        pair
    }

    /**
     * Индекс для прогона: готовый берётся как есть, отсутствующий собирается.
     *
     * Проверка идёт на диске ([RagIndex.exists]), а не по состоянию страницы: файл мог появиться
     * или пропасть между запусками, и решение должно опираться на то, что есть сейчас.
     */
    private suspend fun prepareIndex(current: DayBase): RagIndex {
        synchronized(lock) {
            if (index.exists) {
                indexState = IndexDto.READY
                indexChunks = runCatching { index.chunkCount() }.getOrDefault(indexChunks)
                indexReused = true
                indexMillis = 0L
            }
        }
        return if (index.exists) index else build(current)
    }

    /** Сборка индекса: прогресс уходит в состояние, чтобы страница показывала ход работы. */
    private suspend fun build(current: DayBase): RagIndex {
        synchronized(lock) {
            indexState = IndexDto.BUILDING
            indexChunks = 0
            indexTotal = 0
            indexReused = false
        }
        val started = System.nanoTime()
        val built = index.build(current) { ready, all ->
            synchronized(lock) {
                indexChunks = ready
                indexTotal = all
                indexMillis = (System.nanoTime() - started) / 1_000_000
            }
        }
        synchronized(lock) {
            indexState = IndexDto.READY
            indexChunks = built.chunks.size
            indexTotal = built.chunks.size
            indexMillis = built.elapsedMillis
        }
        return index
    }

    /** Считает метрики и пишет отчёты: файл всегда описывает прогон целиком, а не один вопрос. */
    private fun publish() {
        val trials = synchronized(lock) { completed.toList() }
        if (trials.isEmpty()) return
        val header = header()
        val config = synchronized(lock) { requireNotNull(stageConfig) }
        val stages = Stages.summary(trials)
        // Отчёт дня 23 ведёт [StageReport]: в нём сравнение трёх режимов, разбор этапов и лог запросов.
        val report = StageReport(header, config, Report(header))
        val comparisonFile = workDir.resolve(REPORT_FILE)
        val logFile = workDir.resolve(LOG_FILE)
        Files.writeString(comparisonFile, report.markdown(trials, stages))
        Files.writeString(logFile, report.log(trials))
        // Метрики дня 22 считает тот же [Report.summary], что и в дне 22: второй набор формул
        // разошёлся бы с первым, и сверять отчёты стало бы нечем.
        val day22 = Report(header).summary(trials.map { it.day22 })
        synchronized(lock) {
            summary = day22
            stageSummary = stages.toDto()
            reports = listOf(
                ReportDto("Сравнение и этапы", comparisonFile.toAbsolutePath().toString()),
                ReportDto("Лог запросов", logFile.toAbsolutePath().toString())
            )
        }
    }

    /** Шапка отчёта: чем и на каких настройках получены числа. */
    private fun header(): RunHeader = synchronized(lock) {
        val current = requireNotNull(base)
        RunHeader(
            model = model,
            embedding = embedding.name,
            strategy = strategy,
            // Top-K дня 22 берётся из настроек прогона: без него Source Hit Rate не воспроизводится.
            topK = requireNotNull(stageConfig).baselineTopK,
            baseChars = current.chars,
            baseTokens = current.tokens,
            basePages = current.pages.size,
            chunks = indexChunks,
            reused = indexReused,
            indexMillis = indexMillis
        )
    }

    private fun indexDto(): IndexDto = IndexDto(
        file = index.file.toAbsolutePath().toString(),
        strategy = strategy.name.lowercase(),
        chunks = indexChunks,
        total = indexTotal,
        state = indexState
    )

    /** Сообщение об отказе: у отказа API ценен его ответ, у остальных — тип и текст. */
    private fun message(cause: Throwable): String = when (cause) {
        is LlmApiException -> "Модель отказала: статус ${cause.status}, ${cause.message}"
        else -> cause.message ?: cause::class.simpleName ?: "неизвестная ошибка"
    }

    companion object {

        /** Каталог по умолчанию: свой, а не прогонный, чтобы страница не переписывала отчёт консоли. */
        const val DEFAULT_DIR = "build/rag-ui"

        const val DEFAULT_PORT = 8098

        /** Три фрагмента — значение задания дня; больше можно, меньше нельзя. */
        const val DEFAULT_TOP_K = 3

        const val MAX_TOP_K = 20

        const val REPORT_FILE = "report.md"
        const val LOG_FILE = "log.md"

        const val BUSY_MESSAGE = "Прогон уже идёт: дождитесь его конца"
        const val NO_QUESTIONS_MESSAGE = "Не выбрано ни одного вопроса"

        const val NO_KEY_MESSAGE =
            "Ключ DEEPSEEK_API_KEY не найден: положите его в server/.env или задайте переменной " +
                "окружения и перезапустите страницу — без ключа модель не отвечает"
    }
}
