package com.osvin.aichallenge.indexing.chunking

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk

/**
 * Общий контракт нарезки: документ на входе — чанки на выходе.
 *
 * Индексатор знает только этот интерфейс, поэтому обе стратегии проходят один и тот же путь:
 * `Document → ChunkingStrategy → List<DocumentChunk> → EmbeddingProvider → IndexedChunk`.
 * Ни одна стратегия не знает ни про embeddings, ни про хранилище — их дело резать текст.
 *
 * `suspend` здесь нет намеренно: нарезка — чистая функция от текста, сети и диска она не видит,
 * а требование приостанавливаемости заставило бы и её вызывающих тащить корутины там, где они
 * не нужны. Приостанавливаются только `EmbeddingProvider.embed` и методы `VectorStore`.
 */
interface ChunkingStrategy {

    /** Какой стратегией сделан чанк: попадает в метаданные и отличает два индекса. */
    val type: ChunkingStrategyType

    /** Режет документ на чанки. Пустой документ даёт пустой список. */
    fun chunk(document: Document): List<DocumentChunk>
}
