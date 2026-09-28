package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.inputSchema
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Инструмент глазами вызова: объявление и поведение.
 *
 * Источник репозиториев подменён: проверяются фильтр, отказ на неизвестном значении до
 * обращения к сети и то, что любая ошибка GitHub приходит результатом с `isError`, а не
 * роняет сервер. Схема проверяется по той же спецификации, по которой сервер регистрирует
 * инструмент, — своей копии «как должно быть у клиента» здесь нет.
 */
class GetRepositoriesToolTest {

    private val tool = getRepositoriesTool(GitHubMcpServer.visibilityArgument)

    @Test
    fun `фильтр отдаёт только запрошенную видимость`() = runBlocking {
        assertEquals(listOf("public-repo", "legacy-repo"), names(tool.read(FakeGitHubApi(REPOSITORIES), request("public"))))
        assertEquals(listOf("private-repo"), names(tool.read(FakeGitHubApi(REPOSITORIES), request("private"))))
        assertEquals(REPOSITORIES.map { it.name }, names(tool.read(FakeGitHubApi(REPOSITORIES), request("all"))))
    }

    @Test
    fun `без аргумента возвращаются все репозитории`() = runBlocking {
        assertEquals(REPOSITORIES.map { it.name }, names(tool.read(FakeGitHubApi(REPOSITORIES), request())))
    }

    @Test
    fun `ответ инструмента — репозитории в обещанной форме`() = runBlocking {
        val result = tool.read(FakeGitHubApi(REPOSITORIES), request("private"))

        assertEquals(REPOSITORIES.filter { it.`private` }, Json.decodeFromString<List<GitHubRepository>>(result.text()))
    }

    @Test
    fun `пустой список репозиториев — не ошибка`() = runBlocking {
        val result = tool.read(FakeGitHubApi(emptyList()), request("private"))

        assertEquals(emptyList(), names(result))
        assertTrue(result.isError != true, "пустой список стал ошибкой вызова")
    }

    @Test
    fun `неизвестное значение visibility — отказ без обращения к API`() = runBlocking {
        val api = FakeGitHubApi(REPOSITORIES)

        val result = tool.read(api, request("secret"))

        assertEquals(true, result.isError)
        assertTrue("public" in result.text() && "private" in result.text(), "отказ не перечисляет допустимые значения")
        assertEquals(0, api.calls, "на неизвестном значении всё же пошли в GitHub")
    }

    @Test
    fun `без токена инструмент отвечает ошибкой`() = runBlocking {
        val api = GitHubApiImpl(
            config = GitHubConfig(apiBase = "http://127.0.0.1:1"),
            credentials = testCredentials(token = null),
            client = HttpClient(MockEngine { respond("[]") })
        )

        val result = tool.read(api, request())

        assertEquals(true, result.isError)
        assertTrue(GitHubConfig.TOKEN_ENV in result.text(), "отказ не перечисляет, где искали доступ")
    }

    @Test
    fun `отказы GitHub и сеть возвращаются ошибкой инструмента`() = runBlocking {
        val codes = listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.InternalServerError
        )
        codes.forEach { status ->
            val result = tool.read(apiAnswering { respond("нет", status) }, request())
            assertEquals(true, result.isError, "код ${status.value} не стал ошибкой")
        }

        val network = tool.read(apiAnswering { throw IOException("обрыв") }, request())
        assertEquals(true, network.isError)
    }

    @Test
    fun `схема инструмента перечисляет видимости и не требует аргумент`() {
        val declared = GitHubMcpServer.tools(FakeGitHubApi(emptyList()))
            .single { it.name == "get_repositories" }

        assertEquals("get_repositories", declared.name)
        val schema = declared.inputSchema()
        val visibility = schema.properties!!["visibility"]!!.jsonObject
        assertEquals("string", visibility["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("all", "public", "private"),
            visibility["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue("visibility" !in schema.required.orEmpty())
    }

    /** Реализация на подставном движке: сеть заменена, поведение ошибок — настоящее. */
    private fun apiAnswering(handler: io.ktor.client.engine.mock.MockRequestHandler): GitHubApi =
        GitHubApiImpl(
            config = GitHubConfig(apiBase = "https://api.github.com"),
            credentials = testCredentials(),
            client = HttpClient(MockEngine(handler))
        )

    /** Вызов инструмента: аргумент либо назван, либо отсутствует. */
    private fun request(visibility: String? = null): CallToolRequest = CallToolRequest(
        CallToolRequestParams(
            name = "get_repositories",
            arguments = buildJsonObject { visibility?.let { put("visibility", it) } }
        )
    )

    /** Имена репозиториев из ответа инструмента: ответ — JSON-массив обещанной формы. */
    private fun names(result: CallToolResult): List<String> =
        Json.decodeFromString<List<GitHubRepository>>(result.text()).map { it.name }

    /** Текст ответа инструмента: инструмент отвечает текстом, а не блоками. */
    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    /**
     * Источник репозиториев для проверок: считает вызовы, в сеть не ходит.
     *
     * Профиль и отчёт о доступе отдаёт-заглушки: инструменту `get_repositories` они не нужны,
     * но интерфейс обязан их иметь — иначе подставной источник не был бы источником данных
     * GitHub, и инструмент проверялся бы на другом типе, чем настоящий.
     */
    private class FakeGitHubApi(private val repositories: List<GitHubRepository>) : GitHubApi {

        var calls = 0
            private set

        override suspend fun repositories(): List<GitHubRepository> {
            calls++
            return repositories
        }

        override suspend fun account(): GitHubAccount =
            GitHubAccount(login = null, scopes = emptyList(), scopesReported = false)

        override suspend fun access(): GitHubAccessReport =
            GitHubAccessReport.unavailable(hint = null)
    }

    private companion object {

        /** Набор для фильтра: у «legacy-repo» видимость выведена из флага — как у старых ответов. */
        val REPOSITORIES = listOf(
            GitHubRepository(
                id = 1, name = "public-repo", fullName = "octo/public-repo",
                `private` = false, visibility = "public",
                url = "https://github.com/octo/public-repo", description = "публичный"
            ),
            GitHubRepository(
                id = 2, name = "private-repo", fullName = "octo/private-repo",
                `private` = true, visibility = "private",
                url = "https://github.com/octo/private-repo"
            ),
            GitHubRepository(
                id = 3, name = "legacy-repo", fullName = "octo/legacy-repo",
                `private` = false, visibility = "public",
                url = "https://github.com/octo/legacy-repo"
            )
        )
    }
}
