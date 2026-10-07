package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.rag.Api
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.Comparison
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.Controls
import com.osvin.aichallenge.rag.Corpus
import com.osvin.aichallenge.rag.DayBase
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.RagAgent
import com.osvin.aichallenge.rag.RagIndex
import com.osvin.aichallenge.rag.Report
import com.osvin.aichallenge.rag.Run
import com.osvin.aichallenge.rag.RunHeader
import com.osvin.aichallenge.rag.Summary
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
 * Состояние страницы дня 22: индекс базы, прогон контрольных вопросов и отчёты.
 *
 * Сессия держит ровно то, чего нет в конвейере: индекс рабочего каталога, набор результатов
 * и состояние прогона. Сами ответы получает [RagAgent], сверка — [Check], метрики — [Report]:
 * страница не считает ни одного числа сама, иначе её числа разошлись бы с отчётом прогона
 * в консоли, а сверять одно с другим стало бы нечем.
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

    private data class ResultPair(val without: ModeResult? = null, val with: ModeResult? = null)

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

    /** Top-K последнего прогона: он попадает в отчёт, и без него Source Hit Rate не воспроизводится. */
    private var topK = DEFAULT_TOP_K

    private var state = StateDto.IDLE
    private var stage: String? = null
    private var stageTitle: String? = null
    private var done = 0
    private var total = 0
    private var error: String? = null
    private var elapsedMs = 0L
    private val results = LinkedHashMap<String, ResultPair>()

    /** Прогнанные вопросы целиком: по ним считаются метрики и пишутся отчёты. */
    private val completed = ArrayList<Comparison>()

    private var summary: Summary? = null
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
            topK = DEFAULT_TOP_K,
            maxTopK = MAX_TOP_K,
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
                ResultDto(
                    id = id,
                    question = control?.question.orEmpty(),
                    absent = control?.absent ?: false,
                    without = pair.without?.toDto(),
                    with = pair.with?.toDto()
                )
            },
            summary = summary,
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
     * со страницы. Top-K здесь же, потому что это параметр прогона, а не настройка интерфейса:
     * он попадает в отчёт, и числа без него не воспроизводятся.
     */
    fun start(ids: List<String>, topK: Int): StartOutcome {
        val current = base ?: return StartOutcome.Rejected(Corpus.MISSING_MESSAGE)
        if (apiKey == null) return StartOutcome.Rejected(NO_KEY_MESSAGE)
        if (ids.isEmpty()) return StartOutcome.Rejected(NO_QUESTIONS_MESSAGE)
        if (topK < 1 || topK > MAX_TOP_K) {
            return StartOutcome.Rejected("topK должен быть от 1 до $MAX_TOP_K, получено $topK")
        }
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
            this.topK = topK
        }
        scope.launch { runJob(ids, current, topK) }
        return StartOutcome.Accepted
    }

    /**
     * Прогон: индекс, вопросы по очереди, отчёты.
     *
     * Вопрос прогоняется сначала без базы, потом с ней — один и тот же порядок для всех вопросов.
     * Разный порядок для разных вопросов сделал бы числа несравнимыми между собой: два режима
     * должны отличаться только тем, что один видит найденные фрагменты, а другой нет.
     *
     * Результат первого режима показывается сразу, не дожидаясь второго: обращение к модели идёт
     * секундами, и человеку видно, где прогон находится.
     */
    private suspend fun runJob(ids: List<String>, current: DayBase, topK: Int) {
        val started = System.nanoTime()
        try {
            val prepared = prepareIndex(current)
            val agent = RagAgent(llm(requireNotNull(apiKey)), prepared.retriever(topK), model)
            for (id in ids) {
                val control = requireNotNull(Controls.byId(id))
                synchronized(lock) {
                    stage = id
                    stageTitle = control.question
                }
                val without = ask(agent, control, Mode.WITHOUT_RAG)
                record(id, without, null)
                val with = ask(agent, control, Mode.WITH_RAG)
                val pair = record(id, without, with)
                if (pair.without?.failed == false && pair.with?.failed == false) {
                    completed += Comparison(
                        control = control,
                        withRag = Run(
                            control,
                            requireNotNull(pair.with.answer),
                            requireNotNull(pair.with.check)
                        ),
                        withoutRag = Run(
                            control,
                            requireNotNull(pair.without.answer),
                            requireNotNull(pair.without.check)
                        )
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

    /** Записывает результат режима и отдаёт пару целиком: по ней видно, чего ещё не было. */
    private fun record(id: String, without: ModeResult, with: ModeResult?): ResultPair =
        synchronized(lock) {
            val previous = results[id] ?: ResultPair()
            val pair = previous.copy(without = without, with = with ?: previous.with)
            results[id] = pair
            if (with != null) done++
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
        val comparisons = synchronized(lock) { completed.toList() }
        if (comparisons.isEmpty()) return
        val report = Report(header())
        val comparisonFile = workDir.resolve(REPORT_FILE)
        val logFile = workDir.resolve(LOG_FILE)
        Files.writeString(comparisonFile, report.markdown(comparisons))
        Files.writeString(logFile, report.log(comparisons))
        val summary = report.summary(comparisons)
        synchronized(lock) {
            this.summary = summary
            reports = listOf(
                ReportDto("Сравнение ответов", comparisonFile.toAbsolutePath().toString()),
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
            topK = topK,
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
