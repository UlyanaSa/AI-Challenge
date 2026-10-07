package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Тесты этапов дня 23: фильтр, второй этап, финальный Top-K и переписывание.
 *
 * Проверяется не внутреннее устройство конвейера, а то, что видно снаружи: какие фрагменты ушли
 * в контекст, каким номером, каким запросом их искали и что модель получила в запросе. Это тот
 * уровень, на котором ошибка меняет вывод прогона: сдвинутая нумерация источников ломает ссылки
 * в ответе, а потерянный исходный вопрос — само сравнение.
 *
 * Поиск здесь подставной: он должен отдавать ровно заданную выдачу с заданными близостями, потому
 * что проверяются решения этапов, а не качество embeddings. Модель тоже подставная, кроме одного
 * теста: у [LlmReranker] проверяется разбор её ответа.
 */
class PipelineTest {

    /** Поиск, отдающий заготовленную выдачу и запоминающий, что у него спросили. */
    private class FakeFinder(private val sources: List<Source>) : SourceFinder {

        val queries = mutableListOf<String>()

        override suspend fun find(question: String): Retrieval {
            queries += question
            return Retrieval(sources, queryVector = listOf(0.1f, 0.2f), millis = 7)
        }
    }

    /** Переписыватель с заготовленным запросом. */
    private class FakeRewriter(private val query: String) : QueryRewriter {
        override val name: String = "тест"
        override suspend fun rewrite(question: String): Rewrite = Rewrite(question, query, millis = 3)
    }

    /** Второй этап, расставляющий кандидатов в заданном порядке. */
    private class FakeReranker(private val order: List<String>) : Reranker {
        override val name: String = "тест"
        override suspend fun rank(question: String, candidates: List<Source>): Rerank = Rerank(
            scored = candidates.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .mapIndexed { index, source -> Scored(source, (order.size - index).toDouble()) }
        )
    }

    private fun source(rank: Int, similarity: Double, id: String = "chunk-$rank", text: String = "текст $rank"): Source =
        Source(
            rank = rank,
            similarity = similarity,
            text = text,
            id = id,
            source = "book.md",
            title = null,
            pages = listOf(10 + rank),
            section = "Глава первая",
            chunkIndex = rank
        )

    @Test
    fun `фильтр отсекает слабых кандидатов, остальных оставляет`() = runBlocking {
        val finder = FakeFinder(listOf(source(1, 0.9), source(2, 0.5), source(3, 0.3)))
        val pipeline = RagPipeline(finder, null, SimilarityFilter(0.5), null, finalTopK = 3)

        val retrieval = pipeline.find("вопрос")
        val trace = assertNotNull(retrieval.trace)

        assertEquals(listOf("chunk-1", "chunk-2"), retrieval.sources.map { it.id })
        assertEquals(2, trace.accepted)
        assertEquals(1, trace.removed)
        assertFalse(trace.empty)
    }

    @Test
    fun `порог отсеял всех — контекста нет, и модель получает сообщение без фрагментов`() = runBlocking {
        val finder = FakeFinder(listOf(source(1, 0.4), source(2, 0.3)))
        val pipeline = RagPipeline(finder, null, SimilarityFilter(0.9), null, finalTopK = 3)
        val llm = FakeLlm()
        val agent = RagAgent(llm, pipeline, model = "test-model")

        val answer = agent.ask("Назови причину", Mode.WITH_RAG)

        val trace = assertNotNull(answer.retrieval.trace)
        assertTrue(answer.sources.isEmpty(), "в контекст не ушло ничего")
        assertTrue(trace.empty, "трейс говорит, что фильтр отсеял всех")
        val user = llm.requests.single().messages.last().content
        assertTrue(user.contains("не осталось ни одного"), "модели сказано, что фрагментов нет")
        assertFalse(user.contains("book.md"), "пустой блок фрагментов в запрос не подставляется")
        assertTrue(llm.requests.single().messages.first().content.contains("Опирайся только на фрагменты"))
    }

    @Test
    fun `финальный Top-K ограничивает контекст и нумерует источники с единицы`() = runBlocking {
        val finder = FakeFinder((1..5).map { source(it, 1.0 - it * 0.05) })
        val pipeline = RagPipeline(finder, null, SimilarityFilter(0.5), null, finalTopK = 3)

        val retrieval = pipeline.find("вопрос")

        assertEquals(3, retrieval.sources.size)
        assertEquals(listOf(1, 2, 3), retrieval.sources.map { it.rank })
        assertEquals(listOf("chunk-1", "chunk-2", "chunk-3"), retrieval.sources.map { it.id })
        assertEquals(5, assertNotNull(retrieval.trace).candidates.size, "поиск просил больше, чем ушло в контекст")
        assertEquals(5, assertNotNull(retrieval.trace).retrievalTopK)
    }

    @Test
    fun `второй этап меняет порядок и состав не трогает`() = runBlocking {
        val finder = FakeFinder(listOf(source(1, 0.9), source(2, 0.8), source(3, 0.7)))
        val pipeline = RagPipeline(finder, null, SimilarityFilter(0.0), FakeReranker(listOf("chunk-3", "chunk-1", "chunk-2")), finalTopK = 2)

        val retrieval = pipeline.find("вопрос")
        val trace = assertNotNull(retrieval.trace)

        assertEquals(listOf("chunk-3", "chunk-1"), retrieval.sources.map { it.id })
        assertEquals(listOf(1, 2), retrieval.sources.map { it.rank })
        assertEquals(
            setOf("chunk-1", "chunk-2", "chunk-3"),
            trace.candidates.map { it.source.id }.toSet(),
            "состав кандидатов второй этап не меняет"
        )
        val moved = trace.candidates.associateBy { it.source.id }
        assertEquals(3, moved.getValue("chunk-3").source.rank, "близость ставила этот фрагмент третьим")
        assertEquals(1, moved.getValue("chunk-3").rerankRank, "а второй этап — первым")
        assertEquals(1, moved.getValue("chunk-3").finalRank)
        assertEquals(1, moved.getValue("chunk-1").source.rank)
        assertEquals(2, moved.getValue("chunk-1").rerankRank)
        assertEquals(2, moved.getValue("chunk-1").finalRank)
        assertEquals(3, moved.getValue("chunk-2").rerankRank)
        assertNull(moved.getValue("chunk-2").finalRank, "в финальный Top-K он не попал")
    }

    @Test
    fun `переписанный запрос идёт в поиск, исходный вопрос — в запрос к модели`() = runBlocking {
        val finder = FakeFinder(listOf(source(1, 0.9)))
        val pipeline = RagPipeline(
            finder = finder,
            rewriter = FakeRewriter("Степан Трофимович Верховенский воспитатель"),
            filter = SimilarityFilter(0.0),
            reranker = null,
            finalTopK = 3
        )
        val llm = FakeLlm()

        val answer = RagAgent(llm, pipeline, model = "test-model").ask("Почему он оказался в доме?", Mode.WITH_RAG)

        assertEquals(listOf("Степан Трофимович Верховенский воспитатель"), finder.queries)
        assertEquals("Почему он оказался в доме?", assertNotNull(answer.retrieval.trace).original)
        val user = llm.requests.single().messages.last().content
        assertTrue(user.contains("Почему он оказался в доме?"), "в модель уходит исходный вопрос")
        assertFalse(user.contains("воспитатель"), "переписанный запрос в модель не уходит")
    }

    @Test
    fun `LLM-реранкер разбирает оценки модели и ставит выше оценённого`() = runBlocking {
        val llm = FakeLlm(answers = listOf("1=2\n2=9\n3=5"))
        val reranker = LlmReranker(llm, model = "test-model")
        val candidates = listOf(source(1, 0.9), source(2, 0.8), source(3, 0.7))

        val rerank = reranker.rank("вопрос", candidates)
        val ordered = rerank.scored.sortedByDescending { it.score }

        assertEquals(listOf("chunk-2", "chunk-3", "chunk-1"), ordered.map { it.source.id })
        assertEquals(3, rerank.scoredByModel)
        assertNull(rerank.note)
    }

    @Test
    fun `LLM-реранкер при неразобранном ответе оставляет порядок по близости и говорит об этом`() = runBlocking {
        val llm = FakeLlm(answers = listOf("не могу оценить фрагменты"))
        val reranker = LlmReranker(llm, model = "test-model")

        val rerank = reranker.rank("вопрос", listOf(source(1, 0.9), source(2, 0.8)))

        assertEquals(0, rerank.scoredByModel)
        assertEquals(listOf(0.9, 0.8), rerank.scored.map { it.score })
        assertTrue(
            assertNotNull(rerank.note).contains("не разобраны"),
            "о сбое разбора сказано в отчёте, а не проглочено"
        )
    }

    @Test
    fun `LLM-реранкер принимает список оценок по порядку фрагментов`() = runBlocking {
        val candidates = (1..4).map { source(it, 1.0 - it * 0.05) }
        // Так отвечает модель, когда не пишет номера: одно число на строку, в порядке фрагментов.
        val listed = LlmReranker(FakeLlm(answers = listOf("5\n5\n0\n0")), model = "test-model")
            .rank("вопрос", candidates)
        assertEquals(listOf(5.0, 5.0, 0.0, 0.0), listed.scored.map { it.score })
        assertEquals(4, listed.scoredByModel)
        assertNotNull(listed.note, "о формате ответа сказано в отчёте")

        // Чисел меньше, чем фрагментов: неизвестно, какое к какому, — оценки не принимаются.
        val short = LlmReranker(FakeLlm(answers = listOf("5\n5\n0")), model = "test-model")
            .rank("вопрос", candidates)
        assertEquals(0, short.scoredByModel)
        assertEquals(candidates.map { it.similarity }, short.scored.map { it.score })
        assertTrue(assertNotNull(short.note).startsWith("оценки модели не разобраны"))
    }

    @Test
    fun `эвристический второй этап поднимает фрагмент с именами вопроса`() = runBlocking {
        val question = "Что Виргинский сделал с Лебедкиным в роще?"
        val unrelated = source(1, 0.9, id = "unrelated", text = "Степан Трофимович любил читать книги и спорить.")
        val relevant = source(2, 0.4, id = "relevant", text = "Виргинский схватил Лебедкина обеими руками за волосы.")

        val rerank = HeuristicReranker().rank(question, listOf(unrelated, relevant))
        val ordered = rerank.scored.sortedByDescending { it.score }

        assertEquals(listOf("relevant", "unrelated"), ordered.map { it.source.id })
        assertEquals(0, rerank.scoredByModel, "эвристика к модели не обращается, и отчёт не должен это утверждать")
    }

    @Test
    fun `потеря правильного фрагмента различается по этапам`() {
        val control = Controls.byId("q01")!!
        val correct = source(1, 0.62, id = "correct", text = "У него остался пятилетний сын.")
        val other = source(2, 0.71, id = "other", text = "Ничего об этом.")

        val inFinal = trace(
            candidates = listOf(
                Candidate(correct, accepted = true, rerankScore = 0.8, rerankRank = 1, finalRank = 1),
                Candidate(other, accepted = true, rerankScore = 0.4, rerankRank = 2, finalRank = 2)
            )
        )
        val cutByFilter = trace(
            candidates = listOf(
                Candidate(correct, accepted = false),
                Candidate(other, accepted = true, rerankScore = 0.4, rerankRank = 1, finalRank = 1)
            )
        )
        val lostByRerank = trace(
            candidates = listOf(
                Candidate(correct, accepted = true, rerankScore = 0.2, rerankRank = 2),
                Candidate(other, accepted = true, rerankScore = 0.9, rerankRank = 1, finalRank = 1)
            )
        )
        val lostBySearch = trace(candidates = listOf(Candidate(other, accepted = true, rerankScore = 0.9, rerankRank = 1, finalRank = 1)))

        val checks = listOf(inFinal, cutByFilter, lostByRerank, lostBySearch)
            .map { Stages.evaluate(control, it) }

        assertEquals(Loss.NONE, checks[0]?.loss)
        assertEquals(Loss.FILTER, checks[1]?.loss)
        assertEquals(1, checks[1]?.wronglyFiltered, "отсечённый фрагмент был правильным")
        assertEquals(0, checks[2]?.wronglyFiltered)
        assertEquals(Loss.RERANK, checks[2]?.loss)
        assertEquals(Loss.RETRIEVAL, checks[3]?.loss)
        assertEquals(0, checks[0]?.removed)
        assertEquals(1, checks[1]?.removed, "порог отсеял одного кандидата")
        assertEquals(0, checks[2]?.removed)
    }

    @Test
    fun `проверка переписывания считает имя потерянным только когда его правда нет`() = runBlocking {
        val control = Controls.byId("q01")!!
        val corpus = "Степан Трофимович Верховенский вернулся и жил в доме Варвары Петровны."
        val found = FakeFinder(listOf(source(1, 0.6, id = "fact", text = "У него остался пятилетний сын.")))

        // В вопросе «Степана Трофимовича», в запросе «Степан Трофимович»: та же форма имени
        // в другом падеже — это не потеря, и проверка обязана молчать.
        val kept = RewriteEval(found, corpus, FakeRewriter("Степан Трофимович первая жена сын"))
            .run(listOf(control))
            .single()
        assertTrue(kept.lostNames.isEmpty(), "имя в другом падеже не потеряно: ${kept.lostNames}")
        assertTrue(kept.inventedNames.isEmpty(), "имя есть в книге — оно не выдумано")

        val dropped = RewriteEval(found, corpus, FakeRewriter("первая жена умерла остался сын"))
            .run(listOf(control))
            .single()
        assertEquals(
            listOf("Степана", "Трофимовича"),
            dropped.lostNames,
            "а без имени в запросе потеря видна"
        )

        // Короткое имя в другом падеже: в вопросе «Петра», в запросе «Пётр» — основы из пяти букв
        // («петра» и «пётр») не совпадают, и проверка по полной основе объявила бы потерю.
        val short = Controls.byId("q10")!!
        val keptShort = RewriteEval(found, "Пётр Степанович Верховенский уехал.", FakeRewriter("Пётр Степанович Верховенский"))
            .run(listOf(short))
            .single()
        assertTrue(keptShort.lostNames.isEmpty(), "короткое имя в другом падеже не потеряно: ${keptShort.lostNames}")
        assertTrue(keptShort.inventedNames.isEmpty(), "имя есть в книге — оно не выдумано")

        val invented = RewriteEval(found, corpus, FakeRewriter("Степан Трофимович Варвара Ставрогина"))
            .run(listOf(control))
            .single()
        assertEquals(
            listOf("Ставрогина"),
            invented.inventedNames,
            "имя, которого нет ни в вопросе, ни в книге, — выдумка"
        )
    }

    /** Трейс с заданными кандидатами: остальные поля для этих проверок не важны. */
    private fun trace(candidates: List<Candidate>): Trace = Trace(
        original = "вопрос",
        rewritten = null,
        retrievalTopK = candidates.size,
        threshold = 0.6,
        candidates = candidates,
        retrievalMillis = 1,
        rewriteMillis = 0,
        rerankMillis = 0
    )

    /** Клиент модели: записывает запросы и отвечает заготовками. */
    private class FakeLlm(private val answers: List<String> = listOf("ответ")) : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            val answer = answers.getOrElse(requests.size - 1) { answers.last() }
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(ChatMessage(role = "assistant", content = answer), finishReason = "stop")
                ),
                usage = null
            )
        }
    }
}
