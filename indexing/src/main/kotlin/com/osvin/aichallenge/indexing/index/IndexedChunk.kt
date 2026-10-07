package com.osvin.aichallenge.indexing.index

import com.osvin.aichallenge.indexing.model.DocumentChunk
import kotlinx.serialization.Serializable

/**
 * Чанк вместе со своим embedding — то, что хранится в индексе.
 *
 * Сериализуется целиком: файл индекса содержит и текст, и метаданные, и вектор. Тогда поиск
 * после перезапуска не требует ни исходных документов, ни повторного счёта embeddings — из
 * файла поднимается всё, что нужно для ответа.
 */
@Serializable
data class IndexedChunk(
    val chunk: DocumentChunk,
    val embedding: List<Float>
)
