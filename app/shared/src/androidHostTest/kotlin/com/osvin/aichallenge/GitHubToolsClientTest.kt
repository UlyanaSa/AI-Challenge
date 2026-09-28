package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatRequest
import com.osvin.aichallenge.data.ChatStore
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.repository.ChatRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub на клиенте: снимок подключения, отказ на подключение и вызов инструмента
 * в ленте чата.
 *
 * Сервер подставной ([MockEngine]): тест смотрит, что видно на экране и что уходит
 * на провод, и работает без сети и без настоящего GitHub. Ветка с инструментами
 * проверяется по той же дороге, что и обычный вопрос: клиент сам GitHub не знает,
 * поэтому всё, что он может, — показать снимок и передать вызов.
 */
class GitHubToolsClientTest {

    /**
     * Подключение отдаёт то, что ответил сервер: имя и версию MCP-сервера и объявленные
     * инструменты вместе с их аргументами. Клиент этот список не выдумывает — второго
     * источника инструментов у него нет.
     */
    @Test
    fun connectingGivesToolsFromServer() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/github/connect" -> CONNECTED to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        // Токена нет: сервер возьмёт GITHUB_TOKEN из своего окружения
        repository.connectGitHub(null)

        val connection = assertNotNull(repository.github.value, "снимок подключения не пришёл")
        assertTrue(connection.connected, "подключение не отмечено как выполненное")
        assertEquals("ai-challenge-github", connection.server)
        assertEquals("1.0.0", connection.version)

        val tool = connection.tools.single()
        assertEquals("get_repositories", tool.name)
        assertEquals("Репозитории владельца токена", tool.description)
        val argument = tool.arguments.single()
        assertEquals("visibility", argument.name)
        assertEquals("какие репозитории вернуть", argument.description)
        assertEquals("string", argument.type)
        assertFalse(argument.required, "необязательный аргумент показан обязательным")
        assertEquals(listOf("all", "public", "private"), argument.values)
        assertNull(repository.githubError.value, "успешное подключение оставило ошибку")
    }

    /**
     * Отказ подключения виден строкой ошибки, а снимок остаётся прежним: состояние
     * подключения держит сервер, и стереть снимок значило бы показать «отключено» там,
     * где подключение, возможно, живо.
     */
    @Test
    fun refusedConnectionKeepsSnapshotAndShowsReason() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/github" -> CONNECTED to HttpStatusCode.OK
                "/v1/github/connect" -> """{"success":false,"error":"GITHUB_TOKEN не задан"}""" to
                    HttpStatusCode.BadGateway
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        repository.loadGitHub()
        val before = assertNotNull(repository.github.value)
        assertTrue(before.connected)

        repository.connectGitHub("неверный-токен")

        assertEquals("GITHUB_TOKEN не задан", repository.githubError.value)
        assertEquals(before, repository.github.value, "отказ подключения подменил снимок")
    }

    /**
     * Вызов инструмента остаётся в ленте служебной записью: роль `TOOL`, команда
     * с аргументами и результат. Текста у записи нет — карточку вызова несёт
     * `ChatMessage.tools`, — а хранится она вместе с сообщениями чата.
     */
    @Test
    fun toolCallAppearsInFeedAsToolRecord() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val store = InMemoryChatStore()
        val repository = newRepository(requests, store) { request ->
            when (request.url.encodedPath) {
                "/v1/github/call" -> CALL_RESPONSE to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }
        val chat = repository.createChat()

        repository.callGitHubTool("get_repositories", """{"visibility":"private"}""")

        val record = repository.messages.value.last()
        assertEquals(MessageRole.TOOL, record.role)
        assertEquals("", record.content, "у служебной записи появился текст")
        val call = record.tools.single()
        assertEquals("get_repositories", call.name)
        assertEquals("""{"visibility":"private"}""", call.arguments)
        assertEquals("2 приватных репозитория", call.result)
        assertFalse(call.failed)
        assertEquals("get_repositories {\"visibility\":\"private\"}", call.command())

        // Запись сохранена, а не только показана: после перезапуска видно, откуда взялись данные
        assertEquals(MessageRole.TOOL, store.messages(chat.id).last().role)
    }

    /**
     * Записи о вызовах инструментов не уезжают модели: они остаются в ленте для человека,
     * а история диалога уходит без них. Иначе вызов выглядел бы для модели уже сделанным,
     * и она повторила бы его или сослалась на данные, которых в переписке не было.
     */
    @Test
    fun toolRecordsDoNotGoToModelHistory() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val store = InMemoryChatStore()
        val repository = newRepository(requests, store) { request ->
            when (request.url.encodedPath) {
                "/v1/github/call" -> CALL_RESPONSE to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }
        val chat = repository.createChat()

        repository.sendMessage("Покажи репозитории")
        repository.callGitHubTool("get_repositories", """{"visibility":"all"}""")
        repository.sendMessage("И что там")

        val sent = lastChatRequest(requests)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            sent.history?.map { it.role },
            "в историю для модели попала не только переписка"
        )
        assertEquals(listOf("Покажи репозитории", REPLY), sent.history?.map { it.content })

        // Из ленты запись при этом не пропала: она осталась в хранилище чата
        assertTrue(
            store.messages(chat.id).any { it.role == MessageRole.TOOL },
            "запись о вызове пропала из хранилища"
        )
    }

    /**
     * Вызовы, которые сделала сама модель, остаются при её сообщении: под ответом видно,
     * что агент вызывал и чем это кончилось, — иначе данные в ответе выглядели бы
     * взятыми из ниоткуда.
     */
    @Test
    fun modelToolCallsAreKeptWithAssistantReply() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/chat/completions" -> TOOL_ANSWER to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }
        repository.createChat()

        repository.sendMessage("Покажи приватные репозитории")

        val assistant = repository.messages.value.last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(REPLY, assistant.content)
        val call = assistant.tools.single()
        assertEquals("get_repositories", call.name)
        assertEquals("""{"visibility":"private"}""", call.arguments)
        assertEquals("2 приватных репозитория", call.result)
        assertFalse(call.failed)
    }

    /**
     * Снимок GitHub обновляется после ответа модели: сервер инструментов мог умереть
     * между вопросами, и без нового чтения кнопка показывала бы живое подключение,
     * хотя вызовы уже не работают.
     */
    @Test
    fun sendingMessageRefreshesGitHubSnapshot() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        var toolsAlive = true
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/github" -> (if (toolsAlive) CONNECTED else DISCONNECTED) to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        repository.loadGitHub()
        assertTrue(repository.github.value?.connected == true)

        // Сервер инструментов умер между вопросами
        toolsAlive = false
        repository.createChat()
        repository.sendMessage("Обычный вопрос")

        assertEquals(
            false, repository.github.value?.connected,
            "снимок GitHub не обновился после ответа модели"
        )
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Ответ подставного сервера: текст, который попадает в историю чата. */
        const val REPLY = "Ответ агента"

        /** Обычная переписка: вызовов инструментов в этом ответе нет. */
        val CHAT_RESPONSE = """{"success":true,"reply":"$REPLY"}"""

        /** Подключение выполнено: MCP-сервер назвался и объявил инструмент с аргументом. */
        val CONNECTED = """
            {"connected":true,"server":"ai-challenge-github","version":"1.0.0",
             "tools":[{"name":"get_repositories","description":"Репозитории владельца токена",
                       "arguments":[{"name":"visibility","description":"какие репозитории вернуть",
                                     "type":"string","required":false,
                                     "values":["all","public","private"]}]}]}
        """.trimIndent()

        /** Подключения нет: сервер инструментов не поднят. */
        const val DISCONNECTED = """{"connected":false,"tools":[]}"""

        /** Ответ инструмента: данные, которые модель получила от GitHub. */
        const val CALL_RESPONSE =
            """{"name":"get_repositories","result":"2 приватных репозитория","failed":false}"""

        /** Ответ модели с вызовом инструмента: вызов остаётся при её сообщении. */
        val TOOL_ANSWER = """
            {"success":true,"reply":"$REPLY",
             "tokens":{"tools":{"calls":[{"name":"get_repositories",
                                          "arguments":"{\"visibility\":\"private\"}",
                                          "result":"2 приватных репозитория","failed":false}],
                                "rounds":1,"tokens":100}}}
        """.trimIndent()

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun testClient(
            requests: MutableList<HttpRequestData>,
            answer: (HttpRequestData) -> Pair<String, HttpStatusCode> = { CHAT_RESPONSE to HttpStatusCode.OK }
        ) = HttpClient(
            MockEngine { request ->
                requests += request
                val (body, status) = answer(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) {
            install(ContentNegotiation) { json(JSON) }
        }

        /** Репозиторий над подставным сервером и хранилищем в памяти. */
        fun newRepository(
            requests: MutableList<HttpRequestData>,
            store: ChatStore = InMemoryChatStore(),
            answer: (HttpRequestData) -> Pair<String, HttpStatusCode>
        ) = ChatRepository(BASE_URL, store, testClient(requests, answer))

        /** Последний запрос к модели: проверка связи и удаление сессии в него не попадают. */
        fun lastChatRequest(requests: List<HttpRequestData>): ChatRequest = JSON.decodeFromString(
            (requests.last { it.url.encodedPath == "/v1/chat/completions" }.body as TextContent).text
        )
    }
}
