package com.osvin.aichallenge

import com.osvin.aichallenge.mcp.github.GitHubMcpServer
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.models.GitHubCallResponse
import com.osvin.aichallenge.models.GitHubConnection
import com.sun.net.httpserver.HttpExchange
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Маршруты GitHub на настоящем MCP-процессе: подключение, доступ, список инструментов, вызов
 * и отключение.
 *
 * Прогон живой в той части, которая принадлежит серверу: инструменты — настоящий отдельный
 * процесс по протоколу MCP, поднятый так же, как его поднимает приложение. Подставной только
 * GitHub — HTTP-сервер на свободном порту в этом же тесте. Код-путь от подстановки не
 * меняется: сервер инструментов берёт адрес API из `GITHUB_API_BASE` и ходит по нему так же,
 * как ходил бы в `api.github.com`.
 *
 * Доступ подкладывается окружением дочернего процесса, а не кодом сервера приложения: он и
 * на машине человека приходит из окружения, и другого пути у сервера приложения нет —
 * токена он не читает вовсе. Поэтому в первом прогоне `GITHUB_TOKEN` задан процессу
 * инструментов, а во втором отсечены все источники разом (пустые `GITHUB_TOKEN` и
 * `GITHUB_TOKEN_FILE`, пустой `PATH` — чтобы `security`, `gh` и `git` не нашлись, — и пустой
 * `HOME` — чтобы не нашёлся файл доступа по умолчанию). Второй прогон и есть проверка
 * «доступа нет»: она обязана быть детерминированной, иначе на машине с настроенным GitHub
 * она проверяла бы машину, а не код.
 *
 * Проверяется контракт маршрутов, а не внутренности: снимок до подключения, состояние после
 * подключения с инструментом и его аргументом, доступ в снимке (логин и права из заголовка
 * `X-OAuth-Scopes`), вызов с фильтром, отказ инструмента как ответ (`200`, `failed=true`),
 * неизвестное имя как ошибка запроса (`404`) и вызов без соединения (`409`). Отдельно —
 * подключение без доступа: `200`, `authorized=false` и перечень проверенных мест в `hint`,
 * а не отказ шлюза. И ещё отдельно — повторное нажатие кнопки: доступ перечитывается у живого
 * соединения, потому что та же кнопка у интерфейса работает как «Проверить снова».
 * Заодно проверяется, что токен не утекает ни в один ответ.
 *
 * Процесс и подставной GitHub гасятся в `finally`: прогон не должен оставлять после себя
 * ни чужого процесса, ни занятого порта, ни временного каталога.
 */
class GitHubRoutesTest {

    /** Разбор ответов: проверяется структура снимка и ответа, а не точный текст. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `маршруты GitHub — подключение, доступ, инструменты, вызов, отключение`() {
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

                // Подключение без тела: доступ берётся готовым с машины, и сервер приложения
                // не принимает его ни полем запроса, ни где-либо ещё — искать его будет
                // сервер инструментов по своим источникам (здесь — по `GITHUB_TOKEN`).
                val connect = client.post("/v1/github/connect")
                val connectBody = connect.bodyAsText()
                assertEquals(HttpStatusCode.OK, connect.status, connectBody)
                val connected = json.decodeFromString<GitHubConnection>(connectBody)
                assertTrue(connected.connected, "после подключения соединение живое: $connectBody")
                assertEquals(GitHubMcpServer.NAME, connected.server)
                assertEquals(GitHubMcpServer.VERSION, connected.version)

                // Доступ приехал отчётом инструмента `github_access`, а не проверкой сервера
                // приложения: логин — из ответа `GET /user`, права — из заголовка `X-OAuth-Scopes`.
                assertTrue(connected.authorized, "доступ задан окружению процесса: $connectBody")
                assertEquals("ulanocka", connected.login, connectBody)
                assertEquals(
                    listOf("repo", "read:user"),
                    connected.scopes,
                    "права приходят из заголовка X-OAuth-Scopes: $connectBody"
                )
                assertTrue(connected.scopesReported, "заголовок был — права сообщены: $connectBody")
                assertTrue(
                    connected.source?.contains("GITHUB_TOKEN") == true,
                    "источник доступа назван пометкой, а не значением токена: $connectBody"
                )
                assertNull(connected.hint, "доступ есть — подсказывать нечего: $connectBody")
                assertTrue(
                    connected.tools.any { it.name == GitHubTools.GITHUB_ACCESS_TOOL },
                    "инструмент, которым сервер рассказал о доступе, объявлен им же: $connectBody"
                )

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

    @Test
    fun `маршруты GitHub — подключение без доступа отвечает состоянием, а не отказом`() {
        val github = StubGitHubApi()
        // Пустой каталог как PATH и HOME: ни `security`, ни `gh`, ни `git` не найдутся, и
        // файла доступа по умолчанию тоже не будет. Пустые переменные доступа отсекают
        // первые два источника. Иначе прогон проверял бы машину, а не код: на машине
        // с настроенным GitHub доступ нашёлся бы, и «доступа нет» не воспроизвелось бы.
        val empty = Files.createTempDirectory("github-access-less")
        val githubTools = GitHubTools(
            config = localMcpServerConfig(
                GitHubMcpServer.MAIN_CLASS,
                env = mapOf(
                    "GITHUB_API_BASE" to github.baseUrl,
                    "GITHUB_TOKEN" to "",
                    "GITHUB_TOKEN_FILE" to "",
                    "PATH" to empty.toString(),
                    "HOME" to empty.toString()
                )
            ),
            onLog = { println("[agent] $it") }
        )

        try {
            testApplication {
                application { module(githubTools) }

                // Подключение состоялось и без доступа: 200, а не 502 — процесс инструментов
                // поднялся, недоступен именно GitHub, и это состояние с причиной, а не отказ.
                val connect = client.post("/v1/github/connect")
                val connectBody = connect.bodyAsText()
                assertEquals(HttpStatusCode.OK, connect.status, connectBody)
                val connected = json.decodeFromString<GitHubConnection>(connectBody)
                assertTrue(connected.connected, "процесс инструментов поднялся: $connectBody")
                assertEquals(GitHubMcpServer.NAME, connected.server, connectBody)
                assertFalse(connected.authorized, "ни один источник не дал доступа: $connectBody")
                assertNull(connected.login, connectBody)
                assertTrue(connected.scopes.isEmpty(), connectBody)
                assertFalse(connected.scopesReported, connectBody)
                assertNull(connected.source, connectBody)

                // Подсказка — не «ошибка», а перечень проверенных мест и того, что сделать:
                // по ней человек и создаёт доступ. Проверяется перечень, а не его редакция,
                // поэтому утверждаемся на места из контракта, а не на фразы вокруг них.
                val hint = connected.hint
                assertNotNull(hint, "человеку нужна причина и что делать: $connectBody")
                assertTrue(hint.contains("Проверено"), "перечень проверенных мест: $hint")
                listOf("GITHUB_TOKEN", "связка ключей", "gh auth token", "git credential").forEach { place ->
                    assertTrue(
                        hint.contains(place),
                        "перечень проверенных мест должен называть $place: $hint"
                    )
                }

                // Инструменты поднявшегося сервера видны и без доступа: подключение и доступ —
                // разные поля снимка, и второе не отменяет первого.
                assertTrue(connected.tools.isNotEmpty(), "инструменты объявлены сервером: $connectBody")

                // Вызов инструмента без доступа — ответ инструмента, а не ошибка транспорта:
                // 200 с `failed=true` и причиной, как и любой другой отказ инструмента.
                val refusedCall = client.post("/v1/github/call") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"get_repositories","arguments":{}}""")
                }
                val refusedBody = refusedCall.bodyAsText()
                assertEquals(HttpStatusCode.OK, refusedCall.status, refusedBody)
                assertTrue(
                    json.decodeFromString<GitHubCallResponse>(refusedBody).failed,
                    "без доступа инструмент обязан отказать: $refusedBody"
                )
            }
        } finally {
            runBlocking { githubTools.close() }
            github.stop()
            empty.toFile().deleteRecursively()
        }
    }

    @Test
    fun `маршруты GitHub — повторное подключение перечитывает доступ`() {
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

                val first = json.decodeFromString<GitHubConnection>(
                    client.post("/v1/github/connect").bodyAsText()
                )
                assertTrue(first.authorized, "первое подключение находит доступ")

                // Токен отозвали, пока соединение живо. Та же кнопка — «Проверить снова» —
                // обязана это показать: снимок, снятый при подъёме процесса, здесь был бы
                // неправдой, а кнопка — бесполезной.
                github.revokeAccess()
                val againBody = client.post("/v1/github/connect").bodyAsText()
                val again = json.decodeFromString<GitHubConnection>(againBody)
                assertTrue(again.connected, "отозванный доступ не рвёт соединение: $againBody")
                assertFalse(again.authorized, "доступ перечитан заново: $againBody")
                assertNull(again.login, "владельца больше не называют: $againBody")
                assertNotNull(again.hint, "человеку нужна причина отказа: $againBody")
                assertTrue(
                    again.source?.contains("GITHUB_TOKEN") == true,
                    "источник проверенного токена остаётся в отчёте: $againBody"
                )
                assertTrue(again.tools.isNotEmpty(), "инструменты остались на месте: $againBody")

                // Снимок, который читает интерфейс, — тот же, что вернуло подключение:
                // состояние живёт на сервере, а не в ответе на нажатие кнопки.
                val snapshot = json.decodeFromString<GitHubConnection>(
                    client.get("/v1/github").bodyAsText()
                )
                assertFalse(snapshot.authorized, "снимок отдаёт перечитанное состояние: $snapshot")
            }
        } finally {
            runBlocking { githubTools.close() }
            github.stop()
        }
    }
}

/**
 * Подставной GitHub: маршруты `GET /user`, `GET /user/repos`, `GET /repos/{owner}/{repo}` и
 * `GET /repos/{owner}/{repo}/commits` с тем же JSON, что отдаёт настоящий API.
 *
 * Набор репозиториев нарочно неоднородный: приватный, публичный и запись без поля
 * `visibility` — так видно, что фильтр инструмента работает и что видимость выводится из
 * признака приватности. `/user` нужен, чтобы у доступа был владелец, а заголовок
 * `X-OAuth-Scopes` — чтобы у него были права: состоянию доступа в снимке неоткуда взяться,
 * кроме ответа GitHub. Адрес подставляется серверу инструментов переменной окружения:
 * настоящего GitHub здесь нет, но код-путь — тот же, что с настоящим.
 */
private class StubGitHubApi {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Доступ отозван: так проверяется, что повторное подключение читает состояние заново. */
    private val revoked = AtomicBoolean(false)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/user") { exchange ->
            if (revoked.get()) {
                respond(exchange, """{"message": "Bad credentials"}""", status = 401)
            } else {
                respond(exchange, USER, scopes = "repo, read:user")
            }
        }
        server.createContext("/user/repos") { exchange ->
            respond(exchange, REPOSITORIES)
        }
        server.createContext("/repos") { exchange ->
            val body = if (exchange.requestURI.path.endsWith("/commits")) COMMITS else REPOSITORY
            respond(exchange, body)
        }
        server.start()
    }

    /** Отзывает доступ: владельца больше не называют, как после отзыва токена. */
    fun revokeAccess() = revoked.set(true)

    /** Ответ с телом, кодом и, если он есть, заголовком прав: как у настоящего GitHub. */
    private fun respond(
        exchange: HttpExchange,
        json: String,
        scopes: String? = null,
        status: Int = 200
    ) {
        val body = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        scopes?.let { exchange.responseHeaders.add("X-OAuth-Scopes", it) }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    fun stop() = server.stop(0)

    private companion object {

        /** Ответ GitHub REST API на `/user`: владелец доступа. */
        val USER = """{"login": "ulanocka", "id": 1, "name": "Улан"}"""

        /**
         * Ответ GitHub REST API на `/repos/{owner}/{repo}`: один репозиторий из набора ниже.
         *
         * Тот же репозиторий, что первым в [REPOSITORIES]: имена в обоих ответах — имена GitHub
         * (`full_name`, `html_url`), и разбирает их одна форма модели.
         */
        val REPOSITORY = """
            {"id": 1, "name": "ai_challenge_task1",
             "full_name": "ulanocka/ai_challenge_task1", "private": true, "visibility": "private",
             "html_url": "https://github.com/ulanocka/ai_challenge_task1",
             "description": "Челлендж по Android-разработке: агент и MCP"}
        """.trimIndent()

        /**
         * Ответ GitHub REST API на `/repos/{owner}/{repo}/commits`: три коммита, свежие первыми.
         *
         * У последнего автора нет вовсе — так GitHub отвечает на коммиты без учётной записи,
         * и обещанная форма это допускает.
         */
        val COMMITS = """
            [
              {"sha": "a1b2c3d", "commit": {"message": "День 20: инструменты репозитория и коммитов",
               "author": {"name": "Улан", "email": "ulan@example.com"}}},
              {"sha": "d4e5f6a", "commit": {"message": "День 19: чистка",
               "author": {"name": "Улан", "email": "ulan@example.com"}}},
              {"sha": "b7c8d9e", "commit": {"message": "День 18: доступ", "author": null}}
            ]
        """.trimIndent()

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
