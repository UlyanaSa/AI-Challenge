package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.model.DocumentFile
import kotlin.math.roundToInt

/**
 * Статистика одного индекса — то, по чему задание требует сравнить стратегии на уровне чанков.
 *
 * [sectionCoverage] — доля чанков, у которых заполнен `section`. У структурной стратегии это
 * мера полноты метаданных, у фиксированной — ноль по построению: разделы она не разбирает.
 * [sections] — сколько различных разделов вообще попало в метаданные.
 *
 * [sourceTokens] и [chunkTokens] — токены ([Tokens]) в исходных файлах и в чанках этой стратегии.
 * Токенов в чанках не меньше, чем в документе, и лишние — цена нарезки: перекрытие фиксированной
 * стратегии и заголовки, повторённые в чанках. Это объём работы embedding, и по нему видно,
 * во сколько раз стратегия дороже самого текста.
 *
 * [pageCoverage] — доля чанков, у которых известны страницы источника. Это та же полнота
 * метаданных, но с другой стороны: чанк без страниц нельзя показать человеку с указанием, где
 * ответ лежит в книге. У корпуса с вёрсткой страницы есть у обеих стратегий, и различаются они
 * не наличием страниц, а их точностью — она и видна в отчёте рядом с каждым результатом.
 *
 * [fileBoundaryCrossings] — сколько чанков склеили текст двух файлов. Это прямая мера потери
 * файловой границы, и считается она одинаково для обеих стратегий: по содержимому чанка, а не
 * по тому, какая стратегия его создала, — иначе сравнение было бы подогнано.
 */
data class IndexStatistics(
    val strategy: ChunkingStrategyType,
    val documents: Int,
    val files: Int,
    val sourceChars: Int,
    val sourceTokens: Int,
    val chunks: Int,
    val chunkTokens: Int,
    val averageChunkSize: Int,
    val minChunkSize: Int,
    val maxChunkSize: Int,
    val sections: Int,
    val sectionCoverage: Double,
    val pageCoverage: Double,
    val fileBoundaryCrossings: Int
) {
    companion object {

        /** Длина «подписи» файла, по которой видно, что чанк содержит его край. */
        private const val MARKER_LENGTH = 24

        /**
         * Считает статистику по чанкам одной стратегии.
         *
         * Пересечение границы файлов определяется по содержимому: чанк, в котором встречаются
         * конец одного файла и начало другого (в этом порядке), собрал текст из двух файлов.
         * Длина подписи [MARKER_LENGTH] выбрана как компромисс: короче — ложные срабатывания на
         * общих фразах, длиннее — пропуск чанка, разрезавшего стык почти посередине. Оценка
         * помечена как эвристика и в отчёте подписана.
         */
        fun of(
            strategy: ChunkingStrategyType,
            documents: List<Document>,
            chunks: List<DocumentChunk>
        ): IndexStatistics {
            val files = documents.flatMap { it.files }
            val sizes = chunks.map { it.content.length }
            val sections = chunks.mapNotNull { it.metadata.section }.distinct()

            return IndexStatistics(
                strategy = strategy,
                documents = documents.size,
                files = files.size,
                sourceChars = files.sumOf { it.content.length },
                sourceTokens = Tokens.count(documents),
                chunks = chunks.size,
                chunkTokens = chunks.sumOf { Tokens.count(it.content) },
                averageChunkSize = if (sizes.isEmpty()) 0 else sizes.average().roundToInt(),
                minChunkSize = sizes.minOrNull() ?: 0,
                maxChunkSize = sizes.maxOrNull() ?: 0,
                sections = sections.size,
                sectionCoverage = if (chunks.isEmpty()) 0.0
                else chunks.count { it.metadata.section != null }.toDouble() / chunks.size,
                pageCoverage = if (chunks.isEmpty()) 0.0
                else chunks.count { it.metadata.pages.isNotEmpty() }.toDouble() / chunks.size,
                fileBoundaryCrossings = chunks.count { crossesFileBoundary(it, files) }
            )
        }

        private fun crossesFileBoundary(chunk: DocumentChunk, files: List<DocumentFile>): Boolean {
            val content = chunk.content
            return files.any { first ->
                val tail = first.content.trim().takeLast(MARKER_LENGTH)
                val tailAt = if (tail.length < MARKER_LENGTH) -1 else content.indexOf(tail)
                tailAt >= 0 && files.any { second ->
                    if (second === first) return@any false
                    val head = second.content.trim().take(MARKER_LENGTH)
                    val headAt = if (head.length < MARKER_LENGTH) -1 else content.indexOf(head)
                    headAt > tailAt
                }
            }
        }
    }
}
