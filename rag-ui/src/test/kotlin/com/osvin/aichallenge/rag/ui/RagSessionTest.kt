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
 * Тесты страницы дня 22: прогон на настоящем индексе, но с подставной моделью.
 *
 * Проверяется то, чего не видно в отчёте: что страница показывает оба конвейера и показывает их
 * верно — с найденными фрагментами и вектором вопроса у агента с базой и без поиска у агента
 * без базы. Ошибка здесь не сломала бы ни один прогон: она нарисовала бы путь запроса, которого
 * не было, и числа сравнения читались бы по чужому пути.
 *
 * Индекс строится хешированием (сеть не нужна), корпус — тот же PDF дня, что у остальных прогонов:
 * без него тест не имеет предмета и пропускается, как и в `:rag`.
 */
class RagSessionTest {

    /** Ответ, в котором есть все факты первого вопроса набора. */
    private val answer = "Остался пятилетний сын; жена скончалась в Париже."

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
    fun `прогон вопроса заполняет оба конвейера и пишет отчёты`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, FakeLlm(answer))
        // Корпус дня берётся из встроенного PDF: если его нет, проверять нечего.
        if (session.setup().base.chars == 0) return@runBlocking

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), 3))
        val state = await(session)

        assertEquals(StateDto.DONE, state.state)
        assertEquals(1, state.results.size)
        val result = state.results.single()
        assertEquals("q01", result.id)

        // Агент с базой: фрагменты, вектор вопроса и время поиска — то, из чего рисуется конвейер.
        val with = requireNotNull(result.with)
        assertEquals(ModeDto.DONE, with.state)
        assertEquals(3, with.sources.size, "в запрос уходит Top-K фрагментов")
        assertTrue(requireNotNull(with.queryDimension) > 0, "вектор вопроса виден странице")
        assertTrue(with.retrievalMillis >= 0)
        assertEquals(2, with.messages.size, "системное правило и вопрос с контекстом")
        assertTrue(
            with.messages[0].content.contains("Опирайся только на фрагменты"),
            "в режиме с базой системное правило требует опираться на фрагменты"
        )
        val first = requireNotNull(with.sources.firstOrNull())
        assertTrue(
            with.messages[1].content.contains(first.text.take(40)),
            "в запросе есть найденный текст, а не только вопрос"
        )
        assertTrue(with.messages[1].content.contains("[${first.rank}]"), "фрагмент подписан своим номером")

        // Агент без базы: ни фрагментов, ни вектора, ни времени поиска.
        val without = requireNotNull(result.without)
        assertEquals(ModeDto.DONE, without.state)
        assertTrue(without.sources.isEmpty(), "режим без базы не получает фрагментов")
        assertEquals(null, without.queryDimension, "вектора вопроса в режиме без базы нет")
        assertEquals(0L, without.retrievalMillis)
        assertEquals("Вопрос: ${result.question}", without.messages[1].content, "в запрос уходит только вопрос")

        // Попадание источника у вопроса с ответом в базе всегда «да» или «нет», а не прочерк:
        // прочерк страница показывает только там, где ответа в базе нет вовсе.
        assertTrue(with.hit != null, "у вопроса с ответом в базе попадание источника определено")

        // Метрики и отчёты: числа считает Report, страница их только показывает. Модель отвечает
        // заготовкой, поэтому оценка известна заранее — а попадёт ли нужный текст в выдачу, зависит
        // от поиска, и этого тест не проверяет: проверка поиска живёт в наборе (:rag).
        val summary = requireNotNull(state.summary)
        assertEquals(1, summary.questions)
        assertEquals(1, summary.scored)
        assertEquals(2, summary.factsTotal)
        assertEquals(2, summary.factsWith)
        assertEquals(2, summary.factsWithout)
        assertEquals(2.0, summary.averageWith)
        assertEquals(2.0, summary.averageWithout)
        assertTrue(Files.exists(dir.resolve(RagSession.REPORT_FILE)), "сравнение записано на диск")
        assertTrue(Files.exists(dir.resolve(RagSession.LOG_FILE)), "лог запросов записан на диск")
        assertEquals(2, state.reports.size)
        scope.cancel()
    }

    @Test
    fun `второй прогон поверх идущего отвергается`() = runBlocking {
        val dir = Files.createTempDirectory("rag-ui-busy")
        val scope = CoroutineScope(Dispatchers.Default)
        val session = session(dir, scope, FakeLlm(answer, pauseMillis = 200))
        if (session.setup().base.chars == 0) return@runBlocking

        assertEquals(StartOutcome.Accepted, session.start(listOf("q01"), 3))
        val second = session.start(listOf("q02"), 3)

        assertTrue(second is StartOutcome.Rejected, "два прогона писали бы одни файлы и путали результаты")
        assertEquals(StateDto.DONE, await(session).state)
        assertEquals(1, session.state().results.size, "второй запрос ничего не прогнал")
        scope.cancel()
    }
}
