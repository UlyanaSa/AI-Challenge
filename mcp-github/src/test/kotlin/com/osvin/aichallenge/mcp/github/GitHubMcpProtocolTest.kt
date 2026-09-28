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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Демонстрация дня 17: агент подключается к MCP-серверу GitHub и получает от него репозитории.
 *
 * GitHub подменён настоящим локальным HTTP-сервером (`com.sun.net.httpserver`): клиент
 * поднимает процесс сервера, тот ходит по реальному localhost на подставные ответы — сеть
 * не нужна, а путь «MCP → HTTP → JSON» проходит целиком, включая токен из окружения.
 *
 * Проверяется не «мы умеем вызывать свою функцию», а протокол и фильтр: агент видит
 * объявленный инструмент, вызывает его с `visibility`, и в ответе остаются только те
 * репозитории, о которых спросили. Ожидаемое объявление берётся из [GitHubMcpServer.tools] —
 * той же спецификации, по которой сервер регистрирует инструмент.
 */
class GitHubMcpProtocolTest {

    @Test
    fun `агент получает из GitHub-сервера только нужные репозитории`() = runBlocking {
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

            val declared = GitHubMcpServer.tools(aStub()).single()
            assertEquals(listOf(declared.name), tools.map { it.name }, "список инструментов у клиента")
            assertEquals(declared.description, tools.single().description, "описание инструмента")

            val privateRepos = repositories(session.client.callTool("get_repositories", mapOf("visibility" to "private")))
            log("вызов get_repositories visibility=private: ${privateRepos.map { it.name }}")
            assertEquals(listOf("private-repo"), privateRepos.map { it.name })

            val publicRepos = repositories(session.client.callTool("get_repositories", mapOf("visibility" to "public")))
            log("вызов get_repositories visibility=public: ${publicRepos.map { it.name }}")
            assertEquals(listOf("public-repo", "legacy-repo"), publicRepos.map { it.name })

            tools
        } finally {
            session.close()
            gitHub.stop(0)
        }

        assertFalse(session.isRunning, "серверный процесс остался работать после закрытия сессии")
        assertTrue(paths.isNotEmpty() && paths.all { it == "/user/repos" }, "сервер ходил не в /user/repos: $paths")
        assertTrue(authorizations.all { it == "Bearer $TEST_TOKEN" }, "сервер ходил без токена: $authorizations")
        assertEquals(1, listed.size, "инструмент потерялся")
        log("сессия закрыта: серверный процесс остановлен, токен дошёл до GitHub")
    }

    /**
     * Подставной GitHub: отдаёт детерминированный ответ и запоминает, о чём его спросили.
     *
     * Ответ содержит все три случая видимости — явные `private`/`public` и репозиторий вовсе
     * без поля `visibility`: фильтр должен работать и на последнем, иначе проверка обошла бы
     * обходной путь, ради которого он и написан.
     */
    private fun mockGitHub(paths: MutableList<String>, authorizations: MutableList<String?>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val body = ANSWER.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    /** Ответ инструмента обратно в модель: так его читает и агент, и проверка. */
    private fun repositories(result: CallToolResult): List<GitHubRepository> =
        Json.decodeFromString<List<GitHubRepository>>(result.text())

    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    /** Источник репозиториев, нужный только чтобы получить объявление инструмента. */
    private fun aStub(): GitHubApi = object : GitHubApi {
        override suspend fun repositories(): List<GitHubRepository> = emptyList()
    }

    /** Строка демонстрации — в том же виде, что у прочих демонстраций проекта. */
    private fun log(line: String) = println("[agent] $line")

    /** Заголовок этапа: по нему видно, что печатает прогон. */
    private fun stage(title: String) = println("[agent] === $title ===")

    private companion object {

        const val TEST_TOKEN = "test-token"

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
