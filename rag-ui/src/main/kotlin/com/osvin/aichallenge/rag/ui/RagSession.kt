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
import com.osvin.aichallenge.rag.GroundedAgent
import com.osvin.aichallenge.rag.GroundedReport
import com.osvin.aichallenge.rag.GroundedTrial
import com.osvin.aichallenge.rag.Grounding
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
import com.osvin.aichallenge.rag.chat.ChatReport
import com.osvin.aichallenge.rag.chat.ChatScenario
import com.osvin.aichallenge.rag.chat.ChatScenarioRunner
import com.osvin.aichallenge.rag.chat.ChatScenarios
import com.osvin.aichallenge.rag.chat.ChatSession
import com.osvin.aichallenge.rag.chat.ChatSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
 * Четвёртым к вопросу добавляется день 24 — grounded-ответ ([GroundedAgent]) на **той же выдаче**,
 * что улучшенный режим: проверка достаточности до обращения к модели, требование цитат в ответе
 * и программная проверка каждой цитаты. Блок дня 24 не заменяет три режима и не спорит с ними:
 * он показывает то, чего в них нет, — ответ, который можно проверить по источнику, и честный отказ
 * там, где подтверждать нечем ([Grounding], [GroundedReport]).
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
 *
 * День 25 добавляет к этому разговор ([ChatSession]): тот же поиск и та же модель, что у прогонов,
 * но с памятью задачи и историей, которую держит движок. Сессия не пересказывает разговор своими
 * полями — она отдаёт его состояние целиком ([chatState]) и принимает реплику, очистку и запуск
 * сценария. Разговор намеренно не вторая копия клиента и не второй индекс: он собран на том же
 * `LlmClient` и на том же построенном индексе, что прогоны, — иначе числа разговора и прогона
 * относились бы к разным условиям. Пока разговор начат, пересборка индекса отвергается
 * ([CHAT_REBUILD_MESSAGE]): подменить поиск под идущим разговором значило бы отвечать не по тому
 * индексу, по которому отвечали предыдущие ходы.
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
    private val llm: (String) -> LlmClient = Api::model,
    /**
     * Настройки разговора дня 25: поиск, окно истории и порог достаточности.
     *
     * Параметром, а не константой, по той же причине, что и фабрика клиента ([llm]): тест прогоняет
     * разговор без сети и с нулевым порогом, чтобы контекст был достаточным всегда, — а на странице
     * стоит значение дня 24 ([ChatSettings] по умолчанию). Второе место, где порог превращается
     * в число, разошлось бы с настройками сценариев, и разговор мерился бы не тем условием,
     * что прогон.
     */
    private val chatSettings: ChatSettings = ChatSettings()
) {

    private data class ResultPair(
        val without: ModeResult? = null,
        val baseline: ModeResult? = null,
        val improved: ModeResult? = null,
        /**
         * Grounded-ответ дня 24: он уже готовым DTO, а не парой «ответ — сверка».
         *
         * Сверка дня 24 живёт в [RagSession.groundedTrials] вместе с предыдущим ответом: из неё
         * считается сводка §17, и странице она не нужна по частям — ей нужен исход, названный
         * признаками [GroundedDto].
         */
        val grounded: GroundedDto? = null
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

    /**
     * Единственный слот длительной работы: прогон или пересборка индекса.
     *
     * Слот один, потому что работы пишут одни и те же поля: состояние, индекс и файлы отчётов.
     * Две работы сразу показали бы на странице смесь двух прогонов, а отчёт описывал бы один из них,
     * и понять, какой именно, было бы невозможно.
     */
    private var job: Job? = null

    /**
     * Номер текущей работы: по нему отменённая работа узнаёт, что её записи больше не нужны.
     *
     * Отмена корутины кооперативная: запрос к модели может вернуться уже после отмены, и без номера
     * вытесненный прогон дописал бы свой вопрос поверх очищенного состояния — рядом с вопросами
     * нового набора. Поле читается и вне замка ([RagSession.alive] в цикле вопросов), поэтому оно
     * летучее: без этого проверка могла бы не увидеть чужую запись.
     */
    @Volatile
    private var runId = 0L

    /** Начало текущей работы: по нему считается время, если прогон остановили до конца. */
    private var startedAt = 0L

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

    /**
     * Вопросы дня 24 целиком: предыдущий ответ, grounded-ответ и их сверка.
     *
     * Отдельно от [completed], потому что набор другой: сверке дня 24 нужен ответ **предыдущего**
     * режима, и без него вопрос в этот список не попадает, даже если три режима дня 23 ответили.
     */
    private val groundedTrials = ArrayList<GroundedTrial>()

    /** Метрики дня 24 (§17): считаются [Grounding.summary] по тем же вопросам, без пересчёта на странице. */
    private var grounding: GroundingDto? = null

    private var reports = emptyList<ReportDto>()

    /**
     * Разговор дня 25: собирается лениво, когда есть и ключ, и готовый индекс.
     *
     * Хранится, а не создаётся на каждый запрос: память задачи и история ходов и есть разговор,
     * и второй экземпляр начинал бы с чистого листа — то есть переставал бы быть тем же разговором.
     * Поэтому пересборка индекса при начатом разговоре отвергается ([rebuildIndex]), а не стирает
     * разговор молча: подменить поиск под идущим разговором значило бы отвечать не по тому индексу,
     * по которому отвечали предыдущие ходы, и не сказать об этом.
     */
    private var chat: ChatSession? = null

    /**
     * Клиент модели разговора: тот же, что у прогонов (та же фабрика и та же модель), но общий
     * на весь разговор — второй клиент означал бы второй набор соединений к одной и той же модели.
     */
    private var chatLlm: LlmClient? = null

    /** Итог последнего прогона сценария на странице; `null` — сценарий не прогоняли. */
    private var chatScenarioResult: ChatScenarioResultDto? = null

    /** Почему разговор не собрался по индексу: причина остаётся, пока её не исправят. */
    private var chatFailure: String? = null

    /** Идёт ход или сценарий: второй разговор не начинается, пока не кончился первый. */
    private var chatBusy = false

    /** Что именно идёт: «разговор» или сценарий с именем — по этому видно, чего ждёт страница. */
    private var chatActivity: String? = null

    /** Сбой последнего хода или сценария: причина для человека, а не тихо пропавшая реплика. */
    private var chatError: String? = null

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
            // Порог достаточности дня 24 — то же число, что у фильтра (§8): отдельное значение
            // разошлось бы с порогом фильтра, и отказ нельзя было бы объяснить одним условием.
            groundingThreshold = RunSettings.DEFAULT.threshold,
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
                    grounded = pair.grounded,
                    trace = trace?.toDto(),
                    stageCheck = if (control == null) null else Stages.evaluate(control, trace)?.toDto()
                )
            },
            summary = summary,
            stages = stageSummary,
            grounding = grounding,
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
            // Пересборку поверх идущего прогона отвергаем, а не вытесняем: новый индекс подменил бы
            // базу посреди прогона, и вопросы ответились бы по одному индексу, а проверялись по другому.
            if (state == StateDto.RUNNING) return StartOutcome.Rejected(RUNNING_MESSAGE)
            if (indexState == IndexDto.BUILDING) return StartOutcome.Rejected(BUILDING_MESSAGE)
            // Начатый разговор держит индекс так же, как прогон: новый файл подменил бы базу
            // посреди разговора, и следующие ходы отвечались бы по чужому индексу, а память
            // и история остались бы от прежнего. Отвергаем, а не стираем разговор молча.
            if (chat?.turns()?.isNotEmpty() == true) {
                return StartOutcome.Rejected(CHAT_REBUILD_MESSAGE)
            }
            // Разговор без ходов ничего не держит: его поиск соберётся заново по новому файлу,
            // и терять при этом нечего — ни истории, ни памяти в нём ещё нет. Заодно снимается
            // прежняя причина отказа собрать разговор: пересборка — как раз то, чем её лечат.
            chat = null
            chatFailure = null
            val token = occupy()
            job = scope.launch { rebuild(token, current) }
        }
        return StartOutcome.Accepted
    }

    /** Сборка индекса как работа страницы: причина отказа уходит в состояние, слот освобождается. */
    private suspend fun rebuild(token: Long, current: DayBase) {
        try {
            build(token, current)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            synchronized(lock) {
                if (token == runId) {
                    indexState = IndexDto.IDLE
                    error = "Индекс не собрался: ${message(cause)}"
                }
            }
        } finally {
            // Слот освобождает только текущая работа: вытесненная оставила бы поле чужим.
            synchronized(lock) { if (token == runId) job = null }
        }
    }

    /**
     * Запускает прогон выбранных вопросов.
     *
     * Вопросы приходят номерами, а не текстом: набор — условие сравнения, и его не редактируют
     * со страницы. Настройки этапов приходят [RunSettings] и проверяются здесь до запуска: они
     * попадают в отчёт, и числа без них не воспроизводятся, а неизвестный вариант этапа должен
     * отвергнуть запуск, а не упасть в фоне посреди прогона.
     *
     * Запущенный прогон не отвергает второй, а уступает ему место: кнопка «Остановить» уже есть,
     * и заставлять человека останавливать прошлый набор руками значило бы требовать двух нажатий
     * там, где одно очевидно. Проверки при этом идут до вытеснения: отвергнутый запуск не должен
     * стирать данные того прогона, который продолжает идти.
     *
     * Прежняя работа отменяется и дожидается (как в [stop]): она должна успеть убрать за собой —
     * прерванная сборка индекса удаляет свой недописанный файл, — и только после этого стираются
     * данные и занимается слот. Иначе её уборка прошла бы по файлам нового прогона.
     */
    suspend fun start(ids: List<String>, settings: RunSettings): StartOutcome {
        val current = base ?: return StartOutcome.Rejected(Corpus.MISSING_MESSAGE)
        if (apiKey == null) return StartOutcome.Rejected(NO_KEY_MESSAGE)
        if (ids.isEmpty()) return StartOutcome.Rejected(NO_QUESTIONS_MESSAGE)
        settings.validate()?.let { return StartOutcome.Rejected(it) }
        val unknown = ids.filter { Controls.byId(it) == null }
        if (unknown.isNotEmpty()) {
            return StartOutcome.Rejected("В наборе нет вопросов: ${unknown.joinToString(", ")}")
        }

        synchronized(lock) { job?.takeIf { it.isActive } }?.let { previous ->
            previous.cancel()
            withTimeoutOrNull(STOP_TIMEOUT_MS) { previous.join() }
        }
        synchronized(lock) {
            // Начатый прогон не отвергается, а вытесняется: страница даёт кнопку остановки, и ждать
            // конца чужого набора, чтобы запустить свой, незачем. Вытеснение отменяет работу и чистит
            // её данные — новый прогон описывает другой набор вопросов и другие настройки, и числа
            // двух прогонов рядом читались бы как один прогон.
            val token = occupy()
            clear()
            state = StateDto.RUNNING
            stage = StateDto.INDEX_STAGE
            stageTitle = "Подготовка индекса"
            total = ids.size
            job = scope.launch { runJob(token, ids, current, settings) }
        }
        return StartOutcome.Accepted
    }

    /**
     * Останавливает текущую работу: прогон или пересборку индекса.
     *
     * Данные при остановке не чистятся: остановка — это «хватит считать», а не «забудь посчитанное».
     * Ответы и сверки, которые успели пройти, остаются на странице вместе с уже записанными файлами,
     * и по ним видно, где прогон встал. Чистит данные новый запуск ([start]) — там это уместно, потому
     * что он и описывает другой прогон.
     *
     * Работа отменяется и **дожидается**: прерванная сборка индекса удаляет свой недописанный файл
     * ([build]) и знает, что делать, ровно пока её номер текущий. Менять номер до её конца значило бы
     * оставить на диске обрывок индекса, а состояние — в «собирается» навсегда. Ожидание ограничено
     * ([STOP_TIMEOUT_MS]): работа, которая не отреагировала на отмену, всё равно перестаёт приниматься
     * — по номеру, — а состояние и индекс лечит следующий прогон ([prepareIndex] не доверяет файлу,
     * оставленному сборкой).
     *
     * Состояние переводится в [StateDto.STOPPED] до отмены: работу закрывает её собственный `finally`,
     * и без этого он объявил бы остановленный прогон законченным.
     */
    suspend fun stop(): Boolean {
        val current = synchronized(lock) {
            val running = job?.takeIf { it.isActive } ?: return false
            if (state == StateDto.RUNNING) {
                state = StateDto.STOPPED
                stage = null
                stageTitle = null
                elapsedMs = if (startedAt > 0) (System.nanoTime() - startedAt) / 1_000_000 else elapsedMs
            }
            running
        }
        current.cancel()
        withTimeoutOrNull(STOP_TIMEOUT_MS) { current.join() }
        synchronized(lock) {
            job = null
            runId++
        }
        return true
    }

    /**
     * Состояние разговора дня 25: лента ходов, память задачи, сводка и итог сценария.
     *
     * Разговор отдаётся целиком, как и состояние прогона: страница опрашивает один ответ и рисует
     * из него и реплики, и память, и числа — собрать их из разных запросов значило бы показать
     * разговор из разных моментов времени. Сводку считает [ChatReport.summarize] по ходам: тот же
     * счёт, что у отчёта, и второй счётчик на странице разошёлся бы с файлом при первой правке.
     *
     * Чат недоступен без ключа и без индекса: первому нечем отвечать, второму не по чему искать.
     * Причина уходит [ChatStateDto.note] отдельной строкой — неработающий чат и пустой разговор
     * выглядят одинаково, если причину не назвать.
     */
    fun chatState(): ChatStateDto = synchronized(lock) {
        chatLocked()
        val reason = chatReasonLocked()
        val session = if (reason == null) chat else null
        val turns = session?.turns().orEmpty()
        ChatStateDto(
            // Имя разговора отдаётся и у недоступного чата, если разговор уже собран: разговор
            // остаётся собой, пока идёт пересборка индекса, и терять его имя незачем.
            sessionId = (session ?: chat)?.sessionId.orEmpty(),
            available = session != null,
            note = reason,
            error = chatError,
            busy = chatBusy,
            activity = chatActivity,
            settings = chatSettings.description,
            scenarios = ChatScenarios.all.map { it.toDto() },
            turns = turns.map { it.toDto() },
            // Память пустая — тот же пустой объект, что и у разговора без единого хода: панель
            // по нему пишет «память пока пуста», а не рисует заголовки без строк.
            memory = session?.memory?.toDto() ?: ChatMemoryDto(),
            summary = session?.let { ChatReport.summarize(turns).toDto() },
            scenario = if (session == null) null else chatScenarioResult
        )
    }

    /**
     * Очередная реплика человека: ответ, обновление памяти задачи и перепись транскрипта.
     *
     * Ход выполняется здесь же, а не в фоне: ответ на реплику — это результат нажатия, и странице
     * незачем опрашивать состояние, чтобы его получить. Пока ход идёт, [ChatStateDto.busy] поднят,
     * и второй ход не начинается — разговор один, как и прогон.
     */
    suspend fun chatSend(text: String): ChatStateDto {
        val session = synchronized(lock) {
            if (chatBusy) return chatState()
            val current = chatLocked() ?: return chatState()
            chatBusy = true
            chatError = null
            chatActivity = CHAT_ACTIVITY
            current
        }
        try {
            session.ask(text.trim())
            publishChat(session)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            // Сбой хода не отменяет разговор: причина уходит в состояние, а лента и память остаются
            // такими, какими были до него, — человек может повторить реплику.
            synchronized(lock) { chatError = "Реплика не выполнена: ${message(cause)}" }
        } finally {
            synchronized(lock) {
                chatBusy = false
                chatActivity = null
            }
        }
        return chatState()
    }

    /**
     * Стирает разговор и память задачи: новый разговор начинается с чистого листа.
     *
     * Память стирается вместе с историей не случайно: она описывает **этот** разговор, и, оставшись
     * от прошлого, превратилась бы в правило, которого человек не устанавливал. Транскрипт на диске
     * переписывается тут же: файл обязан описывать текущий разговор, а не тот, который стёрли.
     */
    suspend fun chatReset(): ChatStateDto {
        val session = synchronized(lock) {
            if (chatBusy) return chatState()
            chatLocked() ?: return chatState()
        }
        session.reset()
        synchronized(lock) {
            chatScenarioResult = null
            chatError = null
        }
        publishChat(session)
        return chatState()
    }

    /**
     * Запускает сценарий дня 25 в фоне.
     *
     * Возвращает состояние сразу, а не через минуту: сценарий — тринадцать реплик, и ждать его конца
     * в ответе на нажатие значило бы держать страницу без ответа всё это время. Прогресс виден через
     * [chatState]: лента растёт по мере ходов, а [ChatStateDto.busy] и [ChatStateDto.activity]
     * называют, что именно идёт. Неизвестное имя сюда не доходит — маршрут отвергает его с перечнем
     * имён ([ChatScenarios.names]), и здесь оно означает уже разобранный сценарий.
     *
     * Сценарий начинается с **чистого разговора**: прежняя лента и память задачи стираются.
     * Иначе приговор сценария описывал бы чужую историю — и, что хуже, мерка дня перестала бы
     * что-либо значить: «цель держится» проверяется тем, что цель разговора, поставленная человеком
     * в первой реплике, не менялась до последней, а если в памяти лежит цель предыдущего разговора,
     * это утверждение проверяет уже не сценарий, а остатки прошлого прогона. Так же и повторное
     * нажатие кнопки: два прогона одного сценария не должны складываться в разговор вдвое длиннее.
     */
    suspend fun chatScenario(name: String): ChatStateDto {
        val scenario = ChatScenarios.byName(name) ?: return chatState()
        synchronized(lock) {
            if (chatBusy) return chatState()
            chatLocked() ?: return chatState()
            chatBusy = true
            chatError = null
            chatActivity = "сценарий ${scenario.name}: ${scenario.title}"
            scope.launch { runScenario(scenario) }
        }
        return chatState()
    }

    /** Разговор на текущем индексе; `null` — его пока нельзя собрать (нет ключа или индекса). */
    private fun chatLocked(): ChatSession? {
        chat?.let { return it }
        if (chatReasonLocked() != null) return null
        return try {
            // Клиент общий на разговор, а поиск берётся у того же индекса, что у прогонов:
            // второй поиск по другому файлу дал бы другие источники при тех же настройках.
            val client = chatLlm ?: llm(requireNotNull(apiKey)).also { chatLlm = it }
            ChatSession(
                llm = client,
                model = model,
                pipeline = chatSettings.pipeline(index.retriever(chatSettings.retrievalTopK), client, model),
                settings = chatSettings
            ).also { chat = it }
        } catch (cause: Exception) {
            // Индекс есть, но не читается (обрывок сборки, чужой файл): причина остаётся, пока
            // её не исправят, — иначе каждый опрос пытался бы открыть тот же битый файл.
            chatFailure = "Чат не собрался по индексу: ${message(cause)}"
            null
        }
    }

    /**
     * Почему чат недоступен; `null` — доступен.
     *
     * Собранный разговор считается доступным, даже когда индекс пересобирается: он держит поиск
     * по тому файлу, с которым начался, и отвечает по нему до конца разговора. Причины называются
     * словами, а не кодом: ключ и индекс лечатся по-разному, и подсказка должна говорить, чем.
     */
    private fun chatReasonLocked(): String? = when {
        apiKey == null -> NO_KEY_MESSAGE
        chatFailure != null -> chatFailure
        chat != null -> null
        indexState == IndexDto.BUILDING -> CHAT_BUILDING_MESSAGE
        !index.exists -> CHAT_INDEX_MESSAGE
        else -> null
    }

    /**
     * Прогон сценария шаг за шагом: ходы через движок, транскрипт по ходу и файл с приговором.
     *
     * Сценарий исполняет [ChatScenarioRunner] — тот же запуск, что в консоли: проверки, приговор
     * и признаки дня считает `:rag`, и второго набора правил проверки на странице нет. Шаги при этом
     * видны по ходу: пока runner спрашивает сессию, сторож переписывает транскрипт на каждый новый
     * ход — по прерванному сценарию на диске должен остаться разговор, а не прошлый файл.
     *
     * Разговор стирается здесь, а не в [chatScenario], по порядку, а не по смыслу: между этими
     * двумя вызовами фоновая задача не выполняется, и стирание должно попасть в тот же промежуток,
     * в котором сценарий начинает спрашивать, — иначе первый же ход человека, попавший между
     * запуском и стиранием, остался бы в памяти задачи, а сценарий считал бы не свой разговор.
     * Приговор прошлого сценария убирается вместе с разговором: пока новый идёт, страница не должна
     * показывать результат предыдущего как свой.
     */
    private suspend fun runScenario(scenario: ChatScenario) {
        try {
            val session = synchronized(lock) {
                chatScenarioResult = null
                chatLocked()
            } ?: return
            session.reset()
            val run = coroutineScope {
                val work = async { ChatScenarioRunner.run(session, scenario) }
                var seen = session.turns().size
                while (!work.isCompleted) {
                    delay(CHAT_WATCH_MS)
                    val now = session.turns().size
                    if (now != seen) {
                        seen = now
                        publishChat(session)
                    }
                }
                work.await()
            }
            publishChat(session)
            Files.writeString(
                workDir.resolve(CHAT_SCENARIO_PREFIX + scenario.name + ".md"),
                ChatScenarioRunner.markdown(session.sessionId, run, chatSettings, model)
            )
            synchronized(lock) { chatScenarioResult = run.toDto() }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            synchronized(lock) { chatError = "Сценарий не доигран: ${message(cause)}" }
        } finally {
            synchronized(lock) {
                chatBusy = false
                chatActivity = null
            }
        }
    }

    /**
     * Переписывает транскрипт разговора: `chat.md` для человека и `chat-log.md` для разбора.
     *
     * Файлы переписываются после каждого хода, а не в конце: транскрипт — это то, что остаётся
     * от разговора, и обрывок прогона (остановка, закрытая страница) не должен оставлять на диске
     * описание прошлого разговора вместо текущего. Лог отдельно от отчёта по той же причине, что
     * и в днях 23–24: отчёт читает человек, лог — тот, кто разбирает сбой формата.
     */
    private fun publishChat(session: ChatSession) {
        val turns = session.turns()
        Files.writeString(
            workDir.resolve(CHAT_FILE),
            ChatReport.markdown(session.sessionId, turns, chatSettings, model)
        )
        Files.writeString(
            workDir.resolve(CHAT_LOG_FILE),
            ChatReport.log(session.sessionId, turns, chatSettings, model)
        )
    }

    /**
     * Занимает слот работы: предыдущая отменяется, номер увеличивается.
     *
     * Номер берётся здесь, а не в [start], потому что он принадлежит слоту, а не прогону: вытеснить
     * работу может и пересборка индекса, и тогда записи вытесненного прогона так же не нужны.
     */
    private fun occupy(): Long {
        job?.cancel()
        job = null
        startedAt = System.nanoTime()
        return ++runId
    }

    /**
     * Освобождает данные предыдущего прогона: ответы, сверки, метрики и список файлов.
     *
     * Файлы на диске не удаляются: прогон перепишет их своими, а до этого лежащий файл описывает
     * последний прогон — после остановки это ровно то, что успело посчитаться. Настройки этапов
     * чистятся вместе с данными: они описывают прогон, которого на странице больше нет.
     */
    private fun clear() {
        results.clear()
        completed.clear()
        groundedTrials.clear()
        summary = null
        stageSummary = null
        grounding = null
        reports = emptyList()
        stageConfig = null
        stage = null
        stageTitle = null
        done = 0
        total = 0
        error = null
        elapsedMs = 0L
    }

    /** Проверка «эта работа всё ещё текущая»: отменённая своих записей не делает. */
    private fun alive(token: Long) {
        if (token != runId) throw CancellationException("Прогон заменён новым запуском")
    }

    /**
     * Прогон: индекс, вопросы по очереди, отчёты.
     *
     * Внутри вопроса режимы идут в фиксированном порядке — без базы, базовый, улучшенный, — и он
     * одинаков для всех вопросов: обращения к модели идут по очереди, и разный порядок для разных
     * вопросов сделал бы числа несравнимыми между собой. День 24 идёт последним и делит выдачу
     * с улучшенным режимом: один поиск на два ответа — условие честного сравнения ([askDay24]).
     *
     * Результат режима показывается сразу, не дожидаясь следующего: обращение к модели идёт
     * секундами, и человеку видно, где прогон находится.
     *
     * Два поиска над одним индексом: базовому режиму нужны [RunSettings.baselineTopK] ближайших
     * чанков, конвейеру — [RunSettings.retrievalTopK] кандидатов. Индекс читается дважды, но
     * остаётся одним файлом: сравнение режимов не зависит от того, какой из них собрал хранилище.
     */
    private suspend fun runJob(token: Long, ids: List<String>, current: DayBase, settings: RunSettings) {
        val started = System.nanoTime()
        try {
            val prepared = prepareIndex(token, current)
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
            // Порог достаточности — тот же, что у фильтра (§8): второе число для «хватает ли
            // контекста» разошлось бы с первым, и объяснить отказ разными порогами было бы нечем.
            val groundedAgent = GroundedAgent(llm, model, settings.threshold)
            for (id in ids) {
                // Проверка на каждой итерации, а не только у записей: вытеснённый прогон должен
                // перестать спрашивать модель, а не просто перестать показывать ответы.
                alive(token)
                val control = requireNotNull(Controls.byId(id))
                synchronized(lock) {
                    stage = id
                    stageTitle = control.question
                }
                val without = ask(baseAgent, control, Mode.WITHOUT_RAG)
                record(token, id, without = without)
                val baseline = ask(baseAgent, control, Mode.WITH_RAG)
                record(token, id, baseline = baseline)
                val day24 = askDay24(id, control, improvedAgent, groundedAgent, pipeline, settings.threshold)
                val pair = record(token, id, improved = day24.improved, grounded = day24.grounded)
                val trial = day24.trial
                val passed = pair.without?.failed == false &&
                    pair.baseline?.failed == false &&
                    pair.improved?.failed == false
                synchronized(lock) {
                    // Номер проверяется у самой записи, а не только у цикла: между проверкой
                    // и записью прогон мог быть вытеснен, и тогда его Trial попал бы в чужой отчёт.
                    alive(token)
                    // Список под тем же замком, что и его чтение в [publish]: страница опрашивает
                    // состояние параллельно прогону, и незакрытая запись видна ей как обрывок списка.
                    trial?.let { groundedTrials += it }
                    if (passed) {
                        completed += Trial(
                            control = control,
                            noBase = run(pair.without),
                            baseline = run(pair.baseline),
                            improved = run(pair.improved)
                        )
                    }
                }
                // День 24 может состояться и без трёх режимов дня 23: у него своя сверка,
                // и при отказе предыдущего режима писать всё равно нужно — его файлы.
                if (passed || trial != null) publish(token)
            }
            synchronized(lock) { if (token == runId) done = ids.size }
        } catch (cause: CancellationException) {
            // Отмена — не отказ прогона: состояние уже переведено тем, кто отменил работу
            // (остановка — в [stop], вытеснение — в [start]).
            throw cause
        } catch (cause: Exception) {
            synchronized(lock) {
                if (token == runId) {
                    state = StateDto.FAILED
                    error = message(cause)
                }
            }
        } finally {
            synchronized(lock) {
                // Итоги подводит только текущая работа: вытесненная ничего не закрывает — её данные
                // уже стёрты, а состояние принадлежит тому прогону, который её заменил.
                if (token == runId) {
                    elapsedMs = (System.nanoTime() - started) / 1_000_000
                    stage = null
                    stageTitle = null
                    job = null
                    // STOPPED ставит [stop] до отмены: без этой проверки `finally` объявил бы
                    // остановленный прогон законченным.
                    if (state != StateDto.FAILED && state != StateDto.STOPPED) state = StateDto.DONE
                }
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
        // Отмену пробрасываем: отказ режима — это отказ модели или поиска, а отмена приходит снаружи,
        // и, проглотив её здесь, страница продолжала бы прогон после нажатия «Остановить».
        if (cause is CancellationException) throw cause
        // Отказ режима — не отказ прогона: второй режим всё ещё может ответить, и страница
        // показывает ошибку в том столбце, где она случилась.
        ModeResult(control, mode, answer = null, check = null, error = message(cause))
    }

    /** Прогон режима в том виде, в каком его принимает [Trial]: без ответа и сверки Trial не собрать. */
    private fun run(result: ModeResult): Run =
        Run(result.control, requireNotNull(result.answer), requireNotNull(result.check))

    /**
     * День 24 на одном вопросе: одна выдача, ответ предыдущего режима и grounded-ответ.
     *
     * Выдача ищется **один раз** ([RagPipeline.find]) и уходит в оба ответа. Это условие сравнения,
     * а не экономия: второй поиск дал бы другую выдачу, и разницу между ответами можно было бы
     * списать на этапы дня 24, хотя её породил бы поиск. Та же схема, что у прогона в консоли
     * ([RagCli.runGrounding]): поиск → предыдущий режим на готовой выдаче → grounded на ней же.
     *
     * Отказ любого из двух ответов не прерывает прогон: причина уходит в свой DTO и видна странице
     * там, где случилась. Если поиск не выполнился, оба ответа дня 24 отметить нечем, и причина
     * у них одна: второй раз искать нельзя, не сломав то самое сравнение на одной выдаче.
     *
     * Сверка ([Grounding.check]) требует ответа предыдущего режима: без него сравнивать grounded
     * не с чем, и этап дня 24 показывает причину, а не нулевые метрики — ноль здесь был бы суждением,
     * которого никто не выносил.
     */
    private suspend fun askDay24(
        id: String,
        control: ControlQuestion,
        agent: RagAgent,
        groundedAgent: GroundedAgent,
        pipeline: RagPipeline,
        threshold: Double
    ): Day24 {
        val found = try {
            pipeline.find(control.question)
        } catch (cause: Exception) {
            // Отмена — не отказ поиска (см. [ask]): её пробрасываем, чтобы прогон действительно встал.
            if (cause is CancellationException) throw cause
            val error = "Поиск не выполнился: ${message(cause)}"
            return Day24(
                improved = ModeResult(control, Mode.WITH_RAG, answer = null, check = null, error = error),
                grounded = failedGrounded(id, control, threshold, error),
                trial = null
            )
        }
        val improved = try {
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
            ModeResult(control, Mode.WITH_RAG, answer = null, check = null, error = message(cause))
        }
        val groundedAnswer = try {
            groundedAgent.answer(control.question, found)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            return Day24(
                improved = improved,
                grounded = failedGrounded(id, control, threshold, "Grounded-ответ не получен: ${message(cause)}"),
                trial = null
            )
        }
        val previous = improved.answer?.let { answer ->
            improved.check?.let { check -> Run(control, answer, check) }
        }
        if (previous == null) {
            return Day24(
                improved = improved,
                grounded = failedGrounded(
                    id = id,
                    control = control,
                    threshold = threshold,
                    error = "Предыдущий режим не ответил: ${improved.error ?: "нет ответа"} — сверять grounded не с чем"
                ),
                trial = null
            )
        }
        val check = Grounding.check(control, previous, groundedAnswer)
        return Day24(
            improved = improved,
            grounded = groundedAnswer.toDto(check, id),
            trial = GroundedTrial(control, previous, groundedAnswer, check)
        )
    }

    /**
     * Итог дня 24 по одному вопросу: ответ предыдущего режима, grounded-ответ как DTO и сверка.
     *
     * Три части, потому что у них три потребителя: [improved] уходит колонкой дня 23, [grounded] —
     * блоком дня 24 на странице, а [trial] собирается в отчёт и сводку §17. [trial] пуст там, где
     * сверка не состоялась: отчёт дня 24 не может показать вопрос, по которому нет сравнения.
     */
    private data class Day24(
        val improved: ModeResult,
        val grounded: GroundedDto,
        val trial: GroundedTrial?
    )

    /** Записывает результат режима и отдаёт пару целиком: по ней видно, чего ещё не было. */
    private fun record(
        token: Long,
        id: String,
        without: ModeResult? = null,
        baseline: ModeResult? = null,
        improved: ModeResult? = null,
        grounded: GroundedDto? = null
    ): ResultPair = synchronized(lock) {
        alive(token)
        val previous = results[id] ?: ResultPair()
        val pair = ResultPair(
            without = without ?: previous.without,
            baseline = baseline ?: previous.baseline,
            improved = improved ?: previous.improved,
            grounded = grounded ?: previous.grounded
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
     *
     * Исключение одно: файл, оставленный **идущей** сборкой, готовым не считается. Индекс пишется
     * по чанкам, и если сборку остановили в середине, на диске лежит её обрывок — он не пуст,
     * поэтому выглядел бы готовым индексом, и прогон ответил бы по неполной базе, не сказав об этом.
     * Такая сборка удаляет свой файл сама ([build]); эта проверка — на случай, когда она не успела
     * этого сделать до конца ожидания в [stop], и состояние осталось «собирается».
     */
    private suspend fun prepareIndex(token: Long, current: DayBase): RagIndex {
        val usable = synchronized(lock) {
            if (index.exists && indexState != IndexDto.BUILDING) {
                indexState = IndexDto.READY
                indexChunks = runCatching { index.chunkCount() }.getOrDefault(indexChunks)
                indexReused = true
                indexMillis = 0L
                true
            } else {
                false
            }
        }
        return if (usable) index else build(token, current)
    }

    /**
     * Сборка индекса: прогресс уходит в состояние, чтобы страница показывала ход работы.
     *
     * Номер проверяется и в обратном вызове, а не только в цикле вопросов: хеширование считает векторы
     * без обращений к сети, и отмена корутины не нашла бы там точки приостановки — нажатие
     * «Остановить» не подействовало бы до конца сборки. Заодно это отсекает прогресс вытесненной
     * сборки: её числа не должны появляться в состоянии нового прогона.
     *
     * Прерванная сборка удаляет свой файл: хранилище пишет индекс после каждого чанка, и без
     * этого недописанный файл выглядел бы для следующего прогона готовым индексом — ответы считались
     * бы по обрывку базы, и отчёт не сказал бы об этом ни слова. Неполный индекс хуже отсутствующего:
     * отсутствующий виден в состоянии и собирается заново.
     */
    private suspend fun build(token: Long, current: DayBase): RagIndex {
        synchronized(lock) {
            indexState = IndexDto.BUILDING
            indexChunks = 0
            indexTotal = 0
            indexReused = false
        }
        val started = System.nanoTime()
        try {
            val built = index.build(current) { ready, all ->
                synchronized(lock) {
                    alive(token)
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
        } catch (cause: CancellationException) {
            synchronized(lock) {
                // Файл трогаем, только пока эта работа текущая. Обычно так и есть: [stop] и [start]
                // отменяют работу и дожидаются её конца, а номер меняют после. Но ожидание ограничено,
                // и работа, не отреагировавшая на отмену, к этому моменту уже теряет номер — тогда
                // файл ей не принадлежит: на диске может писать следующая сборка.
                if (token == runId) {
                    runCatching { Files.deleteIfExists(index.file) }
                    indexState = IndexDto.IDLE
                    indexChunks = 0
                    indexTotal = 0
                    indexReused = false
                }
            }
            throw cause
        }
    }

    /**
     * Считает метрики и пишет отчёты: файл всегда описывает прогон целиком, а не один вопрос.
     *
     * Отчётов два набора, и файлы у них разные. День 23 ведёт [StageReport] — сравнение трёх режимов,
     * разбор этапов и лог запросов; день 24 ведёт [GroundedReport] — ответы с источниками и цитатами,
     * проверка цитат и решение о достаточности. Отдельные файлы здесь не формальность: отчёт дня 24
     * не переписывает сравнение трёх режимов, а ложится рядом, и прочитать одно, не потеряв другое,
     * можно только так.
     *
     * Наборы считаются по своим вопросам: метрики этапов — по [completed], день 24 — по
     * [groundedTrials]. Смешать их нельзя: у дня 24 вопрос без ответа предыдущего режима выпадает
     * из сверки, а у дня 23 он же остаётся полноправным вопросом сравнения.
     */
    private fun publish(token: Long) {
        // Прогон, которого больше нет, файлов не пишет: он переписал бы отчёт нового прогона
        // своим набором вопросов — и на диске оказался бы отчёт о прогоне, которого не было.
        val trials = synchronized(lock) {
            alive(token)
            completed.toList()
        }
        val day24 = synchronized(lock) {
            alive(token)
            groundedTrials.toList()
        }
        if (trials.isEmpty() && day24.isEmpty()) return
        val header = header(token)
        val config = synchronized(lock) { requireNotNull(stageConfig) }
        val written = ArrayList<ReportDto>()
        var day22Summary: Summary? = null
        var stagesDto: StagesDto? = null
        var groundingDto: GroundingDto? = null
        if (trials.isNotEmpty()) {
            val stages = Stages.summary(trials)
            // Отчёт дня 23 ведёт [StageReport]: в нём сравнение трёх режимов, разбор этапов и лог запросов.
            val report = StageReport(header, config, Report(header))
            val comparisonFile = workDir.resolve(REPORT_FILE)
            val logFile = workDir.resolve(LOG_FILE)
            Files.writeString(comparisonFile, report.markdown(trials, stages))
            Files.writeString(logFile, report.log(trials))
            // Метрики дня 22 считает тот же [Report.summary], что и в дне 22: второй набор формул
            // разошёлся бы с первым, и сверять отчёты стало бы нечем.
            day22Summary = Report(header).summary(trials.map { it.day22 })
            stagesDto = stages.toDto()
            written += ReportDto("Сравнение и этапы", comparisonFile.toAbsolutePath().toString())
            written += ReportDto("Лог запросов", logFile.toAbsolutePath().toString())
        }
        if (day24.isNotEmpty()) {
            val report = GroundedReport(header, config, jsonFormat = true)
            // Знаменатель фактов считается по вопросам самой сводки, а не по всему запуску: вопросы
            // без сверки в отчёт не попали, и держать их в знаменателе значило бы занижать охват.
            val groundingSummary = Grounding.summary(
                checks = day24.map { it.check },
                factsTotal = day24.sumOf { it.control.facts.size }
            )
            val comparisonFile = workDir.resolve(GROUNDED_REPORT_FILE)
            val logFile = workDir.resolve(GROUNDED_LOG_FILE)
            Files.writeString(comparisonFile, report.markdown(day24, groundingSummary))
            Files.writeString(logFile, report.log(day24))
            groundingDto = groundingSummary.toDto()
            written += ReportDto("Grounding: ответы, источники и цитаты", comparisonFile.toAbsolutePath().toString())
            written += ReportDto("Grounding: путь запроса", logFile.toAbsolutePath().toString())
        }
        // Поля состояния переписываются только тем, что посчитано сейчас: иначе отчёт об одном
        // вопросе стирал бы сводку по всем уже прогнанным. Номер проверяется и здесь: файлы пишутся
        // секундами, за них прогон мог быть вытеснен, и его сводка не должна лечь поверх чужой.
        synchronized(lock) {
            alive(token)
            if (day22Summary != null) summary = day22Summary
            if (stagesDto != null) stageSummary = stagesDto
            if (groundingDto != null) grounding = groundingDto
            reports = written
        }
    }

    /** Шапка отчёта: чем и на каких настройках получены числа. */
    private fun header(token: Long): RunHeader = synchronized(lock) {
        alive(token)
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

        /**
         * Отчёты дня 24 — отдельные файлы рядом с отчётами дня 23.
         *
         * Имена с пометкой дня, а не общие, потому что это другой отчёт (ответы с источниками
         * и цитатами против сравнения трёх режимов): общий файл переписывал бы сравнение, и один
         * из двух отчётов исчезал бы при каждом прогоне.
         */
        const val GROUNDED_REPORT_FILE = "report-day24.md"
        const val GROUNDED_LOG_FILE = "log-day24.md"

        /**
         * Транскрипт мини-чата и лог его запросов: то, что остаётся от разговора дня 25.
         *
         * Отдельные файлы от отчётов прогонов: разговор и прогон — разные работы, и общий файл
         * переписывал бы один отчёт другим. Сценарий пишется ещё и своим файлом на каждый запуск
         * ([CHAT_SCENARIO_PREFIX] + имя), потому что приговор сценария нужен отдельно от ленты.
         */
        const val CHAT_FILE = "chat.md"
        const val CHAT_LOG_FILE = "chat-log.md"
        const val CHAT_SCENARIO_PREFIX = "chat-"

        /**
         * Сколько ждём между проверками ходов сценария: сторож переписывает транскрипт по ходу.
         *
         * Пять ходов в секунду — это дешевле, чем один пропущенный ход в транскрипте: файл маленький,
         * а польза в том, что прерванный сценарий оставляет на диске то, что успел сказать.
         */
        const val CHAT_WATCH_MS = 200L

        /** Что идёт: имя длительной работы для [ChatStateDto.activity]. */
        const val CHAT_ACTIVITY = "разговор"

        /** Причина недоступности: индекс не построен — чату не по чему искать. */
        const val CHAT_INDEX_MESSAGE =
            "Чат ищет по индексу: соберите индекс кнопкой «Пересобрать индекс» — тогда разговор заработает"

        /** Причина недоступности: индекс собирается прямо сейчас. */
        const val CHAT_BUILDING_MESSAGE = "Индекс собирается: разговор начнётся, когда поиск будет готов"

        /**
         * Отказ пересборки индекса при начатом разговоре: тот же довод, что у [RUNNING_MESSAGE].
         *
         * Новый индекс подменил бы базу посреди разговора, а память и история остались бы от прежнего:
         * следующие ходы отвечались бы по одному индексу, а предыдущие — по другому. Решение остаётся
         * за человеком — очистить разговор кнопкой и пересобрать.
         */
        const val CHAT_REBUILD_MESSAGE =
            "Идёт разговор: пересборка подменила бы индекс посреди разговора — сначала очистите разговор"

        /** Отказ пустой реплики: пустой вопрос не ход разговора. */
        const val CHAT_EMPTY_MESSAGE = "Пустая реплика: напишите вопрос"

        /** Отказ второго хода поверх идущего: разговор один, как и прогон. */
        const val CHAT_BUSY_MESSAGE = "Разговор занят: дождитесь конца хода или сценария"

        /**
         * Сколько ждём конца отменённой работы: прогона или сборки индекса.
         *
         * Отмена в корутине кооперативная, и работа может не отреагировать мгновенно (запрос к модели
         * или запись файла доводятся до конца). Ждать бесконечно нельзя — страница ждёт ответа
         * на нажатие, — а не ждать вовсе значило бы оставить за прерванной сборкой её недописанный
         * файл. Пять секунд покрывают уборку, а не отреагировавшую работу добирает номер прогона.
         */
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Отказ пересборки при идущем прогоне: пересборку вытеснить прогоном можно, а наоборот — нет.
         *
         * Вытеснение здесь было бы тихим: прогон продолжил бы считать по базе, которой на диске уже
         * нет, и его числа описывали бы индекс, по которому отвечали не все вопросы. Поэтому причина
         * называется прямо, а решение остаётся за человеком: остановить прогон кнопкой и пересобрать.
         */
        const val RUNNING_MESSAGE =
            "Прогон идёт: пересборка подменила бы индекс посреди прогона — сначала нажмите «Остановить»"

        /** Отказ пересборки при идущей пересборке: второй сборки в одном слоте быть не может. */
        const val BUILDING_MESSAGE = "Индекс уже собирается: дождитесь конца или остановите работу"
        const val NO_QUESTIONS_MESSAGE = "Не выбрано ни одного вопроса"

        const val NO_KEY_MESSAGE =
            "Ключ DEEPSEEK_API_KEY не найден: положите его в server/.env или задайте переменной " +
                "окружения и перезапустите страницу — без ключа модель не отвечает"
    }
}
