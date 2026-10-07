package com.osvin.aichallenge.indexing.search

import com.osvin.aichallenge.indexing.model.DocumentChunk

/**
 * Один результат семантического поиска: найденный чанк и его близость к запросу.
 *
 * [similarity] — косинусная близость в диапазоне от -1 до 1 (у наших векторов — от 0 до 1,
 * потому что они неотрицательны по построению). Результаты приходят отсортированными по убыванию:
 * порядок — часть контракта поиска, а не то, что вызывающий должен доделывать сам.
 */
data class SearchResult(
    val chunk: DocumentChunk,
    val similarity: Double
)
