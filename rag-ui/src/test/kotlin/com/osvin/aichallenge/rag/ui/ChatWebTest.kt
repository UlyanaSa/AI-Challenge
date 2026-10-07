package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.rag.chat.ChatScenarios
import com.osvin.aichallenge.rag.chat.ChatSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Ответ подставной модели по книге: свободный текст, под которым стоит утверждение с цитатой. */
private const val CHAT_ANSWER_TEXT = "По фрагментам книги: так сказано в первой главе."

/**
 * Тесты веб-части дня 25: реплики, память задачи, сценарий и недоступность — на настоящем индексе,
 * но с подставной моделью.
 *
 * Проверяется то, чего не видно в отчёте движка: что страница отдаёт разговор состоянием целиком,
 * что источник собран из метаданных найденного чанка, а не из текста модели, что память задачи
 * доезжает до панели, что сценарий идёт в фоне и оставляет на диске транскрипт и файл с приговором,
 * и что без ключа или индекса чат называет причину, а не молчит пустой лентой.
 *
 * Подставная модель отвечает на три служебных вызова чата разными заготовками и различает их
 * по системному сообщению: память задачи — своим заданием, переписывание запроса — своим, ответ —
 * первым системным сообщением чата. Цитата в ответе берётся из текста первого фрагмента, который
 * видела модель: так проверка цитат дня 24 проходит по-настоящему, а не по подсказке теста.
 *
 * Настройки чата задаются явно, а не берутся по умолчанию: нулевой порог делает контекст достаточным
 * всегда, и прогон остаётся детерминированным — числа ответов не зависят от того, насколько близко
 * хеширование подобрало чанки. Индекс строится хешированием, сеть не нужна; корпус — тот же PDF дня,
 * что у остальных прогонов: без него тест не имеет предмета и пропускается, как и в `:rag`.
 */
class ChatWebTest {

    /** Память задачи, которую подставная модель возвращает на каждой реплике. */
    private val goal = "разобрать первую главу"

    /**
     * Подставная модель чата: различает три служебных вызова по системному сообщению.
     *
     * Ответ по книге строит цитату из текста первого фрагмента, который пришёл в промпте: иначе
     * проверка цитат ([com.osvin.aichallenge.rag.Citations]) отбросила бы утверждение, и ход вышел бы
     * отказом. Пустой контекст распознаётся по отсутствию фрагмента — тогда отвечает по памяти задачи
     * видом `dialogue`, как это делает живая модель.
     */
    private class ChatFakeLlm(
        private val goal: String,
        private val constraints: List<String> = listOf("отвечай только по первой главе"),
        private val terms: List<Pair<String, String>> = listOf("сын" to "Пётр Степанович"),
        private val query: String = "Степан Трофимович первая глава"
    ) : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            val system = request.messages.firstOrNull { it.role == "system" }?.content.orEmpty()
            val reply = when {
                system.startsWith("Ты ведёшь память задачи") -> memoryJson()
                system.startsWith("Ты готовишь поисковый запрос") -> query
                else -> answerJson(request)
            }
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(
                        ChatMessage(role = "assistant", content = reply),
                        finishReason = "stop"
                    )
                ),
                usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
            )
        }

        private fun memoryJson(): String = """{"goal":${json(goal)},"clarifications":[],""" +
            """"constraints":[${constraints.joinToString(",") { json(it) }}],""" +
            """"terms":[${terms.joinToString(",") { term -> """{"term":${json(term.first)},"meaning":${json(term.second)}}""" }}]}"""

        /** Ответ по книге: утверждение с цитатой из первого фрагмента либо ответ о разговоре. */
        private fun answerJson(request: DeepSeekRequest): String {
            val prompt = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            val fragment = fragmentText(prompt)
                ?: return """{"kind":"dialogue","answer":"Отвечаю по памяти задачи.","insufficient":false,"claims":[]}"""
            val quote = fragment.take(80).trim()
            return """{"kind":"base","answer":${json(CHAT_ANSWER_TEXT)},"insufficient":false,"claims":""" +
                """[{"claim":"Сведения первой главы","fragment":1,"quote":${json(quote)}}]}"""
        }

        /** Текст первого фрагмента в промпте: строки после подписи `[1] …` и до следующего фрагмента. */
        private fun fragmentText(prompt: String): String? {
            val start = prompt.indexOf("[1] ")
            if (start < 0) return null
            val lineEnd = prompt.indexOf('\n', start)
            if (lineEnd < 0) return null
            // Конец — начало следующего фрагмента или строки вопроса, что раньше: у последнего
            // фрагмента следующего нет, и без второй границы в текст попал бы сам вопрос.
            val end = listOf(prompt.indexOf("\n\n[", lineEnd), prompt.indexOf("\n\nВопрос: ", lineEnd))
                .filter { it >= 0 }
                .minOrNull() ?: prompt.length
            val text = prompt.substring(lineEnd + 1, end).trim()
            return text.ifEmpty { null }
        }

        /** Строка в JSON: кавычки, обратные слэши и переводы строк — иначе ответ не разберётся. */
        private fun json(text: String): String = buildString {
            append('"')
            text.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append(' ') else append(char)
                }
            }
            append('"')
        }
    }

    private fun hashing() = Embedding(
        provider = HashingEmbeddingProvider(),
        kind = "hashing",
        model = null,
        note = "тест",
        features = null
    )

    /**
     * Разговор теста идёт с нулевым порогом: контекст достаточен всегда, и ответ по базе не зависит
     * от того, насколько близко хеширование подобрало чанки.
     */
    private fun chatSettings() = ChatSettings(
        threshold = 0.0,
        retrievalTopK = 6,
        finalTopK = 3,
        rerank = ChatSettings.RERANK_NONE
    )

    private fun session(
        dir: Path,
        scope: CoroutineScope,
        llm: LlmClient,
        apiKey: String? = "test-key"
    ) = RagSession(
        workDir = dir,
        scope = scope,
        embedding = hashing(),
        strategy = ChunkingStrategyType.STRUCTURAL,
        model = "test-model",
        apiKey = apiKey,
        llm = { llm },
        chatSettings = chatSettings()
    )

    /** Собирает индекс: чат ищет по готовому файлу, и без него разговор недоступен по построению. */
    private suspend fun buildIndex(session: RagSession) {
        assertEquals(StartOutcome.Accepted, session.rebuildIndex())
        withTimeout(120_000) {
            while (session.state().index.state != IndexDto.READY) delay(50)
        }
    }

    /** Ждёт конца фонового сценария: он идёт в фоне, и состояние приходит через опрос. */
    private suspend fun awaitChat(session: RagSession): ChatStateDto = withTimeout(180_000) {
        while (session.chatState().busy) delay(50)
        session.chatState()
    }

    @Test
    fun `чат отвечает по базе и держит память задачи`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-chat")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, ChatFakeLlm(goal))
        if (session.setup().base.chars == 0) return@runBlocking
        buildIndex(session)

        val state = session.chatSend("Кем был Степан Трофимович?")

        assertTrue(state.available, "с ключом и индексом чат доступен")
        assertEquals(1, state.turns.size)
        val turn = state.turns.single()
        assertEquals("по базе", turn.kindTitle, "ответ по фрагментам, а не по памяти задачи")
        assertTrue(turn.answered, "реплика получила ответ")

        // Источник собран из метаданных найденного чанка: `chunk_id` в тексте ответа модели не звучал,
        // поэтому появиться в источнике из её ответа он не мог. Такой тест падает, если источник
        // собирается из текста модели, а не из чанка.
        assertTrue(turn.sources.isNotEmpty(), "у ответа по базе есть источник")
        val source = turn.sources.first()
        assertTrue(source.chunkId.isNotBlank(), "у источника есть идентификатор чанка")
        assertTrue(source.source.isNotBlank(), "у источника назван файл корпуса")
        assertFalse(turn.answer!!.contains(source.chunkId), "chunk_id пришёл из метаданных, а не из ответа модели")
        assertEquals(1, source.fragment, "источник связан с номером фрагмента из утверждения")

        // Память задачи уходит странице полями, а не текстом: если её потерять, панель опустеет,
        // и разговор перестанет держать предмет — это и защищает проверка ниже.
        assertEquals(goal, state.memory.goal, "цель разговора видна в состоянии")
        assertEquals(listOf("отвечай только по первой главе"), state.memory.constraints)
        assertEquals(listOf(ChatTermDto("сын", "Пётр Степанович")), state.memory.terms)
        assertFalse(state.memory.empty)
        assertEquals(1, requireNotNull(state.summary).turns)
        assertTrue(requireNotNull(state.summary).sourcesEverywhere, "у ответа назван источник")

        assertTrue(Files.exists(dir.resolve(RagSession.CHAT_FILE)), "транскрипт разговора записан")
        assertTrue(Files.exists(dir.resolve(RagSession.CHAT_LOG_FILE)), "лог разговора записан")
        scope.cancel()
    }

    @Test
    fun `очистка разговора стирает ленту и память`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-chat-reset")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, ChatFakeLlm(goal))
        if (session.setup().base.chars == 0) return@runBlocking
        buildIndex(session)

        val before = session.chatSend("Кем был Степан Трофимович?")
        assertTrue(before.turns.isNotEmpty(), "разговор начат")
        assertEquals(goal, before.memory.goal)

        val cleared = session.chatReset()

        assertTrue(cleared.turns.isEmpty(), "лента очищена")
        assertTrue(cleared.memory.empty, "память задачи очищена вместе с лентой")
        assertEquals(null, cleared.memory.goal)
        assertEquals(0, requireNotNull(cleared.summary).turns)
        assertTrue(cleared.scenario == null, "итог сценария тоже стёрт")
        // Транскрипт на диске обязан описывать текущий разговор, а не стёртый: файл переписан пустым.
        val transcript = Files.readString(dir.resolve(RagSession.CHAT_FILE))
        assertFalse(transcript.contains("Степан Трофимович"), "в файле не осталось вопросов стёртого разговора")
        scope.cancel()
    }

    @Test
    fun `сценарий завершается приговором и пишет транскрипт`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-chat-scenario")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, ChatFakeLlm(goal))
        if (session.setup().base.chars == 0) return@runBlocking
        buildIndex(session)

        val started = session.chatScenario("scene-1")
        assertTrue(started.busy, "сценарий идёт в фоне, ответ на запуск не ждёт его конца")

        val finished = awaitChat(session)
        assertFalse(finished.busy, "сценарий закончился")
        val scenario = requireNotNull(finished.scenario)
        assertEquals("scene-1", scenario.name)
        assertTrue(scenario.verdict.isNotEmpty(), "состояние отдаёт приговор")
        assertEquals(ChatScenarios.byName("scene-1")!!.length, scenario.checks.size, "проверка на каждый шаг сценария")
        assertEquals(ChatScenarios.byName("scene-1")!!.length, finished.turns.size, "лента выросла на все реплики сценария")
        assertTrue(
            Files.exists(dir.resolve("chat-scene-1.md")),
            "файл сценария записан под именем сценария"
        )
        assertTrue(Files.exists(dir.resolve(RagSession.CHAT_FILE)), "транскрипт разговора записан")
        scope.cancel()
    }

    @Test
    fun `без ключа чат недоступен с причиной`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-chat-nokey")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, ChatFakeLlm(goal), apiKey = null)

        val state = session.chatState()

        assertFalse(state.available, "без ключа модель не отвечает, и чат недоступен")
        assertEquals(RagSession.NO_KEY_MESSAGE, state.note, "состояние называет причину, а не молчит пустой лентой")
        assertTrue(state.turns.isEmpty())
        scope.cancel()
    }

    @Test
    fun `чат без индекса называет причину`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-chat-noindex")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, ChatFakeLlm(goal))
        if (session.setup().base.chars == 0) return@runBlocking

        val state = session.chatState()

        assertFalse(state.available, "без индекса искать не по чему")
        assertEquals(RagSession.CHAT_INDEX_MESSAGE, state.note, "причина названа: индекс не построен")
        scope.cancel()
    }
}
