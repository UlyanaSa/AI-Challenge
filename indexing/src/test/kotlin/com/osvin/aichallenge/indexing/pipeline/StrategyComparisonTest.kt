package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.ChunkMetadata
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Оценка качества поиска и поиск разрезов — на этих числах строится вывод о стратегиях, поэтому
 * проверяются границы: что считается попаданием, как считается MRR, что считается разрезом.
 */
class StrategyComparisonTest {

    private val key = AnswerKey(
        query = "How does a scope work?",
        source = "scope.md",
        section = "CoroutineScope",
        answerText = "A scope binds coroutines to a lifecycle. When the job of the scope is cancelled, every coroutine of the scope is cancelled too."
    )

    private fun chunk(id: String, content: String, source: String = "scope.md") = DocumentChunk(
        id = id,
        content = content,
        metadata = ChunkMetadata(source, "Coroutines", null, 0, ChunkingStrategyType.FIXED_SIZE)
    )

    private fun result(id: String, content: String, similarity: Double) = SearchResult(chunk(id, content), similarity)

    @Test
    fun `relevance is measured by content, not by metadata`() {
        val onTopic = chunk("a", "A scope binds coroutines to a lifecycle. The job of the scope is cancelled, and every coroutine of the scope is cancelled too.")
        val offTopic = chunk("b", "Ktor installs plugins with the install function and configures routing.")

        assertTrue(StrategyComparison.isRelevant(onTopic, key))
        assertFalse(StrategyComparison.isRelevant(offTopic, key))
    }

    @Test
    fun `a chunk that tells most of the section is relevant even with extra words`() {
        val mostly = chunk("a", "A scope binds coroutines to a lifecycle. The job of the scope is cancelled with every coroutine.")

        assertTrue(StrategyComparison.isRelevant(mostly, key), "лишние слова не должны мешать: доля считается от эталона")
    }

    @Test
    fun `an answer is an answer in whatever file the chunk landed`() {
        val fromOtherFile = chunk("a", key.answerText, source = "basics.md")

        assertTrue(
            StrategyComparison.isRelevant(fromOtherFile, key),
            "релевантность считается по содержанию: чанк, пересёкший границу файлов, содержит ответ, " +
                "а дефект его метаданных измеряется отдельной строкой отчёта"
        )
    }

    @Test
    fun `a chunk that only touches the section is not relevant`() {
        val touched = chunk("a", "A scope cancels plugins when the install function is called.")

        assertFalse(StrategyComparison.isRelevant(touched, key))
    }

    @Test
    fun `empty answer or chunk is never relevant`() {
        val emptyKey = key.copy(answerText = "")

        assertFalse(StrategyComparison.isRelevant(chunk("a", "A scope binds coroutines to a lifecycle."), emptyKey))
        assertFalse(StrategyComparison.isRelevant(chunk("b", ""), key))
    }

    @Test
    fun `score counts top results and reciprocal rank`() {
        val first = QueryOutcome(key, listOf(
            result("a", "A scope binds coroutines to a lifecycle and cancels every coroutine with the job.", 0.9),
            result("b", "Ktor installs plugins with the install function.", 0.5)
        ))
        val third = QueryOutcome(key, listOf(
            result("c", "Ktor installs plugins with the install function.", 0.9),
            result("d", "Ktor configures routing with path parameters.", 0.8),
            result("e", "A scope binds coroutines to a lifecycle and cancels every coroutine with the job.", 0.7)
        ))
        val missed = QueryOutcome(key, listOf(
            result("f", "Ktor installs plugins with the install function.", 0.9),
            result("g", "Ktor configures routing with path parameters.", 0.8),
            result("h", "Compose hoists state to the caller.", 0.7),
            result("i", "A scope binds coroutines to a lifecycle and cancels every coroutine with the job.", 0.6)
        ))

        val score = StrategyComparison.score(listOf(first, third, missed))

        assertEquals(3, score.queries)
        assertEquals(1, score.relevantAtTop1)
        assertEquals(2, score.relevantInTop3)
        assertEquals((1.0 + 1.0 / 3) / 3, score.meanReciprocalRank, 1e-9)
    }

    @Test
    fun `MRR ranks the first relevant result, not the best similarity`() {
        val outcome = QueryOutcome(key, listOf(
            result("a", "Ktor installs plugins with the install function.", 0.99),
            result("b", "A scope binds coroutines to a lifecycle and cancels every coroutine with the job.", 0.10)
        ))

        assertEquals(0.5, StrategyComparison.score(listOf(outcome)).meanReciprocalRank, 1e-9)
    }

    @Test
    fun `a fixed chunk strictly inside a structural chunk is a cut`() {
        val structural = chunk("s", "The section starts here. Then the fragment continues to its end.")
        val inside = chunk("f", "Then the fragment continues to its end.")

        val cuts = StrategyComparison.cuts(listOf(inside), listOf(structural))

        assertEquals(1, cuts.size)
        assertEquals("s", cuts.single().structuralChunkId)
        assertTrue(cuts.single().startsMidSection, "фиксированный чанк начался в середине раздела")
        assertEquals("The section starts here. ", cuts.single().cutFrom)
    }

    @Test
    fun `a chunk equal to the whole section or outside it is not a cut`() {
        val structural = chunk("s", "The section starts here. Then the fragment continues to its end.")

        assertTrue(StrategyComparison.cuts(listOf(structural), listOf(structural)).isEmpty())
        assertTrue(StrategyComparison.cuts(listOf(chunk("o", "Text from another section entirely.")), listOf(structural)).isEmpty())
    }

    @Test
    fun `cut that starts at the section beginning is reported but marked`() {
        val structural = chunk("s", "The section starts here. Then the fragment continues to its end.")
        val head = chunk("f", "The section starts here.")

        val cut = StrategyComparison.cuts(listOf(head), listOf(structural)).single()

        assertFalse(cut.startsMidSection)
        assertEquals("", cut.cutFrom)
    }
}
