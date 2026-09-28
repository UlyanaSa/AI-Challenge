package com.osvin.aichallenge.mcp.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Репозитории из GitHub REST API: `GET /user/repos`.
 *
 * Ходит по сети сам (Ktor + CIO): сервер общается с клиентом по stdio, а наружу ему нужен
 * HTTP-клиент. Клиент передаётся параметром со значением по умолчанию — тесты подставляют
 * подставной движок (MockEngine) и проверяют разбор и ошибки без сети, рабочий сервер берёт
 * собственный клиент на CIO. Владелец клиента один: созданный здесь закрывается здесь ([close]),
 * а переданный закрывает тот, кто его создал, — поэтому `main` процесс просто завершает.
 *
 * @param config Куда ходить и с каким токеном.
 * @param client HTTP-клиент; по умолчанию создаётся свой на CIO.
 */
class GitHubApiImpl(
    private val config: GitHubConfig,
    private val client: HttpClient = HttpClient(CIO)
) : GitHubApi, Closeable {

    /**
     * Постраничная выборка с ограничением на число страниц.
     *
     * GitHub отдаёт репозитории страницами по [PAGE_SIZE] и ссылку на следующую страницу —
     * в заголовке `Link`. Ограничение [MAX_PAGES] защищает от бесконечного цикла, если ссылка
     * `next` окажется замкнутой или чужой: без него сервер мог бы ходить в GitHub вечно,
     * а пользователь — не дождаться ответа. Десяти страниц по [PAGE_SIZE] репозиториев хватает
     * с запасом, а большего инструмент и не обещает.
     */
    override suspend fun repositories(): List<GitHubRepository> {
        val token = config.requireToken()
        val collected = mutableListOf<GitHubRepository>()
        var next: String? = firstPage()
        var page = 0
        while (next != null && page < MAX_PAGES) {
            val response = request(next, token)
            collected += parsePage(response.bodyAsText())
            next = response.headers.nextPage()
            page++
        }
        return collected
    }

    /** Закрывает созданный здесь HTTP-клиент: соединения и потоки движка не должны утекать. */
    override fun close() = client.close()

    /** Первая страница: количество на страницу и порядок — от недавно обновлённых. */
    private fun firstPage(): String =
        "${config.apiBase.trimEnd('/')}/user/repos?per_page=$PAGE_SIZE&sort=$SORT"

    /**
     * Запрос с заголовками доступа и разбором отказа.
     *
     * Заголовки — почти дословно из документации GitHub: `Bearer`-токен, версия API через
     * `Accept` и `User-Agent` (GitHub без него отвечает отказом). Отказ разбирается здесь,
     * а не у вызывающего: коды не-2xx означают разные вещи, и различать их должен тот, кто
     * держал ответ в руках.
     */
    private suspend fun request(url: String, token: String): HttpResponse {
        val response = try {
            client.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ACCEPT)
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
        } catch (cancelled: CancellationException) {
            // Отмена — не сбой сети: её должен увидеть вызывающий, а не текст «нет доступа».
            throw cancelled
        } catch (error: Exception) {
            throw GitHubApiException(status = null, message = "сеть до GitHub недоступна: ${error.message}")
        }

        if (response.status.isSuccess()) return response

        throw when (response.status) {
            HttpStatusCode.Unauthorized -> GitHubApiException(
                status = response.status.value,
                message = "токен не принят GitHub (401): проверьте, что ${GitHubConfig.TOKEN_ENV} " +
                    "содержит действующий токен и не истёк"
            )

            HttpStatusCode.Forbidden -> GitHubApiException(
                status = response.status.value,
                message = "GitHub отказал (403): у токена нет прав на чтение репозиториев " +
                    "или исчерпан лимит запросов"
            )

            else -> GitHubApiException(
                status = response.status.value,
                message = "GitHub ответил ${response.status.value}: " +
                    response.bodyAsText().take(ERROR_BODY_LIMIT)
            )
        }
    }

    /** Страница ответа в обещанную форму: незнакомые поля GitHub игнорируются. */
    private fun parsePage(body: String): List<GitHubRepository> =
        JSON.decodeFromString<List<GitHubRepositoryResponse>>(body).map { it.toRepository() }

    private companion object {

        /** Сколько репозиториев просить за страницу — верхняя граница GitHub. */
        const val PAGE_SIZE = 100

        /** Порядок: сначала недавно обновлённые — так свежие репозитории не теряются за страницей. */
        const val SORT = "updated"

        /** Страниц не больше этого: см. KDoc [repositories]. */
        const val MAX_PAGES = 10

        /** Версия GitHub REST API в заголовке `Accept`. */
        const val ACCEPT = "application/vnd.github+json"

        /** Кто ходит: GitHub требует непустой `User-Agent`. */
        const val USER_AGENT = "ai-challenge-github-mcp"

        /** Сколько тела ошибки показать: длинный ответ GitHub человеку не нужен. */
        const val ERROR_BODY_LIMIT = 500

        /** Разбор ответа: чужие поля не срывают чтение — GitHub добавляет их чаще, чем мы правим модель. */
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Адрес следующей страницы из заголовка `Link` или null, если страниц больше нет.
 *
 * Формат заголовка — `<https://…?page=2>; rel="next", <…>; rel="last"`, и нужен именно элемент
 * с `rel="next"`: он есть, пока есть что листать. Ссылка абсолютная, поэтому берётся как есть,
 * без сборки адреса заново — иначе пришлось бы повторять параметры GitHub и разойтись с ним.
 */
private fun io.ktor.http.Headers.nextPage(): String? =
    getAll(HttpHeaders.Link).orEmpty()
        .flatMap { it.split(",") }
        .firstOrNull { it.contains("rel=\"next\"") }
        ?.substringAfter('<')
        ?.substringBefore('>')
