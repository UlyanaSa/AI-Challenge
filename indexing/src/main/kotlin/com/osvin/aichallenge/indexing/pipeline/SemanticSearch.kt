package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.embedding.EmbeddingProvider
import com.osvin.aichallenge.indexing.index.VectorStore
import com.osvin.aichallenge.indexing.search.SearchResult

/** Результаты поиска вместе с вектором запроса, которым они получены. */
data class QuerySearch(val queryVector: List<Float>, val results: List<SearchResult>)

/**
 * Поиск по индексу: запрос → embedding → ближайшие чанки.
 *
 * Отдельный класс, а не метод хранилища, потому что запрос считает та же модель, что и чанки,
 * а хранилище о модели не знает: ему на вход приходит вектор. Держать вектор запроса и вектор
 * чанка в одном пространстве — обязанность этого класса, и здесь же она и проверяется: запрос
 * уходит в тот же [EmbeddingProvider], которым индексировались документы.
 *
 * Один экземпляр обслуживает один индекс. Чтобы сравнить стратегии, запрос считается один раз
 * и подаётся в два поиска — так разница в результатах не может прийти из разницы embeddings.
 */
class SemanticSearch(
    private val embedder: EmbeddingProvider,
    private val store: VectorStore
) {

    /** Находит до [limit] ближайших чанков, отсортированных по убыванию близости. */
    suspend fun search(query: String, limit: Int = 3): List<SearchResult> =
        searchWithQuery(query, limit).results

    /**
     * То же, что [search], но вместе с вектором запроса.
     *
     * Вектор нужен там, где близость показывают, а не только считают: разложить её на слагаемые
     * можно только теми векторами, которыми она посчитана, а посчитать запрос вторым вызовом
     * [EmbeddingProvider] значило бы надеяться на его детерминизм вместо того, чтобы вернуть
     * ровно тот вектор, который ушёл в [VectorStore.search].
     */
    suspend fun searchWithQuery(query: String, limit: Int = 3): QuerySearch {
        val vector = embedder.embed(query)
        return QuerySearch(vector, store.search(vector, limit))
    }
}
