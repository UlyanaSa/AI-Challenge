package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.rag.Answer
import com.osvin.aichallenge.rag.Api
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.Controls
import com.osvin.aichallenge.rag.Corpus
import com.osvin.aichallenge.rag.DayBase
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.RagIndex
import com.osvin.aichallenge.rag.Report
import com.osvin.aichallenge.rag.Run
import com.osvin.aichallenge.rag.RunHeader
import com.osvin.aichallenge.rag.StageConfig
import com.osvin.aichallenge.rag.StageReport
import com.osvin.aichallenge.rag.StageSummary
import com.osvin.aichallenge.rag.Stages
import com.osvin.aichallenge.rag.TokensUsed
import com.osvin.aichallenge.rag.Trial
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Сравнение моделей на одном наборе вопросов: качество, скорость и стабильность ответов.
 *
 * Задание дня 28 просит сравнить локальную и облачную модели — по качеству, скорости и стабильности.
 * Эти три числа нельзя получить из одного прогона: качество и скорость видны по любому, а стабильность
 * требует **повтора** того же прогона той же моделью. Поэтому сравнение — это матрица «модель × повтор»,
 * и все её клетки сняты при одинаковых вопросах, настройках этапов и **одном индексе**: другого индекса
 * у страницы нет ([RagSession] и раздел сравнения берут один и тот же файл), и разница между моделями
 * не может быть разницей поиска.
 *
 * Моделей две, и вторая может отсутствовать: облачной нужен ключ, которого на этой машине может
 * не быть. Отсутствие — часть результата, а не ошибка запуска: сравнение идёт по тому, что есть,
 * а недоступная модель остаётся в состоянии с причиной, и страница показывает её словами, а не
 * пустыми колонками, которые читались бы как «ответила плохо».
 *
 * Считает сравнение тот же код, что и прогон дня 23 ([QuestionRunner], [Stages], [Check]): второй
 * сборщик конвейера или второй набор формул разошёлся бы с отчётом прогона, и сверять одно с другим
 * стало бы нечем. Своё у сравнения только то, чего у прогона нет: порядок «модель × повтор»,
 * сведение трёх чисел и разбор расхождений между повторами.
 *
 * Работа идёт в фоне и **одна на страницу**: прогон дня 23 и сравнение — разные наборы данных,
 * но один и тот же индекс и одна и та же модель в памяти демона, поэтому одновременные работы
 * упирались бы друг в друга по времени, а числа получились бы снятыми в разных условиях.
 */
class CompareSession(
    /** Каталог страницы: отчёты сравнения ложатся в его подкаталог `compare`. */
    private val workDir: Path,
    private val scope: CoroutineScope,
    private val embedding: Embedding,
    private val strategy: ChunkingStrategyType,
    /** Имя облачной модели: его называет запуск страницы, а не транспорт. */
    private val cloudModel: String,
    /** Имя локальной модели — то, которым отвечает демон Ollama. */
    private val localModel: String,
    /** Ключ облака: `null` — облачная модель в сравнении не участвует, и это видно в состоянии. */
    private val apiKey: String?,
    /**
     * Как построить клиента для провайдера: живые клиенты ([Api.modelFor]) на странице, подставной
     * в тесте. Провайдер — параметр, а не поле сессии: сравнение работает ровно с двумя моделями
     * одновременно, и выбор одной из них окружением здесь был бы неверен.
     */
    private val client: (LlmProvider, String) -> LlmClient = Api::modelFor
) {

    private val lock = Any()

    private val base: DayBase? = runBlocking { Corpus.read(workDir) }

    private val index = RagIndex(workDir, embedding, strategy)

    private var state = StateDto.IDLE
    private var stage: String? = null
    private var error: String? = null
    private var done = 0
    private var total = 0
    private var repeats = DEFAULT_REPEATS
    private var questionIds: List<String> = emptyList()
    private var elapsedMs = 0L
    private var startedAt = 0L
    private var stageConfig: StageConfig? = null
    private var reports = emptyList<ReportDto>()

    /** Прогоны по моделям: заполняются по ходу работы, читаются состоянием. */
    private val runs = LinkedHashMap<LlmProvider, MutableList<ModelRun>>()

    /** Стабильность по модели: считается, когда повторов больше одного. */
    private val stability = LinkedHashMap<LlmProvider, CompareStabilityDto>()

    private var job: Job? = null

    @Volatile
    private var runId = 0L

    /**
     * Один прогон набора одной моделью: ответы, метрики и время.
     *
     * Мутируемый, в отличие от DTO: прогон идёт минутами, и состояние страницы показывает его
     * по ходу — иначе человек видел бы «идёт» без единого числа до самого конца.
     */
    private class ModelRun(val provider: LlmProvider, val number: Int) {
        var elapsedMs: Long = 0
        var config: StageConfig? = null
        /** Почему прогон не состоялся: причина первого отказа, если ни один ответ не вышел. */
        var failure: String? = null
        var quality: StageSummary? = null
        val modes = LinkedHashMap<String, Modes>()
        val trials = ArrayList<Trial>()
        val questionDtos = LinkedHashMap<String, CompareQuestionDto>()
    }

    /** Ответы одного вопроса в трёх режимах: чего ещё нет — `null`. */
    private data class Modes(
        val without: ModeResult? = null,
        val baseline: ModeResult? = null,
        val improved: ModeResult? = null
    )

    /** Текущее состояние сравнения: что идёт, что уже посчитано и с какими числами. */
    fun state(): CompareStateDto = synchronized(lock) {
        CompareStateDto(
            state = state,
            stage = stage,
            error = error,
            done = done,
            total = total,
            repeats = repeats,
            questions = questionIds,
            index = indexDto(),
            config = stageConfig?.toDto(),
            models = Planned.entries.map { planned ->
                val provider = planned.provider
                CompareModelDto(
                    provider = provider.wire,
                    title = planned.title,
                    model = planned.model(this@CompareSession),
                    available = planned.available(this@CompareSession),
                    note = planned.note(this@CompareSession),
                    failure = runs[provider]?.firstNotNullOfOrNull { it.failure },
                    repeats = runs[provider].orEmpty().map { it.toDto() },
                    stability = stability[provider]
                )
            },
            reports = reports,
            elapsedMs = elapsedMs
        )
    }

    /**
     * Запускает сравнение: набор вопросов, число повторов каждой модели и настройки этапов.
     *
     * Индекс не собирается здесь намеренно. Сборка — отдельное действие страницы (кнопка «Собрать
     * индекс»), и она общая у прогона и у сравнения: индексировать базу дважды значило бы получить
     * два файла и два разных поиска под одним сравнением. Поэтому у сравнения нет и своей
     * пересборки: сломать общий индекс оно не может.
     */
    suspend fun start(ids: List<String>, repeats: Int, settings: RunSettings): StartOutcome {
        if (base == null) return StartOutcome.Rejected(Corpus.MISSING_MESSAGE)
        if (ids.isEmpty()) return StartOutcome.Rejected(RagSession.NO_QUESTIONS_MESSAGE)
        if (repeats < 1 || repeats > MAX_REPEATS) return StartOutcome.Rejected(repeatsMessage(repeats))
        settings.validate()?.let { return StartOutcome.Rejected(it) }
        val unknown = ids.filter { Controls.byId(it) == null }
        if (unknown.isNotEmpty()) {
            return StartOutcome.Rejected("В наборе нет вопросов: ${unknown.joinToString(", ")}")
        }
        if (!index.exists) return StartOutcome.Rejected(INDEX_MISSING_MESSAGE)

        synchronized(lock) { job?.takeIf { it.isActive } }?.let { previous ->
            previous.cancel()
            withTimeoutOrNull(STOP_TIMEOUT_MS) { previous.join() }
        }
        synchronized(lock) {
            // Номер выдаётся до очистки: вытесненная работа увидит чужой номер и не допишет
            // свои ответы поверх нового сравнения.
            val token = occupy()
            state = StateDto.RUNNING
            questionIds = ids
            this.repeats = repeats
            total = (Planned.entries.count { it.available(this) } * repeats).coerceAtLeast(1)
            job = scope.launch { runJob(token, ids, requireNotNull(base), repeats, settings) }
        }
        return StartOutcome.Accepted
    }

    /** Останавливает сравнение: посчитанное остаётся на странице, работа прекращается. */
    suspend fun stop(): Boolean {
        val running = synchronized(lock) {
            val current = job?.takeIf { it.isActive } ?: return false
            if (state == StateDto.RUNNING) {
                state = StateDto.STOPPED
                stage = null
                elapsedMs = if (startedAt > 0) (System.nanoTime() - startedAt) / 1_000_000 else elapsedMs
            }
            current
        }
        running.cancel()
        withTimeoutOrNull(STOP_TIMEOUT_MS) { running.join() }
        synchronized(lock) {
            job = null
            runId++
        }
        return true
    }

    /**
     * Сравнение целиком: для каждой модели — свои повторы, для каждого повтора — весь набор вопросов.
     *
     * Порядок фиксирован и читается как матрица: сначала все повторы локальной модели, потом
     * облачной. Модели не чередуются по вопросам намеренно: у Ollama модель держится в памяти
     * демона, и переключение между двумя моделями на каждом вопросе стоило бы времени на загрузку,
     * а числа скорости показывали бы не модель, а переключение.
     */
    private suspend fun runJob(token: Long, ids: List<String>, current: DayBase, repeats: Int, settings: RunSettings) {
        val started = System.nanoTime()
        val planned = Planned.entries.filter { it.available(this) }
        try {
            for (plan in planned) {
                val provider = plan.provider
                for (number in 1..repeats) {
                    alive(token)
                    runOnce(token, plan, number, ids, current, settings)
                    synchronized(lock) {
                        if (token == runId) done++
                    }
                    alive(token)
                }
                // Стабильность считается после всех повторов модели: сравнить повтор с повтором
                // можно только тогда, когда оба уже есть.
                stabilityFor(token, provider)
                alive(token)
            }
            synchronized(lock) { if (token == runId) done = total }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            // Ловим и `Error`, а не только `Exception`: `finally` ниже объявляет сравнение
            // законченным, и `Error`, прошедший мимо этой ветки, превратился бы в «сравнение прошло,
            // прогонов 0 из N» — пустая таблица выглядела бы как результат сравнения. Так и было,
            // когда Gradle перезаписал jar под работающей страницей и прогон упал с
            // `NoClassDefFoundError` на не загруженном ещё классе.
            synchronized(lock) {
                if (token == runId) {
                    state = StateDto.FAILED
                    error = failureMessage(cause)
                }
            }
        } finally {
            synchronized(lock) {
                if (token == runId) {
                    elapsedMs = (System.nanoTime() - started) / 1_000_000
                    stage = null
                    job = null
                    if (state != StateDto.FAILED && state != StateDto.STOPPED) state = StateDto.DONE
                }
            }
        }
    }

    /** Один повтор набора одной моделью: три режима на каждый вопрос и метрики этого повтора. */
    private suspend fun runOnce(
        token: Long,
        plan: Planned,
        number: Int,
        ids: List<String>,
        current: DayBase,
        settings: RunSettings
    ) {
        val client = client(plan.provider, apiKey.orEmpty())
        val runner = QuestionRunner(client, plan.model(this), settings, index)
        val run = ModelRun(plan.provider, number)
        run.config = runner.config
        synchronized(lock) {
            alive(token)
            if (token == runId) stageConfig = runner.config
            runs.getOrPut(plan.provider) { ArrayList() }.add(run)
        }
        val started = System.nanoTime()
        for (id in ids) {
            alive(token)
            val control = requireNotNull(Controls.byId(id))
            synchronized(lock) {
                if (token == runId) stage = "${plan.title} · повтор $number · $id"
            }
            val without = runner.without(control)
            val baseline = runner.baseline(control)
            // Поиск улучшенного режима — один на вопрос, как и в дне 24: второй поиск дал бы другую
            // выдачу, и ответ улучшенного режима относился бы к другому контексту, чем у соседнего
            // режима того же вопроса.
            val improved = try {
                runner.improved(control, runner.find(control.question))
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                ModeResult(control, Mode.WITH_RAG, answer = null, check = null, error = failureMessage(cause))
            }
            synchronized(lock) {
                alive(token)
                if (token != runId) return
                run.modes[id] = Modes(without, baseline, improved)
                run.questionDtos[id] = questionDto(control, Modes(without, baseline, improved))
                allRun(run, control, Modes(without, baseline, improved))?.let { run.trials += it }
            }
            alive(token)
        }
        val quality = Stages.summary(run.trials)
        synchronized(lock) {
            alive(token)
            if (token != runId) return
            run.elapsedMs = (System.nanoTime() - started) / 1_000_000
            run.quality = quality
            // Ни один ответ не вышел — это провал прогона, а не «модель ответила на нули»:
            // пустые колонки читались бы как результат сравнения, поэтому причина называется
            // на уровне модели, а не только внутри каждого ответа.
            if (run.trials.isEmpty()) {
                run.failure = run.modes.values
                    .flatMap { listOfNotNull(it.without, it.baseline, it.improved) }
                    .firstNotNullOfOrNull { it.error?.takeIf { _ -> it.failed } }
                    ?.let { "Прогон не состоялся: $it" }
            }
        }
        writeReports(token, run, current, settings)
    }

    /**
     * Собирает прогон вопроса в [Trial], когда ответили все три режима.
     *
     * Неполный вопрос в метрики не попадает: метрика этапов считается по вопросам, где есть все три
     * ответа, и добавление вопроса с отказавшим режимом занижало бы средние оценки — отказ модели
     * не то же самое, что её неверный ответ.
     */
    private fun allRun(run: ModelRun, control: ControlQuestion, modes: Modes): Trial? {
        val without = modes.without?.run()
        val baseline = modes.baseline?.run()
        val improved = modes.improved?.run()
        if (without == null || baseline == null || improved == null) return null
        if (run.trials.any { it.control.id == control.id }) return null
        return Trial(control, without, baseline, improved)
    }

    /** Режим в том виде, в каком его принимает [Trial]: без ответа и сверки прогона нет. */
    private fun ModeResult.run(): Run? = when {
        failed -> null
        answer == null || check == null -> null
        else -> Run(control, answer, check)
    }

    /** Ответ вопроса улучшенным RAG — то, что читает страница сравнения. */
    private fun questionDto(control: ControlQuestion, modes: Modes): CompareQuestionDto = CompareQuestionDto(
        id = control.id,
        question = control.question,
        absent = control.absent,
        improved = modes.improved?.toCompare()
    )

    /**
     * Ответ улучшенного RAG: оценка, исход, расход и время.
     *
     * Отдельно от [ModeDto] намеренно: страница сравнения показывает не путь запроса, а вердикт,
     * и тащить в неё контекст, фрагменты и сообщения запроса значило бы гонять по сети десятки
     * копий одного текста ради двух чисел. Путь запроса остаётся у прогона — он там нужен целиком.
     */
    private fun ModeResult.toCompare(): CompareAnswerDto {
        val answer = answer
        val check = check
        return CompareAnswerDto(
            state = if (failed) ModeDto.FAILED else ModeDto.DONE,
            error = error,
            text = answer?.text.orEmpty(),
            score = check?.score ?: 0,
            matched = check?.matched ?: 0,
            total = check?.total ?: control.facts.size,
            outcome = if (check == null) "" else Check.outcome(control, check).title,
            hit = if (control.absent || check == null) null else check.retrievedFacts.any { it },
            promptTokens = answer?.promptTokens,
            completionTokens = answer?.completionTokens,
            retrievalMillis = answer?.retrieval?.millis ?: 0L,
            elapsedMillis = answer?.elapsedMillis ?: 0L,
            sources = answer?.sources?.size ?: 0
        )
    }

    /** Прогон в виде, который читает страница: качество, скорость и ответы. */
    private fun ModelRun.toDto(): CompareRepeatDto = synchronized(lock) {
        CompareRepeatDto(
            number = number,
            elapsedMs = elapsedMs,
            quality = (quality ?: Stages.summary(trials)).toCompareQuality(),
            speed = speedOf(),
            questions = questionDtos.values.toList()
        )
    }

    /**
     * Качество улучшенного RAG в узком виде: те же числа [StagesDto], но те, по которым сравнивают.
     *
     * Отбор одного режима, а не второй расчёт: средние, попадания и потери улучшенного режима
     * `Stages` уже посчитала по тем же вопросам, и брать их оттуда — единственный способ не завести
     * вторую формулу рядом с первой.
     */
    private fun StageSummary.toCompareQuality(): CompareQualityDto = CompareQualityDto(
        questions = questions,
        scored = scored,
        score = improvedScore,
        facts = improvedFacts,
        factsTotal = factsTotal,
        hits = improvedHits,
        removed = removed,
        wronglyFiltered = wronglyFiltered,
        emptyContext = emptyContext,
        lostInRetrieval = lostInRetrieval,
        lostInFilter = lostInFilter,
        lostInRerank = lostInRerank
    )

    /**
     * Скорость прогона на улучшенном RAG: токены, генерация и поиск по его ответам.
     *
     * Считается по ответам, а не по метрикам дня 22: у того отчёта такие числа есть только для двух
     * режимов — «без базы» и «с базой», — а сравнивают улучшенный. Формула при этом одна и та же
     * (сумма по ответам), и она здесь единственная.
     */
    private fun ModelRun.speedOf(): CompareSpeedDto = synchronized(lock) {
        val answers = modes.values.mapNotNull { it.improved?.answer }
        CompareSpeedDto(
            improved = tokens(answers),
            answers = answers.size,
            answerMillis = answers.sumOf { it.elapsedMillis },
            retrievalMillis = answers.sumOf { it.retrieval?.millis ?: 0L }
        )
    }

    /**
     * Расход режима: сколько токенов запрошено и получено и у скольких ответов API их сообщил.
     *
     * [TokensUsed.reported] — не украшение: не все ответы возвращаются с числами токенов, и без него
     * сумма выглядела бы полной, хотя посчитана по части ответов.
     */
    private fun tokens(answers: List<Answer>): TokensUsed = TokensUsed(
        prompt = answers.sumOf { it.promptTokens ?: 0 },
        completion = answers.sumOf { it.completionTokens ?: 0 },
        reported = answers.count { it.promptTokens != null || it.completionTokens != null }
    )

    /**
     * Стабильность модели: повтор против повтора по каждому вопросу улучшенного RAG.
     *
     * Сравниваются соседние повторы, а не первый с последним: при двух повторах это одно и то же,
     * а при большем их числе рядом стоящие пары показывают, на каком повторе модель поехала.
     */
    private fun stabilityFor(token: Long, provider: LlmProvider) {
        val list = synchronized(lock) { runs[provider].orEmpty().toList() }
        if (list.size < 2) return
        val pairs = ArrayList<ComparePairDto>()
        var compared = 0
        var identical = 0
        var scoreChanged = 0
        var scoreUp = 0
        var scoreDown = 0
        var similaritySum = 0.0
        for (index in 1 until list.size) {
            val before = list[index - 1]
            val after = list[index]
            for (id in before.questionDtos.keys) {
                // Один режим — улучшенный: сравнение идёт по нему, и стабильность считается по тому же
                // пути, которым модели сравнивают. Стабильность двух других режимов описывала бы
                // ступени конвейера, а не систему.
                run {
                    val first = before.improvedOf(id) ?: return@run
                    val second = after.improvedOf(id) ?: return@run
                    compared++
                    val same = first.text == second.text
                    val similarity = if (same) 1.0 else textSimilarity(first.text, second.text)
                    similaritySum += similarity
                    if (same) identical++
                    if (first.score != second.score) {
                        scoreChanged++
                        if (second.score > first.score) scoreUp++ else scoreDown++
                    }
                    if (!same) {
                        pairs += ComparePairDto(
                            id = id,
                            identical = false,
                            similarity = similarity,
                            scoreBefore = first.score,
                            scoreAfter = second.score,
                            before = first.text,
                            after = second.text
                        )
                    }
                }
            }
        }
        if (compared == 0) return
        val dto = CompareStabilityDto(
            compared = compared,
            identical = identical,
            changed = compared - identical,
            scoreChanged = scoreChanged,
            scoreUp = scoreUp,
            scoreDown = scoreDown,
            averageSimilarity = similaritySum / compared,
            // Средняя оценка повторов: складываются все ответы всех сравнённых пар, и первый повтор
            // попадает в неё столько раз, сколько у него соседей, — при двух повторах это ровно
            // «средняя оценка первого прогона» и «средняя оценка второго».
            averageScoreFirst = averageScore(list, 0),
            averageScoreLast = averageScore(list, list.size - 1),
            pairs = pairs
        )
        synchronized(lock) { if (token == runId) stability[provider] = dto }
    }

    /** Средняя оценка улучшенного режима в одном повторе: по ней видно, разъехались прогоны или нет. */
    private fun averageScore(list: List<ModelRun>, number: Int): Double {
        val answers = synchronized(lock) {
            list[number].questionDtos.values.mapNotNull { it.improved }.filter { it.state == ModeDto.DONE }
        }
        return if (answers.isEmpty()) 0.0 else answers.sumOf { it.score }.toDouble() / answers.size
    }

    /** Ответ одного вопроса в одном режиме: по нему сравниваются повторы. */
    private fun ModelRun.improvedOf(id: String): CompareAnswerDto? = synchronized(lock) {
        questionDtos[id]?.improved
    }

    /**
     * Отчёт о прогоне: тот же файл, что у дня 23, только с именем модели и повтора в имени файла.
     *
     * Пишется тем же [StageReport]: сравнение — это те же десять вопросов и тот же конвейер, и второй
     * формат отчёта заставил бы читать два файла двумя способами.
     */
    private fun writeReports(token: Long, run: ModelRun, current: DayBase, settings: RunSettings) {
        val trials = synchronized(lock) {
            alive(token)
            run.trials.toList()
        }
        if (trials.isEmpty()) return
        val config = synchronized(lock) { run.config ?: stageConfig } ?: return
        val header = RunHeader(
            model = if (run.provider == LlmProvider.LOCAL) localModel else cloudModel,
            embedding = embedding.name,
            strategy = strategy,
            topK = settings.baselineTopK,
            baseChars = current.chars,
            baseTokens = current.tokens,
            basePages = current.pages.size,
            chunks = runCatching { index.chunkCount() }.getOrDefault(0),
            reused = true,
            indexMillis = 0L
        )
        val stages = Stages.summary(trials)
        val report = StageReport(header, config, Report(header))
        val dir = workDir.resolve(COMPARE_DIR)
        Files.createDirectories(dir)
        val file = dir.resolve("${run.provider.wire}-repeat-${run.number}.md")
        Files.writeString(file, report.markdown(trials, stages))
        synchronized(lock) {
            alive(token)
            reports = reports + ReportDto(
                name = "${run.provider.wire} · повтор ${run.number}",
                file = file.toAbsolutePath().toString()
            )
        }
    }

    private fun occupy(): Long = synchronized(lock) {
        runId++
        state = StateDto.RUNNING
        stage = null
        error = null
        elapsedMs = 0
        startedAt = System.nanoTime()
        done = 0
        runs.clear()
        stability.clear()
        reports = emptyList()
        runId
    }

    /** Индекс, по которому идёт сравнение: тот же файл, что у прогона страницы. */
    private fun indexDto(): IndexDto {
        val chunks = runCatching { index.chunkCount() }.getOrDefault(0)
        return IndexDto(
            file = index.file.toAbsolutePath().toString(),
            strategy = strategy.name.lowercase(),
            chunks = chunks,
            total = chunks,
            state = if (index.exists) IndexDto.READY else IndexDto.IDLE
        )
    }

    private fun alive(token: Long) {
        if (token != runId) throw CancellationException("Сравнение вытеснено новым запуском")
    }

    /** Модели сравнения и то, чем каждая отвечает на этой машине. */
    private enum class Planned(val provider: LlmProvider, val title: String) {
        LOCAL(LlmProvider.LOCAL, LOCAL_TITLE),
        CLOUD(LlmProvider.CLOUD, CLOUD_TITLE);

        fun model(session: CompareSession): String =
            if (provider == LlmProvider.LOCAL) session.localModel else session.cloudModel

        fun available(session: CompareSession): Boolean =
            provider == LlmProvider.LOCAL || !session.apiKey.isNullOrBlank()

        fun note(session: CompareSession): String? = when {
            provider == LlmProvider.LOCAL -> null
            session.apiKey.isNullOrBlank() -> NO_CLOUD_MESSAGE
            else -> null
        }
    }

    companion object {

        /** Подкаталог отчётов сравнения: свои имена, чтобы не затирать отчёты прогона. */
        const val COMPARE_DIR = "compare"

        /**
         * Сколько повторов по умолчанию — один.
         *
         * Один повтор — это полное сравнение качества и скорости: две модели на одном наборе.
         * Второй повтор ничего не добавляет к ним, зато добавляет разрез стабильности, а стоит
         * ещё одного полного набора на каждой модели, поэтому его включают осознанно.
         */
        const val DEFAULT_REPEATS = 1

        /** Верхняя граница повторов: каждый повтор — это ещё десять вопросов на каждой модели. */
        const val MAX_REPEATS = 5

        const val LOCAL_TITLE = "локальная модель"
        const val CLOUD_TITLE = "облачная модель"

        const val NO_CLOUD_MESSAGE =
            "Облачной модели нет: ключ DEEPSEEK_API_KEY не найден — сравнение идёт по локальной модели"

        const val INDEX_MISSING_MESSAGE =
            "Индекс не собран: соберите его кнопкой на странице — сравнение идёт по готовому индексу"

        private const val STOP_TIMEOUT_MS = 5_000L



        private fun repeatsMessage(repeats: Int): String =
            "Число повторов «$repeats» не подходит: нужно от 1 до $MAX_REPEATS"

        /**
         * Схожесть двух ответов: доля совпавшего текста, 1 — дословно.
         *
         * Считается как у `difflib.SequenceMatcher.ratio`: удвоенное число совпавших символов
         * (длина наибольшей общей подпоследовательности) к их общему числу. Числа этой меры
         * сравнимы с теми, что получаются при разборе отчётов вне страницы, и это важно:
         * стабильность мерилась разными способами в одном отчёте и в подписи на экране — тогда
         * расхождение подписей выглядело бы как расхождение моделей.
         *
         * Наибольшая общая подпоследовательность, а не расстояние правки: ответы отличаются
         * вставками и перестановками слов, и «почти такой же ответ» должен получать высокое число
         * независимо от того, вставлено ли слово в середину или заменено целиком.
         */
        fun textSimilarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var previous = IntArray(b.length + 1)
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    current[j] = if (a[i - 1] == b[j - 1]) {
                        previous[j - 1] + 1
                    } else {
                        maxOf(previous[j], current[j - 1])
                    }
                }
                val swap = previous
                previous = current
                current = swap
                java.util.Arrays.fill(current, 0)
            }
            return 2.0 * previous[b.length] / (a.length + b.length)
        }
    }
}
