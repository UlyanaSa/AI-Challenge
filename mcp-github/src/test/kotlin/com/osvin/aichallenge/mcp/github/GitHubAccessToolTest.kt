package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.inputSchema
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

/**
 * Инструмент `github_access` глазами вызова: объявление и отчёт о доступе.
 *
 * Проверяется главное обещание инструмента — он отчитывается, а не отказывает: при доступе,
 * без доступа, при недоступной сети и при неожиданном сбое ответ остаётся разбираемым отчётом
 * и никогда не приходит ошибкой вызова. Разбор ответа здесь тот же, каким его читает клиент:
 * если форма отчёта разойдётся с ожидаемой, тест не разберёт ответ и упадёт.
 */
class GitHubAccessToolTest {

    private val tool = githubAccessTool()

    @Test
    fun `инструмент объявлен без аргументов`() {
        assertEquals("github_access", tool.name)
        assertEquals(emptyList(), tool.arguments, "у отчёта о доступе аргументов быть не должно")

        val schema = tool.inputSchema()

        assertTrue(schema.properties!!.isEmpty(), "схема обещает аргументы, которых нет")
        assertNull(schema.required, "обязательных аргументов у инструмента нет")
    }

    @Test
    fun `доступ есть — отчёт называет вход, права и источник`() = runBlocking {
        val api = api {
            respond("""{"login":"ulanocka"}""", headers = headersOf("X-OAuth-Scopes", listOf("repo")))
        }

        val result = tool.read(api, request())

        assertEquals(false, result.isError)
        val report = report(result)
        assertTrue(report.authorized)
        assertEquals("ulanocka", report.login)
        assertEquals(listOf("repo"), report.scopes)
        assertTrue(report.scopesReported)
        assertEquals(TEST_SOURCE, report.source)
        assertNull(report.hint)
        assertTrue(TEST_TOKEN !in result.text(), "значение токена попало в ответ инструмента")
    }

    @Test
    fun `права не сообщены — отчёт говорит это словами, а не пустотой`() = runBlocking {
        val result = tool.read(api { respond("""{"login":"octo"}""") }, request())

        val report = report(result)
        assertEquals(emptyList(), report.scopes)
        assertFalse(report.scopesReported, "отсутствие заголовка выдано за «прав нет»")
    }

    @Test
    fun `доступа нет — отчёт перечисляет проверенные места`() = runBlocking {
        val api = GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE),
            credentials = testCredentials(token = null),
            client = HttpClient(MockEngine { respond("[]") })
        )

        val result = tool.read(api, request())

        assertEquals(false, result.isError, "отсутствие доступа стало ошибкой инструмента")
        val report = report(result)
        assertFalse(report.authorized)
        assertNull(report.source)
        assertTrue(GitHubConfig.TOKEN_ENV in report.hint.orEmpty(), "подсказка не назвала переменную")
        assertTrue("chmod 600" in report.hint.orEmpty(), "подсказка не сказала про права файла")
        assertTrue(TEST_TOKEN !in result.text(), "значение токена попало в ответ инструмента")
    }

    @Test
    fun `сеть недоступна — это состояние, а не отказ инструмента`() = runBlocking {
        val result = tool.read(api { throw IOException("обрыв соединения") }, request())

        assertEquals(false, result.isError, "недоступная сеть стала ошибкой инструмента")
        val report = report(result)
        assertFalse(report.authorized)
        assertEquals(TEST_SOURCE, report.source, "источник токена потерялся")
        assertTrue("сеть" in report.hint.orEmpty(), "причина не названа: ${report.hint}")
    }

    @Test
    fun `непринятый токен — состояние с кодом в подсказке`() = runBlocking {
        val result = tool.read(api { respond("нет", HttpStatusCode.Unauthorized) }, request())

        assertEquals(false, result.isError)
        val report = report(result)
        assertFalse(report.authorized)
        assertTrue("401" in report.hint.orEmpty(), "подсказка не назвала код: ${report.hint}")
    }

    @Test
    fun `неожиданный сбой отвечает отчётом, а не ошибкой вызова`() = runBlocking {
        val broken = object : GitHubApi {
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

            override suspend fun access(): GitHubAccessReport =
                throw IllegalStateException("поломка разбора")
        }

        val result = tool.read(broken, request())

        assertEquals(false, result.isError, "сбой внутри инструмента вышел наружу кодом ошибки")
        val report = report(result)
        assertFalse(report.authorized)
        assertTrue("поломка разбора" in report.hint.orEmpty(), "причина сбоя потерялась")
    }

    /** Реализация на подставном движке: сеть заменена, поведение отчёта — настоящее. */
    private fun api(handler: io.ktor.client.engine.mock.MockRequestHandler): GitHubApi =
        GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE),
            credentials = testCredentials(),
            client = HttpClient(MockEngine(handler))
        )

    /** Вызов без аргумента: у инструмента их нет, и вызов это подтверждает. */
    private fun request(): CallToolRequest = CallToolRequest(
        CallToolRequestParams(name = "github_access", arguments = buildJsonObject { })
    )

    /** Ответ инструмента в обещанную форму: так его разбирает клиент. */
    private fun report(result: CallToolResult): GitHubAccessReport =
        Json { ignoreUnknownKeys = true }.decodeFromString(result.text())

    /** Текст ответа инструмента: инструмент отвечает текстом, а не блоками. */
    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private companion object {

        const val API_BASE = "https://api.github.com"
    }
}
