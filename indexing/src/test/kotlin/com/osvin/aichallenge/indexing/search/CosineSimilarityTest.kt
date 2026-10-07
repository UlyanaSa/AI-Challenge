package com.osvin.aichallenge.indexing.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Границы косинусной близости: крайние значения (совпадение, ортогональность, ноль) и отказ
 * сравнивать векторы из разных пространств. Числа берём «ручные», чтобы тест не зависел от
 * embedding-провайдера.
 */
class CosineSimilarityTest {

    @Test
    fun `одинаковые векторы дают косинус 1`() {
        val v = listOf(0.6f, 0.8f)
        assertEquals(1.0, CosineSimilarity.cosine(v, v), 1e-9)
    }

    @Test
    fun `ортогональные векторы дают косинус 0`() {
        assertEquals(0.0, CosineSimilarity.cosine(listOf(1f, 0f), listOf(0f, 1f)), 1e-9)
    }

    @Test
    fun `нулевой вектор даёт 0, а не NaN`() {
        val zero = listOf(0f, 0f, 0f)
        assertEquals(0.0, CosineSimilarity.cosine(zero, listOf(1f, 2f, 3f)), 1e-9)
        assertEquals(0.0, CosineSimilarity.cosine(zero, zero), 1e-9)
    }

    @Test
    fun `векторы разной длины не сравниваются`() {
        assertFailsWith<IllegalArgumentException> {
            CosineSimilarity.cosine(listOf(1f, 2f), listOf(1f, 2f, 3f))
        }
    }
}
