package com.osvin.aichallenge.mcp.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Обращение к GitHub REST API: запрос, страницы и отказы.
 *
 * GitHub подменён движком MockEngine: настоящая сеть здесь не нужна и вредна — проверяются
 * заголовки доступа, обход страниц по `Link` и то, что каждый отказ превращается в
 * [GitHubApiException] с кодом, а не в исключение транспорта.
 */
class GitHubApiImplTest {

    @Test
    fun `запрос идёт с токеном, версией API и именем клиента`() = runBlocking {
        lateinit var seen: HttpRequestData
        val api = api { request ->
            seen = request
            respond("[]")
        }

        api.repositories()

        assertEquals("Bearer $TEST_TOKEN", seen.headers[HttpHeaders.Authorization])
        assertEquals("application/vnd.github+json", seen.headers[HttpHeaders.Accept])
        assertTrue(seen.headers[HttpHeaders.UserAgent].orEmpty().isNotBlank())
        assertEquals("/user/repos", seen.url.encodedPath)
        assertEquals("100", seen.url.parameters["per_page"])
        assertEquals("updated", seen.url.parameters["sort"])
    }

    @Test
    fun `страницы обходятся по заголовку Link`() = runBlocking {
        val api = api { request ->
            if (request.url.parameters["page"] == null) {
                respond(
                    content = """[{"id":1,"name":"first","full_name":"octo/first","html_url":"u","private":false,"visibility":"public"}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.Link to listOf("""<$API_BASE/user/repos?page=2>; rel="next", <$API_BASE/user/repos?page=2>; rel="last"""")
                    )
                )
            } else {
                respond("""[{"id":2,"name":"second","full_name":"octo/second","html_url":"u","private":true,"visibility":"private"}]""")
            }
        }

        assertEquals(listOf("first", "second"), api.repositories().map { it.name })
    }

    @Test
    fun `без токена в сеть не ходим`() = runBlocking {
        var requested = false
        val api = GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE, token = null),
            client = HttpClient(MockEngine { requested = true; respond("[]") })
        )

        val error = assertFailsWith<GitHubApiException> { api.repositories() }

        assertNull(error.status, "до GitHub не дошло — кода быть не должно")
        assertTrue(GitHubConfig.TOKEN_ENV in error.message.orEmpty(), "в причине не названа переменная")
        assertFalse(requested, "без токена запрос всё же ушёл")
    }

    @Test
    fun `401 и 403 объясняются словами`() = runBlocking {
        val unauthorized = failureOf(HttpStatusCode.Unauthorized)
        assertEquals(401, unauthorized.status)
        assertTrue("токен" in unauthorized.message.orEmpty())

        val forbidden = failureOf(HttpStatusCode.Forbidden)
        assertEquals(403, forbidden.status)
        assertTrue("прав" in forbidden.message.orEmpty() || "лимит" in forbidden.message.orEmpty())
    }

    @Test
    fun `прочие коды возвращаются с кодом и телом`() = runBlocking {
        val failure = failureOf(HttpStatusCode.InternalServerError, body = "внутренняя ошибка GitHub")

        assertEquals(500, failure.status)
        assertTrue("500" in failure.message.orEmpty())
        assertTrue("внутренняя ошибка GitHub" in failure.message.orEmpty())
    }

    @Test
    fun `сетевой сбой — это ошибка без кода`() = runBlocking {
        val api = api { throw IOException("обрыв соединения") }

        val error = assertFailsWith<GitHubApiException> { api.repositories() }

        assertNull(error.status)
        assertTrue("сеть" in error.message.orEmpty())
    }

    /** Отказ с заданным кодом: тело и заголовки для этих проверок не нужны. */
    private suspend fun failureOf(status: HttpStatusCode, body: String = "нет"): GitHubApiException =
        assertFailsWith { api { respond(body, status) }.repositories() }

    /** Реализация на подставном движке: адрес настоящий, сеть — нет. */
    private fun api(handler: MockRequestHandler): GitHubApiImpl =
        GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE, token = TEST_TOKEN),
            client = HttpClient(MockEngine(handler))
        )

    private companion object {

        const val API_BASE = "https://api.github.com"
        const val TEST_TOKEN = "test-token"
    }
}
