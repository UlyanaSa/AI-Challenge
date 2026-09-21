package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatRequest
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.DialogBranches
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.MemoryRecord
import com.osvin.aichallenge.data.MemoryReport
import com.osvin.aichallenge.data.MemoryType
import com.osvin.aichallenge.data.MemoryWriteRequest
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.TokenReport
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
import kotlin.test.assertFalse
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
     * значениями по умолчанию (по умолчанию — память агента), чтобы сервер
     * не угадывал выбор клиента. Ветки в запросах обычных стратегий не участвуют.
     */
    @Test
    fun chosenStrategyAndWindowGoToRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests)

        repository.createChat()
        repository.sendMessage(
            "Сколько стоит поездка",
            GenerationSettings(strategy = ContextStrategy.MEMORY, windowMessages = 4)
        )
        val windowed = lastChatRequest(requests)
        assertEquals("memory", windowed.strategy)
        assertEquals(4, windowed.windowMessages)

        repository.sendMessage("А если на поезде")
        val defaults = lastChatRequest(requests)
        assertEquals("memory", defaults.strategy)
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
     * Память агента из ответа попадает в состояние и показывается в шторке,
     * а ответ без отчёта ничего не ломает: сообщения на месте, память не стирается.
     * Запись адресуется слоем и текстом — ключа у неё нет.
     */
    @Test
    fun memoryFromReplyLandsInStateAndMissingReportIsHarmless() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests, MEMORY_REPLY, PLAIN_REPLY)
        val settings = GenerationSettings(strategy = ContextStrategy.MEMORY)

        repository.createChat()
        assertNull(repository.memory.value, "у нового чата отчёта о памяти ещё нет")

        repository.sendMessage("Куда едем", settings)
        assertEquals(
            listOf(MemoryRecord("working", "цель — маршрут на Казань")),
            repository.memory.value?.working
        )
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            repository.memory.value?.longTerm
        )

        // Следующий ответ пришёл без отчёта агента: диалог продолжается, память не пропадает
        repository.sendMessage("А когда", settings)
        assertEquals(listOf("Куда едем", REPLY, "А когда", REPLY), repository.messages.value.map { it.content })
        assertEquals(
            listOf(MemoryRecord("working", "цель — маршрут на Казань")),
            repository.memory.value?.working
        )
    }

    /**
     * Совместимость с днём 10: старое значение стратегии «facts» клиент понимает как
     * «memory», и отчёт с ним так же доносит память до состояния — сервер прошлого дня
     * ничего не ломает.
     */
    @Test
    fun dayTenFactsValueIsUnderstoodAsMemory() = runBlocking {
        assertEquals(ContextStrategy.MEMORY, ContextStrategy.fromWire("facts"))

        val requests = mutableListOf<HttpRequestData>()
        val repository = newRepository(requests, DAY_TEN_REPLY)

        repository.createChat()
        repository.sendMessage("Куда едем", GenerationSettings(strategy = ContextStrategy.MEMORY))
        assertEquals(
            listOf(MemoryRecord("working", "цель — маршрут на Казань")),
            repository.memory.value?.working
        )
    }

    /**
     * Снимок памяти приезжает при открытии чата и уходит в состояние целиком:
     * оба слоя и каталог типов с подписями, а запрос несёт сессию именно этого
     * чата — память у каждого чата своя.
     */
    @Test
    fun memorySnapshotOfTheChatLandsInState() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(requests, listOf(MEMORY_SNAPSHOT))

        val chat = repository.createChat()
        repository.openChat(chat.id)

        val read = memoryRequests(requests).last()
        assertEquals(HttpMethod.Get, read.method)
        assertEquals(chat.id, read.url.parameters["sessionId"], "снимок читается по сессии открытого чата")
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            repository.layers.value?.working
        )
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            repository.layers.value?.longTerm
        )
        assertEquals(MEMORY_TYPES, repository.layers.value?.types, "каталог типов берётся из снимка")
    }

    /**
     * «Запомнить» уходит телом {sessionId, layer, value} — без ключа, которого
     * у записи больше нет, — а состояние берётся из ответа: в шторке оказывается
     * снимок сервера, а не то, что клиент набрал сам.
     */
    @Test
    fun rememberSendsWriteBodyAndTakesSnapshotFromAnswer() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(
            requests,
            listOf(MEMORY_SNAPSHOT),
            listOf(HttpStatusCode.OK to MEMORY_SNAPSHOT_AFTER_WRITE)
        )

        val chat = repository.createChat()
        repository.remember("long_term", "часовой пояс — MSK")

        val write = memoryRequests(requests).last()
        assertEquals(HttpMethod.Post, write.method)
        val body = (write.body as TextContent).text
        assertEquals(
            MemoryWriteRequest(sessionId = chat.id, layer = "long_term", value = "часовой пояс — MSK"),
            JSON.decodeFromString<MemoryWriteRequest>(body)
        )
        assertFalse("\"key\"" in body, "ключа у записи нет — в теле его быть не может: $body")
        assertEquals(
            listOf(
                MemoryRecord("long_term", "хранилище — Room"),
                MemoryRecord("long_term", "часовой пояс — MSK")
            ),
            repository.layers.value?.longTerm
        )
        assertNull(repository.memoryError.value, "успешная запись не оставляет текста ошибки")
    }

    /**
     * «Забыть» уходит методом DELETE с типом и текстом записи в параметрах,
     * а кириллица кодируется в процентах — текст доезжает до сервера как набран.
     */
    @Test
    fun forgetSendsLayerAndEncodedValue() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(
            requests,
            listOf(MEMORY_SNAPSHOT),
            listOf(HttpStatusCode.OK to MEMORY_SNAPSHOT_AFTER_FORGET)
        )

        val chat = repository.createChat()
        repository.forget("working", "цель — учёт расходов")

        val delete = memoryRequests(requests).last()
        assertEquals(HttpMethod.Delete, delete.method)
        assertEquals(chat.id, delete.url.parameters["sessionId"])
        assertEquals("working", delete.url.parameters["layer"])
        assertEquals("цель — учёт расходов", delete.url.parameters["value"], "текст уезжает без искажений")
        assertTrue(
            "%d1%86" in delete.url.encodedQuery.lowercase(),
            "кириллица кодируется в процентах: ${delete.url.encodedQuery}"
        )
        assertEquals(emptyList(), repository.layers.value?.working, "состояние взято из ответа на удаление")
    }

    /**
     * Отказ сервера на явную запись виден в шторке, а снимок остаётся: память — не
     * часть диалога, и ошибка записи не переводит чат в состояние ошибки.
     */
    @Test
    fun serverRefusalShowsInSheetAndKeepsSnapshot() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(
            requests,
            listOf(MEMORY_SNAPSHOT),
            listOf(HttpStatusCode.BadRequest to """{"success":false,"error":"тип памяти неизвестен"}""")
        )

        repository.createChat()
        repository.remember("неведомый", "значение")

        assertEquals("тип памяти неизвестен", repository.memoryError.value)
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            repository.layers.value?.working,
            "снимок до отказа остаётся в шторке"
        )
        assertEquals(ChatUiState.Idle, repository.state.value, "отказ памяти не переводит чат в ошибку")
    }

    /**
     * После ответа агента шторка перечитывает снимок: агент мог дописать память сам,
     * и в шторке оказывается то, что теперь лежит в слоях.
     */
    @Test
    fun agentReplyReloadsSnapshot() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(
            requests,
            listOf(MEMORY_SNAPSHOT, MEMORY_SNAPSHOT_AFTER_WRITE)
        )

        val chat = repository.createChat()
        assertEquals(1, memoryRequests(requests).size, "снимок читается уже при создании чата")

        repository.sendMessage("Куда едем")

        val reads = memoryRequests(requests)
        assertEquals(2, reads.size, "после ответа агента снимок перечитывается")
        assertEquals(chat.id, reads.last().url.parameters["sessionId"])
        assertEquals(
            listOf(
                MemoryRecord("long_term", "хранилище — Room"),
                MemoryRecord("long_term", "часовой пояс — MSK")
            ),
            repository.layers.value?.longTerm,
            "в шторке то, что память записала сама"
        )
    }

    /**
     * Каталог типов из снимка — единственный источник выбора: тип для записи берётся
     * из него как есть и уходит на сервер тем же значением слоя, своей таблицы
     * типов у клиента нет.
     */
    @Test
    fun typeCatalogFromSnapshotIsTheOnlySourceOfChoice() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(
            requests,
            listOf(MEMORY_SNAPSHOT),
            listOf(HttpStatusCode.OK to MEMORY_SNAPSHOT_AFTER_WRITE)
        )

        repository.createChat()
        assertEquals(MEMORY_TYPES, repository.layers.value?.types, "каталог типов берётся из снимка")

        // Писаемый тип из каталога: в запрос уходит именно его слой
        val chosen = repository.layers.value!!.types.last { it.writable }
        repository.remember(chosen.layer, "язык — русский")
        val body = JSON.decodeFromString<MemoryWriteRequest>(
            (memoryRequests(requests).last().body as TextContent).text
        )
        assertEquals(chosen.layer, body.layer, "в запись уходит слой из каталога")
        assertEquals("язык — русский", body.value)
    }

    /**
     * Удаление активного чата чистит шторку: снимок чужой сессии в ней не остаётся.
     */
    @Test
    fun deletingChatClearsSnapshot() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val repository = newMemoryRepository(requests, listOf(MEMORY_SNAPSHOT))

        val chat = repository.createChat()
        assertNotNull(repository.layers.value, "у созданного чата снимок уже загружен")

        repository.deleteChat(chat.id)
        assertNull(repository.layers.value, "снимок удалённого чата не показывается")
        assertNull(repository.memoryError.value)
    }

    /**
     * Отчёт агента пишется в лог платформы одной записью, по строке на характеристику,
     * каждая строка — с префиксом `[agent] `: строки о стратегии, окне, ветке и слоях
     * памяти видны в logcat вместе с остальными. У записи памяти печатается тип как
     * есть и текст, ключа в логе нет.
     */
    @Test
    fun reportLogGoesLineByLine() {
        val report = TokenReport(
            strategy = "branches",
            windowMessages = 10,
            droppedMessages = 3,
            excludedMessages = 2,
            branchId = "ветка-1",
            memory = MemoryReport(
                working = listOf(MemoryRecord("working", "цель — собрать ТЗ")),
                longTerm = listOf(MemoryRecord("long_term", "хранилище — Room")),
                workingTokens = 14,
                longTermTokens = 9,
                shortTermMessages = 6,
                shortTermDropped = 8,
                rejected = 1,
                evicted = 2,
                updateTokens = 120,
                updateCostUsd = 0.00002
            )
        )

        val lines = report.logEntry("deepseek-v4-flash").lines()
        assertTrue(lines.all { it.startsWith("[agent] ") }, "строка лога без префикса: $lines")
        assertTrue("[agent] стратегия: Ветки диалога" in lines, "нет строки о стратегии: $lines")
        assertTrue("[agent] окно: последние 10 сообщ., отброшено 3" in lines, "нет строки об окне: $lines")
        assertTrue("[agent] активная ветка: ветка-1 (вне её пути: 2 сообщ.)" in lines, "нет строки о ветке: $lines")

        assertTrue(
            "[agent] память: рабочая 1 шт. (14 ток.), долговременная 1 шт. (9 ток.)" in lines,
            "нет строки о слоях памяти: $lines"
        )
        assertTrue(
            "[agent] - working | цель — собрать ТЗ" in lines,
            "нет строки рабочей памяти: $lines"
        )
        assertTrue(
            "[agent] - long_term | хранилище — Room" in lines,
            "нет строки долговременной памяти: $lines"
        )
        assertTrue(
            "[agent] краткосрочная: 6 сообщ. в запросе, не ушло 8" in lines,
            "нет строки о краткосрочной памяти: $lines"
        )
        assertTrue("[agent] отклонено записей: 1" in lines, "нет строки об отклонённых записях: $lines")
        assertTrue("[agent] вытеснено записей: 2" in lines, "нет строки о вытесненных записях: $lines")
        assertTrue(
            "[agent] обновление памяти: 120 ток., цена $0.000020" in lines,
            "нет строки об обновлении памяти: $lines"
        )
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Ответ подставного сервера: текст, который попадает в историю чата. */
        const val REPLY = "Ответ агента"

        /** Ответ без отчёта агента: так выглядит день 9 и сбой расчёта токенов. */
        val PLAIN_REPLY = """{"success":true,"reply":"$REPLY"}"""

        /** Ответ стратегии «память агента»: в отчёте видно, что ушло по слоям. */
        val MEMORY_REPLY = """
            {"success":true,"reply":"$REPLY","tokens":
                {"strategy":"memory","window_messages":10,
                 "memory":{"working":[{"layer":"working","value":"цель — маршрут на Казань"}],
                 "long_term":[{"layer":"long_term","value":"хранилище — Room"}],
                 "working_tokens":14,"long_term_tokens":9,
                 "short_term_messages":6,"short_term_dropped":8}}}
        """.trimIndent()

        /** Ответ сервера дня 10: то же значение стратегии, что клиент понимает как «memory». */
        val DAY_TEN_REPLY = """
            {"success":true,"reply":"$REPLY","tokens":
                {"strategy":"facts","window_messages":10,
                 "memory":{"working":[{"layer":"working","value":"цель — маршрут на Казань"}],
                 "working_tokens":14}}}
        """.trimIndent()

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Снимок памяти для тестов стратегий: слои пусты, каталог тоже. */
        const val EMPTY_SNAPSHOT = """{"working":[],"long_term":[],"types":[]}"""

        /** Каталог типов задания: подпись и пояснение клиент берёт отсюда, а не из своей таблицы. */
        val TYPES_JSON = """
            [{"layer":"short_term","title":"краткосрочная","hint":"текущий диалог","writable":false},
             {"layer":"working","title":"рабочая","hint":"данные текущей задачи","writable":true},
             {"layer":"long_term","title":"долговременная","hint":"профиль, решения, знания","writable":true}]
        """.trimIndent()

        /** Каталог типов из [TYPES_JSON] так, как его разбирает клиент. */
        val MEMORY_TYPES = listOf(
            MemoryType("short_term", "краткосрочная", "текущий диалог", writable = false),
            MemoryType("working", "рабочая", "данные текущей задачи", writable = true),
            MemoryType("long_term", "долговременная", "профиль, решения, знания", writable = true)
        )

        /** Снимок памяти чата: оба слоя и каталог типов. */
        val MEMORY_SNAPSHOT = """
            {"working":[{"layer":"working","value":"цель — учёт расходов"}],
             "long_term":[{"layer":"long_term","value":"хранилище — Room"}],
             "types":$TYPES_JSON}
        """.trimIndent()

        /** Снимок после записи: тот же чат, но с записью, которую вернул сервер. */
        val MEMORY_SNAPSHOT_AFTER_WRITE = """
            {"working":[{"layer":"working","value":"цель — учёт расходов"}],
             "long_term":[{"layer":"long_term","value":"хранилище — Room"},
                          {"layer":"long_term","value":"часовой пояс — MSK"}],
             "types":$TYPES_JSON}
        """.trimIndent()

        /** Снимок после удаления записи: рабочий слой пуст, остальное на месте. */
        val MEMORY_SNAPSHOT_AFTER_FORGET = """
            {"working":[],
             "long_term":[{"layer":"long_term","value":"хранилище — Room"}],
             "types":$TYPES_JSON}
        """.trimIndent()

        /**
         * Подставной сервер: отвечает следующим телом из [bodies], последним —
         * сколько бы запросов ни пришло; без тел отвечает обычным ответом агента.
         * Очередь тел — про ответы агента: снимок памяти чат читает отдельным
         * запросом, и тому тесты стратегий тела не назначают.
         */
        fun fakeServer(requests: MutableList<HttpRequestData>, vararg bodies: String): HttpClient {
            val answers = bodies.ifEmpty { arrayOf(PLAIN_REPLY) }
            var index = 0
            return HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = when (request.url.encodedPath) {
                        "/v1/memory" -> EMPTY_SNAPSHOT
                        else -> {
                            val answer = answers[minOf(index, answers.lastIndex)]
                            index++
                            answer
                        }
                    }
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

        /**
         * Подставной сервер с памятью: на `GET /v1/memory` отвечает следующими снимками
         * из [snapshots] (последним — сколько угодно раз), на запись и удаление — из
         * [writes] (без них — последним снимком), остальные запросы получают обычный
         * ответ агента. Так в одном тесте видно и что ушло на сервер, и что из ответа
         * попало в состояние.
         */
        fun memoryServer(
            requests: MutableList<HttpRequestData>,
            snapshots: List<String>,
            writes: List<Pair<HttpStatusCode, String>> = emptyList()
        ): HttpClient {
            var reads = 0
            var wrote = 0
            return HttpClient(
                MockEngine { request ->
                    requests += request
                    val answer = when {
                        request.url.encodedPath != "/v1/memory" -> HttpStatusCode.OK to PLAIN_REPLY
                        request.method == HttpMethod.Get -> {
                            val body = snapshots[minOf(reads, snapshots.lastIndex)]
                            reads++
                            HttpStatusCode.OK to body
                        }
                        else -> {
                            val write = writes.getOrNull(wrote)
                                ?: (HttpStatusCode.OK to snapshots.last())
                            wrote++
                            write
                        }
                    }
                    respond(
                        content = answer.second,
                        status = answer.first,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ) {
                install(ContentNegotiation) { json(JSON) }
            }
        }

        fun newMemoryRepository(
            requests: MutableList<HttpRequestData>,
            snapshots: List<String>,
            writes: List<Pair<HttpStatusCode, String>> = emptyList()
        ): ChatRepository = ChatRepository(BASE_URL, InMemoryChatStore(), memoryServer(requests, snapshots, writes))

        /** Запросы к памяти чата: чтение снимка, запись и удаление записи. */
        fun memoryRequests(requests: List<HttpRequestData>): List<HttpRequestData> =
            requests.filter { it.url.encodedPath == "/v1/memory" }

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
