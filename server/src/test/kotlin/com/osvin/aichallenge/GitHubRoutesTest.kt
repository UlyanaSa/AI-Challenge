package com.osvin.aichallenge

import com.osvin.aichallenge.mcp.github.GitHubMcpServer
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.models.GitHubCallResponse
import com.osvin.aichallenge.models.GitHubConnection
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Маршруты GitHub на настоящем MCP-процессе: подключение, список инструментов, вызов
 * и отключение.
 *
 * Прогон живой в той части, которая принадлежит серверу: инструменты — настоящий отдельный
 * процесс по протоколу MCP, поднятый так же, как его поднимает приложение. Подставной только
 * GitHub — HTTP-сервер на свободном порту в этом же тесте, потому что токена GitHub в
 * окружении нет. Код-путь от подстановки не меняется: сервер инструментов берёт адрес API из
 * `GITHUB_API_BASE` и ходит по нему так же, как ходил бы в `api.github.com`.
 *
 * Проверяется контракт маршрутов, а не внутренности: снимок до подключения, состояние после
 * подключения с инструментом и его аргументом, вызов с фильтром, отказ инструмента как ответ
 * (`200`, `failed=true`), неизвестное имя как ошибка запроса (`404`) и вызов без соединения
 * (`409`). Заодно проверяется, что токен не утекает ни в один ответ.
 *
 * Процесс и подставной GitHub гасятся в `finally`: прогон не должен оставлять после себя
 * ни чужого процесса, ни занятого порта.
 */
class GitHubRoutesTest {

    /** Разбор ответов: проверяется структура снимка и ответа, а не точный текст. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `маршруты GitHub — подключение, инструменты, вызов, отключение`() {
        val github = StubGitHubApi()
        val githubTools = GitHubTools(
            config = localMcpServerConfig(
                GitHubMcpServer.MAIN_CLASS,
                env = mapOf("GITHUB_API_BASE" to github.baseUrl, "GITHUB_TOKEN" to "test-token")
            ),
            onLog = { println("[agent] $it") }
        )

        try {
            testApplication {
                application { module(githubTools) }

                // До подключения сервер инструментов не поднят: это нормальное состояние,
                // а не ошибка, — снимок говорит об этом флагом и пустым списком.
                val before = client.get("/v1/github")
                val beforeBody = before.bodyAsText()
                assertEquals(HttpStatusCode.OK, before.status, beforeBody)
                val beforeState = json.decodeFromString<GitHubConnection>(beforeBody)
                assertFalse(beforeState.connected, "до подключения соединения быть не должно")
                assertTrue(beforeState.tools.isEmpty(), "инструменты спрашивать ещё не у кого")

                // Подключение: токен лежит в окружении сервера инструментов, поэтому тела
                // хватает пустого объекта — это и есть путь подключения без ручного ввода.
                val connect = client.post("/v1/github/connect") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                val connectBody = connect.bodyAsText()
                assertEquals(HttpStatusCode.OK, connect.status, connectBody)
                val connected = json.decodeFromString<GitHubConnection>(connectBody)
                assertTrue(connected.connected, "после подключения соединение живое: $connectBody")
                assertEquals(GitHubMcpServer.NAME, connected.server)
                assertEquals(GitHubMcpServer.VERSION, connected.version)

                val tool = connected.tools.single { it.name == "get_repositories" }
                assertTrue(
                    !tool.description.isNullOrBlank(),
                    "описание инструмента нужно и человеку, и модели: $connectBody"
                )
                val visibility = tool.arguments.single { it.name == "visibility" }
                assertEquals("string", visibility.type)
                assertFalse(visibility.required, "visibility необязателен: без него инструмент вернёт всё")
                assertEquals(
                    listOf("all", "public", "private"),
                    visibility.values,
                    "допустимые значения уезжают клиенту из схемы инструмента"
                )
                assertNotNull(visibility.description, "пояснение аргумента нужно форме ручного вызова")

                // Повторное чтение отдаёт тот же снимок: состояние живёт на сервере, а не в ответе.
                val again = json.decodeFromString<GitHubConnection>(
                    client.get("/v1/github").bodyAsText()
                )
                assertTrue(again.connected)

                // Вызов с фильтром: приватные репозитории есть, публичного в ответе нет.
                val privateCall = client.post("/v1/github/call") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"get_repositories","arguments":{"visibility":"private"}}""")
                }
                val privateBody = privateCall.bodyAsText()
                assertEquals(HttpStatusCode.OK, privateCall.status, privateBody)
                val privateAnswer = json.decodeFromString<GitHubCallResponse>(privateBody)
                assertEquals("get_repositories", privateAnswer.name)
                assertFalse(privateAnswer.failed, "инструмент ответил без отказа: $privateBody")
                assertTrue(privateAnswer.result.contains("ai_challenge_task1"), privateBody)
                assertTrue(privateAnswer.result.contains("legacy-tools"), privateBody)
                assertFalse(
                    privateAnswer.result.contains("dotfiles"),
                    "в приватные попал публичный репозиторий: $privateBody"
                )

                // Отказ инструмента — это ответ: вызов дошёл, инструмент назвал причину,
                // поэтому 200 с `failed=true`, а не ошибка транспорта.
                val refusedCall = client.post("/v1/github/call") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"get_repositories","arguments":{"visibility":"all и мусор"}}""")
                }
                val refusedBody = refusedCall.bodyAsText()
                assertEquals(HttpStatusCode.OK, refusedCall.status, refusedBody)
                val refused = json.decodeFromString<GitHubCallResponse>(refusedBody)
                assertTrue(refused.failed, "неизвестное значение должно быть отказом инструмента: $refusedBody")
                assertTrue(
                    refused.result.contains("допустимые"),
                    "причина отказа должна перечислять допустимые значения: $refusedBody"
                )

                // Имени нет в списке — вызова не было, значит ошибка запроса: 404.
                val unknownCall = client.post("/v1/github/call") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"delete_everything","arguments":{}}""")
                }
                val unknownBody = unknownCall.bodyAsText()
                assertEquals(HttpStatusCode.NotFound, unknownCall.status, unknownBody)
                assertTrue(unknownBody.contains("delete_everything"), unknownBody)

                // Отключение гасит процесс: состояние возвращается к «не подключено».
                val disconnect = client.post("/v1/github/disconnect")
                val disconnectBody = disconnect.bodyAsText()
                assertEquals(HttpStatusCode.OK, disconnect.status, disconnectBody)
                assertFalse(
                    json.decodeFromString<GitHubConnection>(disconnectBody).connected,
                    "после отключения соединения быть не должно"
                )

                // Вызова без соединения не было — ошибка запроса: 409.
                val offlineCall = client.post("/v1/github/call") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"get_repositories","arguments":{}}""")
                }
                val offlineBody = offlineCall.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, offlineCall.status, offlineBody)

                // Токен не должен появиться ни в одном ответе: он остаётся дочернему процессу.
                listOf(
                    beforeBody, connectBody, privateBody, refusedBody,
                    unknownBody, disconnectBody, offlineBody
                ).forEach { body ->
                    assertFalse(
                        body.contains("test-token"),
                        "токен не должен возвращаться в ответе: $body"
                    )
                }
            }
        } finally {
            runBlocking { githubTools.close() }
            github.stop()
        }
    }
}

/**
 * Подставной GitHub: один маршрут `GET /user/repos` с тем же JSON, что отдаёт настоящий API.
 *
 * Набор нарочно неоднородный: приватный, публичный и запись без поля `visibility` — так видно,
 * что фильтр инструмента работает и что видимость выводится из признака приватности. Адрес
 * подставляется серверу инструментов переменной окружения: настоящего токена в окружении нет,
 * но код-путь — тот же, что с настоящим.
 */
private class StubGitHubApi {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/user/repos") { exchange ->
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
