package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.ServerSettings
import com.osvin.aichallenge.data.ServiceChatCreated
import com.osvin.aichallenge.data.ServiceChatHistory
import com.osvin.aichallenge.data.ServiceChatList
import com.osvin.aichallenge.data.ServiceChatRequest
import com.osvin.aichallenge.data.ServiceChatResponse
import com.osvin.aichallenge.data.ServiceCreateChatRequest
import com.osvin.aichallenge.data.ServiceDeletedChat
import com.osvin.aichallenge.data.ServiceErrorBody
import com.osvin.aichallenge.data.ServiceHealth
import com.osvin.aichallenge.data.ServiceModel
import com.osvin.aichallenge.data.ServiceModels
import com.osvin.aichallenge.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Отказ приватного сервиса `/api/…`: тело `{code, message, requestId}` в виде исключения.
 *
 * Причину храним разобранной ([code]), а не только текстом, потому что разные коды
 * требуют разного поведения: `unauthorized` — это состояние авторизации, `chat_not_found` —
 * повод завести сессию заново, `rate_limited`/`overloaded` — повторить позже ([retryAfterSeconds]).
 * Разбирать текст сообщения для этого было бы нельзя: формулировку сервис вправе
 * изменить, а код — часть контракта.
 *
 * @param code Код отказа из контракта (`unauthorized`, `rate_limited`, …).
 * @param message Человеческая причина от сервиса — её и показываем.
 * @param retryAfterSeconds `Retry-After` в секундах, если сервис его прислал.
 */
class ServiceApiException(
    val code: String,
    override val message: String,
    val retryAfterSeconds: Int? = null
) : Exception(message)

/**
 * HTTP-вызовы приватного сервиса `/api/…`.
 *
 * Отдельный от [ChatRepository] класс, а не методы репозитория: у сервиса свой ключ,
 * свой адрес и свои коды отказа, и держать это отдельно от пути `/v1` — единственный
 * способ не задеть последний. Клиент берётся готовым ([HttpClient]): движок подставляет
 * тот, кто собирает репозиторий, поэтому тесты поднимают сервис на `MockEngine`, а не
 * на настоящей сети.
 *
 * Ключ уходит заголовком `X-API-Key` и только сюда: `ChatRepository` кладёт этот клиент
 * себе и больше его никуда не передаёт, поэтому на `/v1/…` — чужой поверхности — ключ
 * не попадает. Заголовок не пишется в лог: логируется метод, путь и код ответа, — иначе
 * ключ осел бы в логах устройства, а текст сообщения человека — рядом.
 */
class ServiceClient(private val client: HttpClient) {

    /**
     * Живость сервиса: `GET /api/health`. Ключа не требует — это проверка до его ввода.
     *
     * Отказ (503) тоже несёт причину словами (`message`, иначе `ollama`), и её забираем
     * в исключение, а не теряем: по коду 503 без текста человек не поймёт, не поднят
     * демон или не загружена модель. Формат ошибки тут свой, не общий `{code,message,requestId}`,
     * поэтому разбираем [ServiceHealth], а не [ServiceErrorBody].
     */
    suspend fun health(settings: ServerSettings): ServiceHealth = call {
        val response = client.get(settings.baseUrl + API_HEALTH)
        log("GET", API_HEALTH, response)
        val body = runCatching { response.body<ServiceHealth>() }.getOrNull()
        if (!response.status.isSuccess()) {
            val reason = body?.message?.takeIf { it.isNotBlank() }
                ?: body?.ollama?.takeIf { it.isNotBlank() }?.let { "демон Ollama: $it" }
                ?: "сервис ответил кодом ${response.status.value}"
            throw ServiceApiException(code = SERVICE_UNAVAILABLE, message = reason)
        }
        body ?: ServiceHealth(status = "ok")
    }

    /** Список моделей сервиса: `GET /api/models`. Требует ключ. */
    suspend fun models(settings: ServerSettings): List<ServiceModel> = call {
        val response = client.get(settings.baseUrl + API_MODELS) {
            header(API_KEY_HEADER, settings.apiKey)
        }
        log("GET", API_MODELS, response)
        failOrThrow(response)
        response.body<ServiceModels>().models
    }

    /**
     * Новая сессия сервиса: `POST /api/chats`.
     *
     * Заголовок необязателен — сервис даст свой. Прошлый чат, из которого строится сводка,
     * не выбирается: это дело сервиса (§1.2), и клиент, назвавший его сам, показал бы
     * сводку не того диалога, который человек считает прошлым.
     */
    suspend fun createChat(settings: ServerSettings, title: String?): ServiceChatCreated = call {
        val response = client.post(settings.baseUrl + API_CHATS) {
            contentType(ContentType.Application.Json)
            header(API_KEY_HEADER, settings.apiKey)
            setBody(ServiceCreateChatRequest(title?.takeIf { it.isNotBlank() }))
        }
        log("POST", API_CHATS, response)
        failOrThrow(response)
        response.body()
    }

    /**
     * Список чатов сервиса: `GET /api/chats`. Требует ключ.
     *
     * @param limit Сколько чатов просить; null — умолчание сервиса. Больше его потолка
     *        присылать бессмысленно: сервис обрежет сам (§1.1).
     */
    suspend fun chats(settings: ServerSettings, limit: Int? = null): ServiceChatList = call {
        val response = client.get(settings.baseUrl + API_CHATS) {
            header(API_KEY_HEADER, settings.apiKey)
            limit?.let { parameter(LIMIT_QUERY, it) }
        }
        log("GET", API_CHATS, response)
        failOrThrow(response)
        response.body()
    }

    /** История сессии: `GET /api/chats/{id}`. Нет сессии — `chat_not_found`. */
    suspend fun history(settings: ServerSettings, chatId: String): ServiceChatHistory = call {
        val path = "$API_CHATS/$chatId"
        val response = client.get(settings.baseUrl + path) {
            header(API_KEY_HEADER, settings.apiKey)
        }
        log("GET", path, response)
        failOrThrow(response)
        response.body()
    }

    /** Один ход чата: `POST /api/chat`. Тело — [ServiceChatRequest] по контракту. */
    suspend fun send(settings: ServerSettings, chatId: String, message: String): ServiceChatResponse = call {
        val response = client.post(settings.baseUrl + API_CHAT) {
            contentType(ContentType.Application.Json)
            header(API_KEY_HEADER, settings.apiKey)
            setBody(
                ServiceChatRequest(
                    chatId = chatId,
                    message = message,
                    model = settings.model,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    contextWindow = settings.contextWindow
                )
            )
        }
        log("POST", API_CHAT, response)
        failOrThrow(response)
        response.body()
    }

    /** Удаление сессии: `DELETE /api/chats/{id}`. */
    suspend fun deleteChat(settings: ServerSettings, chatId: String) {
        call {
            val path = "$API_CHATS/$chatId"
            val response = client.delete(settings.baseUrl + path) {
                header(API_KEY_HEADER, settings.apiKey)
            }
            log("DELETE", path, response)
            failOrThrow(response)
        }
    }

    /**
     * Ход к сервису, у которого на выходе один вид отказа: [ServiceApiException].
     *
     * Сеть отказывает по-разному. Сервис, который ответил, разбирается по контракту
     * ([failOrThrow]): код в теле и `Retry-After`. Сервис, до которого запрос не дошёл,
     * на веб-таргете отвечает JS-ошибкой отвергнутого `fetch` — это [Throwable], но не
     * исключение Kotlin, и `catch (e: Exception)` у вызывающего её пропускал: страница
     * падала с «Uncaught runtime errors» вместо строки «Недоступен». Здесь у обоих
     * отказов одна форма, и вызывающему разбирать второй вид не нужно.
     *
     * Отмена корутины отказом не считается и пробрасывается дальше: иначе работа,
     * брошенная экраном, выглядела бы сбоем сервиса.
     *
     * @param block Ход к сервису целиком: запрос, разбор ответа и проверка кода.
     */
    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceApiException) {
            // Свой отказ проходит как есть: у него уже есть код контракта, а по коду
            // выбирается поведение (повторить, завести сессию заново, спросить ключ)
            throw e
        } catch (e: Throwable) {
            // Подробность — в лог, а человеку причина словами: текст движка («Fail to fetch»)
            // не подсказывает, что править, а адрес сервиса в логе и так виден строкой выше
            platformLog("service", "отказ соединения: ${e.message ?: "причина не названа"}")
            throw ServiceApiException(code = SERVICE_UNREACHABLE, message = "сервис не отвечает")
        }

    /**
     * Отказ сервиса как исключение: тело `{code, message, requestId}` и `Retry-After`.
     *
     * Тело разбираем бережно (`runCatching`): сервис мог ответить не по контракту
     * (прокси, 502), и тогда сообщением становится код статуса, а не потерянное
     * исключение разбора — показать «сервис ответил 502» честнее, чем промолчать.
     */
    private suspend fun failOrThrow(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull()
        val parsed = runCatching { response.body<ServiceErrorBody>() }.getOrNull()
        throw ServiceApiException(
            code = parsed?.code?.takeIf { it.isNotBlank() } ?: SERVICE_UNAVAILABLE,
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: "сервис ответил кодом ${response.status.value}",
            retryAfterSeconds = retryAfter
        )
    }

    /** Лог клиента: метод, путь и код ответа. Ни ключа, ни текста сообщения здесь нет намеренно. */
    private fun log(method: String, path: String, response: HttpResponse) {
        platformLog("service", "$method $path → ${response.status.value}")
    }

    private companion object {
        /** Заголовок ключа доступа сервиса. */
        const val API_KEY_HEADER = "X-API-Key"

        const val API_HEALTH = "/api/health"
        const val API_MODELS = "/api/models"
        const val API_CHAT = "/api/chat"
        const val API_CHATS = "/api/chats"

        /** Параметр запроса с размером списка чатов: `GET /api/chats?limit=…` (§1.1). */
        const val LIMIT_QUERY = "limit"

        /** Код для отказа, у которого своего кода не нашлось: дальше решает состояние соединения. */
        const val SERVICE_UNAVAILABLE = "service_unavailable"

        /**
         * Код отказа «запрос не дошёл»: сервис не ответил вовсе — ни кодом, ни телом.
         *
         * Свой код, а не [SERVICE_UNAVAILABLE]: тот приходит от ответившего сервиса (503),
         * и по нему видно, что служба жива, но не может ответить. Здесь её не слышно.
         */
        const val SERVICE_UNREACHABLE = "service_unreachable"
    }
}
