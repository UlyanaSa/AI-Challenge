package com.osvin.aichallenge.indexing.chunking

import com.osvin.aichallenge.indexing.model.ChunkMetadata
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.model.chunkId

/**
 * Baseline-стратегия: документ склеивается в один поток текста и режется окном фиксированной
 * длины с перекрытием.
 *
 * Это намеренно «глупая» стратегия: границы файлов, заголовков, абзацев и блоков кода для неё
 * не существуют. Окно двигается на `chunkSize - overlap`, поэтому соседние чанки делят участок
 * длиной [overlap] — перекрытие нужно, чтобы фраза на стыке не потерялась для embedding.
 *
 * Склейка файлов разделителем `"\n\n"` идёт не ради структуры, а потому что так граница файла
 * остаётся хотя бы видимым пробелом, а не слипанием двух слов. `source` при этом берётся по
 * началу окна: если чанк пересёк границу, честно назвать один файл нельзя, и это осознанная
 * потеря — ровно та, которую фиксирует сравнение стратегий. По той же причине страницы считаются
 * только по части окна внутри этого файла: второй файл имеет свою нумерацию.
 */
class FixedSizeChunker(
    val chunkSize: Int = 1500,
    val overlap: Int = 200
) : ChunkingStrategy {

    init {
        require(chunkSize > 0) { "chunkSize должен быть положительным, получено $chunkSize" }
        require(overlap >= 0) { "overlap не может быть отрицательным, получено $overlap" }
        // Иначе шаг окна был бы нулевым или отрицательным и нарезка зациклилась бы.
        require(overlap < chunkSize) {
            "overlap ($overlap) должен быть меньше chunkSize ($chunkSize), иначе окно не двигается вперёд"
        }
    }

    override val type: ChunkingStrategyType = ChunkingStrategyType.FIXED_SIZE

    override fun chunk(document: Document): List<DocumentChunk> {
        // Запоминаем смещение начала каждого файла, чтобы по началу окна восстановить `source`.
        val text = StringBuilder()
        val fileStarts = ArrayList<Int>(document.files.size)
        document.files.forEachIndexed { index, file ->
            if (index > 0) text.append(FILE_SEPARATOR)
            fileStarts.add(text.length)
            text.append(file.content)
        }
        val full = text.toString()
        if (full.isEmpty()) return emptyList()

        val step = chunkSize - overlap
        val chunks = ArrayList<DocumentChunk>()
        var chunkIndex = 0
        var start = 0
        while (start < full.length) {
            val end = minOf(start + chunkSize, full.length)
            val raw = full.substring(start, end)
            // Хвостовые пробелы — шум склейки: срезаем, но не до пустоты, иначе чанк исчез бы,
            // а вместе с ним и кусок покрытия. Пустой остаток при этом всё равно не создаётся:
            // start < full.length гарантирует непустой raw.
            val content = raw.trimEnd().ifEmpty { raw }
            val fileIndex = idxOfStart(fileStarts, start)
            val file = document.files[fileIndex]
            val source = file.name
            // Страницы считаются по той части окна, которая лежит в этом файле: дальше начинается
            // текст соседнего файла со своей нумерацией, и страницы двух глав в одном чанке
            // читались бы как один диапазон.
            val fileOffset = start - fileStarts[fileIndex]
            val pages = file.pagesIn(fileOffset, minOf(end - fileStarts[fileIndex], file.content.length))
            chunks.add(
                DocumentChunk(
                    id = chunkId(type, document.id, source, chunkIndex),
                    content = content,
                    metadata = ChunkMetadata(
                        source = source,
                        title = document.title,
                        section = null,
                        chunkIndex = chunkIndex,
                        strategy = type,
                        pages = pages
                    )
                )
            )
            chunkIndex++
            start += step
        }
        return chunks
    }

    /** Индекс файла, внутри которого начинается окно; разделитель относится к предыдущему файлу. */
    private fun idxOfStart(fileStarts: List<Int>, offset: Int): Int {
        var result = 0
        for (i in fileStarts.indices) {
            if (fileStarts[i] <= offset) result = i else break
        }
        return result
    }

    private companion object {
        const val FILE_SEPARATOR = "\n\n"
    }
}
