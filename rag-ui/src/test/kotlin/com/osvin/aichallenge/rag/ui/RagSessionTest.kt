package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Тесты страницы дня 23: прогон на настоящем индексе, но с подставной моделью.
 *
 * Проверяется то, чего не видно в отчёте: что страница показывает три конвейера и показывает их
 * верно — базовый берёт Top-K дня 22, улучшенный уходит в контекст с финальным Top-K и оставляет
 * трейс этапов, а режим без базы не ищет вовсе. Ошибка здесь не сломала бы ни один прогон: она
 * нарисовала бы путь запроса, которого не было, и числа сравнения читались бы по чужому пути.
 *
 * День 24 проверяется тем же прогоном: grounded-этап идёт на той же выдаче, что улучшенный режим,
 * берёт тот же порог и пишет свои файлы, не переписывая отчёты трёх режимов. Подставная модель
 * отвечает свободным текстом, поэтому цитат в её ответе нет: проверяется не качество цитат — для
 * этого нужен живой прогон, — а то, что этап выполнился и назвал исход словами `:rag`.
 *
 * Настройки прогона задаются явно, а не берутся по умолчанию: числа трейса тогда известны заранее,
 * и видно, что поля настроек действительно управляют конвейером. Переписывание выключено, а второй
 * этап — эвристический: так подставная модель отвечает только на вопросы, и прогон остаётся
 * детерминированным.
 *
 * Индекс строится хешированием (сеть не нужна), корпус — тот же PDF дня, что у остальных прогонов:
 * без него тест не имеет предмета и пропускается, как и в `:rag`.
 */
class RagSessionTest {

    /** Ответ, в котором есть все факты первого вопроса набора. */
    private val answer = "Остался пятилетний сын; жена скончалась в Париже."

    /** Настройки прогона теста: шесть кандидатов поиска, три — в контекст, без переписывания. */
    private val settings = RunSettings(
        baselineTopK = 3,
        retrievalTopK = 6,
        finalTopK = 3,
        threshold = 0.0,
        rewrite = "none",
        rerank = "heuristic"
    )

    /** Подставная модель: записывает запросы и отвечает заготовкой — сеть в тестах не нужна. */
    private class FakeLlm(private val text: String, private val pauseMillis: Long = 0) : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            if (pauseMillis > 0) delay(pauseMillis)
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(
                        ChatMessage(role = "assistant", content = text),
                        finishReason = "stop"
                    )
                ),
                usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
            )
        }
    }

    private fun hashing() = Embedding(
        provider = HashingEmbeddingProvider(),
        kind = "hashing",
        model = null,
        note = "тест",
        features = null
    )

    private fun session(dir: Path, scope: CoroutineScope, llm: LlmClient) = RagSession(
        workDir = dir,
        scope = scope,
        embedding = hashing(),
        strategy = ChunkingStrategyType.STRUCTURAL,
        model = "test-model",
        apiKey = "test-key",
        llm = { llm }
    )

    /** Ждёт конца прогона: работа идёт в фоне, и состояние приходит через опрос. */
    private suspend fun await(session: RagSession): StateDto = withTimeout(120_000) {
        while (session.state().state == StateDto.RUNNING) delay(50)
        session.state()
    }

    @Test
    fun `прогон вопроса заполняет три конвейера и пишет отчёты`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, FakeLlm(answer))
        // Корпус дня берётся из встроенного PDF: если его нет, проверять нечего.
        if (session.setup().base.chars == 0) return@runBlocking

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), settings))
        val state = await(session)

        assertEquals(StateDto.DONE, state.state)
        assertEquals(1, state.results.size)
        val result = state.results.single()
        assertEquals("q01", result.id)

        // Настройки прогона доехали до состояния: ими объясняются числа и трейс.
        val config = requireNotNull(state.config)
        assertEquals(3, config.baselineTopK)
        assertEquals(6, config.retrievalTopK)
        assertEquals(3, config.finalTopK)
        assertEquals(null, config.rewrite, "переписывание выключено настройкой")
        assertEquals("эвристика (слова, имена, близость)", config.rerank)

        // Базовый режим: Top-K дня 22, вектор вопроса и время поиска — то, из чего рисуется конвейер.
        val baseline = requireNotNull(result.baseline)
        assertEquals(ModeDto.DONE, baseline.state)
        assertEquals(3, baseline.sources.size, "базовый режим берёт Top-K дня 22")
        assertTrue(requireNotNull(baseline.queryDimension) > 0, "вектор вопроса виден странице")
        assertTrue(baseline.retrievalMillis >= 0)
        assertEquals(2, baseline.messages.size, "системное правило и вопрос с контекстом")
        assertTrue(
            baseline.messages[0].content.contains("Опирайся только на фрагменты"),
            "в режиме с базой системное правило требует опираться на фрагменты"
        )
        val baselineFirst = requireNotNull(baseline.sources.firstOrNull())
        assertTrue(
            baseline.messages[1].content.contains(baselineFirst.text.take(40)),
            "в запросе есть найденный текст, а не только вопрос"
        )
        assertTrue(baseline.messages[1].content.contains("[${baselineFirst.rank}]"), "фрагмент подписан своим номером")

        // Улучшенный режим: тот же вопрос, но в контекст уходит финальный Top-K, а не выдача поиска.
        val improved = requireNotNull(result.improved)
        assertEquals(ModeDto.DONE, improved.state)
        assertEquals(3, improved.sources.size, "в контекст уходит финальный Top-K")

        // Трейс этапов: число кандидатов задано настройкой, а не размером контекста.
        val trace = requireNotNull(result.trace)
        assertEquals(result.question, trace.original)
        assertEquals(null, trace.rewritten, "переписывания в этом прогоне нет")
        assertEquals(6, trace.candidates.size, "retrievalTopK управляет числом кандидатов")
        assertEquals(0.0, trace.threshold)
        assertEquals(3, trace.passed, "в контекст проходят ровно finalTopK фрагментов")
        assertTrue(trace.accepted >= trace.passed)
        val check = requireNotNull(result.stageCheck)
        assertEquals(6, check.candidates)
        assertEquals(trace.accepted, check.accepted)

        // Режим без базы: ни фрагментов, ни вектора, ни времени поиска.
        val without = requireNotNull(result.without)
        assertEquals(ModeDto.DONE, without.state)
        assertTrue(without.sources.isEmpty(), "режим без базы не получает фрагментов")
        assertEquals(null, without.queryDimension, "вектора вопроса в режиме без базы нет")
        assertEquals(0L, without.retrievalMillis)
        assertEquals("Вопрос: ${result.question}", without.messages[1].content, "в запрос уходит только вопрос")

        // Попадание источника у вопроса с ответом в базе всегда «да» или «нет», а не прочерк:
        // прочерк страница показывает только там, где ответа в базе нет вовсе.
        assertTrue(baseline.hit != null, "у вопроса с ответом в базе попадание источника определено")

        // Метрики дня 22 считает Report, метрики этапов — Stages: страница их только показывает.
        // Модель отвечает заготовкой, поэтому оценка известна заранее — а попадёт ли нужный текст
        // в выдачу, зависит от поиска, и этого тест не проверяет: проверка поиска живёт в наборе (:rag).
        val summary = requireNotNull(state.summary)
        assertEquals(1, summary.questions)
        assertEquals(1, summary.scored)
        assertEquals(2, summary.factsTotal)
        assertEquals(2, summary.factsWith)
        assertEquals(2, summary.factsWithout)
        assertEquals(2.0, summary.averageWith)
        assertEquals(2.0, summary.averageWithout)
        val stages = requireNotNull(state.stages)
        assertEquals(1, stages.questions)
        assertEquals(1, stages.scored)
        assertEquals(2.0, stages.improvedScore)
        assertTrue(Files.exists(dir.resolve(RagSession.REPORT_FILE)), "сравнение записано на диск")
        assertTrue(Files.exists(dir.resolve(RagSession.LOG_FILE)), "лог запросов записан на диск")

        // День 24: ответ grounded-режима идёт на той же выдаче, что улучшенный, и получает свой блок
        // и свои файлы. Порог достаточности — тот же, что у фильтра, а сверку считает `:rag`.
        val grounded = requireNotNull(result.grounded)
        assertEquals(GroundedDto.DONE, grounded.state)
        assertEquals(trace.threshold, grounded.confidence.threshold, "порог достаточности — порог фильтра")
        assertEquals(1, requireNotNull(state.grounding).questions)
        assertTrue(Files.exists(dir.resolve(RagSession.GROUNDED_REPORT_FILE)), "ответы с цитатами записаны на диск")
        assertTrue(Files.exists(dir.resolve(RagSession.GROUNDED_LOG_FILE)), "лог дня 24 записан на диск")
        scope.cancel()
    }

    @Test
    fun `второй прогон поверх идущего отвергается`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-busy")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, FakeLlm(answer, pauseMillis = 200))
        if (session.setup().base.chars == 0) return@runBlocking

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), settings))
        val second = session.start(listOf("q02"), settings)

        assertTrue(second is StartOutcome.Rejected, "два прогона писали бы одни файлы и путали результаты")
        assertEquals(StateDto.DONE, await(session).state)
        assertEquals(1, session.state().results.size, "второй запрос ничего не прогнал")
        scope.cancel()
    }
}
