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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Данные GitHub в REST API: `GET /user/repos`, `GET /user`, `GET /repos/{owner}/{repo}`
 * и `GET /repos/{owner}/{repo}/commits`.
 *
 * Ходит по сети сам (Ktor + CIO): сервер общается с клиентом по stdio, а наружу ему нужен
 * HTTP-клиент. Клиент передаётся параметром со значением по умолчанию — проверки подставляют
 * подставной движок (MockEngine) и проверяют разбор и ошибки без сети, рабочий сервер берёт
 * собственный клиент на CIO. Владелец клиента один: созданный здесь закрывается здесь ([close]),
 * а переданный закрывает тот, кто его создал, — поэтому `main` процесс просто завершает.
 *
 * Токен приходит не параметром, а цепочкой источников ([GitHubCredentials]): он ищется на машине
 * в момент обращения и только там — в поле класса токен не живёт, поэтому не может ни утечь
 * в лог, ни пережить отзыв доступа. Найденное место ([GitHubAccess.source]) попадает в тексты
 * ошибок вместо самого значения: человеку важно знать, какой токен не принят.
 *
 * @param config Куда ходить и какой файл с токеном человек назвал.
 * @param credentials Цепочка источников токена.
 * @param client HTTP-клиент; по умолчанию создаётся свой на CIO.
 */
class GitHubApiImpl(
    private val config: GitHubConfig,
    private val credentials: GitHubCredentials,
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
        val access = accessOrFail()
        val collected = mutableListOf<GitHubRepository>()
        var next: String? = firstPage()
        var page = 0
        while (next != null && page < MAX_PAGES) {
            val response = request(next, access, REPOSITORIES_FORBIDDEN)
            collected += parsePage(response.bodyAsText())
            next = response.headers.nextPage()
            page++
        }
        return collected
    }

    /**
     * Один репозиторий владельца токена.
     *
     * Владелец спрашивается у профиля ([account]), а не берётся параметром: адрес репозитория
     * включает логин, а из токена логин не выводится — GitHub его не называет в ответе на
     * `/user/repos`. Лишний запрос на вызов — цена за то, что владелец всегда тот же, что
     * у токена: иначе модель угадывала бы логин, которого не знает.
     */
    override suspend fun repository(name: String): GitHubRepository {
        val access = accessOrFail()
        val owner = ownerOrFail()
        val response = request(repositoryUrl(owner, name), access, REPOSITORY_FORBIDDEN)
        return JSON.decodeFromString<GitHubRepositoryResponse>(response.bodyAsText()).toRepository()
    }

    /**
     * Последние коммиты репозитория владельца токена.
     *
     * `limit` уходит в `per_page`: GitHub отдаёт коммиты страницами, и просить страницу нужного
     * размера дешевле, чем получить сотню и обрезать её у себя — лишние данные ещё и уехали бы
     * в разбор. Постраничного обхода здесь нет намеренно: инструмент обещает «последние
     * коммиты», а не «все», и предел [limit] задаёт вызывающий.
     */
    override suspend fun commits(name: String, limit: Int): List<GitHubCommit> {
        val access = accessOrFail()
        val owner = ownerOrFail()
        val response = request(commitsUrl(owner, name, limit), access, COMMITS_FORBIDDEN)
        return JSON.decodeFromString<List<GitHubCommitResponse>>(response.bodyAsText())
            .map { it.toCommit() }
    }

    /**
     * Профиль владельца токена и права токена.
     *
     * Права берутся из заголовка [OAUTH_SCOPES_HEADER]: у classic-токена это список через
     * запятую, у fine-grained токенов заголовка нет вовсе — тогда [GitHubAccount.scopesReported]
     * говорит «права не сообщены», и инструмент именно это и напишет. Заголовок с пустым
     * значением — противоположный случай («права сообщены, их нет»), и различие сохраняется:
     * «не сообщено» нельзя показывать как «нет прав».
     */
    override suspend fun account(): GitHubAccount {
        val access = accessOrFail()
        val response = request(profileUrl(), access, PROFILE_FORBIDDEN)
        val profile = JSON.decodeFromString<GitHubProfileResponse>(response.bodyAsText())
        val reported = response.headers[OAUTH_SCOPES_HEADER]
        val scopes = reported
            ?.split(SCOPES_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        return GitHubAccount(
            login = profile.login,
            scopes = scopes.orEmpty(),
            scopesReported = reported != null
        )
    }

    /**
     * Состояние доступа: найден ли токен и принял ли его GitHub.
     *
     * Разбор здесь, а не в инструменте, потому что только здесь известны обе половины ответа:
     * откуда взят токен ([GitHubCredentials]) и что ответил GitHub ([account]). Инструменту
     * остаётся напечатать готовый отчёт, а клиенту — решить, показывать ли кнопку «Проверить
     * снова»: по [GitHubAccessReport.authorized] видно, что проверка не прошла, а по
     * [GitHubAccessReport.hint] — что с этим делать.
     */
    override suspend fun access(): GitHubAccessReport = when (val lookup = lookupAccess()) {
        is GitHubCredentialLookup.Missing ->
            GitHubAccessReport.unavailable(missingAccessHint(lookup.tried))

        is GitHubCredentialLookup.Found -> try {
            val account = account()
            GitHubAccessReport(
                authorized = true,
                login = account.login,
                scopes = account.scopes,
                scopesReported = account.scopesReported,
                source = lookup.access.source,
                hint = null
            )
        } catch (error: GitHubApiException) {
            // Токен есть, а GitHub его не принял или не ответил: это тоже состояние доступа,
            // а не отказ инструмента. Источник остаётся в отчёте — человеку видно, какой токен
            // проверяли, и это единственное, чем такой случай отличается от «токена нет».
            GitHubAccessReport.unavailable(hint = error.message, source = lookup.access.source)
        }
    }

    /** Закрывает созданный здесь HTTP-клиент: соединения и потоки движка не должны утекать. */
    override fun close() = client.close()

    /**
     * Доступ или внятная ошибка с перечнем проверенных мест.
     *
     * Исключение, а не null: вызывающему без токена нечего делать, кроме как сообщить о нём,
     * а исключение несёт готовый текст и превращается в `isError` одним местом
     * ([getRepositoriesTool]). Возврат null означал бы проверку на каждом шаге обращения к API
     * — и рано или поздно забытую.
     */
    private suspend fun accessOrFail(): GitHubAccess = when (val lookup = lookupAccess()) {
        is GitHubCredentialLookup.Found -> lookup.access
        is GitHubCredentialLookup.Missing -> throw GitHubApiException(
            status = null,
            message = missingAccessHint(lookup.tried)
        )
    }

    /**
     * Поиск доступа на диспетчере ввода-вывода.
     *
     * Поиск — это чтение файлов и запуск внешних команд, то есть блокирующая работа на секунды.
     * На диспетчере по умолчанию она задержала бы кадры протокола MCP, которые обслуживает тот
     * же поток: сервер отвечал бы не на текущий вызов, а после чужого `gh auth token`.
     */
    private suspend fun lookupAccess(): GitHubCredentialLookup =
        withContext(Dispatchers.IO) { credentials.lookup() }

    /**
     * Логин владельца токена или внятный отказ, если GitHub его не назвал.
     *
     * Профиль переспрашивается, а не запоминается в поле: логин — часть адреса, и кэш в поле
     * либо устарел бы после смены токена в процессе, либо потребовал бы сброса при отзыве
     * доступа, о котором сервер узнаёт только ответом GitHub. Цена — один запрос, а профиль
     * всё равно читается инструментом `github_access`; повторный поиск доступа не дороже:
     * успех [GitHubCredentials] кэширует.
     *
     * Отказ здесь — не 401 и не 403, а форма ответа: до GitHub дошло и он ответил, но без
     * логина. Как и у [GitHubAccount.login], это «не названо», а не «прав нет».
     */
    private suspend fun ownerOrFail(): String =
        account().login?.takeIf { it.isNotBlank() } ?: throw GitHubApiException(
            status = null,
            message = "GitHub не назвал владельца токена: адрес репозитория собрать не из чего"
        )

    /** Первая страница: количество на страницу и порядок — от недавно обновлённых. */
    private fun firstPage(): String =
        "${config.apiBase.trimEnd('/')}/user/repos?per_page=$PAGE_SIZE&sort=$SORT"

    /** Адрес профиля владельца токена. */
    private fun profileUrl(): String = "${config.apiBase.trimEnd('/')}/user"

    /** Адрес одного репозитория владельца. */
    private fun repositoryUrl(owner: String, name: String): String =
        "${config.apiBase.trimEnd('/')}/repos/$owner/$name"

    /** Адрес коммитов: сколько просить за страницу, задаёт вызывающий через `per_page`. */
    private fun commitsUrl(owner: String, name: String, limit: Int): String =
        "${config.apiBase.trimEnd('/')}/repos/$owner/$name/commits?per_page=$limit"

    /**
     * Запрос с заголовками доступа и разбором отказа.
     *
     * Заголовки — почти дословно из документации GitHub: `Bearer`-токен, версия API через
     * `Accept` и `User-Agent` (GitHub без него отвечает отказом). Отказ разбирается здесь,
     * а не у вызывающего: коды не-2xx означают разные вещи, и различать их должен тот, кто
     * держал ответ в руках.
     *
     * [access] нужен целиком, а не одним токеном: в текст отказа уходит название источника
     * ([GitHubAccess.source]), и человек читает, какой именно токен не принят, — а сам токен
     * в текст не попадает.
     *
     * [forbidden] — объяснение для 403 от того, кто знает, что читали: у репозиториев и профиля
     * причины отказа разные, и «нет прав на чтение профиля» вместо «на чтение репозиториев»
     * отправило бы человека проверять не то.
     */
    private suspend fun request(url: String, access: GitHubAccess, forbidden: String): HttpResponse {
        val response = try {
            client.get(url) {
                header(HttpHeaders.Authorization, "Bearer ${access.token}")
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
                message = "токен не принят GitHub (401): токен из источника «${access.source}» " +
                    "отозван или истёк"
            )

            HttpStatusCode.Forbidden -> GitHubApiException(
                status = response.status.value,
                message = "GitHub отказал (403): $forbidden"
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

        /** Заголовок с правами токена: так GitHub называет их у classic-токенов. */
        const val OAUTH_SCOPES_HEADER = "X-OAuth-Scopes"

        /** Разделитель прав в заголовке — запятая с пробелом, но полагаемся только на запятую. */
        const val SCOPES_SEPARATOR = ","

        /** Почему 403 при чтении репозиториев. */
        const val REPOSITORIES_FORBIDDEN =
            "у токена нет прав на чтение репозиториев или исчерпан лимит запросов"

        /** Почему 403 при чтении профиля: причина другая, и путать их нельзя. */
        const val PROFILE_FORBIDDEN =
            "у токена нет прав на чтение профиля или исчерпан лимит запросов"

        /** Почему 403 при чтении одного репозитория: причин две, и обе называются. */
        const val REPOSITORY_FORBIDDEN =
            "у токена нет прав на чтение этого репозитория или исчерпан лимит запросов"

        /** Почему 403 при чтении коммитов: причина другая, и путать их нельзя. */
        const val COMMITS_FORBIDDEN =
            "у токена нет прав на чтение коммитов или исчерпан лимит запросов"

        /** Сколько тела ошибки показать: длинный ответ GitHub человеку не нужен. */
        const val ERROR_BODY_LIMIT = 500

        /** Разбор ответа: чужие поля не срывают чтение — GitHub добавляет их чаще, чем мы правим модель. */
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Ответ GitHub на `GET /user` — только то, что обещает сервер.
 *
 * Форма внутренняя, как и [GitHubRepositoryResponse]: чужие поля профиля (аватары, планы, число
 * подписчиков) инструментам не нужны, а неизвестные ключи разбор игнорирует. Права профилю не
 * принадлежат — они у токена, и приходят заголовком, а не телом.
 *
 * @param login Логин владельца; может отсутствовать, если GitHub его не назвал.
 */
@Serializable
internal data class GitHubProfileResponse(val login: String? = null)

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
