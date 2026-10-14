package com.osvin.aichallenge.tuning

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClientFactory
import com.osvin.aichallenge.agent.OllamaProbe
import com.osvin.aichallenge.rag.ControlQuestion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Прогон дня 29: `./gradlew :llm-tuning:tuningDemo`.
 *
 * Прогон отвечает на десять вопросов кейса каждым профилем ([Tunings]) — «как сейчас», «+ окно
 * контекста», «+ инструкция под кейс» и «окно + инструкция + бюджет», — сверяет ответы с
 * ожиданиями набора той же проверкой, что у дня 22, печатает сравнение и кладёт отчёт в каталог
 * прогона. Порядок профилей фиксирован и одинаков для всех вопросов: обращения к модели идут
 * по очереди, и разный порядок сделал бы числа несравнимыми.
 *
 * Живого флага здесь нет — и это отличие от `ragDemo` намеренное. Прогон не тратит денег: он
 * обращается к демону на этой машине, и ключа ему не нужно вовсе. Вместо флага перед прогоном
 * проверяется демон ([daemonReady]): без `ollama serve` десять вопросов дали бы десять
 * названных отказов, то есть отчёт, читающийся как «профили не работают», — а это была бы
 * неправда о профилях и правда о демоне.
 *
 * Ключ облачного провайдера прогону не нужен и нигде не читается: профили дня — локальные
 * (провайдер `LOCAL` в конфигурации каждого профиля), и второй транспорт в сравнении квантований
 * смысла не имеет.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (cause: IllegalArgumentException) {
        println(cause.message)
        println(USAGE)
        exitProcess(2)
    }

    try {
        runBlocking { run(options) }
    } catch (cause: LlmApiException) {
        // Ответ API нужен человеку целиком: он объясняет, дело в модели или в демоне.
        println("Модель отказала: статус ${cause.status}, ${cause.message}")
        exitProcess(1)
    }
}

/**
 * Прогон всех выбранных профилей по выбранным вопросам.
 *
 * Клиент собирается на каждый профиль заново ([LlmClientFactory]), а HTTP-клиент остаётся один
 * на прогон: окно контекста, модель, температура и рассуждение — свойства транспорта профиля,
 * и общий клиент с ними значил бы, что профили отвечают одной настройкой, а отчёт показывает
 * разные. Общий HTTP-клиент при этом экономит соединения и держит один срок запроса на все
 * профили — иначе разницу во времени ответа можно было бы списать на настройку сокета.
 *
 * Снимок памяти берётся дважды на профиль: после первого ответа (модель уже загружена, кэш
 * контекста занят) и в конце прогона профиля (демон мог её выгрузить). В отчёт идёт первый —
 * он относится к загруженной модели, ради которой прогон и идёт, а второй остаётся запасным.
 */
private suspend fun run(options: Options) {
    val tunings = options.tunings()
    val questions = options.questions()

    val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    try {
        val model = tunings.first().config.ollamaModel
        println("Модель: $model (демон Ollama)")
        println("Профили: ${tunings.joinToString(", ") { it.id }}")
        println("Вопросы: ${questions.size} (${questions.joinToString(", ") { it.id }})")

        if (!daemonReady(tunings, http)) return

        val results = mutableListOf<TuningResult>()
        val resources = mutableMapOf<String, ResourceSnapshot>()

        for (tuning in tunings) {
            println()
            println("Профиль ${tuning.id} — ${tuning.title} (${tuning.note})")
            val client = LlmClientFactory(tuning.config, http, AgentLogger.Silent).create(null)
            val (result, snapshot) = runProfile(TuningRunner(client), tuning, questions, http)
            results += result
            snapshot?.let { resources[tuning.id] = it }
            if (result.failed == result.answers.size) {
                println("  все вопросы отказали: проверьте демон и модель (ollama serve, ollama pull $model)")
            }
        }

        val report = TuningReport.markdown(results, resources)
        println()
        // В консоль идёт тот же отчёт, что в файл, только без разметки, если её попросили убрать:
        // два текста разошлись бы при первой же правке колонки, и в консоли были бы одни числа,
        // а в отчёте другие.
        println(if (options.plainText) TuningReport.plain(results, resources) else report)

        val file = options.dir.resolve(REPORT_FILE)
        Files.createDirectories(options.dir)
        Files.writeString(file, report)
        println()
        println("Отчёт: ${file.toAbsolutePath()}")
    } finally {
        http.close()
    }
}

/**
 * Один профиль: прогон по вопросам и снимок памяти.
 *
 * Снимок «в памяти» берётся не после прогона, а внутри него — по первому ответу: модель в этот
 * момент уже загружена демоном, и размер её в памяти относится к тому самому её состоянию,
 * в котором она отвечала. Прогон об этом сообщает обратным вызовом ([TuningRunner.run]), а ждёт
 * первого ответа отдельная корутина: оба идут в одном прогоне и не мешают друг другу —
 * замер памяти идёт параллельно вопросу, а не вместо него.
 */
private suspend fun runProfile(
    runner: TuningRunner,
    tuning: Tuning,
    questions: List<ControlQuestion>,
    http: HttpClient
): Pair<TuningResult, ResourceSnapshot?> = coroutineScope {
    var inMemory: ResourceSnapshot? = null
    val firstAnswer = CompletableDeferred<Unit>()
    val watcher = launch {
        firstAnswer.await()
        inMemory = Resources.inMemory(http, tuning.config.ollamaUrl, tuning.config.ollamaModel)
    }

    val result = runner.run(tuning, questions) { done, total, id ->
        println("  [$done/$total] $id")
        if (done == FIRST_ANSWER) firstAnswer.complete(Unit)
    }
    // Страховка на вырожденный прогон: без вопросов первый ответ не наступит, и ожидание
    // снимка повисло бы навсегда, хотя сравнивать нечего.
    if (!firstAnswer.isCompleted) firstAnswer.complete(Unit)
    watcher.join()

    val end = Resources.snapshot(http, tuning.config.ollamaUrl, tuning.config.ollamaModel)
    result to (inMemory ?: end)
}

/**
 * Проверка демона и модели до прогона.
 *
 * Обе половины проверки — про человека, который запустил задачу: «демон не отвечает» и «модели
 * нет» требуют разных действий (`ollama serve` и `ollama pull`), и первое же сообщение демона
 * называет нужное. Проверка идёт тем же клиентом, что и запросы, — тем же адресом и тем же
 * сроком, — так что отказ прогона после успешной проверки остаётся отказом модели, а не
 * неверного адреса.
 *
 * @return `true` — можно отвечать; `false` — прогон уже напечатал, чего не хватает.
 */
private suspend fun daemonReady(tunings: List<Tuning>, http: HttpClient): Boolean =
    when (val probe = LlmClientFactory(tunings.first().config, http, AgentLogger.Silent).probeOllama()) {
        is OllamaProbe.Failed -> {
            println(probe.reason)
            false
        }

        is OllamaProbe.Loaded -> {
            // Модель без тега в запуске (`qwen3`) и с тегом у демона (`qwen3:1.7b`) — одна и та же
            // модель, поэтому сравнение идёт по имени до тега.
            val missing = tunings.map { it.config.ollamaModel }.distinct().filterNot { model ->
                probe.models.any { it == model || it.startsWith("$model:") }
            }
            if (missing.isEmpty()) {
                true
            } else {
                println(
                    "У демона нет моделей: ${missing.joinToString(", ")} — " +
                        missing.joinToString("; ") { "ollama pull $it" }
                )
                false
            }
        }
    }

/**
 * Что можно задать прогону.
 *
 * Аргументы разбираются тем же способом, что у `RagCli`: все необязательные, вида `--имя=значение`
 * (кроме флага `--plain-text`), опечатка в имени или значении — отказ разбора с подсказкой, а не
 * молчаливая работа не тем набором. Каталог задаётся аргументом, потому что прогон пишет туда
 * отчёт: держать это в коде значило бы перезаписывать результаты прошлого раза молча.
 *
 * @param dir Каталог прогона: сюда пишется `report.md`.
 * @param config Имя профиля или `null` — все профили (аргумент `--config=all`).
 * @param model Имя модели для всех профилей; `null` — модель профиля.
 * @param only Номера вопросов; пусто — весь набор.
 * @param plainText Печатать таблицу в консоль без markdown-разметки.
 */
private data class Options(
    val dir: Path,
    val config: String?,
    val model: String?,
    val only: List<String>,
    val plainText: Boolean
) {

    /** Профили прогона: один выбранный или вся лестница — с подменённым именем модели, если оно задано. */
    fun tunings(): List<Tuning> = Tunings.withModel(model).filter { config == null || it.id == config }

    /** Вопросы прогона: набор целиком или выбранные по номерам. */
    fun questions(): List<ControlQuestion> =
        if (only.isEmpty()) KmpQuestions.questions
        else KmpQuestions.questions.filter { it.id in only }

    companion object {

        fun parse(args: Array<String>): Options {
            var dir = Path.of(DEFAULT_DIR)
            var config: String? = null
            var model: String? = null
            var only = emptyList<String>()
            var plainText = false

            for (arg in args) {
                if (arg == "--plain-text") {
                    plainText = true
                    continue
                }
                val (name, value) = split(arg)
                when (name) {
                    "--dir" -> dir = Path.of(value)
                    "--config" -> config = configOf(value)
                    "--model" -> model = value.trim().takeIf { it.isNotEmpty() }
                    "--only" -> only = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    else -> throw IllegalArgumentException("Неизвестный аргумент: $arg")
                }
            }

            val unknown = only.filter { KmpQuestions.byId(it) == null }
            require(unknown.isEmpty()) { "В наборе нет вопросов: ${unknown.joinToString(", ")}" }

            return Options(dir = dir, config = config, model = model, only = only, plainText = plainText)
        }

        /** Профиль по имени: `all` — все, незнакомое имя — отказ разбора, а не пустой прогон. */
        private fun configOf(value: String): String? {
            val id = value.trim().lowercase()
            if (id == ALL_CONFIGS) return null
            require(Tunings.byId(id) != null) {
                "Профиль «$value» неизвестен, допустимы $ALL_CONFIGS, " +
                    Tunings.ALL.joinToString(", ") { it.id }
            }
            return id
        }

        private fun split(arg: String): Pair<String, String> {
            val index = arg.indexOf('=')
            require(index > 0) { "Аргумент должен быть вида --имя=значение, получено «$arg»" }
            return arg.take(index) to arg.substring(index + 1)
        }

        /** Каталог прогона по умолчанию: от корня проекта, как у остальных прогонов дней. */
        private const val DEFAULT_DIR = "build/llm-tuning"

        /** Значение `--config`, означающее «все профили»: прогон по умолчанию — вся лестница. */
        private const val ALL_CONFIGS = "all"
    }
}

/** Файл отчёта в каталоге прогона. */
private const val REPORT_FILE = "report.md"

/** Номер первого ответа профиля: по нему берётся снимок памяти. */
private const val FIRST_ANSWER = 1

/**
 * Сроки HTTP-клиента прогона: те же, что у прогона RAG.
 *
 * Модель отвечает секундами, но первая загрузка в память занимает десятки секунд, и короткий
 * срок обрывал бы первый же вопрос. Сроки повторяют `:rag` намеренно: два прогона на одной
 * машине, обрывающие модель по-разному, дали бы разное число отказов на одних и тех же вопросах.
 */
private const val REQUEST_TIMEOUT_MILLIS = 300_000L

private const val CONNECT_TIMEOUT_MILLIS = 20_000L

private val USAGE = """
    Запуск: ./gradlew :llm-tuning:tuningDemo --args="--config=all"

    Прогон отвечает на вопросы кейса каждым профилем сборки и сравнивает ответы той же
    проверкой, что у дня 22. Живой режим не нужен: прогон обращается к демону Ollama.

    Аргументы (все необязательные):
      --dir=build/llm-tuning   каталог прогона: сюда пишется report.md
      --config=all             профиль: baseline, context, template, optimized или all
      --model=qwen3:1.7b       модель демона: подменяет имя модели у всех профилей (ось «квантование»)
      --only=q01,q02           прогнать часть набора
      --plain-text             печатать таблицу в консоль без markdown-разметки
""".trimIndent()
