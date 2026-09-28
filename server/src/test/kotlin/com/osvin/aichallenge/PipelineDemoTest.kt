package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.agent.ToolOutcome
import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.storage.SqliteCurrencyRateRepository
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.pipeline.mcp.PipelineMcpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Живая демонстрация дня 19: цепочку из трёх инструментов собирает модель.
 *
 * Прогон живой целиком, кроме истории курсов: модель настоящая, сервер инструментов — настоящий
 * отдельный процесс по протоколу MCP, файл настоящий. История курсов подставлена одной вставкой
 * во временную базу — её пишет служба курсов, и без неё прогон зависел бы от того, что служба
 * успела собрать; код-путь от подстановки не меняется, инструмент читает `latest()` так же,
 * как читал бы у службы.
 *
 * Что проверяется. Цепочку собирает модель: в просьбе нет ни имён инструментов, ни порядка —
 * «получи курсы, сформируй сводку, сохрани в файл», — а вызывающего кода про три шага в тесте
 * нет. Дальше проверяется передача данных между шагами: документ первого шага уходит аргументом
 * второму, текст второго — аргументом третьему, — и то, что в файле на диске лежит именно
 * переданный текст. Это обещание дня, и оно не выполняется одним лишь фактом вызовов: три
 * вызова без передачи данных были бы тремя отдельными вопросами.
 *
 * Сравнение шага 1 и шага 2 — по данным внутри документа, а не по строкам целиком: текст
 * передаёт модели, и различия в пробелах по краям не значат потери данных. Последний переход
 * (аргумент → файл) сверяется точно: он идёт внутри инструмента, где вольностей нет.
 *
 * Без живого ключа прогон ничего не проверяет, поэтому он выключен по умолчанию:
 * `./gradlew :server:pipelineDemo -Pdemo.live=1` (ключ берётся из `server/.env` или окружения).
 */
class PipelineDemoTest {

    @Test
    fun `модель собирает цепочку курсы — сводка — файл`() = runBlocking {
        if (!demoOnLiveApi) {
            log("живой прогон выключен: нужен флаг -Pdemo.live=1 и ключ DEEPSEEK_API_KEY")
            log("запуск: ./gradlew :server:pipelineDemo -Pdemo.live=1")
            return@runBlocking
        }

        val directory = Files.createTempDirectory("pipeline-demo")
        try {
            val database = directory.resolve("currency.db")
            val reports = directory.resolve("reports")
            seed(database)

            val tools = PipelineTools(
                config = localMcpServerConfig(PipelineMcpServer.MAIN_CLASS).copy(
                    env = mapOf(
                        "CURRENCY_DB" to database.toString(),
                        "PIPELINE_OUTPUT_DIR" to reports.toString()
                    )
                ),
                onLog = { log(it) }
            )
            val http = demoHttpClient()
            try {
                stage("Инструменты, которые агент получил от MCP-сервера пайплайна")
                val available = tools.tools()
                available.forEach { tool ->
                    log("инструмент: ${tool.name} — ${tool.description}")
                    log("  схема аргументов: ${tool.parameters}")
                }
                assertEquals(
                    3,
                    available.size,
                    "сервер пайплайна объявил не три инструмента: ${available.map { it.name }}"
                )

                val calls = mutableListOf<RecordedCall>()
                val agent = LlmAgent(DeepSeekClient(demoApiKey!!, http), logger = AgentLogger.Console)

                stage("Просьба про курсы, сводку и файл")
                val result = agent.run(
                    ASK,
                    AgentOptions(tools = available.map { tool -> recorded(tool, calls) })
                )
                log("вызовы инструментов: ${calls.joinToString { it.name + if (it.outcome.isError) " (отказ)" else "" }}")
                log("раундов с инструментами: ${result.tokens.tools.rounds}, их токенов: ${result.tokens.tools.tokens}")
                log("ответ: ${result.reply}")

                calls.forEach { call ->
                    log("аргументы ${call.name}: ${call.arguments.keys.joinToString()}")
                }
                assertTrue(calls.isNotEmpty(), "модель не позвала ни одного инструмента")
                assertEquals(
                    listOf(RATES_TOOL, SUMMARIZE_TOOL, SAVE_TOOL),
                    calls.map { it.name }.distinct(),
                    "цепочку собрал не тот порядок вызовов: ${calls.map { it.name }}"
                )

                val rates = calls.first { it.name == RATES_TOOL && !it.outcome.isError }
                val summary = calls.first { it.name == SUMMARIZE_TOOL && !it.outcome.isError }
                val saved = calls.first { it.name == SAVE_TOOL && !it.outcome.isError }

                stage("Данные между шагами")
                val document = summary.arguments.textOf("rates")
                    ?: error("второму шагу не передан документ курсов: ${summary.arguments}")
                assertContains(document, "95.8709", message = "во второй шаг ушёл не тот документ курсов")
                log("в сводку ушёл документ первого шага (${document.length} знаков)")

                val content = saved.arguments.textOf("content")
                    ?: error("третьему шагу не передан текст сводки: ${saved.arguments}")
                assertContains(content, "Сводка курсов к RUB", message = "третьему шагу ушла не сводка: $content")
                assertContains(content, "95,8709", message = "в сохранённой сводке нет курса евро: $content")
                log("в файл ушла сводка второго шага (${content.length} знаков)")

                val file = saved.arguments.textOf("name")?.let { reports.resolve(it) } ?: onlyFile(reports)
                assertTrue(Files.exists(file), "файла нет по названному пути: $file")
                assertEquals(
                    content,
                    Files.readString(file),
                    "в файле не тот текст, что модель передала третьему шагу"
                )
                assertEquals(
                    summary.outcome.text,
                    content.trim(),
                    "сводка в файле отличается от ответа второго шага: данные потерялись по дороге"
                )
                log("файл: $file, знаков ${Files.readString(file).length} — сводка дошла целиком")
                assertTrue(result.reply.isNotBlank(), "модель ничего не ответила человеку")
            } finally {
                tools.close()
                http.close()
                log("сессия инструментов закрыта, сервер пайплайна остановлен")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * История курсов: три валюты на один момент, как их пишет служба за один удар.
     *
     * Момент — время прогона, а не выдуманный: инструмент отдаёт его в документе, и сводка
     * называет его человеку. Проверять при этом нечего — важно, что курсы в сводке те же,
     * что здесь.
     */
    private suspend fun seed(path: Path) {
        val repository = SqliteCurrencyRateRepository(path)
        try {
            repository.save(
                listOf(
                    CurrencyRate(Currency.EUR, "95.8709".toBigDecimal(), Instant.now()),
                    CurrencyRate(Currency.USD, "84.3414".toBigDecimal(), Instant.now()),
                    CurrencyRate(Currency.GEL, "32.1693".toBigDecimal(), Instant.now())
                )
            )
        } finally {
            repository.close()
        }
    }

    /** Файл, единственный в каталоге отчётов: модель вправе не называть имя и взять умолчание. */
    private fun onlyFile(reports: Path): Path =
        Files.list(reports).use { files ->
            val found = files.toList()
            assertEquals(1, found.size, "в каталоге отчётов не один файл: $found")
            found.single()
        }

    /** Инструмент с записью вызова: по записи видно и порядок шагов, и что ушло между ними. */
    private fun recorded(tool: AgentTool, calls: MutableList<RecordedCall>): AgentTool = AgentTool(
        name = tool.name,
        description = tool.description,
        parameters = tool.parameters,
        call = { arguments -> tool.call(arguments).also { calls += RecordedCall(tool.name, arguments, it) } }
    )

    /** Живой транспорт: тот же клиент, что у сервера, — отличаются только таймауты теста. */
    private fun demoHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 300_000
        }
    }
}

/**
 * Записанный вызов инструмента: имя, аргументы от модели и ответ инструмента.
 *
 * Аргументы здесь — то же, что ушло инструменту, поэтому по ним и видно передачу данных:
 * `rates` второго шага и `content` третьего.
 */
private class RecordedCall(val name: String, val arguments: JsonObject, val outcome: ToolOutcome)

/** Текстовый аргумент вызова: null, если модель его не назвала или он не строка. */
private fun JsonObject.textOf(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** Имена инструментов провода: литералы, как и в объявлении сервера пайплайна. */
private const val RATES_TOOL = "getExchangeRates"
private const val SUMMARIZE_TOOL = "summarizeRates"
private const val SAVE_TOOL = "saveToFile"

/** Просьба демонстрации: порядок шагов в ней назван, а инструменты и их имена — нет. */
private const val ASK =
    "Получи текущие курсы USD, EUR и GEL относительно рубля, сформируй краткую сводку и сохрани её в файл"

/** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
private fun log(line: String) = println("[agent] $line")

/** Заголовок этапа: по нему видно, на каком шаге цепочки прогон. */
private fun stage(title: String) = println("[agent] === $title ===")

/** Живой режим включается явно: без него прогон не тратит бюджет, как и прочие демонстрации. */
private val demoLive: Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/** Ключ: из задачи `pipelineDemo` (берёт `server/.env`), из окружения или из запуска в IDE. */
private val demoApiKey: String? =
    System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

/** Идёт ли прогон на живом API: нужен и флаг, и ключ. */
private val demoOnLiveApi: Boolean = demoLive && demoApiKey != null
