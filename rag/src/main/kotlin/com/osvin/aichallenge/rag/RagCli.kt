package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmApiException
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
        runBlocking { run(options, apiKey) }
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
 * Прогон целиком: база, индекс, десять вопросов в двух режимах, отчёт.
 *
 * Индекс переиспользуется, если он уже собран ([RagIndex.exists] и отсутствие `--rebuild`)
 * — этого и требует задание: поиск по индексу, а не индексация при каждом запросе. Пересборка
 * остаётся решением прогона, а не свойством запуска: она меняет базу, по которой получены ответы,
 * и печатается в отчёте отдельной строкой, чтобы числа прошлого прогона нельзя было принять
 * за числа этого.
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

    val agent = RagAgent(
        llm = Api.model(apiKey),
        finder = index.retriever(options.topK),
        model = options.model
    )

    val comparisons = options.questions().map { control ->
        val without = ask(agent, control, Mode.WITHOUT_RAG)
        val with = ask(agent, control, Mode.WITH_RAG)
        println(
            "${control.id} (${Check.outcome(control, with.check).title}): " +
                "без RAG ${without.check.score} из 2, с RAG ${with.check.score} из 2"
        )
        Comparison(control = control, withRag = with, withoutRag = without)
    }

    val report = Report(
        RunHeader(
            model = options.model,
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
    )
    println()
    println(report.console(comparisons))

    val comparison = options.dir.resolve(REPORT_FILE)
    Files.writeString(comparison, report.markdown(comparisons))
    val log = options.dir.resolve(LOG_FILE)
    Files.writeString(log, report.log(comparisons))
    println("Ответы целиком: $comparison")
    println("Путь запросов (контекст и ответы каждого режима): $log")
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
    val topK: Int,
    val strategy: ChunkingStrategyType,
    val model: String,
    /** Ограничение набора: пусто — берутся все десять вопросов. */
    val only: List<String>,
    /** Пересобрать индекс, даже если он уже есть. */
    val rebuild: Boolean
) {

    /** Вопросы прогона: набор целиком или выбранные по номерам. */
    fun questions(): List<ControlQuestion> =
        if (only.isEmpty()) Controls.questions else Controls.questions.filter { it.id in only }

    companion object {

        fun parse(args: Array<String>): Options {
            var dir = Path.of(DEFAULT_DIR)
            var topK = DEFAULT_TOP_K
            var strategy = DEFAULT_STRATEGY
            var model = DEFAULT_MODEL
            var only = emptyList<String>()
            var rebuild = false

            for (arg in args) {
                if (arg == "--rebuild") {
                    rebuild = true
                    continue
                }
                val (name, value) = split(arg)
                when (name) {
                    "--dir" -> dir = Path.of(value)
                    "--topK" -> topK = positive(value, name)
                    "--strategy" -> strategy = strategyOf(value)
                    "--model" -> model = value.ifBlank { DEFAULT_MODEL }
                    "--only" -> only = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    else -> throw IllegalArgumentException("Неизвестный аргумент: $arg")
                }
            }

            // Ноль фрагментов сделал бы режим с RAG копией режима без RAG: сравнение прошло бы,
            // но не сравнило ничего.
            require(topK > 0) { "topK должен быть положительным, получено $topK" }
            val unknown = only.filter { Controls.byId(it) == null }
            require(unknown.isEmpty()) { "В наборе нет вопросов: ${unknown.joinToString(", ")}" }

            return Options(dir, topK, strategy, model, only, rebuild)
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

        private fun strategyOf(value: String): ChunkingStrategyType = when (value.lowercase()) {
            "fixed", "fixed-size", "fixed_size" -> ChunkingStrategyType.FIXED_SIZE
            "structural" -> ChunkingStrategyType.STRUCTURAL
            else -> throw IllegalArgumentException("Стратегия не распознана: «$value» — нужна fixed или structural")
        }

        private const val DEFAULT_DIR = "build/rag"

        /**
         * Три фрагмента — значение из задания на день.
         *
         * Его и достаточно: чанк, в котором лежит ответ, попадает в первую тройку у восьми вопросов
         * набора из девяти, где ответ в базе есть, а девятый (том Консидерана, четвёртый по близости)
         * остаётся готовым примером поисковой ошибки — ровно того случая, ради которого задание
         * просит считать попадание источника отдельно от оценки ответа. Размер выдачи настраивается
         * (`--topK=`), и в отчёте он напечатан: без него Source Hit Rate не воспроизводится.
         */
        private const val DEFAULT_TOP_K = 3

        /**
         * Структурная нарезка по умолчанию: на корпусе дня она держала эталонный фрагмент
         * в выдаче чаще фиксированного окна (отчёт дня 21), а границы глав для книги естественнее
         * окна в символах. Фиксированное окно остаётся доступным и печатается в отчёте.
         */
        private val DEFAULT_STRATEGY = ChunkingStrategyType.STRUCTURAL
        /** Модель по умолчанию — общая настройка приложения, а не своё число у прогона. */
        private const val DEFAULT_MODEL = AppConfig.DEFAULT_MODEL
    }
}

private const val PROGRESS_STEP = 10
private const val REPORT_FILE = "report.md"
private const val LOG_FILE = "log.md"

private val USAGE = """
    Запуск: ./gradlew :rag:ragDemo -Pdemo.live=1 --args="--dir=build/rag --topK=3 --strategy=structural"

    Аргументы (все необязательные):
      --dir=build/rag            каталог прогона: корпус, индекс и отчёт
      --topK=3                   сколько фрагментов базы уходит в запрос
      --strategy=structural      нарезка корпуса: structural или fixed
      --model=deepseek-v4-flash  модель, которой отвечают оба режима
      --only=q03,q04             прогнать часть набора
      --rebuild                  пересобрать индекс, даже если он уже есть
""".trimIndent()
