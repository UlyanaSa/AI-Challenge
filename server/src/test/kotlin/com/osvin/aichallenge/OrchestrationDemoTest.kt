package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.MCP_FLAG
import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import com.osvin.aichallenge.currency.storage.SqliteCurrencyRateRepository
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.orchestration.McpOrchestrator
import com.osvin.aichallenge.orchestration.McpToolRegistry
import com.osvin.aichallenge.orchestration.ToolServers
import com.osvin.aichallenge.report.ReportConfig
import com.osvin.aichallenge.report.mcp.ReportMcpServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Живая демонстрация дня 20: длинный workflow через три MCP-сервера собирает модель.
 *
 * Прогон живой целиком, кроме GitHub и истории курсов: модель настоящая, три сервера инструментов —
 * настоящие отдельные процессы по протоколу MCP, отчёт пишется настоящий. GitHub подставной
 * (HTTP-сервер в этом же тесте), история курсов посеяна во временную базу: код-путь от подстановки
 * не меняется — серверы берут адрес API и базу из окружения так же, как брали бы настоящие.
 *
 * Что проверяется. Модель получает **один** список инструментов трёх серверов (имена вызовов
 * несут признак сервера: `currency__…`, `github__…`, `report__…`) и по обычной просьбе проходит
 * цепочку сама: курсы (Currency MCP) → репозитории, репозиторий, коммиты (GitHub MCP) → сборка
 * и запись отчёта (Report MCP). Кода, который знал бы про этот порядок, в тесте нет — есть одна
 * просьба и один список инструментов.
 *
 * Маршрут каждого вызова виден по строкам протокола оркестратора ([McpOrchestrator]): в них
 * названы имя вызова, сервер, размеры аргументов, длительность и исход. По этим строкам
 * восстанавливается workflow одного запроса, а по файлу отчёта — то, что данные разных серверов
 * действительно встретились: в отчёте есть и курсы из Currency MCP, и найденный репозиторий
 * из GitHub MCP.
 *
 * Без живого ключа прогон ничего не проверяет, поэтому он выключен по умолчанию:
 * `./gradlew :server:orchestrationDemo -Pdemo.live=1` (ключ берётся из `server/.env` или окружения).
 */
class OrchestrationDemoTest {

    @Test
    fun `модель проходит цепочку через три MCP-сервера`() = runBlocking {
        if (!demoOnLiveApi) {
            log("живой прогон выключен: нужен флаг -Pdemo.live=1 и ключ DEEPSEEK_API_KEY")
            log("запуск: ./gradlew :server:orchestrationDemo -Pdemo.live=1")
            return@runBlocking
        }

        val directory = Files.createTempDirectory("orchestration-demo")
        try {
            val database = directory.resolve("currency.db")
            val reports = directory.resolve("reports")
            seedCurrency(database)

            val github = OrchestrationStubGitHub()
            val protocol = CopyOnWriteArrayList<String>()
            val onProtocol = { line: String -> protocol += line; log("оркестратор: $line") }

            val githubTools = GitHubTools(
                config = localMcpServerConfig(
                    GITHUB_SERVER_MAIN,
                    env = mapOf("GITHUB_API_BASE" to github.baseUrl, "GITHUB_TOKEN" to TEST_TOKEN)
                ),
                onLog = { log(it) }
            )
            val currencyTools = CurrencyTools(
                config = localMcpServerConfig(
                    CurrencyMcpServer.MAIN_CLASS,
                    args = listOf(MCP_FLAG),
                    env = mapOf("CURRENCY_DB" to database.toString())
                ),
                onLog = { log(it) }
            )
            val reportTools = ReportTools(
                config = localMcpServerConfig(
                    ReportMcpServer.MAIN_CLASS,
                    env = mapOf(ReportConfig.REPORTS_DIR_ENV to reports.toString())
                ),
                onLog = { log(it) }
            )
            val http = demoHttpClient()
            try {
                // Сессию GitHub открывает человек кнопкой (так устроено с дня 16), поэтому
                // в демонстрации её открываем явно — как это делает приложение по нажатию.
                val connection = githubTools.connect()
                log("GitHub: ${connection::class.simpleName}")

                val registry = McpToolRegistry()
                val orchestrator = McpOrchestrator(registry, onLog = onProtocol)
                // Ответы инструментов записываются: по ним видно, что именно вернул сервер курсов,
                // и проверка отчёта сравнивается с ним, а не с выдуманными числами. Курсы служба
                // берёт из сети, поэтому свои числа в проверке разошлись бы с её данными.
                val answers = mutableMapOf<String, String>()
                val servers = listOf(
                    ToolServers.GITHUB to githubTools.tools(),
                    ToolServers.CURRENCY to currencyTools.tools(),
                    ToolServers.REPORT to reportTools.tools()
                ).map { (serverId, tools) -> serverId to tools.map { recorded(it, answers) } }

                stage("Единый список инструментов трёх серверов")
                val tools = orchestratedTools(registry, orchestrator, servers)
                tools.forEach { tool -> log("инструмент: ${tool.name} — ${tool.description}") }
                val names = tools.map { it.name }
                assertEquals(names.distinct(), names, "имена вызовов должны различаться: $names")
                listOf(
                    "${ToolServers.GITHUB}__get_repositories",
                    "${ToolServers.GITHUB}__getRepository",
                    "${ToolServers.GITHUB}__getRecentCommits",
                    "${ToolServers.CURRENCY}__get_currency_rates",
                    "${ToolServers.REPORT}__createReport",
                    "${ToolServers.REPORT}__saveReport"
                ).forEach { expected ->
                    assertTrue(expected in names, "в списке нет $expected: $names")
                }

                val agent = LlmAgent(DeepSeekClient(demoApiKey!!, http), logger = AgentLogger.Console)

                stage("Основной сценарий дня: курсы, GitHub-активность, отчёт")
                val result = agent.run(ASK, AgentOptions(tools = tools))
                log("ответ: ${result.reply}")

                val routed = protocol.filter { it.startsWith("requestId=") }
                routed.forEach { log("вызов: $it") }
                val route = routed.map { line -> Pair(valueOf(line, "server"), valueOf(line, "tool")) }
                val order = route.map { it.first }.distinct()
                log("маршрут: ${route.joinToString { (server, tool) -> "$server→$tool" }}")

                assertEquals(
                    listOf(ToolServers.CURRENCY, ToolServers.GITHUB, ToolServers.REPORT),
                    order,
                    "workflow прошёл не через три сервера или не в том порядке: $route"
                )
                assertTrue(
                    route.size >= 5,
                    "workflow должен состоять из нескольких вызовов: $route"
                )
                // Сколько именно шагов на GitHub-сервере, решает модель: в одном прогоне она
                // берёт репозиторий отдельным вызовом, в другом сразу просит коммиты. Проверка
                // держится за то, что шагов там больше одного, а не за конкретный их набор.
                assertTrue(
                    route.count { it.first == ToolServers.GITHUB } >= 2,
                    "на GitHub-сервере должен был быть не один вызов: $route"
                )

                stage("Отчёт: данные двух серверов в одном файле")
                val report = onlyFile(reports)
                val content = Files.readString(report)
                log("отчёт: $report, знаков ${content.length}")
                assertContains(content, "kmp-agent", message = "в отчёте нет репозитория из GitHub MCP: $content")
                val rates = answers[CURRENCY_RATES_TOOL]
                    ?: error("сервер курсов не ответил: ${answers.keys}")
                val rateNumbers = kotlin.text.Regex("\\d+\\.\\d+").findAll(rates).map { it.value }.distinct().toList()
                log("курсы из Currency MCP: ${rateNumbers.take(6)}")
                assertTrue(
                    rateNumbers.any { number -> content.contains(number) || content.contains(number.replace('.', ',')) },
                    "в отчёте нет ни одного курса из Currency MCP ($rateNumbers): $content"
                )
                assertTrue(result.reply.isNotBlank(), "модель ничего не ответила человеку")
                log("workflow собран моделью: серверов 3, вызовов ${routed.size}, отчёт записан")
            } finally {
                reportTools.close()
                currencyTools.close()
                githubTools.close()
                http.close()
                github.stop()
                log("сессии серверов закрыты, подставной GitHub остановлен")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** История курсов: три валюты на один момент — как их пишет служба за один удар. */
    private suspend fun seedCurrency(path: Path) {
        val repository = SqliteCurrencyRateRepository(path)
        try {
            val moment = Instant.now()
            repository.save(
                listOf(
                    CurrencyRate(Currency.USD, "82.15".toBigDecimal(), moment),
                    CurrencyRate(Currency.EUR, "96.42".toBigDecimal(), moment),
                    CurrencyRate(Currency.GEL, "30.31".toBigDecimal(), moment)
                )
            )
        } finally {
            repository.close()
        }
    }

    /** Инструмент с записью ответа: записанное и сравнивается с содержимым отчёта. */
    private fun recorded(tool: com.osvin.aichallenge.agent.AgentTool, answers: MutableMap<String, String>) =
        com.osvin.aichallenge.agent.AgentTool(
            name = tool.name,
            description = tool.description,
            parameters = tool.parameters,
            call = { arguments -> tool.call(arguments).also { outcome -> answers[tool.name] = outcome.text } }
        )

    /** Файл, единственный в каталоге отчётов: имя выбирает модель или умолчание сервера. */
    private fun onlyFile(reports: Path): Path = Files.list(reports).use { files ->
        val found = files.toList()
        assertEquals(1, found.size, "в каталоге отчётов не один файл: $found")
        found.single()
    }

    /** Значение поля из строки протокола: `server=github`. */
    private fun valueOf(line: String, field: String): String =
        line.split(' ').first { it.startsWith("$field=") }.substringAfter('=')

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
 * Подставной GitHub: владелец доступа, репозитории, один репозиторий и его коммиты.
 *
 * Маршруты те же, что у настоящего API (`/user`, `/user/repos`, `/repos/{owner}/{repo}`,
 * `/repos/{owner}/{repo}/commits`), и ответы — того же вида: разбор ответов проверяется
 * не подстановкой, а работой сервера инструментов. Репозиторий `kmp-agent` в наборе есть:
 * просьба называет его по имени.
 */
private class OrchestrationStubGitHub {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/user/repos") { exchange -> respond(exchange, REPOSITORIES) }
        server.createContext("/user") { exchange -> respond(exchange, USER, scopes = "repo, read:user") }
        server.createContext("/repos/ulanocka/kmp-agent/commits") { exchange -> respond(exchange, COMMITS) }
        server.createContext("/repos/ulanocka/kmp-agent") { exchange -> respond(exchange, REPOSITORY) }
        server.start()
    }

    private fun respond(exchange: HttpExchange, json: String, scopes: String? = null) {
        val body = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        scopes?.let { exchange.responseHeaders.add("X-OAuth-Scopes", it) }
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    fun stop() = server.stop(0)

    private companion object {

        val USER = """{"login": "ulanocka", "id": 1, "name": "Улан"}"""

        val REPOSITORIES = """
            [
              {"id": 1, "name": "kmp-agent", "full_name": "ulanocka/kmp-agent", "private": true,
               "visibility": "private", "html_url": "https://github.com/ulanocka/kmp-agent",
               "description": "Агент и MCP"},
              {"id": 2, "name": "mcp-server", "full_name": "ulanocka/mcp-server", "private": false,
               "visibility": "public", "html_url": "https://github.com/ulanocka/mcp-server",
               "description": "Серверы инструментов"}
            ]
        """.trimIndent()

        val REPOSITORY = """
            {"id": 1, "name": "kmp-agent", "full_name": "ulanocka/kmp-agent", "private": true,
             "visibility": "private", "html_url": "https://github.com/ulanocka/kmp-agent",
             "description": "Агент и MCP"}
        """.trimIndent()

        val COMMITS = """
            [
              {"sha": "a1b2c3d", "commit": {"message": "Add MCP orchestrator", "author": {"name": "ulana"}}},
              {"sha": "b2c3d4e", "commit": {"message": "Route tool calls to servers", "author": {"name": "ulana"}}},
              {"sha": "c3d4e5f", "commit": {"message": "Report MCP server", "author": {"name": "ulana"}}},
              {"sha": "d4e5f6a", "commit": {"message": "Tool registry", "author": {"name": "ulana"}}},
              {"sha": "e5f6a7b", "commit": {"message": "Docs for day 20", "author": {"name": "ulana"}}}
            ]
        """.trimIndent()
    }
}

/** Имя точки входа сервера GitHub: литерал, как и у прочих демонстраций проекта. */
private const val GITHUB_SERVER_MAIN = "com.osvin.aichallenge.mcp.github.GitHubMcpServerKt"

/** Подставной доступ: сервер инструментов считает его найденным и ходит в подставной API. */
private const val TEST_TOKEN = "test-token"

/**
 * Имя инструмента курсов у его сервера — литерал, как и у прочих имён провода.
 *
 * Имя без признака сервера: запись ответов идёт до регистрации, и там инструмент называется
 * так, как его объявил сервер.
 */
private const val CURRENCY_RATES_TOOL = "get_currency_rates"

/** Просьба основного сценария дня — дословно из задания. */
private const val ASK =
    "Получи текущие курсы USD, EUR и GEL относительно рубля. Затем найди мои репозитории, " +
        "найди репозиторий kmp-agent, получи последние 5 коммитов. После этого создай общий отчёт " +
        "с курсами валют и активностью GitHub и сохрани его в файл."

/** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
private fun log(line: String) = println("[agent] $line")

/** Заголовок этапа: по нему видно, на каком шаге прогон. */
private fun stage(title: String) = println("[agent] === $title ===")

/** Живой режим включается явно: без него прогон не тратит бюджет, как и прочие демонстрации. */
private val demoLive: Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/** Ключ: из задачи `orchestrationDemo` (берёт `server/.env`), из окружения или из запуска в IDE. */
private val demoApiKey: String? =
    System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

/** Идёт ли прогон на живом API: нужен и флаг, и ключ. */
private val demoOnLiveApi: Boolean = demoLive && demoApiKey != null
