package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Демонстрация дня 18: доступа к GitHub хватает с машины, и сервер сам о нём докладывает.
 *
 * GitHub подменён настоящим локальным HTTP-сервером (`com.sun.net.httpserver`): клиент поднимает
 * процесс сервера, тот ходит по реальному localhost на подставные ответы — сеть не нужна, а путь
 * «MCP → HTTP → JSON» проходит целиком, включая токен, взятый из окружения цепочкой источников.
 *
 * Проверяется не «мы умеем вызывать свою функцию», а два обещания дня: инструмент
 * `get_repositories` отдаёт только те репозитории, о которых спросили, а инструмент
 * `github_access` называет вход, права и источник токена — и при этом не отвечает ошибкой.
 * Ожидаемые объявления берутся из [GitHubMcpServer.tools] — той же спецификации, по которой
 * сервер регистрирует инструменты.
 */
class GitHubMcpProtocolTest {

    @Test
    fun `агент получает из GitHub-сервера репозитории и отчёт о доступе`() = runBlocking {
        val paths = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val gitHub = mockGitHub(paths, authorizations)
        val port = gitHub.address.port
        stage("MCP: подключение к серверу GitHub")
        log("подставной GitHub: http://127.0.0.1:$port, репозиториев 3")

        val session = openMcpSession(
            localMcpServerConfig(mainClass = GitHubMcpServer.MAIN_CLASS).copy(
                env = mapOf(
                    "GITHUB_TOKEN" to TEST_TOKEN,
                    "GITHUB_API_BASE" to "http://127.0.0.1:$port"
                )
            ),
            onServerStderr = { log("сервер: $it") }
        )

        val listed = try {
            log("соединение установлено: ${session.serverName} ${session.serverVersion}")
            assertEquals(GitHubMcpServer.NAME, session.serverName)

            val tools = session.listTools()
            log("получено инструментов: ${tools.size}")
            tools.forEach { tool -> log("инструмент: ${tool.name}; аргументы: ${tool.arguments.joinToString { it.name }}") }

            val declared = GitHubMcpServer.tools(aStub())
            assertEquals(declared.map { it.name }, tools.map { it.name }, "список инструментов у клиента")
            assertEquals(declared.map { it.description }, tools.map { it.description }, "описания инструментов")

            val privateRepos = repositories(session.client.callTool("get_repositories", mapOf("visibility" to "private")))
            log("вызов get_repositories visibility=private: ${privateRepos.map { it.name }}")
            assertEquals(listOf("private-repo"), privateRepos.map { it.name })

            val publicRepos = repositories(session.client.callTool("get_repositories", mapOf("visibility" to "public")))
            log("вызов get_repositories visibility=public: ${publicRepos.map { it.name }}")
            assertEquals(listOf("public-repo", "legacy-repo"), publicRepos.map { it.name })

            val accessCall = session.client.callTool("github_access", emptyMap())
            val access = access(accessCall)
            log(
                "вызов github_access: доступ ${if (access.authorized) "есть" else "не найден"}; " +
                    "вход ${access.login}; источник ${access.source}"
            )
            assertFalse(accessCall.isError == true, "отчёт о доступе пришёл ошибкой вызова")
            assertTrue(access.authorized, "токен из окружения не признан: ${access.hint}")
            assertEquals("ulanocka", access.login)
            assertEquals(listOf("repo", "read:user"), access.scopes)
            assertTrue(access.scopesReported, "права из заголовка не доехали до клиента")
            assertTrue(
                "GITHUB_TOKEN" in access.source.orEmpty(),
                "источник токена назван не тот: ${access.source}"
            )
            assertNull(access.hint, "у найденного доступа не должно быть подсказки")
            assertTrue(TEST_TOKEN !in accessCall.text(), "значение токена попало в ответ инструмента")
            log("в ответе нет значения токена — только место, откуда он взят")

            tools
        } finally {
            session.close()
            gitHub.stop(0)
        }

        assertFalse(session.isRunning, "серверный процесс остался работать после закрытия сессии")
        assertTrue(
            paths.isNotEmpty() && paths.all { it == "/user/repos" || it == "/user" },
            "сервер ходил не туда: $paths"
        )
        assertTrue(authorizations.all { it == "Bearer $TEST_TOKEN" }, "сервер ходил без токена: $authorizations")
        assertEquals(4, listed.size, "инструмент потерялся")
        log("сессия закрыта: серверный процесс остановлен, токен дошёл до GitHub")
    }

    /**
     * Подставной GitHub: отдаёт детерминированный ответ и запоминает, о чём его спросили.
     *
     * Ответ репозиториев содержит все три случая видимости — явные `private`/`public` и
     * репозиторий вовсе без поля `visibility`: фильтр должен работать и на последнем, иначе
     * проверка обошла бы обходной путь, ради которого он и написан. На `/user` приходит профиль
     * и заголовок с правами — именно так GitHub отвечает на вопрос о доступе. `/repos/…` и
     * `/repos/…/commits` отдают один репозиторий и коммиты — то, что читают `get_repository`
     * и `get_recent_commits`; коммит без автора там тоже есть, потому что модель допускает
     * его отсутствие.
     */
    private fun mockGitHub(paths: MutableList<String>, authorizations: MutableList<String?>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            paths += path
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val profile = path == "/user"
            val body = when {
                profile -> PROFILE
                path.endsWith("/commits") -> COMMITS
                path.startsWith("/repos/") -> REPOSITORY
                else -> ANSWER
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            if (profile) exchange.responseHeaders.add("X-OAuth-Scopes", SCOPES)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    /** Ответ инструмента обратно в модель: так его читает и агент, и проверка. */
    private fun repositories(result: CallToolResult): List<GitHubRepository> =
        Json.decodeFromString<List<GitHubRepository>>(result.text())

    /** Отчёт о доступе: разбирается той же формой, которой его читает клиент. */
    private fun access(result: CallToolResult): GitHubAccessReport =
        Json { ignoreUnknownKeys = true }.decodeFromString<GitHubAccessReport>(result.text())

    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    /** Источник данных, нужный только чтобы получить объявления инструментов. */
    private fun aStub(): GitHubApi = object : GitHubApi {
        override suspend fun repositories(): List<GitHubRepository> = emptyList()

        override suspend fun repository(name: String): GitHubRepository = GitHubRepository(
            id = 0,
            name = name,
            fullName = "octo/$name",
            `private` = false,
            visibility = "public",
            url = "https://github.com/octo/$name"
        )

        override suspend fun commits(name: String, limit: Int): List<GitHubCommit> = emptyList()

        override suspend fun account(): GitHubAccount =
            GitHubAccount(login = null, scopes = emptyList(), scopesReported = false)

        override suspend fun access(): GitHubAccessReport = GitHubAccessReport.unavailable(hint = null)
    }

    /** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
    private fun log(line: String) = println("[agent] $line")

    /** Заголовок этапа: по нему видно, что печатает прогон. */
    private fun stage(title: String) = println("[agent] === $title ===")

    private companion object {

        /** Права, которые подставной GitHub сообщает заголовком. */
        const val SCOPES = "repo, read:user"

        /** Профиль владельца токена: так GitHub отвечает на `GET /user`. */
        const val PROFILE = """{"login":"ulanocka"}"""

        /** Один репозиторий: так GitHub отвечает на `GET /repos/{owner}/{repo}`. */
        val REPOSITORY = """
            {
              "id": 1,
              "name": "public-repo",
              "full_name": "octo/public-repo",
              "html_url": "https://github.com/octo/public-repo",
              "private": false,
              "visibility": "public",
              "description": "публичный"
            }
        """.trimIndent()

        /**
         * Коммиты: так GitHub отвечает на `GET /repos/{owner}/{repo}/commits`.
         *
         * У третьего коммита автора нет вовсе — этот случай обещан моделью
         * ([GitHubCommit.author] допускает null), и подстановка обязана его показывать.
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

        val ANSWER = """
            [
              {
                "id": 1,
                "name": "public-repo",
                "full_name": "octo/public-repo",
                "html_url": "https://github.com/octo/public-repo",
                "private": false,
                "visibility": "public",
                "description": "публичный"
              },
              {
                "id": 2,
                "name": "private-repo",
                "full_name": "octo/private-repo",
                "html_url": "https://github.com/octo/private-repo",
                "private": true,
                "visibility": "private"
              },
              {
                "id": 3,
                "name": "legacy-repo",
                "full_name": "octo/legacy-repo",
                "html_url": "https://github.com/octo/legacy-repo",
                "private": false
              }
            ]
        """.trimIndent()
    }
}
