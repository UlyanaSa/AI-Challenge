package com.osvin.aichallenge

import com.osvin.aichallenge.data.ChatRequest
import com.osvin.aichallenge.data.CurrencyChangeItem
import com.osvin.aichallenge.data.CurrencyChat
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.line
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Лента курсов валют: приложение ведёт её само — раз в минуту печатает строку
 * с текущими курсами, на смене часа добавляет сводку часа, а в закреплённом чате
 * держит не больше [FEED_LIMIT] последних сообщений.
 *
 * Сервер подставной ([MockEngine]): тест смотрит, что именно уходит на провод
 * и что из ответа попадает в ленту, и работает без сети. Время и пауза между ударами
 * монитора задаются тестом ([DrivenMonitor]): ждать настоящую минуту и настоящий час
 * в тесте нечем, а удары при этом проходят через тот же цикл, что и в приложении.
 */
class CurrencyMonitorTest {

    /**
     * Первый удар монитора сразу печатает строку с курсами: время — из метки сервера,
     * числа — ровно теми цифрами, что пришли, с русской запятой. Роль у строки служебная
     * ([MessageRole.MONITOR]): её напечатало приложение, а не человек.
     */
    @Test
    fun firstTickPrintsRatesFromServer() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()
        val monitor = DrivenMonitor(this, repository, FIRST_TICK)
        try {
            monitor.awaitStart()
        } finally {
            monitor.stop()
        }

        val lines = server.store.messages(CurrencyChat.ID)
        assertEquals(1, lines.size, "первый удар должен печатать одну строку")
        val line = lines.single()
        assertEquals(MessageRole.MONITOR, line.role)
        assertEquals(
            "12:05 · EUR 95,8709 · USD 84,3414 · GEL 32,1693",
            line.content
        )
    }

    /**
     * Сводка часа появляется только на смене часа: на первом ударе час лишь запоминается,
     * поэтому перезапуск приложения (новый монитор) не добавляет сводку за час, которого
     * он не наблюдал. В самой сводке — интервал прошедшего часа и строка на валюту:
     * рост, падение и «без изменения» там, где сервер изменения не назвал.
     */
    @Test
    fun hourSummaryIsAddedOnlyWhenTheHourChanges() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()
        val monitor = DrivenMonitor(this, repository, FIRST_TICK)
        try {
            monitor.awaitStart()
            // Тот же час: ещё одна минутная строка, сводки быть не должно
            monitor.tick()
            assertEquals(
                0,
                server.store.messages(CurrencyChat.ID).count { it.content.startsWith("Сводка за час") },
                "сводка часа добавлена внутри одного часа"
            )

            monitor.now = FIRST_TICK + HOUR_MS
            monitor.tick()
        } finally {
            monitor.stop()
        }

        val lines = server.store.messages(CurrencyChat.ID)
        assertEquals(
            1,
            lines.count { it.content.startsWith("Сводка за час") },
            "на смене часа должна быть ровно одна сводка"
        )
        assertEquals(
            listOf(
                "Сводка за час 12:00–13:00:",
                "EUR 95,5 → 96,5 (+1,5, +1,5789%)",
                "USD 84,3414 → 84,2 (-0,1414, -0,168%)",
                "GEL — → 32,1693 (без изменения)"
            ),
            lines.last().content.lines()
        )
    }

    /**
     * Лента держит только последние сообщения: строки приходят раз в минуту, и без
     * обрезки чат рос бы без конца. Обрезаются именно старые — в ленте остаются
     * последние [FEED_LIMIT] строк.
     */
    @Test
    fun feedKeepsOnlyTheLastMessages() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()
        repository.ensureCurrencyChat()

        repeat(FEED_LIMIT + 5) { index ->
            repository.appendMonitorMessage(CurrencyChat.ID, "строка ${index + 1}")
        }

        val lines = server.store.messages(CurrencyChat.ID)
        assertEquals(FEED_LIMIT, lines.size)
        assertEquals("строка 6", lines.first().content, "обрезались не самые старые строки")
        assertEquals("строка ${FEED_LIMIT + 5}", lines.last().content)
    }

    /**
     * Закреплённый чат заводится один раз и стоит первым в списке, сколько бы раз
     * монитор ни звал заведение: идентификатор у чата постоянный, поэтому повторный
     * вызов находит тот же чат. Первым он остаётся и тогда, когда в соседнем чате
     * только что писали, — закрепление важнее свежести.
     */
    @Test
    fun pinnedChatIsCreatedOnceAndListedFirst() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()

        val currency = repository.ensureCurrencyChat()
        repository.ensureCurrencyChat()

        assertEquals(1, server.store.chats().count { it.id == CurrencyChat.ID })
        assertEquals(CurrencyChat.TITLE, currency.title)
        assertTrue(currency.pinned, "чат ленты не закреплён")

        val dialog = repository.createChat()
        repository.sendMessage("Привет")

        assertEquals(CurrencyChat.ID, repository.chats.value.first().id)
        assertEquals(dialog.id, repository.chats.value.last().id)
    }

    /**
     * Закреплённый чат удалить нельзя: он ведёт приложение, и второго такого не заведётся.
     * На устройстве чат остаётся на месте, а сессия на сервере не чистится — иначе после
     * удаления осталась бы висящая сессия, а лента началась бы заново.
     */
    @Test
    fun pinnedChatCannotBeDeleted() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()
        repository.ensureCurrencyChat()

        repository.deleteChat(CurrencyChat.ID)

        assertNotNull(server.store.chat(CurrencyChat.ID), "закреплённый чат удалён из хранилища")
        assertTrue(repository.chats.value.any { it.id == CurrencyChat.ID })
        assertTrue(
            server.requests.none { it.method == HttpMethod.Delete },
            "сессия закреплённого чата очищена на сервере, хотя чат не удалён"
        )
    }

    /**
     * В историю запроса к модели строки монитора не попадают: их писало приложение,
     * и модель приняла бы курсы за слова пользователя. Реплики диалога при этом уходят
     * как обычно — вопрос пользователя и ответ ассистента на месте.
     */
    @Test
    fun monitorLinesDoNotReachTheModel() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()

        val chat = repository.createChat()
        repository.sendMessage("Первый вопрос")
        repository.appendMonitorMessage(chat.id, "12:05 · EUR 95,8709")
        repository.sendMessage("Второй вопрос")

        assertEquals(2, chatRequests(server.requests).size, "вопрос ушёл в модель не один раз")
        val history = assertNotNull(
            chatRequests(server.requests).last().history,
            "история диалога не ушла в модель"
        )
        assertEquals(listOf("Первый вопрос", REPLY), history.map { it.content })
        assertTrue(
            history.none { it.role == MessageRole.MONITOR },
            "строка монитора ушла в историю запроса к модели"
        )
        assertTrue(history.any { it.role == MessageRole.USER })
        assertTrue(history.any { it.role == MessageRole.ASSISTANT })
    }

    /**
     * Шапка закреплённого чата спрашивает сутки: маршрут получает `hours = 24`,
     * а снимок с сервера ложится в состояние вместе с числом покрытых часов — по нему
     * карточка честно говорит, что наблюдений за сутки не хватило.
     */
    @Test
    fun dayChangeIsReadForTheHeader() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()

        repository.loadCurrencyChange()

        val read = server.requests.last { it.url.encodedPath == "/v1/currency/change" }
        assertEquals("24", read.url.parameters["hours"], "шапка спросила не сутки")
        val feed = assertNotNull(repository.currencyChange.value, "снимок изменения не пришёл")
        assertEquals(24, feed.hours)
        assertEquals(23, feed.hoursCovered)
        assertNull(repository.currencyChangeError.value, "успешное чтение оставило ошибку")
    }

    /**
     * Отказ сервера виден строкой, а уже прочитанный снимок не стирается: показать
     * «изменений нет» там, где они прочитаны, значило бы соврать. Сводка часа пользуется
     * тем же маршрутом и теми же строками — причина у них общая.
     */
    @Test
    fun changeFailureKeepsTheSnapshotAndShowsTheReason() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()
        repository.loadCurrencyChange()
        val read = assertNotNull(repository.currencyChange.value)

        server.change = CHANGE_ERROR to HttpStatusCode.BadGateway
        repository.loadCurrencyChange()

        assertEquals("нет истории курсов", repository.currencyChangeError.value)
        assertEquals(read, repository.currencyChange.value, "прочитанный снимок стёрт отказом")
    }

    /**
     * Валюта без наблюдений: прочерк и слова, а не «без изменения».
     *
     * Так выглядит только что установленное приложение: часовых сводок ещё нет, и сервер
     * отвечает прочерками. «Без изменения» на их месте читалось бы как «курс стоял на месте»,
     * хотя курса в этом окне никто не наблюдал.
     */
    @Test
    fun currencyWithoutObservationsIsPrintedAsAbsent() {
        val item = CurrencyChangeItem(currency = "EUR", samples = 0, hours = 0)

        assertEquals("EUR — (наблюдений нет)", item.line())
    }

    /**
     * Пауза между ударами ведёт к точке минутной сетки, а не отмеряется минутой после работы.
     *
     * Момент времени берётся у монитора на каждом шаге цикла (начало сетки, удар, расчёт
     * паузы), поэтому записанные паузы показывают, что именно отмеряет цикл: удар в полсекунды
     * оставляет 59,3 с до следующей минуты, а удар в 70 с перекрывает свою точку — и пауза
     * ведёт к следующей, не сдвигая сетку и не повторяя удар немедленно.
     */
    @Test
    fun pauseGoesToTheNextGridPoint() = runBlocking {
        val server = FakeCurrencyServer()
        val repository = server.repository()

        assertEquals(
            59_300L,
            firstPause(this, repository, listOf(FIRST_TICK, FIRST_TICK + 500, FIRST_TICK + 700)),
            "пауза должна вести к точке сетки, а не отмеряться минутой после работы"
        )
        assertEquals(
            49_900L,
            firstPause(this, repository, listOf(FIRST_TICK, FIRST_TICK + 70_000, FIRST_TICK + 70_100)),
            "удар длиннее минуты должен пропускать перекрытую точку сетки, а не сдвигать её"
        )
    }

    /**
     * Первая пауза цикла за заданные моменты времени: моментов ровно на один удар —
     * начало сетки, сам удар и расчёт паузы после него.
     */
    private suspend fun firstPause(
        scope: CoroutineScope,
        repository: ChatRepository,
        moments: List<Long>
    ): Long {
        val clock = ArrayDeque(moments)
        val pauses = Channel<Long>(Channel.UNLIMITED)
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val job = scope.launch {
            repository.runCurrencyMonitor(
                now = { clock.removeFirst() },
                wait = {
                    pauses.send(it)
                    gate.receive()
                }
            )
        }
        val pause = pauses.receive()
        job.cancel()
        return pause
    }

    /**
     * Медленный сервер не останавливает ленту: чтение курсов срывается по сроку, и удар
     * заканчивается, не дождавшись ответа.
     *
     * Срок в приложении — двадцать секунд ([ChatRepository.CURRENCY_READ_TIMEOUT_MS]),
     * а здесь он задан сотней миллисекунд: проверке важно поведение, а не число — чтение
     * возвращает «курсов нет» вместо вечного ожидания. Без срока зависший сервер держал бы
     * удар, и следующий удар монитора не наступал бы вовсе.
     */
    @Test
    fun slowServerEndsTheReadByDeadline() = runBlocking {
        val store = InMemoryChatStore()
        val client = HttpClient(
            MockEngine {
                delay(SLOW_ANSWER_MS)
                respond(
                    CURRENCY_SNAPSHOT,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(JSON) }
        }
        val repository = ChatRepository(BASE_URL, store, client, currencyReadTimeoutMs = READ_DEADLINE_MS)

        assertNull(
            repository.loadCurrencySnapshot(),
            "чтение по сроку должно вернуть отсутствие курсов, а не дождаться медленного ответа"
        )
    }

    /**
     * Подставной сервер курсов: на свои маршруты отвечает заданными телами, на остальные —
     * обычным ответом агента. Ответы памяти, профиля, задачи и инвариантов намеренно не
     * разводятся: у всех этих моделей есть умолчания, а неизвестные поля отбрасываются,
     * поэтому один и тот же ответ разбирается любой из них — и чат открывается в тесте
     * без отдельной обвязки на каждый маршрут.
     */
    private class FakeCurrencyServer {
        val store = InMemoryChatStore()
        val requests = mutableListOf<HttpRequestData>()

        /** Ответ `GET /v1/currency`. */
        var snapshot: Pair<String, HttpStatusCode> = CURRENCY_SNAPSHOT to HttpStatusCode.OK

        /** Ответ `GET /v1/currency/change?hours=24`. */
        var change: Pair<String, HttpStatusCode> = DAY_CHANGE to HttpStatusCode.OK

        /** Ответ `GET /v1/currency/change?hours=1` — сводке часа. */
        var hourChange: Pair<String, HttpStatusCode> = HOUR_CHANGE to HttpStatusCode.OK

        val client: HttpClient = HttpClient(
            MockEngine { request ->
                requests += request
                val (body, status) = when (request.url.encodedPath) {
                    "/v1/currency" -> snapshot
                    "/v1/currency/change" ->
                        if (request.url.parameters["hours"] == "1") hourChange else change
                    "/v1/chat/completions" -> CHAT_RESPONSE to HttpStatusCode.OK
                    else -> PLAIN_REPLY to HttpStatusCode.OK
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) {
            install(ContentNegotiation) { json(JSON) }
        }

        fun repository(): ChatRepository = ChatRepository(BASE_URL, store, client)
    }

    /**
     * Монитор под управлением теста: время удара задаёт тест, а пауза между ударами —
     * не настоящая минута, а ожидание команды [tick]. Так удары проходят через тот же
     * цикл, что и в приложении ([ChatRepository.runCurrencyMonitor]), но тест не спит
     * ни минуты и сам решает, какой сейчас час.
     *
     * Удар, который монитор делает сразу при запуске, отмечается в [awaitStart]: цикл
     * всегда сообщает о конце удара, поэтому тест не угадывает момент, а ждёт его.
     */
    private class DrivenMonitor(scope: CoroutineScope, repository: ChatRepository, start: Long) {
        private val gate = Channel<Unit>(Channel.UNLIMITED)
        private val beat = Channel<Unit>(Channel.UNLIMITED)

        /** Момент следующего удара; тест меняет его перед [tick], чтобы сменить час. */
        var now: Long = start

        private val job: Job = scope.launch {
            repository.runCurrencyMonitor(
                now = { now },
                wait = { _ ->
                    beat.send(Unit)
                    gate.receive()
                }
            )
        }

        /** Ждёт конца удара, сделанного сразу при запуске. */
        suspend fun awaitStart() {
            beat.receive()
        }

        /** Пропускает ещё один удар за момент [now]. */
        suspend fun tick() {
            gate.send(Unit)
            beat.receive()
        }

        /** Останавливает монитор: своего конца у бесконечного цикла нет. */
        fun stop() {
            job.cancel()
        }
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Ответ агента: его текст попадает в историю чата. */
        const val REPLY = "Ответ агента"

        /** Сколько последних сообщений держит лента закреплённого чата. */
        const val FEED_LIMIT = 240

        /** Миллисекунд в часе. */
        const val HOUR_MS = 3_600_000L

        /** Срок чтения курсов в проверке срока: короткий, чтобы проверка не ждала секунды. */
        const val READ_DEADLINE_MS = 100L

        /** Ответ подставного сервера в проверке срока: медленнее срока, поэтому он и срабатывает. */
        const val SLOW_ANSWER_MS = 300L

        /** 12:05 UTC: номер часа 492 — это 12 часов суток, минута — пятая. */
        val FIRST_TICK = 492L * HOUR_MS + 5 * 60_000L

        /** Курсы с меткой сервера: метка и есть время строки ленты. */
        const val CURRENCY_SNAPSHOT =
            """{"updatedAt":"2026-09-28T12:05:00Z","rates":{"EUR":95.8709,"USD":84.3414,"GEL":32.1693}}"""

        /**
         * Изменение за час: рост у евро, падение у доллара и «проценты не посчитаны»
         * у лари — так в тесте видны все три вида строки.
         */
        val HOUR_CHANGE = """
            {"hours":1,"hoursCovered":1,
             "from":"2026-09-28T12:00:00Z","to":"2026-09-28T13:00:00Z",
             "currencies":[
               {"currency":"EUR","firstRate":95.5,"lastRate":96.5,"change":1.5,
                "changePercent":1.5789,"minRate":95.5,"maxRate":96.5,"averageRate":96.0,
                "samples":60,"hours":1},
               {"currency":"USD","firstRate":84.3414,"lastRate":84.2,"change":-0.1414,
                "changePercent":-0.168,"minRate":84.2,"maxRate":84.3414,"averageRate":84.27,
                "samples":60,"hours":1},
               {"currency":"GEL","firstRate":null,"lastRate":32.1693,"change":null,
                "changePercent":null,"minRate":null,"maxRate":null,"averageRate":null,
                "samples":0,"hours":0}]}
        """.trimIndent()

        /** Изменение за сутки: наблюдений меньше запрошенного окна — так и должно быть у нового приложения. */
        val DAY_CHANGE = """
            {"hours":24,"hoursCovered":23,
             "from":"2026-09-27T16:00:00Z","to":"2026-09-28T15:00:00Z",
             "currencies":[
               {"currency":"EUR","firstRate":95.87,"lastRate":96.5,"change":0.63,
                "changePercent":0.66,"minRate":95.5,"maxRate":96.5,"averageRate":96.0,
                "samples":140,"hours":23},
               {"currency":"USD","firstRate":84.3414,"lastRate":84.2,"change":-0.1414,
                "changePercent":-0.168,"minRate":84.2,"maxRate":84.3414,"averageRate":84.27,
                "samples":140,"hours":23},
               {"currency":"GEL","firstRate":32.0,"lastRate":32.1693,"change":0.1693,
                "changePercent":0.53,"minRate":32.0,"maxRate":32.1693,"averageRate":32.1,
                "samples":140,"hours":23}]}
        """.trimIndent()

        /** Отказ маршрута изменения курсов: так сервер отвечает, когда истории ещё нет. */
        const val CHANGE_ERROR = """{"error":"нет истории курсов"}"""

        /** Обычный ответ агента: вызовов инструментов в нём нет. */
        val CHAT_RESPONSE = """{"success":true,"reply":"$REPLY"}"""

        /** Ответ на служебные чтения чата: у всех их моделей есть умолчания. */
        val PLAIN_REPLY = CHAT_RESPONSE

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Запросы к модели с разобранным телом; проверка связи сюда не попадает. */
        fun chatRequests(requests: List<HttpRequestData>): List<ChatRequest> = requests
            .filter { it.url.encodedPath == "/v1/chat/completions" }
            .map { JSON.decodeFromString<ChatRequest>((it.body as TextContent).text) }
    }
}
