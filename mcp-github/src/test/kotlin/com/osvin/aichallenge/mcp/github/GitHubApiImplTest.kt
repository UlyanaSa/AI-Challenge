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
 * Обращение к GitHub REST API: запрос, страницы, профиль и отказы.
 *
 * GitHub подменён движком MockEngine: настоящая сеть здесь не нужна и вредна — проверяются
 * заголовки доступа, обход страниц по `Link`, разбор профиля и то, что каждый отказ
 * превращается в [GitHubApiException] с кодом, а не в исключение транспорта.
 *
 * Цепочка источников тоже подменена: тесты настраивают её явно ([credentials]), поэтому
 * ни один из них не смотрит в настоящее окружение и не поднимает внешних команд.
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
    fun `профиль читается с токеном и правами из заголовка`() = runBlocking {
        lateinit var seen: HttpRequestData
        val api = api { request ->
            seen = request
            respond(
                content = """{"login":"ulanocka"}""",
                headers = headersOf("X-OAuth-Scopes", listOf("repo, read:user,, gist"))
            )
        }

        val account = api.account()

        assertEquals("ulanocka", account.login)
        assertEquals(listOf("repo", "read:user", "gist"), account.scopes)
        assertTrue(account.scopesReported, "права пришли заголовком, а отчёт говорит «не сообщены»")
        assertEquals("/user", seen.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", seen.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `без заголовка прав профиль говорит, что права не сообщены`() = runBlocking {
        val account = api { respond("""{"login":"octo"}""") }.account()

        assertEquals("octo", account.login)
        assertEquals(emptyList(), account.scopes)
        assertFalse(account.scopesReported, "отсутствие заголовка выдано за «прав нет»")
    }

    @Test
    fun `пустой заголовок прав — это «прав нет», а не «не сообщены»`() = runBlocking {
        val account = api {
            respond("""{"login":"octo"}""", headers = headersOf("X-OAuth-Scopes", listOf("")))
        }.account()

        assertEquals(emptyList(), account.scopes)
        assertTrue(account.scopesReported)
    }

    @Test
    fun `без токена в сеть не ходим`() = runBlocking {
        var requested = false
        val api = GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE),
            credentials = testCredentials(token = null),
            client = HttpClient(MockEngine { requested = true; respond("[]") })
        )

        val error = assertFailsWith<GitHubApiException> { api.repositories() }

        assertNull(error.status, "до GitHub не дошло — кода быть не должно")
        assertTrue(GitHubConfig.TOKEN_ENV in error.message.orEmpty(), "в причине не названо, где искали")
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
    fun `отказ на профиле объясняется профилем, а не репозиториями`() = runBlocking {
        val failure = assertFailsWith<GitHubApiException> {
            api { respond("нет", HttpStatusCode.Forbidden) }.account()
        }

        val message = failure.message.orEmpty()
        assertTrue("профил" in message, "отказ не называет, что именно читали: $message")
        assertFalse("репозитори" in message, "отказ про профиль говорит про репозитории: $message")
    }

    @Test
    fun `непринятый токен на профиле — тот же 401`() = runBlocking {
        val failure = assertFailsWith<GitHubApiException> {
            api { respond("нет", HttpStatusCode.Unauthorized) }.account()
        }

        assertEquals(401, failure.status)
        assertTrue("токен" in failure.message.orEmpty())
        assertTrue(TEST_TOKEN !in failure.message.orEmpty(), "значение токена попало в текст ошибки")
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

    @Test
    fun `состояние доступа называет источник и вход`() = runBlocking {
        val report = api {
            respond("""{"login":"ulanocka"}""", headers = headersOf("X-OAuth-Scopes", listOf("repo")))
        }.access()

        assertTrue(report.authorized)
        assertEquals("ulanocka", report.login)
        assertEquals(listOf("repo"), report.scopes)
        assertTrue(report.scopesReported)
        assertEquals(TEST_SOURCE, report.source)
        assertNull(report.hint)
        assertTrue(TEST_TOKEN !in report.toString(), "значение токена попало в отчёт")
    }

    @Test
    fun `без доступа состояние приходит подсказкой, а не исключением`() = runBlocking {
        var requested = false
        val report = GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE),
            credentials = testCredentials(token = null),
            client = HttpClient(MockEngine { requested = true; respond("[]") })
        ).access()

        assertFalse(report.authorized)
        assertNull(report.source)
        assertTrue(GitHubConfig.TOKEN_ENV in report.hint.orEmpty(), "подсказка не назвала проверенные места")
        assertFalse(requested, "без токена запрос всё же ушёл")
    }

    @Test
    fun `недоступная сеть — это состояние доступа с причиной`() = runBlocking {
        val report = api { throw IOException("обрыв соединения") }.access()

        assertFalse(report.authorized)
        assertEquals(TEST_SOURCE, report.source, "источник токена потерялся в отчёте")
        assertTrue("сеть" in report.hint.orEmpty(), "причина не названа: ${report.hint}")
    }

    /** Отказ с заданным кодом: тело и заголовки для этих проверок не нужны. */
    private suspend fun failureOf(status: HttpStatusCode, body: String = "нет"): GitHubApiException =
        assertFailsWith { api { respond(body, status) }.repositories() }

    /** Реализация на подставном движке: адрес настоящий, сеть — нет. */
    private fun api(handler: MockRequestHandler): GitHubApiImpl =
        GitHubApiImpl(
            config = GitHubConfig(apiBase = API_BASE),
            credentials = testCredentials(),
            client = HttpClient(MockEngine(handler))
        )

    private companion object {

        const val API_BASE = "https://api.github.com"
    }
}
