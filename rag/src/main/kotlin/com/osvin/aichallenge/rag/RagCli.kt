package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.EmbeddingProviders
import com.osvin.aichallenge.indexing.pipeline.Tokens
import com.osvin.aichallenge.indexing.ui.PdfLoadException
import com.osvin.aichallenge.models.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Прогон дня 22: `./gradlew :rag:ragDemo` (живой режим — `-Pdemo.live=1`).
 *
 * Прогон отвечает на десять контрольных вопросов ([Controls]) дважды — сначала без базы, потом
 * с найденными фрагментами, — сверяет ответы с ожиданиями набора и печатает сравнение. Порядок
 * режимов фиксирован и одинаков для всех вопросов: обращения к модели идут по очереди, и разный
 * порядок для разных вопросов сделал бы числа несравнимыми между собой.
 *
 * Живой режим включается флагом, а не наличием ключа: прогон стоит денег и идёт минутами, поэтому
 * запуск без флага обязан закончиться подсказкой, а не двадцатью запросами. Ключ берётся оттуда
 * же, откуда его берёт сервер ([Api.key]), и нигде не печатается.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (cause: IllegalArgumentException) {
        println(cause.message)
        println(USAGE)
        exitProcess(2)
    }

    if (!live()) {
        println("Живой прогон выключен: нужен флаг -Pdemo.live=1 и ключ DEEPSEEK_API_KEY.")
        println(USAGE)
        return
    }

    val apiKey = Api.key()
    if (apiKey == null) {
        println("Ключ DEEPSEEK_API_KEY не задан: положите его в server/.env или задайте переменной окружения.")
        println(USAGE)
        return
    }

    try {
        runBlocking { if (options.grounded) runGrounding(options, apiKey) else run(options, apiKey) }
    } catch (cause: PdfLoadException) {
        println("База дня не разобралась: ${cause.message}")
        exitProcess(1)
    } catch (cause: LlmApiException) {
        // Ответ API нужен человеку целиком: он объясняет, дело в ключе, в модели или в лимите.
        println("Модель отказала: статус ${cause.status}, ${cause.message}")
        exitProcess(1)
    }
}

/**
 * Прогон дня 23: база, индекс, три режима ответа, отчёт и эксперименты.
 *
 * Режимов три, и они отвечают на разные вопросы сравнения. **Без базы** — нижняя граница: что модель
 * помнит сама. **Базовый RAG** — то, что дал поиск дня 22 (Top-K по близости), и он же учтён
 * в метриках дня 22. **Улучшенный RAG** — конвейер с переписыванием запроса, расширенной выдачей,
 * порогом и вторым этапом. Все три идут по одному индексу и одной модели: иначе разница между
 * колонками была бы разницей настроек, а не этапов.
 *
 * Порядок вызовов внутри вопроса фиксирован (без базы → базовый → улучшенный) и одинаков для всех
 * вопросов: обращения к модели идут по очереди, и разный порядок сделал бы числа несравнимыми.
 *
 * Индекс переиспользуется, если он уже собран ([RagIndex.exists] и отсутствие `--rebuild`) —
 * этого и требует задание: поиск по индексу, а не индексация при каждом запросе. Пересборка остаётся
 * решением прогона, а не свойством запуска: она меняет базу, по которой получены ответы, и печатается
 * в отчёте отдельной строкой.
 */
private suspend fun run(options: Options, apiKey: String) {
    val embedding = EmbeddingProviders.fromEnv()
    println("Векторы: ${embedding.name} (${embedding.note})")

    val base = Corpus.read(options.dir)
    if (base == null) {
        println(Corpus.MISSING_MESSAGE)
        return
    }
    println("База: ${base.text} — ${base.chars} символов, ${base.tokens} токенов, страниц ${base.pages.size}")

    val index = RagIndex(options.dir, embedding, options.strategy)

    // Индекс пересобирается только тогда, когда его нет или когда об этом попросили явно:
    // индексировать базу заново перед каждым запросом не нужно, а два индекса одного корпуса
    // дали бы две разные выдачи под одним отчётом.
    val reuse = index.exists && !options.rebuild
    val built = if (reuse) null else index.build(base) { done, total ->
        // Прогресс по каждому чанку был бы потоком строк, а индексация занимает секунды:
        // человеку достаточно видеть, что прогон не встал.
        if (done == total || done % PROGRESS_STEP == 0) println("  индексация: $done из $total")
    }
    val chunks = built?.chunks?.size ?: index.chunkCount()
    val indexMillis = built?.elapsedMillis ?: 0L
    println(
        if (reuse) {
            "Индекс уже собран: ${index.file} — $chunks чанков, переиндексации нет (--rebuild пересоберёт)"
        } else {
            val chunkTokens = built!!.chunks.sumOf { Tokens.count(it.content) }
            "Индекс: ${index.file} — $chunks чанков, $chunkTokens токенов, " +
                "${"%.1f".format(indexMillis / 1000.0)} с"
        }
    )

    val llm = Api.model(apiKey)
    val model = options.model
    val rewriter = options.rewriterOf(llm, model)
    val reranker = options.rerankerOf(llm, model)

    // Два поиска над одним индексом: базовому режиму нужны три ближайших чанка, конвейеру — десять
    // кандидатов. Один индекс читается дважды, но остаётся одним и тем же файлом: сравнение режимов
    // не должно зависеть от того, какой из них собрал хранилище.
    val baselineFinder = index.retriever(options.topK)
    val candidateFinder = index.retriever(options.retrievalTopK)
    val pipeline = RagPipeline(
        finder = candidateFinder,
        rewriter = rewriter,
        filter = SimilarityFilter(options.threshold),
        reranker = reranker,
        finalTopK = options.finalTopK
    )

    val baseAgent = RagAgent(llm, baselineFinder, model)
    val improvedAgent = RagAgent(llm, pipeline, model)

    val trials = options.questions().map { control ->
        val noBase = ask(baseAgent, control, Mode.WITHOUT_RAG)
        val baseline = ask(baseAgent, control, Mode.WITH_RAG)
        val improved = ask(improvedAgent, control, Mode.WITH_RAG)
        val check = Stages.evaluate(control, improved.answer.retrieval.trace)
        println(
            "${control.id} (${Check.outcome(control, improved.check).title}): " +
                "без базы ${noBase.check.score} из 2, базовый ${baseline.check.score} из 2, " +
                "улучшенный ${improved.check.score} из 2" +
                (check?.let { ", Hit@${options.finalTopK}: ${if (it.inFinal) "да" else "нет"} (${it.loss.title})" } ?: "")
        )
        Trial(control = control, noBase = noBase, baseline = baseline, improved = improved)
    }

    val header = RunHeader(
        model = model,
        embedding = embedding.name,
        strategy = options.strategy,
        topK = options.topK,
        baseChars = base.chars,
        baseTokens = base.tokens,
        basePages = base.pages.size,
        chunks = chunks,
        reused = reuse,
        indexMillis = indexMillis
    )
    val report = StageReport(
        header = header,
        stages = StageConfig(
            baselineTopK = options.topK,
            retrievalTopK = options.retrievalTopK,
            finalTopK = options.finalTopK,
            threshold = options.threshold,
            rewrite = rewriter?.name,
            rerank = reranker?.name
        ),
        report = Report(header)
    )

    val summary = Stages.summary(trials)
    println()
    println(report.console(trials, summary))

    val comparison = options.dir.resolve(REPORT_FILE)
    Files.writeString(comparison, report.markdown(trials, summary))
    val log = options.dir.resolve(LOG_FILE)
    Files.writeString(log, report.log(trials))
    println("Ответы целиком и разбор этапов: $comparison")
    println("Путь запросов по этапам: $log")

    runSweep(options, index, improvedAgent, report)
    runRewriteCheck(options, index, base, report, rewriter)
}

/**
 * Прогон дня 24: grounded-ответы против ответов предыдущего дня на одних и тех же выдачах.
 *
 * Главное решение прогона — **выдача ищется один раз на вопрос** и уходит в оба режима. Предыдущий
 * отвечает по ней так же, как отвечал в дне 23 (тот же промпт, тот же контекст), grounded — по ней же,
 * но с проверкой достаточности и требованием цитат. Именно это делает сравнение сравнением дня 24:
 * разница между колонками — разница проверок, а не поиска, и вопрос «стало ли проверяемо» отделяется
 * от вопроса «стало ли точнее». Второй поиск дал бы другую выдачу, и разницу можно было бы списать
 * на шум эмбеддингов.
 *
 * Правило «модель не отвечает при слабом контексте» проверяется порядком вызовов, а не обещанием:
 * при недостаточном контексте [GroundedAgent] возвращает отказ, не обращаясь к модели, и в логе
 * такого вопроса контекста нет вовсе. Предыдущий режим при этом спрашивается всегда — иначе
 * сравнивать было бы не с чем, и «grounded отказался» ничего не говорило бы о том, отвечал ли
 * на этот вопрос обычный RAG.
 *
 * Порог достаточности — тот же, что у фильтра (задание §8): второе число для «хватает ли контекста»
 * разошлось бы с первым, и объяснить отказ разными порогами было бы нечем.
 */
private suspend fun runGrounding(options: Options, apiKey: String) {
    val embedding = EmbeddingProviders.fromEnv()
    println("Векторы: ${embedding.name} (${embedding.note})")

    val base = Corpus.read(options.dir)
    if (base == null) {
        println(Corpus.MISSING_MESSAGE)
        return
    }
    println("База: ${base.text} — ${base.chars} символов, ${base.tokens} токенов, страниц ${base.pages.size}")

    val index = RagIndex(options.dir, embedding, options.strategy)
    val reuse = index.exists && !options.rebuild
    val built = if (reuse) null else index.build(base) { done, total ->
        if (done == total || done % PROGRESS_STEP == 0) println("  индексация: $done из $total")
    }
    val chunks = built?.chunks?.size ?: index.chunkCount()
    val indexMillis = built?.elapsedMillis ?: 0L
    println(
        if (reuse) "Индекс уже собран: ${index.file} — $chunks чанков, переиндексации нет (--rebuild пересоберёт)"
        else "Индекс: ${index.file} — $chunks чанков, ${"%.1f".format(indexMillis / 1000.0)} с"
    )

    val llm = Api.model(apiKey)
    val model = options.model
    val rewriter = options.rewriterOf(llm, model)
    val reranker = options.rerankerOf(llm, model)
    val pipeline = RagPipeline(
        finder = index.retriever(options.retrievalTopK),
        rewriter = rewriter,
        filter = SimilarityFilter(options.threshold),
        reranker = reranker,
        finalTopK = options.finalTopK
    )
    val previous = RagAgent(llm, pipeline, model)
    val grounded = GroundedAgent(llm, model, options.threshold, jsonFormat = !options.plainText)

    val questions = options.questions()
    val trials = questions.map { control ->
        // Одна выдача на два ответа: [RagPipeline.find] вызывается здесь, а не внутри агента,
        // чтобы grounded-режим не искал второй раз и в сравнение не попала разница выдач.
        val found = pipeline.find(control.question)
        val answer = previous.askWith(control.question, Mode.WITH_RAG, found)
        val previousRun = Run(control, answer, Check.evaluate(control, answer.text, answer.sources))
        val groundedAnswer = grounded.answer(control.question, found)
        val trial = GroundedTrial(
            control = control,
            previous = previousRun,
            grounded = groundedAnswer,
            check = Grounding.check(control, previousRun, groundedAnswer)
        )
        println(
            "${control.id}: предыдущий ${if (trial.check.previousCorrect) "верно" else "нет"}, " +
                "grounded ${if (trial.check.correct) "верно" else "нет"}, " +
                "источников ${groundedAnswer.sources.size}, цитат ${groundedAnswer.claims.size}" +
                (groundedAnswer.refusal?.let { ", отказ: ${it.title}" } ?: "") +
                (groundedAnswer.note?.let { " ($it)" } ?: "")
        )
        trial
    }

    val header = RunHeader(
        model = model,
        embedding = embedding.name,
        strategy = options.strategy,
        topK = options.topK,
        baseChars = base.chars,
        baseTokens = base.tokens,
        basePages = base.pages.size,
        chunks = chunks,
        reused = reuse,
        indexMillis = indexMillis
    )
    val report = GroundedReport(
        header = header,
        stages = StageConfig(
            baselineTopK = options.topK,
            retrievalTopK = options.retrievalTopK,
            finalTopK = options.finalTopK,
            threshold = options.threshold,
            rewrite = rewriter?.name,
            rerank = reranker?.name
        ),
        jsonFormat = !options.plainText
    )

    val summary = Grounding.summary(trials.map { it.check }, questions.sumOf { it.facts.size })
    println()
    println(report.console(trials, summary))

    val comparison = options.dir.resolve(REPORT_FILE)
    Files.writeString(comparison, report.markdown(trials, summary))
    val log = options.dir.resolve(LOG_FILE)
    Files.writeString(log, report.log(trials))
    println("Ответы, источники и цитаты: $comparison")
    println("Путь запроса по этапам: $log")
}

/**
 * Подбор порога (§18): таблица значений на одной выдаче, с ответами модели на каждом пороге.
 *
 * Отсутствие порогов в запуске — не ошибка: обычный прогон их не считает. Когда пороги заданы,
 * эксперимент идёт после сравнения и пишет отдельный файл: таблица порогов — не сравнение режимов,
 * и в общем отчёте она читалась бы как его часть.
 */
private suspend fun runSweep(
    options: Options,
    index: RagIndex,
    agent: RagAgent,
    report: StageReport
) {
    if (options.thresholds.isEmpty()) return

    println()
    println("Подбор порога: ${options.thresholds.joinToString(", ") { "%.2f".format(it) }}")

    // Пороги считаются без переписывания и с эвристическим вторым этапом: цель — измерить влияние
    // порога, а не совместное влияние порога, переписывания и оценок модели.
    val sweep = ThresholdSweep(
        finder = index.retriever(options.retrievalTopK),
        reranker = HeuristicReranker(),
        finalTopK = options.finalTopK,
        thresholds = options.thresholds
    )
    val controls = options.questions()
    val rows = sweep.run(controls) { control, retrieval ->
        // Ответы спрашиваются теми же правилами, что в улучшенном режиме: контекст готов, и
        // [RagAgent.askWith] не идёт в поиск второй раз.
        val answer = agent.askWith(control.question, Mode.WITH_RAG, retrieval)
        Check.evaluate(control, answer.text, answer.sources)
    }

    val text = report.sweep(rows, controls.count { !it.absent })
    for (row in rows) {
        println(
            "  порог ${"%.2f".format(row.threshold)}: остаётся ${"%.1f".format(row.kept)} кандидатов, " +
                "отсеяно ${row.removed} (правильных ${row.wronglyFiltered}), " +
                "Hit после фильтра ${row.filterHits}, Hit@${options.finalTopK} ${row.finalHits}, " +
                "средняя оценка ${row.score?.let { "%.2f".format(it) } ?: "—"}"
        )
    }
    val file = options.dir.resolve(SWEEP_FILE)
    Files.writeString(file, text)
    println("Подбор порога: $file")
    println()
    println(text)
}

/**
 * Проверка переписывания (§16): поиск по исходному запросу против поиска по переписанному.
 *
 * Идёт последней, потому что требует тех же переписываний, что уже были в прогоне, и без живого
 * ключа невозможна: это единственная часть отчёта, которая без модели не считается.
 */
private suspend fun runRewriteCheck(
    options: Options,
    index: RagIndex,
    base: DayBase,
    report: StageReport,
    rewriter: QueryRewriter?
) {
    if (!options.rewriteCheck || rewriter == null) return

    println()
    println("Проверка Query Rewrite (${rewriter.name})")
    val eval = RewriteEval(
        finder = index.retriever(options.retrievalTopK),
        corpus = base.document.files.joinToString("\n") { it.content },
        rewrite = rewriter
    )
    val cases = eval.run(options.questions())
    val summary = RewriteSummary.of(cases)
    val text = report.rewrite(cases, summary)
    val file = options.dir.resolve(REWRITE_FILE)
    Files.writeString(file, text)
    println(
        "Переписывание помогло в ${summary.helped}, навредило в ${summary.hurt}, " +
            "не изменило поиск в ${summary.same}; потерянных имён ${summary.lostNames}, " +
            "выдуманных ${summary.inventedNames}"
    )
    println("Проверка переписывания: $file")
}

/** Один вопрос в одном режиме: ответ модели и его сверка с ожиданием набора. */
private suspend fun ask(agent: RagAgent, control: ControlQuestion, mode: Mode): Run {
    val answer = agent.ask(control.question, mode)
    return Run(control, answer, Check.evaluate(control, answer.text, answer.sources))
}

/**
 * Включён ли живой режим: флаг Gradle (`-Pdemo.live=1`) или переменная окружения.
 *
 * Признак остаётся у прогона, а не у общего [Api]: страница включается нажатием кнопки, и флаг
 * ей не нужен, а прогон в консоли стоит денег и минут, поэтому без флага обязан остановиться.
 */
private fun live(): Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/**
 * Что можно задать прогону.
 *
 * Каталог задаётся аргументом, потому что прогон пишет туда индекс и отчёт: держать это в коде
 * значило бы перезаписывать результаты прошлого раза молча. Остальные параметры — условия
 * сравнения, и они тоже видны в отчёте: без них числа нельзя ни повторить, ни объяснить.
 */
private data class Options(
    val dir: Path,
    /** Top-K базового режима дня 22: столько фрагментов берёт поиск по близости. */
    val topK: Int,
    /** Top-K первого этапа улучшенного конвейера: столько кандидатов он просит у поиска. */
    val retrievalTopK: Int,
    /** Final Top-K: столько фрагментов улучшенного конвейера уходит в контекст. */
    val finalTopK: Int,
    /** Порог фильтрации; `0.0` — фильтра нет. */
    val threshold: Double,
    /** Вариант переписывания: `none` или `llm`. */
    val rewrite: String?,
    /** Вариант второго этапа: `none`, `heuristic` или `llm`. */
    val rerank: String?,
    val strategy: ChunkingStrategyType,
    val model: String,
    /** Ограничение набора: пусто — берутся все десять вопросов. */
    val only: List<String>,
    /** Пересобрать индекс, даже если он уже есть. */
    val rebuild: Boolean,
    /** Пороги для подбора (§18): пусто — подбор не запускается. */
    val thresholds: List<Double>,
    /** Проверять ли переписывание сравнением двух выдач (§16). */
    val rewriteCheck: Boolean,
    /** Режим дня 24: ответы с источниками, цитатами и проверкой достаточности вместо сравнения моделей. */
    val grounded: Boolean,
    /** Не просить у API ответ строго в виде JSON: проверка формата остаётся, но модель её не видит. */
    val plainText: Boolean
) {

    /** Вопросы прогона: набор целиком или выбранные по номерам. */
    fun questions(): List<ControlQuestion> =
        if (only.isEmpty()) Controls.questions else Controls.questions.filter { it.id in only }

    /** Переписыватель по имени варианта: `null` — этапа нет. */
    fun rewriterOf(llm: LlmClient, model: String): QueryRewriter? = when (rewrite) {
        null -> null
        "llm" -> LlmQueryRewriter(llm, model)
        else -> error("Вариант переписывания не поддержан: $rewrite")
    }

    /**
     * Второй этап по имени варианта: `null` — этапа нет.
     *
     * Имена, а не объекты в разборе аргументов, потому что для обоих вариантов нужен клиент модели,
     * а он появляется только после проверки ключа: собирать клиент в разборе значило бы ходить
     * в сеть до того, как стало ясно, что прогон вообще состоится.
     */
    fun rerankerOf(llm: LlmClient, model: String): Reranker? = when (rerank) {
        null -> null
        "heuristic" -> HeuristicReranker()
        "llm" -> LlmReranker(llm, model)
        else -> error("Вариант второго этапа не поддержан: $rerank")
    }

    companion object {

        fun parse(args: Array<String>): Options {
            var dir = Path.of(DEFAULT_DIR)
            var topK = DEFAULT_TOP_K
            var retrievalTopK = DEFAULT_RETRIEVAL_TOP_K
            var finalTopK = DEFAULT_FINAL_TOP_K
            var threshold = DEFAULT_THRESHOLD
            var rewrite: String? = DEFAULT_REWRITE
            var rerank: String? = DEFAULT_RERANK
            var strategy = DEFAULT_STRATEGY
            var model = DEFAULT_MODEL
            var only = emptyList<String>()
            var rebuild = false
            var thresholds = emptyList<Double>()
            var rewriteCheck = false
            var grounded = false
            var plainText = false

            for (arg in args) {
                if (arg == "--rebuild") {
                    rebuild = true
                    continue
                }
                if (arg == "--rewrite-check") {
                    rewriteCheck = true
                    continue
                }
                if (arg == "--grounded") {
                    grounded = true
                    continue
                }
                if (arg == "--plain-text") {
                    plainText = true
                    continue
                }
                val (name, value) = split(arg)
                when (name) {
                    "--dir" -> dir = Path.of(value)
                    "--topK" -> topK = positive(value, name)
                    "--retrievalTopK" -> retrievalTopK = positive(value, name)
                    "--finalTopK" -> finalTopK = positive(value, name)
                    "--threshold" -> threshold = fraction(value, name)
                    "--rewrite" -> rewrite = variant(value, name, NONE, "llm")
                    "--rerank" -> rerank = variant(value, name, NONE, "heuristic", "llm")
                    "--sweep" -> thresholds = value.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { fraction(it, name) }

                    "--strategy" -> strategy = strategyOf(value)
                    "--model" -> model = value.ifBlank { DEFAULT_MODEL }
                    "--only" -> only = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    else -> throw IllegalArgumentException("Неизвестный аргумент: $arg")
                }
            }

            // Ноль фрагментов сделал бы режим с RAG копией режима без RAG: сравнение прошло бы,
            // но не сравнило ничего.
            require(topK > 0) { "topK должен быть положительным, получено $topK" }

            // Финальный Top-K больше выдачи поиска бессмыслен: конвейер отдал бы меньше, чем просят,
            // и «финальный Top-K = 10» при десяти кандидатах выглядело бы как настройка, а не как
            // невозможность. Проверка стоит на разборе, чтобы прогон не начинался с неверного условия.
            require(finalTopK <= retrievalTopK) {
                "finalTopK ($finalTopK) не может быть больше retrievalTopK ($retrievalTopK)"
            }

            val unknown = only.filter { Controls.byId(it) == null }
            require(unknown.isEmpty()) { "В наборе нет вопросов: ${unknown.joinToString(", ")}" }

            return Options(
                dir = dir,
                topK = topK,
                retrievalTopK = retrievalTopK,
                finalTopK = finalTopK,
                threshold = threshold,
                rewrite = rewrite,
                rerank = rerank,
                strategy = strategy,
                model = model,
                only = only,
                rebuild = rebuild,
                thresholds = thresholds,
                rewriteCheck = rewriteCheck,
                grounded = grounded,
                plainText = plainText
            )
        }

        private fun split(arg: String): Pair<String, String> {
            val index = arg.indexOf('=')
            require(index > 0) { "Аргумент должен быть вида --имя=значение, получено «$arg»" }
            return arg.take(index) to arg.substring(index + 1)
        }

        private fun positive(value: String, name: String): Int {
            val number = value.toIntOrNull()
            require(number != null && number > 0) { "$name: нужно положительное число, получено «$value»" }
            return number
        }

        /** Порог: доля от 0 до 1. Близость косинуса вне этого отрезка не бывает. */
        private fun fraction(value: String, name: String): Double {
            val number = value.replace(',', '.').toDoubleOrNull()
            require(number != null && number in 0.0..1.0) {
                "$name: нужен порог от 0 до 1, получено «$value»"
            }
            return number
        }

        /** Вариант этапа: `none` читается как «этапа нет» и превращается в `null`. */
        private fun variant(value: String, name: String, vararg allowed: String): String? {
            val variant = value.trim().lowercase()
            if (variant == NONE) return null
            require(variant in allowed) {
                "$name: вариант «$value» неизвестен, допустимы $NONE, ${allowed.joinToString(", ")}"
            }
            return variant
        }

        private fun strategyOf(value: String): ChunkingStrategyType = when (value.lowercase()) {
            "fixed", "fixed-size", "fixed_size" -> ChunkingStrategyType.FIXED_SIZE
            "structural" -> ChunkingStrategyType.STRUCTURAL
            else -> throw IllegalArgumentException("Стратегия не распознана: «$value» — нужна fixed или structural")
        }

        private const val DEFAULT_DIR = "build/rag"

        /**
         * Структурная нарезка по умолчанию: на корпусе дня она держала эталонный фрагмент
         * в выдаче чаще фиксированного окна (отчёт дня 21), а границы глав для книги естественнее
         * окна в символах. Фиксированное окно остаётся доступным и печатается в отчёте.
         */
        private val DEFAULT_STRATEGY = ChunkingStrategyType.STRUCTURAL

        /** Модель по умолчанию — общая настройка приложения, а не своё число у прогона. */
        private const val DEFAULT_MODEL = AppConfig.DEFAULT_MODEL

        /**
         * Три фрагмента — значение из задания на день 22, и оно же размер контекста по умолчанию.
         *
         * Его и достаточно: чанк, в котором лежит ответ, попадает в первую тройку у восьми вопросов
         * набора из девяти, где ответ в базе есть, а девятый (том Консидерана, четвёртый по близости)
         * остаётся готовым примером поисковой ошибки — ровно того случая, ради которого задание
         * просит считать попадание источника отдельно от оценки ответа. Размер выдачи настраивается
         * (`--topK=`), и в отчёте он напечатан: без него Source Hit Rate не воспроизводится.
         */
        private const val DEFAULT_TOP_K = 3

        /**
         * Десять кандидатов на первом этапе — пример из задания §5.
         *
         * Число выбрано не по «чем больше, тем лучше», а по цене: кандидаты нужны фильтру и второму
         * этапу, чтобы им было из чего выбирать, но каждый кандидат — это токены в запросе к модели
         * у LLM-реранкера. Десять на корпусе дня — примерно шестая часть индекса.
         */
        private const val DEFAULT_RETRIEVAL_TOP_K = 10

        /** Финальный Top-K по умолчанию — тот же, что контекст дня 22: с ним сравнение честное. */
        private const val DEFAULT_FINAL_TOP_K = 3

        /**
         * Порог по умолчанию — 0,40: значение, выбранное подбором (§18) на этом корпусе.
         *
         * Из трёх проверенных значений только оно сохраняет правильный фрагмент у всех вопросов
         * набора: 0,40 оставляет 9,4 кандидата из 10 и не отсеивает ни одного правильного, 0,50
         * оставляет 3,6 и выбрасывает правильные у двух вопросов (у одного — вместе со всей выдачей),
         * а 0,60 не оставляет почти ничего (0,1 кандидата). Причина в распределении близостей: у этого
         * индекса правильные фрагменты идут от 0,45, поэтому порог выше середины отсекает нужное
         * быстрее, чем шум. Значение по умолчанию существует затем, чтобы обычный прогон (без подбора)
         * не зависел от того, запускали ли подбор раньше; подбор печатает таблицу по всем значениям,
         * и выбранное видно в шапке отчёта.
         */
        private const val DEFAULT_THRESHOLD = 0.40

        /**
         * Переписывание включено по умолчанию — это главный этап дня 23.
         *
         * Вариант `none` остаётся доступным: без него нельзя показать, что дало именно переписывание,
         * а не фильтр с реранкингом.
         */
        private const val DEFAULT_REWRITE = "llm"

        /**
         * Второй этап по умолчанию — эвристический.
         *
         * Он детерминирован и не стоит обращений к модели, поэтому именно он годится в качестве
         * условия по умолчанию: прогон с ним повторяем, а вклад LLM-варианта меряется сравнением
         * (`--rerank=llm`), где видно, стоит ли модель своих токенов.
         */
        private const val DEFAULT_RERANK = "heuristic"

        /** Имя «этапа нет»: одно и то же в разборе аргументов и в подсказке. */
        private const val NONE = "none"
    }
}

private const val PROGRESS_STEP = 10
private const val REPORT_FILE = "report.md"
private const val LOG_FILE = "log.md"
private const val SWEEP_FILE = "sweep.md"
private const val REWRITE_FILE = "rewrite.md"

private val USAGE = """
    Запуск: ./gradlew :rag:ragDemo -Pdemo.live=1 --args="--dir=build/rag --rewrite=llm --rerank=heuristic"

    Аргументы (все необязательные):
      --dir=build/rag            каталог прогона: корпус, индекс и отчёты
      --topK=3                   Top-K базового режима дня 22
      --retrievalTopK=10         сколько кандидатов просит улучшенный конвейер
      --finalTopK=3              сколько фрагментов уходит в контекст после фильтра и реранка
      --threshold=0.40           порог фильтрации: 0 — без порога
      --rewrite=llm              переписывание запроса: none или llm
      --rerank=heuristic         второй этап: none, heuristic или llm
      --sweep=0.40,0.50,0.60     подобрать порог: таблица по значениям (идёт после сравнения)
      --rewrite-check            сравнить поиск по исходному и переписанному запросу
      --grounded                 день 24: ответы с источниками, цитатами и режимом «не знаю»
      --plain-text               день 24: не требовать у API ответ объектом JSON
      --strategy=structural      нарезка корпуса: structural или fixed
      --model=deepseek-v4-flash  модель, которой отвечают все режимы
      --only=q03,q04             прогнать часть набора
      --rebuild                  пересобрать индекс, даже если он уже есть
""".trimIndent()
