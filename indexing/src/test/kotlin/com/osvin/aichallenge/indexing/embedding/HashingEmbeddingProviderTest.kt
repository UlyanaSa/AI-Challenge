package com.osvin.aichallenge.indexing.embedding

import com.osvin.aichallenge.indexing.search.CosineSimilarity
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поведение локального провайдера: детерминизм, нормировка, нечувствительность к регистру и
 * порядку слов, а также то, что похожие тексты ближе непохожих. Проверяем наблюдаемые свойства
 * вектора, а не конкретные индексы хешей — иначе тест ломался бы от любой правки хеш-функции.
 */
class HashingEmbeddingProviderTest {

    private val provider = HashingEmbeddingProvider(dimension = 512)

    @Test
    fun `один и тот же текст даёт одинаковый вектор`() = runBlocking {
        assertEquals(provider.embed("coroutine scope"), provider.embed("coroutine scope"))
    }

    @Test
    fun `длина вектора равна dimension`() = runBlocking {
        assertEquals(512, provider.embed("coroutine scope").size)
    }

    @Test
    fun `непустой текст даёт вектор единичной L2-нормы`() = runBlocking {
        val vector = provider.embed("coroutine scope and lifecycle")
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        // Допуск по точности Float: компоненты хранятся как Float, Double-сумма даёт ~1e-7.
        assertEquals(1.0, norm, 1e-6)
    }

    @Test
    fun `пустой текст даёт нулевой вектор без NaN`() = runBlocking {
        val vector = provider.embed("   ")
        assertEquals(512, vector.size)
        assertTrue(vector.all { it == 0f }, "ожидался нулевой вектор, получено $vector")
    }

    @Test
    fun `регистр не влияет на вектор`() = runBlocking {
        assertEquals(provider.embed("Flow"), provider.embed("flow"))
    }

    @Test
    fun `признаки называют измерения, в которые попадает текст`() {
        val features = provider.features("coroutine scope")

        val words = features.filter { it.kind == FeatureKind.WORD }.map { it.text }
        assertEquals(listOf("coroutine", "scope"), words)
        assertTrue(
            features.filter { it.kind == FeatureKind.TRIGRAM }.any { it.text == "cor" },
            "у слова «coroutine» должны быть триграммы, получено ${features.map { it.text }}"
        )
        assertTrue(features.all { it.dimension in 0 until 512 }, "измерение вне вектора: $features")
        assertEquals(features, provider.features("coroutine scope"), "разбор должен быть воспроизводим")
    }

    @Test
    fun `короткие слова не дают триграмм`() {
        assertTrue(provider.features("flow").none { it.kind == FeatureKind.TRIGRAM })
    }

    @Test
    fun `у каждой ненулевой компоненты вектора есть объясняющий признак`() = runBlocking {
        val text = "component cohesion and component coupling"
        val vector = provider.embed(text)
        val dimensions = provider.features(text).map { it.dimension }.toSet()

        val unexplained = vector.indices.filter { vector[it] != 0f && it !in dimensions }
        assertTrue(unexplained.isEmpty(), "компоненты без признаков: $unexplained")
    }

    @Test
    fun `признаки объясняют близость форм одного слова`() {
        // Слова «component» и «components» не совпадают целиком, но делят триграммы — именно этим
        // на странице объясняется ненулевая близость таких чанков.
        val left = provider.features("component").filter { it.kind == FeatureKind.TRIGRAM }.map { it.dimension }
        val right = provider.features("components").filter { it.kind == FeatureKind.TRIGRAM }.map { it.dimension }

        assertTrue(left.intersect(right.toSet()).isNotEmpty(), "общих триграмм не нашлось")
    }

    @Test
    fun `похожие тексты ближе непохожих`() = runBlocking {
        val scope = provider.embed("coroutine scope")
        val lifecycle = provider.embed("coroutine scope and lifecycle")
        val ktor = provider.embed("ktor plugin installation")

        val similar = CosineSimilarity.cosine(scope, lifecycle)
        val unrelated = CosineSimilarity.cosine(scope, ktor)
        assertTrue(
            similar > unrelated,
            "близость похожих $similar должна быть больше близости непохожих $unrelated"
        )
    }

    @Test
    fun `порядок слов не влияет на вектор`() = runBlocking {
        val a = provider.embed("cancel the job")
        val b = provider.embed("the job cancel")
        assertEquals(1.0, CosineSimilarity.cosine(a, b), 1e-9)
    }

    @Test
    fun `разные dimension дают разные длины`() = runBlocking {
        assertEquals(256, HashingEmbeddingProvider(dimension = 256).embed("coroutine").size)
        assertEquals(512, HashingEmbeddingProvider(dimension = 512).embed("coroutine").size)
    }
}
