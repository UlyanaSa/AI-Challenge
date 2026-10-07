package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.chunking.ChunkingStrategy
import com.osvin.aichallenge.indexing.embedding.EmbeddingProvider
import com.osvin.aichallenge.indexing.index.IndexedChunk
import com.osvin.aichallenge.indexing.index.VectorStore
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk

/**
 * Результат индексации одной стратегией: чанки, из которых собран индекс, и время работы.
 *
 * Время нужно отчёту: сравнение стратегий без цены прогона было бы неполным, а цена здесь —
 * единственная переменная, которую видно вживую (embeddings и поиск у обеих стратегий общие).
 */
data class IndexingResult(
    val strategy: ChunkingStrategyType,
    val chunks: List<DocumentChunk>,
    val elapsedMillis: Long
)

/**
 * Индексатор: документ → стратегия → embeddings → хранилище.
 *
 * Класс знает только контракты (`ChunkingStrategy`, `EmbeddingProvider`, `VectorStore`), поэтому
 * одинаково работает для обеих стратегий, и «две стратегии» из задания — это два экземпляра
 * этого класса с разными стратегиями и разными хранилищами, а не две ветки кода.
 *
 * Порядок шагов задан конвейером: сначала весь документ режется стратегией, потом каждый чанк
 * отдельно уходит в embedding, и только потом попадает в индекс. Embedding получает ровно
 * `chunk.content` — ни метаданных, ни соседних чанков он не видит, иначе близость зависела бы
 * от того, как текст был порезан, и стратегии нельзя было бы сравнивать одной моделью.
 *
 * Нарезка идёт до первого embedding, а не по документу за раз: пока все чанки не нарезаны,
 * неизвестно их число, и прогресс ([onChunk]) нельзя было бы выразить в «сколько из скольких» —
 * а именно так его показывает интерфейс.
 */
class DocumentIndexer(
    private val strategy: ChunkingStrategy,
    private val embedder: EmbeddingProvider,
    private val store: VectorStore
) {

    /**
     * Индексирует набор документов. Возвращает чанки в том порядке, в каком они сохранены.
     *
     * [onChunk] вызывается после каждого сохранённого чанка с числом готовых и общим числом
     * чанков: интерфейс показывает по нему ход работы, а конвейер о вызывающем не знает.
     */
    suspend fun index(
        documents: List<Document>,
        onChunk: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): IndexingResult {
        val startedAt = System.nanoTime()
        val chunks = documents.flatMap { strategy.chunk(it) }
        val indexed = ArrayList<DocumentChunk>(chunks.size)

        chunks.forEachIndexed { index, chunk ->
            val embedding = embedder.embed(chunk.content)
            require(embedding.size == embedder.dimension) {
                "Провайдер вернул вектор длины ${embedding.size} вместо ${embedder.dimension} для чанка ${chunk.id}"
            }
            store.save(IndexedChunk(chunk, embedding))
            indexed += chunk
            onChunk(index + 1, chunks.size)
        }

        return IndexingResult(strategy.type, indexed, (System.nanoTime() - startedAt) / 1_000_000)
    }
}
