package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.AgentResult
import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.agent.ToolOutcome
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Демонстрация дня 17: агент сам вызывает инструмент MCP-сервера GitHub по обычной просьбе.
 *
 * Прогон живой целиком, кроме самого GitHub: модель настоящая, сервер инструментов —
 * настоящий отдельный процесс по протоколу MCP, а GitHub подставной (HTTP-сервер в этом же
 * тесте), потому что токена GitHub в окружении нет. Код-путь от подстановки не меняется:
 * сервер инструментов берёт адрес API из переменной окружения `GITHUB_API_BASE` и ходит по
 * нему так же, как ходил бы в `https://api.github.com`.
 *
 * Что проверяется. Инструмент выбирает модель по фразе — вызывающего кода про GitHub в тесте
 * нет; ответ опирается на данные инструмента — в нём есть имена репозиториев, которых в
 * просьбе не было; фильтр виден по тому, что именно ушло модели (инструмент на этапе
 * обёрнут записью), а не по формулировке ответа, которая у модели свободная.
 *
 * Без живого ключа прогон ничего не проверяет, поэтому он выключен по умолчанию:
 * `./gradlew :server:githubDemo -Pdemo.live=1` (ключ берётся из `server/.env` или окружения).
 */
class GitHubToolsDemoTest {

    @Test
    fun `агент сам выбирает инструмент GitHub и отвечает по его данным`() = runBlocking {
        if (!demoOnLiveApi) {
            log("живой прогон выключен: нужен флаг -Pdemo.live=1 и ключ DEEPSEEK_API_KEY")
            log("запуск: ./gradlew :server:githubDemo -Pdemo.live=1")
            return@runBlocking
        }

        val github = StubGitHub()
        val http = demoHttpClient()
        val tools = githubTools(github)

        try {
            log("GitHub (подставной): ${github.baseUrl}")
            stage("Подключение к серверу инструментов")
            val connection = assertIs<GitHubConnect.Connected>(
                tools.connect("test-token"),
                "сервер инструментов не подключился: соединение теперь открывается явно"
            ).connection
            assertTrue(connection.connected, "снимок подключения должен быть подключённым")
            log("подключено: ${connection.server} ${connection.version}")

            stage("Инструменты, которые агент получил от MCP-сервера")
            val available = tools.tools()
            assertEquals(
                listOf("get_repositories"),
                available.map { it.name },
                "сервер инструментов объявил не то, что ожидалось"
            )
            available.forEach { tool ->
                log("инструмент: ${tool.name} — ${tool.description}")
                log("  схема аргументов: ${tool.parameters}")
            }

            val agent = LlmAgent(DeepSeekClient(demoApiKey!!, http), logger = AgentLogger.Console)

            stage("Все репозитории")
            val all = recorded(available)
            val allReply = answers(agent, all, "Покажи все мои репозитории на GitHub")
            val allAnswer = all.answers.single()
            assertContains(allAnswer.text, "ai_challenge_task1")
            assertContains(allAnswer.text, "dotfiles")
            assertContains(allAnswer.text, "legacy-tools")
            assertContains(allReply.reply, "ai_challenge_task1")
            assertContains(allReply.reply, "dotfiles")

            stage("Публичные репозитории")
            val public = recorded(available)
            val publicReply = answers(agent, public, "Какие из моих репозиториев на GitHub публичные?")
            val publicAnswer = public.answers.single()
            assertContains(publicAnswer.text, "dotfiles")
            assertFalse(
                publicAnswer.text.contains("ai_challenge_task1"),
                "в публичные попал приватный репозиторий: ${publicAnswer.text}"
            )
            assertContains(publicReply.reply, "dotfiles")

            stage("Приватные репозитории")
            val private = recorded(available)
            val privateReply = answers(agent, private, "Покажи мои приватные репозитории на GitHub")
            val privateAnswer = private.answers.single()
            assertContains(privateAnswer.text, "ai_challenge_task1")
            // Видимость этой записи GitHub не назвал: она выведена из признака приватности
            assertContains(privateAnswer.text, "legacy-tools")
            assertFalse(
                privateAnswer.text.contains("dotfiles"),
                "в приватные попал публичный репозиторий: ${privateAnswer.text}"
            )
            assertContains(privateReply.reply, "ai_challenge_task1")

            // Запросы к подставному GitHub должны были уйти от имени пользователя:
            // без токена отказ случился бы раньше, чем обращение к API.
            assertEquals(
                github.requests.size,
                github.requests.count { it.contains("Bearer test-token") },
                "часть запросов ушла без токена: ${github.requests}"
            )
            log("обращений к подставному GitHub: ${github.requests.size}, все с Bearer-токеном")

            stage("Отказ инструмента: нет токена")
            val tokenless = githubTools(github)
            try {
                assertIs<GitHubConnect.Connected>(
                    tokenless.connect(null),
                    "без токена само подключение проходит: токен читается только на вызове"
                )
                val refusal = recorded(tokenless.tools())
                val refusalReply = answers(agent, refusal, "Сколько у меня репозиториев на GitHub?")
                val refusalAnswer = refusal.answers.single()
                assertTrue(refusalAnswer.isError, "без токена инструмент должен был отказать")
                log("отказ инструмента, дошедший до модели: ${refusalAnswer.text}")
                assertTrue(
                    refusalReply.reply.isNotBlank(),
                    "модель должна была ответить человеку по причине отказа, а не промолчать"
                )
            } finally {
                tokenless.close()
            }
        } finally {
            tools.close()
            http.close()
            github.stop()
            log("сессия инструментов закрыта, подставной GitHub остановлен")
        }
    }

    /**
     * Инструменты GitHub как у сервера приложения: тот же модуль на своём classpath, тот же
     * класс точки входа, а адрес API уезжает процессу окружением. Токен здесь не подставляется
     * в конфиг: его называет явное подключение ([GitHubTools.connect]) — так же, как это делает
     * сервер приложения, когда человек нажимает кнопку.
     */
    private fun githubTools(github: StubGitHub) = GitHubTools(
        config = localMcpServerConfig(
            GitHubTools.GITHUB_SERVER_MAIN,
            env = mapOf("GITHUB_API_BASE" to github.baseUrl)
        ),
        onLog = { log(it) }
    )

    /** Инструмент с записью ответа: по нему видно, какие данные получила модель. */
    private fun recorded(tools: List<AgentTool>): RecordedTool = RecordedTool(tools.single())

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

    /** Один запрос пользователя: печатает, что выбрала модель и чем ответила, и отдаёт ответ. */
    private suspend fun answers(agent: LlmAgent, recorded: RecordedTool, phrase: String): AgentResult {
        val result = agent.run(phrase, AgentOptions(tools = listOf(recorded.tool)))
        val calls = result.tokens.tools.calls
        log("вызовы инструментов: " + calls.joinToString { call ->
            call.name + if (call.failed) " (отказ)" else ""
        })
        log("раундов с инструментами: ${result.tokens.tools.rounds}, их токенов: ${result.tokens.tools.tokens}")
        log("ответ: ${result.reply}")
        assertTrue(calls.isNotEmpty(), "модель не позвала инструмент: это и есть предмет демонстрации")
        assertTrue(
            calls.all { it.name == recorded.tool.name },
            "вызван не тот инструмент: $calls"
        )
        return result
    }
}

/**
 * Инструмент с записью того, что ушло модели.
 *
 * Ответ инструмента нужен демонстрации целиком: фильтр по видимости виден по нему, а не по
 * формулировке ответа — формулировку выбирает модель, и проверять по ней фильтр значило бы
 * проверять красноречие, а не работу инструмента.
 */
private class RecordedTool(delegate: AgentTool) {

    val answers = mutableListOf<ToolOutcome>()

    val tool = AgentTool(
        name = delegate.name,
        description = delegate.description,
        parameters = delegate.parameters,
        call = { arguments -> delegate.call(arguments).also { answers += it } }
    )
}

/** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
private fun log(line: String) = println("[agent] $line")

/** Заголовок этапа: по нему видно, на каком запросе прогон. */
private fun stage(title: String) = println("[agent] === $title ===")

/** Живой режим включается явно: без него прогон не тратит бюджет, как и прочие демонстрации. */
private val demoLive: Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/** Ключ: из задачи `githubDemo` (берёт `server/.env`), из окружения или из запуска в IDE. */
private val demoApiKey: String? =
    System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

/** Идёт ли прогон на живом API: нужен и флаг, и ключ. */
private val demoOnLiveApi: Boolean = demoLive && demoApiKey != null

/**
 * Подставной GitHub: один маршрут `GET /user/repos` с тем же JSON, что отдаёт настоящий API.
 *
 * Набор нарочно неоднородный: приватный, публичный и запись без поля `visibility` — так
 * видно и фильтр, и вывод видимости из признака приватности. Адрес подставляется серверу
 * инструментов переменной окружения: настоящего токена в окружении нет, но код-путь — тот
 * же, что с настоящим.
 *
 * Запросы записываются: по ним видно, что сервер инструментов ходит в API от имени
 * пользователя (с Bearer-токеном), а не анонимно.
 */
private class StubGitHub {

    val requests = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/user/repos") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI} | " +
                "Authorization: ${exchange.requestHeaders.getFirst("Authorization") ?: "нет"}"
            val body = REPOSITORIES.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private companion object {
        /** Ответ GitHub REST API: те же имена полей, что у настоящего. */
        val REPOSITORIES = """
            [
              {"id": 1, "name": "ai_challenge_task1",
               "full_name": "ulanocka/ai_challenge_task1", "private": true, "visibility": "private",
               "html_url": "https://github.com/ulanocka/ai_challenge_task1",
               "description": "Челлендж по Android-разработке: агент и MCP"},
              {"id": 2, "name": "dotfiles",
               "full_name": "ulanocka/dotfiles", "private": false, "visibility": "public",
               "html_url": "https://github.com/ulanocka/dotfiles",
               "description": "Личные настройки"},
              {"id": 3, "name": "legacy-tools",
               "full_name": "ulanocka/legacy-tools", "private": true,
               "html_url": "https://github.com/ulanocka/legacy-tools",
               "description": null}
            ]
        """.trimIndent()
    }
}
