package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatRequest
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.DialogBranches
import com.osvin.aichallenge.data.Fact
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.TokenReport
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Стратегии управления контекстом на клиенте: что уезжает на сервер, что видно
 * в чате при ветвлении и что остаётся в состоянии после ответа.
 *
 * Сервер подставной ([MockEngine]): тест смотрит на тела запросов и работает без сети.
 */
class ContextStrategiesClientTest {

    /**
     * Выбранная стратегия и размер окна уезжают в каждом запросе — вместе со
     * значениями по умолчанию (поведение дня 9), чтобы сервер не угадывал выбор клиента.
     * Ветки в запросах обычных стратегий не участвуют.
     */
    @Test
    fun chosenStrategyAndWindowGoToRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests)

        repository.createChat()
        repository.sendMessage(
            "Сколько стоит поездка",
            GenerationSettings(strategy = ContextStrategy.FACTS, windowMessages = 4)
        )
        val windowed = lastChatRequest(requests)
        assertEquals("facts", windowed.strategy)
        assertEquals(4, windowed.windowMessages)

        repository.sendMessage("А если на поезде")
        val defaults = lastChatRequest(requests)
        assertEquals("summary", defaults.strategy)
        assertEquals(GenerationSettings.DEFAULT_WINDOW_MESSAGES, defaults.windowMessages)
        assertNull(defaults.branchId)
        assertNull(defaults.branches)
    }

    /**
     * Стратегия «ветки диалога» присылает структуру веток и активную ветку, а история
     * уезжает с метками: сообщения основной линии без метки, продолжения — со своими
     * идентификаторами. По этим меткам агент и собирает путь активной ветки.
     */
    @Test
    fun branchesStrategySendsBranchStructureAndMessageTags() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests)
        val settings = GenerationSettings(strategy = ContextStrategy.BRANCHES)

        repository.createChat()
        repository.sendMessage("Первый вопрос", settings)
        val forkPoint = repository.messages.value.last()
        repository.createBranchFrom(forkPoint)
        val branchId = repository.activeBranchId.value
        assertNotNull(branchId, "ветка от сообщения активного пути не создалась")

        // Точка ветвления — ответ агента, то есть второе сообщение пути
        assertEquals(listOf("Первый вопрос", REPLY), repository.messages.value.map { it.content })
        repository.sendMessage("Вопрос в ветке", settings)
        repository.sendMessage("Ещё вопрос в ветке", settings)

        val sent = lastChatRequest(requests)
        assertEquals("branches", sent.strategy)
        assertEquals(branchId, sent.branchId)

        val branch = sent.branches?.single()
        assertNotNull(branch, "структура веток не уехала в запрос")
        assertEquals(branchId, branch.id)
        assertEquals(null, branch.parentId)
        assertEquals(2, branch.forkedAfter)

        // История: основная линия без меток, сообщения ветки — с её идентификатором
        val history = sent.history
        assertNotNull(history, "история ветки не уехала в запрос")
        assertEquals(
            listOf("Первый вопрос", REPLY, "Вопрос в ветке", REPLY),
            history.map { it.content }
        )
        assertEquals(listOf(null, null, branchId, branchId), history.map { it.branchId })
    }

    /**
     * Переключение ветки меняет и экран, и контекст следующего запроса: путь вложенной
     * ветки — это путь родителя до точки ветвления плюс её собственные сообщения.
     * Сообщения соседних веток остаются в истории со своими метками — они не теряются,
     * но в путь активной ветки не попадают.
     */
    @Test
    fun switchingBranchShowsAndSendsPathOfTheNewBranch() = runBlocking {
        val store = InMemoryChatStore()
        val requests = mutableListOf<HttpRequestData>()
        val repository = ChatRepository(BASE_URL, store, fakeServer(requests, PLAIN_REPLY))
        val settings = GenerationSettings(strategy = ContextStrategy.BRANCHES)

        repository.createChat()
        repository.sendMessage("Первый вопрос", settings)
        repository.createBranchFrom(repository.messages.value.last())
        val first = repository.activeBranchId.value!!
        repository.sendMessage("Вопрос в первой ветке", settings)

        // Вложенная ветка: продолжение от ответа внутри первой ветки
        repository.createBranchFrom(repository.messages.value.last())
        val nested = repository.activeBranchId.value!!
        repository.sendMessage("Уточнение во второй ветке", settings)
        assertEquals(
            listOf("Первый вопрос", REPLY, "Вопрос в первой ветке", REPLY, "Уточнение во второй ветке", REPLY),
            repository.messages.value.map { it.content }
        )

        // Соседняя ветка от той же точки ветвления, что и первая
        repository.switchBranch(null)
        repository.createBranchFrom(repository.messages.value.last())
        val sibling = repository.activeBranchId.value!!
        repository.sendMessage("Вопрос в третьей ветке", settings)

        // Возврат в первую ветку: соседние продолжения в чат не подмешиваются
        repository.switchBranch(first)
        assertEquals(
            listOf("Первый вопрос", REPLY, "Вопрос в первой ветке", REPLY),
            repository.messages.value.map { it.content }
        )

        // Снова в соседнюю ветку и ещё один запрос: уезжает её путь и её метки
        repository.switchBranch(sibling)
        repository.sendMessage("Ещё вопрос", settings)
        val sent = lastChatRequest(requests)
        assertEquals(sibling, sent.branchId)

        val branches = sent.branches.orEmpty()
        assertEquals(setOf(first, nested, sibling), branches.map { it.id }.toSet())
        val nestedBranch = branches.first { it.id == nested }
        assertEquals(first, nestedBranch.parentId)
        assertEquals(4, nestedBranch.forkedAfter)

        val history = sent.history.orEmpty()
        assertEquals(
            listOf(
                null to "Первый вопрос",
                first to "Вопрос в первой ветке",
                nested to "Уточнение во второй ветке",
                sibling to "Вопрос в третьей ветке"
            ),
            history.filter { it.role == MessageRole.USER }.map { it.branchId to it.content }
        )
    }

    /**
     * Переключение вариантов живёт на самом сообщении: у точки ветвления видно и свою
     * линию, и отведённые ветки, а стрелка переключает активную ветку и контекст
     * следующего запроса — отдельного меню веток не нужно.
     */
    @Test
    fun branchChoicesLiveOnTheMessageAndSwitchContext() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests)
        val settings = GenerationSettings(strategy = ContextStrategy.BRANCHES)

        repository.createChat()
        repository.sendMessage("Первый вопрос", settings)
        repository.createBranchFrom(repository.messages.value.last())
        val branchId = repository.activeBranchId.value
        assertNotNull(branchId, "ветка от сообщения не создалась")

        // Сразу после создания активна новая ветка: у точки ветвления два варианта,
        // и она — последнее сообщение пути, потому что ветка пока пуста.
        val (forkIndex, atFork) = choicePoint(repository)
        assertEquals(1, forkIndex, "точка ветвления — ответ агента на первый вопрос")
        assertEquals(2, atFork.size, "вариантов ровно два: основная линия и ветка")
        assertEquals(listOf(null, branchId), atFork.options)
        assertEquals(1, atFork.current, "сразу после создания активна новая ветка")

        // Стрелка в сторону возвращает на основную линию — и в чате, и в следующем запросе
        repository.switchBranch(atFork.neighbour(-1))
        assertNull(repository.activeBranchId.value, "активной снова стала основная линия")
        repository.sendMessage("Продолжение на линии", settings)
        assertNull(lastChatRequest(requests).branchId, "запрос ушёл без активной ветки")

        // Стрелка обратно ведёт в ветку: её метка снова уезжает в историю запроса
        val (_, back) = choicePoint(repository)
        assertEquals(0, back.current, "на основной линии текущий вариант — первый")
        repository.switchBranch(back.neighbour(1))
        assertEquals(branchId, repository.activeBranchId.value, "стрелка вернула в ветку")
        repository.sendMessage("Продолжение в ветке", settings)
        repository.sendMessage("Ещё вопрос в ветке", settings)

        val sent = lastChatRequest(requests)
        assertEquals(branchId, sent.branchId, "новое продолжение уходит в активную ветку")
        assertEquals(
            listOf(
                null to "Первый вопрос",
                null to "Продолжение на линии",
                branchId to "Продолжение в ветке"
            ),
            sent.history.orEmpty().filter { it.role == MessageRole.USER }.map { it.branchId to it.content }
        )

        // Сообщение, от которого диалог не ветвился, вариантов не показывает
        assertNull(
            DialogBranches.choiceAfter(listOf(ChatMessage(MessageRole.USER, "один")), 0, emptyList(), null),
            "без веток у сообщения нет вариантов"
        )
    }

    /**
     * Память фактов из ответа попадает в состояние и показывается в чате,
     * а ответ без отчёта ничего не ломает: сообщения на месте, память не стирается.
     */
    @Test
    fun factsFromReplyLandInStateAndMissingReportIsHarmless() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests, FACTS_REPLY, PLAIN_REPLY)
        val settings = GenerationSettings(strategy = ContextStrategy.FACTS)

        repository.createChat()
        assertTrue(repository.facts.value.isEmpty(), "у нового чата память фактов не пуста")

        repository.sendMessage("Куда едем", settings)
        assertEquals(listOf(Fact(key = "цель", value = "маршрут на Казань")), repository.facts.value)

        // Следующий ответ пришёл без отчёта агента: диалог продолжается, память не пропадает
        repository.sendMessage("А когда", settings)
        assertEquals(listOf("Куда едем", REPLY, "А когда", REPLY), repository.messages.value.map { it.content })
        assertEquals(listOf(Fact(key = "цель", value = "маршрут на Казань")), repository.facts.value)
    }

    /**
     * Отчёт агента пишется в лог платформы одной записью, по строке на характеристику,
     * каждая строка — с префиксом `[agent] `: строки о стратегии, окне, ветке и фактах
     * видны в logcat вместе с остальными.
     */
    @Test
    fun reportLogGoesLineByLine() {
        val report = TokenReport(
            strategy = "branches",
            windowMessages = 10,
            droppedMessages = 3,
            excludedMessages = 2,
            branchId = "ветка-1",
            facts = listOf(Fact(key = "цель", value = "маршрут на Казань")),
            factsTokens = 14,
            factsUpdateTokens = 120,
            factsUpdateCostUsd = 0.00002
        )

        val lines = report.logEntry("deepseek-v4-flash").lines()
        assertTrue(lines.all { it.startsWith("[agent] ") }, "строка лога без префикса: $lines")
        assertTrue("[agent] стратегия: Ветки диалога" in lines, "нет строки о стратегии: $lines")
        assertTrue("[agent] окно: последние 10 сообщ., отброшено 3" in lines, "нет строки об окне: $lines")
        assertTrue("[agent] активная ветка: ветка-1 (вне её пути: 2 сообщ.)" in lines, "нет строки о ветке: $lines")
        assertTrue("[agent] - цель: маршрут на Казань" in lines, "нет строки факта: $lines")
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Ответ подставного сервера: текст, который попадает в историю чата. */
        const val REPLY = "Ответ агента"

        /** Ответ без отчёта агента: так выглядит день 9 и сбой расчёта токенов. */
        val PLAIN_REPLY = """{"success":true,"reply":"$REPLY"}"""

        /** Ответ стратегии «память фактов»: в отчёте видно, что запомнил агент. */
        val FACTS_REPLY = """
            {"success":true,"reply":"$REPLY","tokens":
                {"strategy":"facts","window_messages":10,
                 "facts":[{"key":"цель","value":"маршрут на Казань"}],"facts_tokens":14}}
        """.trimIndent()

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Подставной сервер: отвечает следующим телом из [bodies], последним —
         * сколько бы запросов ни пришло; без тел отвечает обычным ответом агента.
         */
        fun fakeServer(requests: MutableList<HttpRequestData>, vararg bodies: String): HttpClient {
            val answers = bodies.ifEmpty { arrayOf(PLAIN_REPLY) }
            var index = 0
            return HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = answers[minOf(index, answers.lastIndex)]
                    index++
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ) {
                install(ContentNegotiation) { json(JSON) }
            }
        }

        fun newRepository(
            requests: MutableList<HttpRequestData>,
            vararg bodies: String
        ): ChatRepository = ChatRepository(BASE_URL, InMemoryChatStore(), fakeServer(requests, *bodies))

        /** Запросы к модели с разобранным телом; проверка связи сюда не попадает. */
        fun chatRequests(requests: List<HttpRequestData>): List<ChatRequest> = requests
            .filter { it.url.encodedPath == "/v1/chat/completions" }
            .map { JSON.decodeFromString<ChatRequest>((it.body as TextContent).text) }

        fun lastChatRequest(requests: List<HttpRequestData>): ChatRequest = chatRequests(requests).last()

        /**
         * Точка ветвления активного пути: индекс сообщения и его варианты.
         * Сообщение, у которого вариантов нет, возвращает null, поэтому выбор один.
         */
        fun choicePoint(repository: ChatRepository): Pair<Int, DialogBranches.BranchChoice> {
            val path = repository.messages.value
            return path.indices
                .mapNotNull { index ->
                    DialogBranches
                        .choiceAfter(path, index, repository.branches.value, repository.activeBranchId.value)
                        ?.let { index to it }
                }
                .single()
        }
    }
}
