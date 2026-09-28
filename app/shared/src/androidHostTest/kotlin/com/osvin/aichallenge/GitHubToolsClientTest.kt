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
 * GitHub на клиенте: снимок подключения с доступом, отказ на подключение и вызов
 * инструмента в ленте чата.
 *
 * Сервер подставной ([MockEngine]): тест смотрит, что видно на экране и что уходит
 * на провод, и работает без сети и без настоящего GitHub. Токена в этих сценариях нет
 * вовсе: доступ к GitHub сервер ищет сам на своей машине, а клиент получает готовый
 * снимок — кто доступен, с какими правами и откуда доступ взят. Ветка с инструментами
 * проверяется по той же дороге, что и обычный вопрос: клиент сам GitHub не знает, поэтому
 * всё, что он может, — показать снимок и передать вызов.
 */
class GitHubToolsClientTest {

    /**
     * Подключение отдаёт то, что ответил сервер: доступ к GitHub (кто, с какими правами
     * и откуда он взят) и объявленные инструменты вместе с их аргументами. Клиент ни
     * доступа, ни инструментов не выдумывает — второго источника у него нет, — и токена
     * в запросе не отправляет: доступ лежит на машине сервера, и клиенту его знать нечем.
     */
    @Test
    fun connectingGivesAccessAndToolsFromServer() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/github/connect" -> CONNECTED to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        repository.connectGitHub()

        // Тела у подключения нет: клиенту нечего отправлять — ни токена, ни имени доступа
        val body = requests.last { it.url.encodedPath == "/v1/github/connect" }.body
        assertFalse(
            body is TextContent && body.text.isNotEmpty(),
            "подключение ушло с телом: клиент отправил серверу то, чего у него нет"
        )

        val connection = assertNotNull(repository.github.value, "снимок подключения не пришёл")
        assertTrue(connection.connected, "подключение не отмечено как выполненное")
        assertTrue(connection.authorized, "найденный доступ не доехал до снимка")
        assertEquals("ulanocka", connection.login)
        assertEquals(listOf("repo", "read:user"), connection.scopes)
        assertTrue(connection.scopesReported, "права пришли, но помечены несообщёнными")
        assertEquals("файл ~/.config/ai-challenge/github.token", connection.source)
        assertNull(connection.hint, "при найденном доступе осталась подсказка, что делать")
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
     * Отсутствие доступа — не отказ подключения: сессия поднимается, и сервер отвечает
     * снимком с `authorized = false` и подсказкой. Строкой ошибки это не становится
     * намеренно: «доступа нет» — состояние, которое человек исправляет на машине,
     * а не сбой запроса, и показать его надо словами, а не красной строкой.
     */
    @Test
    fun missingAccessComesAsSnapshotWithHint() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/github/connect" -> NO_ACCESS to HttpStatusCode.OK
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        repository.connectGitHub()

        val connection = assertNotNull(repository.github.value, "снимок подключения не пришёл")
        assertTrue(connection.connected, "сессия поднялась, но подключение не отмечено")
        assertFalse(connection.authorized, "доступ найден там, где его нет")
        assertNull(connection.login, "при отсутствии доступа назван чужой логин")
        assertTrue(connection.scopes.isEmpty(), "права появились без доступа")
        assertFalse(connection.scopesReported, "права помечены сообщёнными без доступа")
        assertNull(connection.source, "назван источник доступа, которого нет")
        val hint = assertNotNull(connection.hint, "причину отсутствия доступа нечем показать")
        assertTrue(
            hint.contains("GITHUB_TOKEN"),
            "подсказка не объясняет, где искать доступ: $hint"
        )
        assertNull(repository.githubError.value, "отсутствие доступа показано как сбой запроса")
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
                "/v1/github/connect" -> """{"success":false,"error":"MCP-сервер не запустился"}""" to
                    HttpStatusCode.BadGateway
                else -> CHAT_RESPONSE to HttpStatusCode.OK
            }
        }

        repository.loadGitHub()
        val before = assertNotNull(repository.github.value)
        assertTrue(before.connected)

        repository.connectGitHub()

        assertEquals("MCP-сервер не запустился", repository.githubError.value)
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

        /**
         * Подключение выполнено: MCP-сервер назвался, доступ к GitHub найден (логин, права
         * из заголовка и место, откуда доступ взят) и объявлен инструмент с аргументом.
         */
        val CONNECTED = """
            {"connected":true,"authorized":true,"login":"ulanocka","scopes":["repo","read:user"],
             "scopesReported":true,"source":"файл ~/.config/ai-challenge/github.token","hint":null,
             "server":"ai-challenge-github","version":"1.0.0",
             "tools":[{"name":"get_repositories","description":"Репозитории владельца токена",
                       "arguments":[{"name":"visibility","description":"какие репозитории вернуть",
                                     "type":"string","required":false,
                                     "values":["all","public","private"]}]}]}
        """.trimIndent()

        /**
         * Сессия поднялась, но доступа к GitHub нет: снимок несёт причину и подсказку,
         * где искать доступ, — самого токена в нём нет и быть не может.
         */
        val NO_ACCESS = """
            {"connected":true,"authorized":false,"login":null,"scopes":[],"scopesReported":false,
             "source":null,
             "hint":"доступ к GitHub не найден. Проверены: GITHUB_TOKEN, файл ~/.config/ai-challenge/github.token. Задайте GITHUB_TOKEN или положите токен в файл.",
             "server":"ai-challenge-github","version":"1.0.0","tools":[]}
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
