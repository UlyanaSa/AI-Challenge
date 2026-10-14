package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatMode
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.InMemorySettingsStorage
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.ServerConnectionState
import com.osvin.aichallenge.data.ServerSettings
import com.osvin.aichallenge.data.ServiceChatRequest
import com.osvin.aichallenge.data.SettingsStorage
import com.osvin.aichallenge.repository.ChatRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ответ подставного сервиса: тело и, если нужно, код и заголовки.
 *
 * Отдельным объектом, а не строкой: проверяются не только успехи — 401 и 429 с
 * `Retry-After` задают исход проверки и текст в ленте, а код без заголовка их не задаст.
 */
private data class Stub(
    val body: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val headers: List<Pair<String, List<String>>> = emptyList()
)

/**
 * Приватный сервис `/api/…` со стороны клиента: проверка подключения и ход в сервисный чат.
 *
 * Сервис подставной ([MockEngine]): тест смотрит, что именно уходит на провод,
 * и работает без сети. Проверяется не «клиент не упал», а контракт: состояния проверки
 * выбираются по коду ответа, ключ уходит только с запросами сервиса, тело хода — по форме
 * из контракта, а `Retry-After` доходит до человека.
 *
 * День 31 добавил к этому чаты сервиса: список (`GET /api/chats`) читается только в режиме
 * «Сервис», чат открывается по идентификатору вместе со сводкой прошлого чата, а ссылка
 * на общий чат разбирается из адреса — и всё это без сети, на том же [MockEngine].
 */
class ServiceClientTest {

    /**
     * Успех: `GET /api/health` отвечает без ключа, `GET /api/models` — с ключом,
     * и выбранная модель есть в списке — состояние [ServerConnectionState.CONNECTED].
     */
    @Test
    fun connectedWhenHealthAndSelectedModelAreOk() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { path ->
            when (path) {
                "/api/health" -> Stub(HEALTH_OK)
                "/api/models" -> Stub(MODELS_WITH_QWEN)
                else -> error("неожиданный путь: $path")
            }
        }
        val report = repository.checkServiceConnection(settings())

        assertEquals(ServerConnectionState.CONNECTED, report.state)
        assertEquals(listOf("qwen3:1.7b"), report.models)
        // Ключ — только у запроса моделей: проверка живости идёт до его ввода, и унесённый
        // туда ключ был бы передан поверхности, которой он не нужен
        assertEquals(null, requests.single { it.url.encodedPath == "/api/health" }.headers["X-API-Key"])
        assertEquals("secret", requests.single { it.url.encodedPath == "/api/models" }.headers["X-API-Key"])
    }

    /**
     * Ключ не принят: живость в порядке, но список моделей отвечает 401 —
     * состояние [ServerConnectionState.AUTH_ERROR], а не «недоступен».
     */
    @Test
    fun authErrorWhenKeyIsRejected() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/health" -> Stub(HEALTH_OK)
                "/api/models" -> Stub(
                    """{"code":"unauthorized","message":"Неверный ключ доступа","requestId":"deadbeef"}""",
                    HttpStatusCode.Unauthorized
                )
                else -> error("неожиданный путь: $path")
            }
        }
        val report = repository.checkServiceConnection(settings())

        assertEquals(ServerConnectionState.AUTH_ERROR, report.state)
        assertTrue(report.message.contains("Ошибка авторизации"), report.message)
    }

    /**
     * Сервис молчит: запрос не доходит — состояние [ServerConnectionState.UNAVAILABLE],
     * и причина названа, а не спрятана за одним словом.
     */
    @Test
    fun unavailableWhenServiceIsUnreachable() = runBlocking {
        val repository = ChatRepository(BASE_URL, InMemoryChatStore(), testClient(MockEngine { error("нет связи") }))
        val report = repository.checkServiceConnection(settings())

        assertEquals(ServerConnectionState.UNAVAILABLE, report.state)
        assertTrue(report.message.startsWith("Недоступен"), report.message)
    }

    /**
     * Сервис ответил, но выбранной модели у него нет: состояние
     * [ServerConnectionState.MODEL_MISSING], и сообщение называет эту модель —
     * иначе человек не поймёт, какую именно не нашёл сервис.
     */
    @Test
    fun modelMissingWhenListLacksSelectedModel() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/health" -> Stub(HEALTH_OK)
                "/api/models" -> Stub("""{"models":[{"name":"llama3:8b"}]}""")
                else -> error("неожиданный путь: $path")
            }
        }
        val report = repository.checkServiceConnection(settings())

        assertEquals(ServerConnectionState.MODEL_MISSING, report.state)
        assertTrue(report.message.contains("qwen3:1.7b"), report.message)
        assertEquals(listOf("llama3:8b"), report.models)
    }

    /**
     * Ход в сервисном чате: `POST /api/chat` уходит с `X-API-Key` и телом по контракту,
     * а ответ сервиса попадает в сервисную ленту рядом с вопросом.
     */
    @Test
    fun serviceMessageIsSentToApiChatWithKeyAndContractBody() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { path ->
            when (path) {
                "/api/chats" -> Stub(
                    """{"chatId":"chat-1","title":"Новый чат","createdAt":1760000000000}""",
                    HttpStatusCode.Created
                )
                "/api/chat" -> Stub(
                    """{"requestId":"a1b2c3d4","chatId":"chat-1","reply":"Ответ сервиса","model":"qwen3:1.7b",
                       "elapsedMs":4957,"promptTokens":43,"completionTokens":570,"totalTokens":613,
                       "trimmedMessages":0,"status":"ok"}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings(temperature = 0.3, maxTokens = 256, contextWindow = 2048))

        assertEquals("chat-1", repository.createServiceChat())
        repository.sendServiceMessage("Привет, сервис")

        val sent = requests.single { it.url.encodedPath == "/api/chat" }
        assertEquals("secret", sent.headers["X-API-Key"])
        val body = JSON.decodeFromString<ServiceChatRequest>((sent.body as TextContent).text)
        assertEquals("chat-1", body.chatId)
        assertEquals("Привет, сервис", body.message)
        assertEquals("qwen3:1.7b", body.model)
        assertEquals(0.3, body.temperature)
        assertEquals(256, body.maxTokens)
        assertEquals(2048, body.contextWindow)

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            repository.serviceMessages.value.map { it.role }
        )
        assertEquals(
            listOf("Привет, сервис", "Ответ сервиса"),
            repository.serviceMessages.value.map { it.content }
        )
    }

    /**
     * Сессию на сервере удалили: на ход приходит 404 `chat_not_found` — клиент заводит
     * сессию заново и повторяет попытку **один раз**. Больше одного повторения быть
     * не должно: иначе мёртвая сессия крутила бы запросы без конца.
     */
    @Test
    fun retriesOnceWhenSessionIsGone() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        var chatsCreated = 0
        var chatAttempts = 0
        val repository = repository(requests) { path ->
            when (path) {
                "/api/chats" -> {
                    chatsCreated += 1
                    Stub("""{"chatId":"chat-$chatsCreated","title":"Новый чат","createdAt":1}""", HttpStatusCode.Created)
                }
                "/api/chat" -> {
                    chatAttempts += 1
                    if (chatAttempts == 1) {
                        Stub(
                            """{"code":"chat_not_found","message":"Сессия не найдена","requestId":"aa11bb22"}""",
                            HttpStatusCode.NotFound
                        )
                    } else {
                        Stub("""{"requestId":"cc33dd44","chatId":"chat-2","reply":"Ответ новой сессии","model":"qwen3:1.7b","status":"ok"}""")
                    }
                }
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())

        repository.sendServiceMessage("Вопрос в удалённую сессию")

        assertEquals(2, chatsCreated)
        assertEquals(2, chatAttempts)
        val sent = requests.filter { it.url.encodedPath == "/api/chat" }
        assertEquals(listOf("chat-1", "chat-2"), sent.map { JSON.decodeFromString<ServiceChatRequest>((it.body as TextContent).text).chatId })
        assertEquals(
            listOf("Вопрос в удалённую сессию", "Ответ новой сессии"),
            repository.serviceMessages.value.map { it.content }
        )
    }

    /**
     * Частоту превысили: 429 с `Retry-After` — человеку показывается причина сервиса
     * вместе со сроком, через который можно повторить, а не один код статуса.
     */
    @Test
    fun rateLimitMessageCarriesRetryAfter() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/chats" -> Stub("""{"chatId":"chat-1","title":"Новый чат","createdAt":1}""", HttpStatusCode.Created)
                "/api/chat" -> Stub(
                    """{"code":"rate_limited","message":"Превышен лимит запросов: 10 в минуту","requestId":"abc12345"}""",
                    HttpStatusCode.TooManyRequests,
                    listOf(HttpHeaders.RetryAfter to listOf("42"))
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())
        repository.createServiceChat()

        repository.sendServiceMessage("Ещё вопрос")

        val state = repository.state.value
        assertIs<ChatUiState.Error>(state)
        assertTrue(state.message.contains("Превышен лимит запросов"), state.message)
        assertTrue(state.message.contains("42"), state.message)
    }

    /**
     * Проверяются переданные настройки, а не сохранённые: шторка показывает черновик,
     * и проверка сохранённых вместо него отвечала бы «ключ не принят» на верный ключ,
     * который человек только что набрал и ещё не сохранил.
     */
    @Test
    fun checkUsesPassedSettingsNotSavedOnes() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { path ->
            when (path) {
                "/api/health" -> Stub(HEALTH_OK)
                "/api/models" -> Stub(MODELS_WITH_QWEN)
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings(baseUrl = "http://saved:8080", apiKey = "saved-key"))

        val report = repository.checkServiceConnection(settings(baseUrl = "http://typed:9090", apiKey = "typed-key"))

        assertEquals(ServerConnectionState.CONNECTED, report.state)
        val models = requests.single { it.url.encodedPath == "/api/models" }
        assertEquals("typed-key", models.headers["X-API-Key"])
        assertEquals("typed:9090", models.url.host + ":" + models.url.port)
    }

    /**
     * Список чатов сервиса читается только в режиме «Сервис», и в панель приходит именно то,
     * что отдал сервис: порядок, число сообщений и сводка элемента.
     *
     * В локальном режиме запроса нет вовсе: панель показывает локальные чаты, и ключ
     * не должен уезжать туда, где сервисом не пользуются.
     */
    @Test
    fun serviceChatsAreReadOnlyInServiceMode() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { path ->
            when (path) {
                "/api/chats" -> Stub(
                    """{"chats":[
                         {"chatId":"b2","title":"Поездка","createdAt":2,"updatedAt":20,"messages":14,
                          "summary":"Обсуждали бюджет поездки: 120 000 ₽"},
                         {"chatId":"a1","title":"Новый чат","createdAt":1,"updatedAt":10,"messages":0}
                       ],"count":2}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }

        // Локальный режим по умолчанию: список сервиса не читается, и запроса не происходит
        repository.loadServiceChats()
        assertTrue(requests.isEmpty(), "в локальном режиме сервис не опрашивается")
        assertTrue(repository.serviceChats.value.chats.isEmpty())
        assertTrue(!repository.serviceChatsLoaded.value)

        repository.updateServerSettings(settings())
        repository.loadServiceChats()

        // Порядок сервиса сохраняется, а не пересортировывается клиентом: список приходит
        // свежими сверху, и вторая копия сортировки на клиенте разошлась бы с серверной
        assertEquals(listOf("b2", "a1"), repository.serviceChats.value.chats.map { it.chatId })
        assertEquals(2, repository.serviceChats.value.count)
        assertEquals(listOf(14, 0), repository.serviceChats.value.chats.map { it.messages })
        assertEquals(
            "Обсуждали бюджет поездки: 120 000 ₽",
            repository.serviceChats.value.chats.first().summary
        )
        assertEquals("secret", requests.single().headers["X-API-Key"])
        assertTrue(repository.serviceChatsLoaded.value)
    }

    /**
     * Открытие чата по идентификатору: история приходит с сервиса, сводка прошлого чата
     * видна ленте, а ход уходит в этот же чат.
     *
     * Сводка в ленту сообщением не попадает: её подставил сервис в запрос к модели,
     * и в диалоге репликой она не была (§5).
     */
    @Test
    fun openServiceChatLoadsHistoryAndAddressesTurnToIt() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { path ->
            when (path) {
                "/api/chats/9c1e" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","createdAt":1,"updatedAt":2,
                        "messages":[{"role":"user","content":"Сколько стоит?","createdAt":3},
                                    {"role":"assistant","content":"120 000 ₽","createdAt":4}],
                        "previousChatId":"f2aa","summary":"Обсуждали бюджет поездки","summaryMessages":14}"""
                )
                "/api/chat" -> Stub(
                    """{"requestId":"aa11bb22","chatId":"9c1e","reply":"Готово","model":"qwen3:1.7b","status":"ok"}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())

        repository.openServiceChat("9c1e")

        assertEquals(
            listOf("Сколько стоит?", "120 000 ₽"),
            repository.serviceMessages.value.map { it.content }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            repository.serviceMessages.value.map { it.role }
        )
        assertEquals("Поездка", repository.serviceChatTitle.value)
        assertEquals("Обсуждали бюджет поездки", repository.serviceSummary.value)
        assertTrue(
            repository.serviceMessages.value.none { it.content.contains("бюджет") },
            "сводка не должна стать сообщением диалога"
        )

        repository.sendServiceMessage("Ещё вопрос")

        val sent = requests.filter { it.url.encodedPath == "/api/chat" }
        assertEquals(
            listOf("9c1e"),
            sent.map { JSON.decodeFromString<ServiceChatRequest>((it.body as TextContent).text).chatId }
        )
        assertEquals("secret", sent.single().headers["X-API-Key"])
    }

    /**
     * «Обновить» перечитывает список и историю открытого чата: написанное другим клиентом
     * приходит только так — о чужих записях в общем чате сервис сам не сообщает.
     */
    @Test
    fun refreshReloadsListAndOpenChatHistory() = runBlocking {
        var historyCalls = 0
        val repository = repository { path ->
            when (path) {
                "/api/chats" -> Stub("""{"chats":[],"count":0}""")
                "/api/chats/9c1e" -> {
                    historyCalls += 1
                    if (historyCalls == 1) {
                        Stub(
                            """{"chatId":"9c1e","title":"Общий чат",
                                "messages":[{"role":"user","content":"Вопрос","createdAt":1}]}"""
                        )
                    } else {
                        Stub(
                            """{"chatId":"9c1e","title":"Общий чат","messages":[
                                 {"role":"user","content":"Вопрос","createdAt":1},
                                 {"role":"assistant","content":"Ответ","createdAt":2},
                                 {"role":"user","content":"Запись другого клиента","createdAt":3}]}"""
                        )
                    }
                }
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())
        repository.openServiceChat("9c1e")
        assertEquals(listOf("Вопрос"), repository.serviceMessages.value.map { it.content })

        repository.refreshService()

        assertEquals(
            listOf("Вопрос", "Ответ", "Запись другого клиента"),
            repository.serviceMessages.value.map { it.content }
        )
    }

    /**
     * Сводка собрана: её текст из ответа сервиса виден ленте, а причины отказа нет.
     */
    @Test
    fun createdChatCarriesSummaryToTheFeed() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/chats" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","createdAt":1,"previousChatId":"f2aa",
                        "summary":"Обсуждали бюджет поездки: 120 000 ₽, срок — до 20 мая",
                        "summaryMessages":14,"summaryError":null}""",
                    HttpStatusCode.Created
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())

        assertEquals("9c1e", repository.createServiceChat())

        assertEquals(
            "Обсуждали бюджет поездки: 120 000 ₽, срок — до 20 мая",
            repository.serviceSummary.value
        )
        assertEquals(null, repository.serviceSummaryError.value)
    }

    /**
     * Сводку построить не удалось: чат всё равно заведён, а причина показана рядом —
     * «сводки нет» без причины не отличить от «прошлого чата не было».
     */
    @Test
    fun createdChatKeepsSummaryFailureReason() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/chats" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","createdAt":1,"previousChatId":null,
                        "summary":null,"summaryMessages":0,"summaryError":"модель недоступна"}""",
                    HttpStatusCode.Created
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())

        assertEquals("9c1e", repository.createServiceChat())

        assertEquals(null, repository.serviceSummary.value)
        assertEquals("модель недоступна", repository.serviceSummaryError.value)
    }

    /**
     * Чат из адреса: `?chat=<id>` — по такой ссылке второй клиент открывает тот же чат.
     * Проверяется разбор адреса, без сети: он одинаков и для ссылки, и для адреса страницы,
     * а пустой `?chat=` чатом не считается — иначе открывался бы чат с пустым идентификатором.
     */
    @Test
    fun chatIdIsParsedFromAddress() {
        assertEquals("9c1e", chatIdFromUrl("https://app.test/?chat=9c1e"))
        assertEquals("9c1e", chatIdFromUrl("http://localhost:8080/index.html?chat=9c1e"))
        assertEquals("9c1e", chatIdFromUrl("?chat=9c1e"))
        assertEquals("9c1e", chatIdFromUrl("?screen=chats&chat=9c1e"))
        assertEquals(null, chatIdFromUrl("?chat="))
        assertEquals(null, chatIdFromUrl("https://app.test/"))
        assertEquals(null, chatIdFromUrl(null))
    }

    /**
     * Ссылка на чат: адрес страницы остаётся рабочим, а слэш перед `?chat=` — один.
     *
     * Склейка «в лоб» давала на живом приложении `http://localhost:8081//?chat=…`, и это
     * видел человек, которому ссылку отдают: он открывает её вторым клиентом. Проверяются
     * оба конца — вид адреса (каталог слэшем, файл без) и то, что собранную ссылку читает
     * тот же разбор `?chat=`, каким её открывает приложение.
     */
    @Test
    fun chatLinkKeepsTheAddressUsable() {
        val root = "http://localhost:8081/"
        val directory = "http://localhost:8081/app/"
        val file = "http://localhost:8081/app/index.html"

        assertEquals("http://localhost:8081/?chat=9c1e", chatLinkTo(root, "9c1e"))
        assertEquals("http://localhost:8081/app/?chat=9c1e", chatLinkTo(directory, "9c1e"))
        assertEquals("http://localhost:8081/app/?chat=9c1e", chatLinkTo("http://localhost:8081/app", "9c1e"))
        assertEquals("http://localhost:8081/app/index.html?chat=9c1e", chatLinkTo(file, "9c1e"))
        assertEquals("9c1e", chatIdFromUrl(chatLinkTo(file, "9c1e")))
    }

    /**
     * Где у платформы нет адреса своей страницы, ссылки на чат не существует.
     *
     * Android-хост-тесты идут на андроидных actual'ах: `appAddress` там null, буфера обмена
     * нет. Это ровно та платформа, где «Поделиться» показывать нечем — кнопка отдала бы
     * голый идентификатор, который не скопировать и не открыть ссылкой. Проверка держит
     * согласие двух концов — признака для шапки и самой ссылки: кнопки не может быть там,
     * где [ChatRepository.shareServiceChat] возвращает null при открытом чате.
     */
    @Test
    fun shareLinkNeedsAnAppAddressAndIsAbsentWithoutOne() = runBlocking {
        val repository = repository { path ->
            when (path) {
                "/api/chats/9c1e" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","messages":[]}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }

        assertFalse(repository.canShareServiceChat, "на этой платформе нет адреса страницы")
        assertNull(repository.shareServiceChat(), "без открытого чата делиться нечем")

        repository.openSharedServiceChat("9c1e")

        assertNull(repository.shareServiceChat(), "адреса нет — ссылки нет, даже когда чат открыт")
    }

    /**
     * Отказ сети, пришедший не исключением Kotlin, — тоже отказ.
     *
     * На веб-таргете недоступный сервис отвечает JS-ошибкой отвергнутого `fetch`: это
     * [Throwable], но не [Exception], и `catch (e: Exception)` её пропускал — страница
     * падала с «Uncaught runtime errors» вместо строки «Недоступен». Проверка держит
     * именно эту форму отказа: [Throwable] без [Exception], как на живом браузере.
     */
    @Test
    fun unreachableServiceIsReportedWhenFailureIsNotAKotlinException() = runBlocking {
        val repository = ChatRepository(
            BASE_URL,
            InMemoryChatStore(),
            testClient(MockEngine { throw Throwable("Fail to fetch") })
        )

        val report = repository.checkServiceConnection(settings())

        assertEquals(ServerConnectionState.UNAVAILABLE, report.state)
        assertTrue(report.message.startsWith("Недоступен"), report.message)
    }

    /**
     * Ссылка переводит режим в «Сервис» и оставляет это на устройстве: после обновления
     * страницы тот же разговор должен открыться снова, а не потеряться вместе с режимом.
     *
     * Проверяются оба конца: режим поднимается из хранилища, и идентификатор чата из ссылки
     * остаётся там же — иначе клиент вернулся бы в локальный режим, ровно как в отказе,
     * ради которого настройки и стали хранить.
     */
    @Test
    fun sharedLinkKeepsServiceModeAndChatAcrossRestart() = runBlocking {
        val storage = InMemorySettingsStorage()
        val repository = repository(settingsStorage = storage) { path ->
            when (path) {
                "/api/chats/9c1e" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","messages":[{"role":"user","content":"Сколько стоит?","createdAt":1}]}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }

        repository.openSharedServiceChat("9c1e")

        val afterRestart = ChatRepository(BASE_URL, InMemoryChatStore(), settingsStorage = storage)
        assertEquals(ChatMode.SERVICE, afterRestart.serverSettings.value.mode)
        assertEquals("9c1e", afterRestart.rememberedServiceChatId())
    }

    /**
     * Настройки сервиса переживают перезапуск приложения: второй репозиторий над тем же
     * хранилищем поднимает и режим, и адрес, и ключ, и рамки запроса.
     *
     * Это починка бага «при обновлении страницы старые чаты не подгружаются»: без настроек
     * клиент возвращался в локальный режим, и о чатах сервиса — живых в его SQLite —
     * не спрашивал вовсе. Проверяется целиком, а не отдельным полем: поднятая половина
     * настроек увела бы запрос не туда, куда человек настраивал.
     */
    @Test
    fun settingsSurviveRestartOverTheSameStorage() {
        val storage = InMemorySettingsStorage()
        val saved = settings(
            baseUrl = "http://service.local:8090",
            apiKey = "ключ-сервиса",
            model = "qwen3:4b",
            temperature = 0.2,
            maxTokens = 128,
            contextWindow = 2048
        )
        ChatRepository(BASE_URL, InMemoryChatStore(), settingsStorage = storage)
            .updateServerSettings(saved)

        val afterRestart = ChatRepository(BASE_URL, InMemoryChatStore(), settingsStorage = storage)

        assertEquals(saved, afterRestart.serverSettings.value)
    }

    /**
     * Последний открытый сервисный чат помнится: после перезапуска приложение возвращается
     * в тот же разговор, а не в пустую ленту.
     *
     * Забывается он вместе с сессией: при смене адреса или ключа чат принадлежит уже
     * другому сервису, и открывать его на новом адресе значило бы искать чужое.
     */
    @Test
    fun lastServiceChatIsRememberedAndForgottenWithTheSession() = runBlocking {
        val storage = InMemorySettingsStorage()
        val repository = repository(settingsStorage = storage) { path ->
            when (path) {
                "/api/chats/9c1e" -> Stub(
                    """{"chatId":"9c1e","title":"Поездка","messages":[{"role":"user","content":"Сколько стоит?","createdAt":1}]}"""
                )
                else -> error("неожиданный путь: $path")
            }
        }
        repository.updateServerSettings(settings())
        repository.openServiceChat("9c1e")

        assertEquals(
            "9c1e",
            ChatRepository(BASE_URL, InMemoryChatStore(), settingsStorage = storage)
                .rememberedServiceChatId(),
            "чат должен подниматься после перезапуска приложения"
        )

        repository.updateServerSettings(settings(baseUrl = "http://other.local:8090"))

        assertEquals(
            null,
            ChatRepository(BASE_URL, InMemoryChatStore(), settingsStorage = storage)
                .rememberedServiceChatId(),
            "чат прежнего сервиса не должен открываться на новом адресе"
        )
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        const val HEALTH_OK =
            """{"status":"ok","provider":"local","model":"qwen3:1.7b","ollama":"ok","modelLoaded":true,"uptimeMs":123}"""

        const val MODELS_WITH_QWEN =
            """{"models":[{"name":"qwen3:1.7b","sizeBytes":1461000000,"quantization":"Q4_K_M","parameterSize":"1.7B"}]}"""

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Настройки в режиме сервиса: подставляем всё, чем подписывается ход. */
        fun settings(
            baseUrl: String = BASE_URL,
            apiKey: String = "secret",
            model: String = "qwen3:1.7b",
            temperature: Double = 0.7,
            maxTokens: Int = 512,
            contextWindow: Int = 4096
        ) = ServerSettings(
            mode = ChatMode.SERVICE,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            contextWindow = contextWindow
        )

        /**
         * Репозиторий над подставным сервисом: ответ выбирается по пути запроса,
         * а все запросы складываются в [requests] — по ним и проверяется провод.
         *
         * [settingsStorage] подставляется, когда проверяется память настроек между
         * запусками: без него репозиторий держит их в памяти процесса, и «перезапуска»
         * в проверке не получится.
         */
        fun repository(
            requests: MutableList<HttpRequestData> = mutableListOf(),
            settingsStorage: SettingsStorage = InMemorySettingsStorage(),
            response: (String) -> Stub
        ): ChatRepository = ChatRepository(
            BASE_URL,
            InMemoryChatStore(),
            testClient(MockEngine { request ->
                requests += request
                respondStub(response(request.url.encodedPath))
            }),
            settingsStorage = settingsStorage
        )

        fun testClient(engine: MockEngine) = HttpClient(engine) {
            install(ContentNegotiation) { json(JSON) }
        }

        /** Ответ подставного сервиса: тело как есть, код и заголовки — из [Stub]. */
        fun MockRequestHandleScope.respondStub(stub: Stub) = respond(
            content = stub.body,
            status = stub.status,
            headers = headersOf(
                *(
                    listOf(HttpHeaders.ContentType to listOf("application/json")) + stub.headers
                ).toTypedArray()
            )
        )
    }
}
