package com.osvin.aichallenge.indexing.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Разбор близости: сводка вектора и слагаемые скалярного произведения.
 *
 * Векторы задаются вручную — на точных числах видно, что сумма слагаемых действительно даёт
 * скалярное произведение, а не похожа на него.
 */
class CosineBreakdownTest {

    @Test
    fun `сводка считает ненулевые, норму и старшие компоненты`() {
        val summary = CosineBreakdown.summarize(listOf(0f, 3f, 4f, 0f), top = 2)

        assertEquals(4, summary.dimension)
        assertEquals(2, summary.nonZero)
        assertEquals(5.0, summary.norm, 1e-9)
        assertEquals(listOf(2, 1), summary.top.map { it.dimension })
        assertEquals(4.0, summary.top.first().weight, 1e-9)
    }

    @Test
    fun `top ограничивает число компонент, но не влияет на норму`() {
        val summary = CosineBreakdown.summarize(listOf(1f, 1f, 1f), top = 1)

        assertEquals(1, summary.top.size)
        assertEquals(3, summary.nonZero)
        assertEquals(kotlin.math.sqrt(3.0), summary.norm, 1e-9)
    }

    @Test
    fun `вклад общих измерений складывается в скалярное произведение`() {
        // 0.6 в Float — это 0.60000002…, поэтому допуск здесь шире, чем у точных двоичных дробей.
        val breakdown = CosineBreakdown.of(listOf(0.6f, 0.8f), listOf(1f, 0f))

        assertEquals(1, breakdown.shared, "общим должно считаться только измерение 0")
        assertEquals(0.6, breakdown.dot, 1e-7)
        assertEquals(0.6, breakdown.similarity, 1e-7)
        assertEquals(listOf(0), breakdown.terms.map { it.dimension })
        assertEquals(0.6, breakdown.terms.single().contribution, 1e-7)
        assertEquals(breakdown.dot, breakdown.shownSum, 1e-9)
    }

    @Test
    fun `сумма показанных слагаемых меньше скалярного произведения, когда слагаемых больше top`() {
        val breakdown = CosineBreakdown.of(listOf(0.5f, 0.5f, 0.5f), listOf(0.5f, 0.5f, 0.5f), top = 1)

        assertEquals(3, breakdown.shared)
        assertEquals(1, breakdown.terms.size)
        assertEquals(0.75, breakdown.dot, 1e-9)
        assertEquals(0.25, breakdown.shownSum, 1e-9)
        assertEquals(1.0, breakdown.similarity, 1e-9)
    }

    @Test
    fun `ортогональные векторы дают нулевую близость и пустой список слагаемых`() {
        val breakdown = CosineBreakdown.of(listOf(1f, 0f), listOf(0f, 1f))

        assertEquals(0, breakdown.shared)
        assertTrue(breakdown.terms.isEmpty())
        assertEquals(0.0, breakdown.similarity, 1e-9)
    }

    @Test
    fun `противоположные знаки гасят вклад измерения`() {
        val breakdown = CosineBreakdown.of(listOf(1f, -1f), listOf(1f, 1f))

        assertEquals(2, breakdown.shared)
        assertEquals(0.0, breakdown.dot, 1e-9)
        assertEquals(0.0, breakdown.similarity, 1e-9)
    }

    @Test
    fun `разная длина векторов — ошибка`() {
        assertFailsWith<IllegalArgumentException> {
            CosineBreakdown.of(listOf(1f, 0f), listOf(1f, 0f, 0f))
        }
    }
}
