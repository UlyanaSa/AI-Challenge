package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.chunking.FixedSizeChunker
import com.osvin.aichallenge.indexing.chunking.StructuralChunker
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.index.JsonVectorStore
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.search.CosineSimilarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * Конвейер от документа до поиска: обе стратегии проходят его одинаково, embeddings считаются
 * одной моделью, а индекс переживает перезапись и повторное чтение с диска.
 */
class IndexerTest {

    private val document = Document(
        id = "coroutines",
        title = "Kotlin Coroutines",
        files = listOf(
            DocumentFile("scope.md", "# CoroutineScope\n\nA scope binds coroutines to a lifecycle.\n\n## Lifecycle\n\nA scope is cancelled when its job is cancelled.\n"),
            DocumentFile("flow.md", "# Flow\n\nA flow is a cold stream of values.\n\n## StateFlow\n\nStateFlow always has a current value.\n")
        )
    )

    private fun tempIndex(name: String) = createTempDirectory("indexing-test").resolve(name)

    @Test
    fun `both strategies index the same documents and save every chunk`() = runBlocking {
        val embedder = HashingEmbeddingProvider(dimension = 64)
        val fixedPath = tempIndex("fixed.json")
        val structuralPath = tempIndex("structural.json")

        val fixed = DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, JsonVectorStore.open(fixedPath))
            .index(listOf(document))
        val structural = DocumentIndexer(StructuralChunker(maxChunkSize = 120), embedder, JsonVectorStore.open(structuralPath))
            .index(listOf(document))

        assertEquals(ChunkingStrategyType.FIXED_SIZE, fixed.strategy)
        assertEquals(ChunkingStrategyType.STRUCTURAL, structural.strategy)
        assertTrue(fixed.chunks.isNotEmpty() && structural.chunks.isNotEmpty())
        assertEquals(fixed.chunks.size, JsonVectorStore.open(fixedPath).size, "в индекс попали не все чанки")
        assertEquals(structural.chunks.size, JsonVectorStore.open(structuralPath).size)
        assertEquals(fixed.chunks.size, fixed.chunks.map { it.id }.distinct().size, "id чанков обязаны быть уникальны")
        assertEquals(structural.chunks.size, structural.chunks.map { it.id }.distinct().size)
    }

    @Test
    fun `saved index answers a query after reopening from disk`() = runBlocking {
        val embedder = HashingEmbeddingProvider(dimension = 64)
        val path = tempIndex("fixed.json")

        DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, JsonVectorStore.open(path))
            .index(listOf(document))

        // Тот же запрос — к хранилищу, поднятому из файла, без исходных документов и без повторной индексации.
        val results = SemanticSearch(embedder, JsonVectorStore.open(path)).search("cold stream of values", limit = 3)

        assertTrue(results.isNotEmpty())
        assertEquals(results.sortedByDescending { it.similarity }.map { it.chunk.id }, results.map { it.chunk.id })
        assertTrue(results.all { it.similarity in 0.0..1.0 })
        assertTrue(results.first().chunk.content.contains("flow"), "ближайшим к запросу о Flow должен быть чанк с Flow")
    }

    @Test
    fun `вектор, отданный поиском, — тот самый, которым посчитана близость`() = runBlocking {
        val embedder = HashingEmbeddingProvider(dimension = 64)
        val store = JsonVectorStore.open(tempIndex("explained.json"))
        DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, store)
            .index(listOf(document))
        val search = SemanticSearch(embedder, store)

        val found = search.searchWithQuery("cold stream of values", limit = 3)

        assertEquals(search.search("cold stream of values", limit = 3).map { it.chunk.id }, found.results.map { it.chunk.id })
        found.results.forEach { result ->
            val chunkVector = store.get(result.chunk.id)!!.embedding
            // Косинус по векторам из индекса обязан совпасть с близостью из выдачи: страница
            // показывает человеку именно эту сверку.
            assertEquals(result.similarity, CosineSimilarity.cosine(found.queryVector, chunkVector), 1e-9)
        }
    }

    @Test
    fun `strategies produce different chunk sets from the same document`() = runBlocking {
        val embedder = HashingEmbeddingProvider(dimension = 64)
        val fixed = DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, JsonVectorStore.open(tempIndex("f.json")))
            .index(listOf(document))
        val structural = DocumentIndexer(StructuralChunker(maxChunkSize = 120), embedder, JsonVectorStore.open(tempIndex("s.json")))
            .index(listOf(document))

        val fixedIds = fixed.chunks.map { it.id }.toSet()
        val structuralIds = structural.chunks.map { it.id }.toSet()

        assertTrue((fixedIds intersect structuralIds).isEmpty(), "id чанков двух стратегий не должны пересекаться")
    }

    @Test
    fun `progress reports every saved chunk against the total`() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()

        val result = DocumentIndexer(
            FixedSizeChunker(chunkSize = 80, overlap = 20),
            HashingEmbeddingProvider(dimension = 64),
            JsonVectorStore.open(tempIndex("progress.json"))
        ).index(listOf(document)) { done, total -> seen += done to total }

        assertEquals(result.chunks.size, seen.size, "прогресс обязан дойти до каждого чанка")
        assertEquals((1..result.chunks.size).toList(), seen.map { it.first })
        assertTrue(seen.all { it.second == result.chunks.size }, "общее число чанков известно с первого шага")
    }

    @Test
    fun `indexing without a progress listener works the same`() = runBlocking {
        val embedder = HashingEmbeddingProvider(dimension = 64)
        val silent = DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, JsonVectorStore.open(tempIndex("a.json")))
            .index(listOf(document))
        val loud = DocumentIndexer(FixedSizeChunker(chunkSize = 80, overlap = 20), embedder, JsonVectorStore.open(tempIndex("b.json")))
            .index(listOf(document)) { _, _ -> }

        assertEquals(silent.chunks.map { it.id }, loud.chunks.map { it.id })
    }
}
