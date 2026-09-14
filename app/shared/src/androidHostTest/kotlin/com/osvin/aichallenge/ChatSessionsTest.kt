package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatRequest
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.repository.ChatRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Чаты и их сессии агента: у каждого чата свой идентификатор сессии и своя история,
 * и всё это живёт в хранилище устройства до удаления чата.
 *
 * Сервер подставной ([MockEngine]): тест смотрит, что именно уходит на сервер,
 * и работает без сети.
 */
class ChatSessionsTest {

    /**
     * Два чата — две сессии и две независимые истории: во втором чате в модель
     * не уезжает ни одно сообщение первого, а идентификаторы сессий разные.
     */
    @Test
    fun eachChatKeepsItsOwnSessionAndHistory() = runBlocking {
        val store = InMemoryChatStore()
        val requests = mutableListOf<HttpRequestData>()
        val repository = ChatRepository(BASE_URL, store, testClient(requests))

        val first = repository.createChat()
        repository.sendMessage("Первый вопрос")
        val second = repository.createChat()
        repository.sendMessage("Второй вопрос")

        val sent = chatRequests(requests)
        assertEquals(listOf(first.id, second.id), sent.map { it.sessionId })
        assertNotEquals(sent[0].sessionId, sent[1].sessionId)
        // Первое сообщение нового чата уходит без истории: чужие сообщения не подмешиваются
        assertEquals(null, sent[1].history)

        // Возвращаемся в первый чат: история своя, сессия та же
        repository.openChat(first.id)
        repository.sendMessage("Второй вопрос первого чата")
        val back = chatRequests(requests).last()
        assertEquals(first.id, back.sessionId)
        assertEquals(
            listOf("Первый вопрос", REPLY, "Второй вопрос первого чата"),
            back.history?.map { it.content }?.plus(back.message)
        )
    }

    /**
     * Сессия и история чата лежат в хранилище: после перезапуска приложения чат
     * открывается с тем же идентификатором сессии, тем же заголовком и той же
     * перепиской.
     */
    @Test
    fun chatKeepsItsSessionAndHistoryAfterRestart() = runBlocking {
        val store = InMemoryChatStore()
        val requests = mutableListOf<HttpRequestData>()
        val repository = ChatRepository(BASE_URL, store, testClient(requests))
        val chat = repository.createChat()
        repository.sendMessage("Считаем бюджет на дорогу")

        // Перезапуск приложения: новый репозиторий над тем же хранилищем
        val restarted = ChatRepository(BASE_URL, store, testClient(requests))
        restarted.loadChats()

        assertEquals(listOf(chat.id), restarted.chats.value.map { it.id })
        assertEquals("Считаем бюджет на дорогу", restarted.chats.value.single().title)

        restarted.openChat(chat.id)
        assertEquals(
            listOf("Считаем бюджет на дорогу", REPLY),
            restarted.messages.value.map { it.content }
        )

        restarted.sendMessage("Какой бюджет я называл?")
        assertEquals(chat.id, chatRequests(requests).last().sessionId)
    }

    /**
     * Удаление чата убирает и его историю, и его сессию — на устройстве и на сервере,
     * при этом соседний чат остаётся нетронутым.
     */
    @Test
    fun deletingChatRemovesItsHistoryAndServerSession() = runBlocking {
        val store = InMemoryChatStore()
        val requests = mutableListOf<HttpRequestData>()
        val repository = ChatRepository(BASE_URL, store, testClient(requests))

        val first = repository.createChat()
        repository.sendMessage("Первый вопрос")
        val second = repository.createChat()
        repository.sendMessage("Второй вопрос")

        repository.deleteChat(first.id)

        assertEquals(listOf(second.id), repository.chats.value.map { it.id })
        assertTrue(store.messages(first.id).isEmpty(), "сообщения удалённого чата остались в хранилище")

        val deletions = requests.filter { it.method == HttpMethod.Delete }
        assertEquals(listOf("/v1/chats/${first.id}"), deletions.map { it.url.encodedPath })
        // Удаление сессии — не повод стирать сообщения соседнего чата
        assertEquals(
            listOf("Второй вопрос", REPLY),
            store.messages(second.id).map { it.content }
        )
    }

    /**
     * Сбой удаления сессии на сервере не мешает удалить чат на устройстве:
     * локальная история не должна зависеть от доступности сервера.
     */
    @Test
    fun chatIsDeletedEvenWhenServerIsUnreachable() = runBlocking {
        val store = InMemoryChatStore()
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Delete) error("сервер недоступен") else respondJson()
        }) {
            install(ContentNegotiation) { json(JSON) }
        }
        val repository = ChatRepository(BASE_URL, store, client)
        val chat = repository.createChat()
        repository.sendMessage("Вопрос")

        repository.deleteChat(chat.id)

        assertTrue(repository.chats.value.isEmpty())
        assertTrue(store.messages(chat.id).isEmpty())
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Ответ подставного сервера: текст, который попадает в историю чата. */
        const val REPLY = "Ответ агента"

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun testClient(requests: MutableList<HttpRequestData>) = HttpClient(
            MockEngine { request ->
                requests += request
                respondJson()
            }
        ) {
            install(ContentNegotiation) { json(JSON) }
        }

        fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson() = respond(
            content = """{"success":true,"reply":"$REPLY"}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

        /** Запросы к модели: удаление сессии и проверка связи в них не попадают. */
        fun chatRequests(requests: List<HttpRequestData>): List<ChatRequest> = requests
            .filter { it.url.encodedPath == "/v1/chat/completions" }
            .map { JSON.decodeFromString<ChatRequest>((it.body as TextContent).text) }
    }
}
