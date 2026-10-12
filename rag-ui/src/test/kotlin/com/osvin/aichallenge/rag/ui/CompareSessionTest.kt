package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.rag.Corpus
import com.osvin.aichallenge.rag.RagIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Тесты сравнения моделей (день 28): две подставные модели на настоящем индексе.
 *
 * Проверяется то, чего не видно в отчётах прогона, — арифметика сравнения. Качество читается
 * из метрик дня 23, но их здесь две; скорость складывается по ответам; а стабильность — единственное
 * место, где страница считает сама, и ошибка в ней выглядит правдоподобно: сравнение повторов
 * «совпало» с «оценка изменилась» перепутать легко, а разница между ними — это разница между
 * «модель переписала ответ» и «модель поменяла вердикт».
 *
 * Ответы моделей заданы заранее и различаются по повторам: первый повтор отвечает текстом с фактами
 * набора (оценка 2), второй — текстом без них (оценка 0). Так числа стабильности известны до прогона,
 * и тест проверяет именно счёт, а не то, как ответила бы живая модель.
 *
 * Индекс строится хешированием (сеть не нужна), корпус — встроенный PDF дня: без него проверять
 * нечего, и тест пропускается, как и в `:rag`.
 */
class CompareSessionTest {

    /** Ответ с обоими фактами первого вопроса набора: оценка 2 из 2. */
    private val answerWithFacts = "Остался пятилетний сын; жена скончалась в Париже."

    /** Ответ без фактов набора: оценка 0 из 2. */
    private val answerWithoutFacts = "Не знаю."

    private val settings = RunSettings(
        baselineTopK = 3,
        retrievalTopK = 6,
        finalTopK = 3,
        threshold = 0.0,
        rewrite = "none",
        rerank = "heuristic"
    )

    /**
     * Подставная модель, которая отвечает по-разному в разных повторах.
     *
     * Повторы различаются номером обращения, а не текстом запроса: так тест говорит «второй прогон
     * ответил иначе» независимо от того, что именно ушло в модель, — а это и есть условие, при
     * котором проверяется стабильность.
     *
     * @param callsPerRepeat Сколько обращений делает один повтор: по три на вопрос (без базы,
     *        базовый, улучшенный).
     * @param first Текст первого повтора.
     * @param second Текст второго повтора и всех следующих.
     */
    private class RepeatLlm(
        private val callsPerRepeat: Int,
        private val first: String,
        private val second: String
    ) : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()
        private var calls = 0

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            val text = if (calls < callsPerRepeat) first else second
            calls++
            return DeepSeekResponse(
                choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", text), finishReason = "stop")),
                usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
            )
        }
    }

    /** Модель, которая не отвечает: провайдер недоступен или API отказал. */
    private class FailingLlm(private val reason: String) : LlmClient {
        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse = error(reason)
    }

    /**
     * Модель, на которой ломается не вызов, а сама загрузка класса.
     *
     * `Error` — не `Exception`, и это не придирка: именно так выглядит прогон, если сборка
     * перезаписала классы под работающей страницей (`NoClassDefFoundError` на классе, который JVM
     * ещё не загрузила). Такой отказ обязан быть назван, а не проглочен.
     */
    private class BrokenLlm : LlmClient {
        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse =
            throw NoClassDefFoundError("com/osvin/aichallenge/models/ChatMessage")
    }

    private fun hashing() = Embedding(
        provider = HashingEmbeddingProvider(),
        kind = "hashing",
        model = null,
        note = "тест",
        features = null
    )

    /** Индекс готового файла: сравнение своих индексов не собирает и работает по готовому. */
    private suspend fun index(dir: Path, base: com.osvin.aichallenge.rag.DayBase) {
        RagIndex(dir, hashing(), ChunkingStrategyType.STRUCTURAL).build(base)
    }

    private fun session(
        dir: Path,
        scope: CoroutineScope,
        apiKey: String?,
        clients: Map<LlmProvider, LlmClient>
    ) = CompareSession(
        workDir = dir,
        scope = scope,
        embedding = hashing(),
        strategy = ChunkingStrategyType.STRUCTURAL,
        cloudModel = "test-cloud",
        localModel = "test-local",
        apiKey = apiKey,
        client = { provider, _ -> clients.getValue(provider) }
    )

    /** Ждёт конца сравнения: работа идёт в фоне, состояние приходит через опрос. */
    private suspend fun await(session: CompareSession): CompareStateDto = withTimeout(120_000) {
        while (session.state().state == StateDto.RUNNING) delay(50)
        session.state()
    }

    @Test
    fun `сравнение считает качество, скорость и стабильность по двум моделям`() = runBlocking {
        val dir = Files.createTempDirectory("rag-compare")
        val scope = CoroutineScope(Dispatchers.Default)
        val base = Corpus.read(dir) ?: return@runBlocking
        index(dir, base)

        // Второй повтор отвечает текстом без фактов: у обеих моделей первый повтор даёт оценку 2,
        // второй — 0, и это известно до прогона.
        val local = RepeatLlm(callsPerRepeat = 3, first = answerWithFacts, second = answerWithoutFacts)
        val cloud = RepeatLlm(callsPerRepeat = 3, first = answerWithFacts, second = answerWithoutFacts)
        val session = session(
            dir = dir,
            scope = scope,
            apiKey = "test-key",
            clients = mapOf(LlmProvider.LOCAL to local, LlmProvider.CLOUD to cloud)
        )

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), repeats = 2, settings = settings))
        val state = await(session)

        assertEquals(StateDto.DONE, state.state)
        assertEquals(4, state.total, "две модели по два повтора")
        assertEquals(4, state.done)
        assertEquals(listOf("q01"), state.questions)
        assertEquals(2, state.models.size)
        assertTrue(state.models.all { it.available }, "обе модели доступны: ключ передан")

        for (model in state.models) {
            assertEquals(2, model.repeats.size, "у каждой модели по два повтора")
            val first = model.repeats.first()
            val second = model.repeats.last()
            // Качество: первый повтор ответил верно улучшенным режимом, второй — нет.
            assertEquals(2.0, first.quality.score)
            assertEquals(2, first.quality.facts, "названы оба факта вопроса")
            assertEquals(2, first.quality.factsTotal)
            assertEquals(1, first.quality.questions)
            assertEquals(0.0, second.quality.score)
            // Скорость: одно обращение улучшенного режима и токены, которые сообщил подставной API.
            assertEquals(1, first.speed.answers, "считается только улучшенный режим")
            assertEquals(10, first.speed.improved.prompt, "один вопрос — 10 токенов запроса")
            assertEquals(20, first.speed.improved.completion, "ответ улучшенного режима — 20 токенов")
            assertEquals(1, first.speed.improved.reported, "расход назвала модель")
            assertTrue(first.speed.answerMillis >= 0)
            // Ответы целиком: сравнение показывает улучшенный RAG, и только его.
            val question = first.questions.first { it.id == "q01" }
            assertEquals(2, question.improved?.score)
            assertEquals(answerWithFacts, question.improved?.text)

            // Стабильность: сравнение идёт по улучшенному режиму — один вопрос, один ответ.
            val stability = assertNotNull(model.stability)
            assertEquals(1, stability.compared, "один вопрос улучшенного режима")
            assertEquals(0, stability.identical)
            assertEquals(1, stability.changed)
            assertEquals(1, stability.scoreChanged)
            assertEquals(0, stability.scoreUp)
            assertEquals(1, stability.scoreDown, "второй повтор ответил хуже")
            assertEquals(2.0, stability.averageScoreFirst)
            assertEquals(0.0, stability.averageScoreLast)
            assertTrue(stability.averageSimilarity < 1.0, "тексты разные")
            assertEquals(1, stability.pairs.size, "расхождение названо с обоими текстами")
            assertTrue(stability.pairs.all { it.before == answerWithFacts && it.after == answerWithoutFacts })
        }

        // Отчёты сравнения — те же файлы дня 23, что у прогона, но со своим именем: они не затирают
        // отчёты прогона страницы.
        val reportDir = dir.resolve(CompareSession.COMPARE_DIR)
        assertTrue(Files.exists(reportDir.resolve("local-repeat-1.md")), "отчёт локальной модели записан")
        assertTrue(Files.exists(reportDir.resolve("cloud-repeat-2.md")), "отчёт облачной модели записан")
        assertEquals(4, state.reports.size)
        assertFalse(Files.exists(dir.resolve(RagSession.REPORT_FILE)), "отчёты прогона сравнение не пишет")
        scope.cancel()
    }

    @Test
    fun `без ключа облачная модель не участвует и её отсутствие названо`() = runBlocking {
        val dir = Files.createTempDirectory("rag-compare-nokey")
        val scope = CoroutineScope(Dispatchers.Default)
        val base = Corpus.read(dir) ?: return@runBlocking
        index(dir, base)

        val local = RepeatLlm(callsPerRepeat = 3, first = answerWithFacts, second = answerWithoutFacts)
        val session = session(
            dir = dir,
            scope = scope,
            apiKey = null,
            clients = mapOf(LlmProvider.LOCAL to local, LlmProvider.CLOUD to FailingLlm("ключ не передан"))
        )

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), repeats = 1, settings = settings))
        val state = await(session)

        assertEquals(StateDto.DONE, state.state)
        assertEquals(1, state.total, "план сравнения — только доступные модели")
        val localModel = state.models.first { it.provider == "local" }
        val cloudModel = state.models.first { it.provider == "cloud" }
        assertTrue(localModel.available)
        assertFalse(cloudModel.available)
        assertNotNull(cloudModel.note, "почему облачной модели нет — словами")
        assertTrue(cloudModel.repeats.isEmpty(), "недоступная модель не прогоняется")
        assertEquals(1, localModel.repeats.size)
        // Без повторов стабильности нет, и это отдельное состояние, а не нули: ноль читался бы
        // как «модель нестабильна».
        assertNull(localModel.stability)
        scope.cancel()
    }

    @Test
    fun `провал модели называется на её уровне, а не тонет в пустых колонках`() = runBlocking {
        val dir = Files.createTempDirectory("rag-compare-fail")
        val scope = CoroutineScope(Dispatchers.Default)
        val base = Corpus.read(dir) ?: return@runBlocking
        index(dir, base)

        val session = session(
            dir = dir,
            scope = scope,
            apiKey = "test-key",
            clients = mapOf(
                LlmProvider.LOCAL to FailingLlm("демон Ollama не отвечает"),
                LlmProvider.CLOUD to RepeatLlm(callsPerRepeat = 3, first = answerWithFacts, second = answerWithFacts)
            )
        )

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), repeats = 1, settings = settings))
        val state = await(session)

        assertEquals(StateDto.DONE, state.state, "отказ модели — не отказ сравнения")
        val failed = state.models.first { it.provider == "local" }
        assertTrue(failed.available, "модель есть — она просто не ответила")
        val failure = assertNotNull(failed.failure)
        assertTrue(failure.contains("демон Ollama не отвечает"), "причина отказа названа: $failure")
        // Ответы всё равно показаны, каждый со своей причиной: страница рисует отказ там, где он был.
        val answer = failed.repeats.single().questions.single().improved
        assertEquals(ModeDto.FAILED, answer?.state)
        assertTrue(answer?.error?.contains("демон Ollama не отвечает") == true, "причина отказа в самом ответе")
        assertNull(state.models.first { it.provider == "cloud" }.failure, "вторая модель ответила")
        scope.cancel()
    }

    @Test
    fun `поломка на уровне JVM не выглядит как состоявшееся сравнение`() = runBlocking {
        val dir = Files.createTempDirectory("rag-compare-broken")
        val scope = CoroutineScope(Dispatchers.Default)
        val base = Corpus.read(dir) ?: return@runBlocking
        index(dir, base)

        val session = session(
            dir = dir,
            scope = scope,
            apiKey = null,
            clients = mapOf(LlmProvider.LOCAL to BrokenLlm(), LlmProvider.CLOUD to BrokenLlm())
        )

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), repeats = 1, settings = settings))
        val state = await(session)

        // Ключевое: `done` и `total` не считаются успехом сами по себе — при `Error` сравнение
        // обязано быть провалом. Иначе страница показывает «готово: прогонов 0 из N» и пустые
        // колонки, и это читается как результат сравнения, которого не было.
        assertEquals(StateDto.FAILED, state.state)
        val error = assertNotNull(state.error)
        assertTrue(error.contains("ChatMessage"), "причина отказа названа: $error")
        assertEquals(0, state.done)
        scope.cancel()
    }
}
