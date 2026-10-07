package com.osvin.aichallenge.indexing.chunking

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Поведенческие тесты baseline-нарезки. Все проверки смотрят на наблюдаемый результат (текст,
 * метаданные, идентификаторы), а не на внутреннее устройство класса.
 */
class FixedSizeChunkerTest {

    private fun document(vararg files: DocumentFile) =
        Document(id = "doc", title = "Документ", files = files.toList())

    private val alphabet = "abcdefghijklmnopqrstuvwxyz"

    @Test
    fun `число чанков и их длины определяются окном и шагом`() {
        val chunks = FixedSizeChunker(chunkSize = 10, overlap = 2).chunk(document(DocumentFile("a.md", alphabet)))

        assertEquals(4, chunks.size, "26 символов при окне 10 и шаге 8 дают 4 окна")
        assertEquals(listOf(10, 10, 10, 2), chunks.map { it.content.length })
    }

    @Test
    fun `перекрытие соседних чанков равно overlap`() {
        val chunks = FixedSizeChunker(chunkSize = 10, overlap = 3).chunk(document(DocumentFile("a.md", alphabet)))

        val step = 10 - 3
        for (i in 0 until chunks.size - 1) {
            assertEquals(
                chunks[i].content.takeLast(3),
                chunks[i + 1].content.take(3),
                "хвост чанка $i и начало чанка ${i + 1} должны совпадать на длине перекрытия"
            )
            // Перекрытие должно быть именно на шаге окна, а не случайным совпадением строки.
            assertEquals(alphabet.substring(i * step, i * step + 3), chunks[i].content.take(3))
        }
    }

    @Test
    fun `конкатенация уникальных частей восстанавливает исходный текст`() {
        val chunks = FixedSizeChunker(chunkSize = 10, overlap = 2).chunk(document(DocumentFile("a.md", alphabet)))

        val rebuilt = buildString {
            append(chunks.first().content)
            chunks.drop(1).forEach { append(it.content.drop(2)) }
        }

        assertEquals(alphabet, rebuilt, "перекрытия не должны ни терять, ни дублировать текст")
    }

    @Test
    fun `последний чанк короче полного окна и индексы идут подряд`() {
        val chunks = FixedSizeChunker(chunkSize = 10, overlap = 2).chunk(document(DocumentFile("a.md", alphabet)))

        assertTrue(chunks.last().content.length < 10, "остаток 2 символа должен быть короче окна")
        assertEquals(listOf(0, 1, 2, 3), chunks.map { it.metadata.chunkIndex })
        assertTrue(chunks.all { it.metadata.strategy == ChunkingStrategyType.FIXED_SIZE })
        assertTrue(chunks.all { it.metadata.section == null }, "baseline заголовки не разбирает")
    }

    @Test
    fun `идентификаторы уникальны для документа из двух файлов`() {
        val chunks = FixedSizeChunker(chunkSize = 6, overlap = 1).chunk(
            document(DocumentFile("a.md", "aaaaaaaaaa"), DocumentFile("b.md", "bbbbbbbbbb"))
        )

        assertTrue(chunks.size > 2)
        assertEquals(chunks.size, chunks.map { it.id }.toSet().size)
    }

    @Test
    fun `чанк может пересекать границу файлов и относится к файлу начала`() {
        val document = document(DocumentFile("a.md", "A".repeat(20)), DocumentFile("b.md", "B".repeat(20)))
        // Склейка: 20 символов 'A', разделитель, 20 символов 'B'. Окно 25 перекрывает стык.
        val chunks = FixedSizeChunker(chunkSize = 25, overlap = 5).chunk(document)

        val crossing = chunks.first()
        assertEquals(25, crossing.content.length)
        assertTrue(crossing.content.contains('A'), "начало чанка — из первого файла")
        assertTrue(crossing.content.contains('B'), "хвост чанка заходит во второй файл")
        assertEquals("a.md", crossing.metadata.source, "source называет файл, где чанк начался")
    }

    @Test
    fun `хвостовые пробелы срезаются, но чанк не исчезает`() {
        val chunks = FixedSizeChunker(chunkSize = 10, overlap = 2).chunk(document(DocumentFile("a.md", "abcdefghij   ")))

        assertEquals(2, chunks.size)
        assertEquals("abcdefghij", chunks.first().content)
        assertEquals("ij", chunks.last().content, "хвост окна из пробелов срезается")
    }

    @Test
    fun `конструктор отвергает некорректные размеры`() {
        assertFailsWith<IllegalArgumentException> { FixedSizeChunker(chunkSize = 100, overlap = 100) }
        assertFailsWith<IllegalArgumentException> { FixedSizeChunker(chunkSize = 100, overlap = 150) }
        assertFailsWith<IllegalArgumentException> { FixedSizeChunker(chunkSize = 0, overlap = 0) }
        assertFailsWith<IllegalArgumentException> { FixedSizeChunker(chunkSize = 10, overlap = -1) }
    }

    @Test
    fun `страницы берутся у файла, в котором лежит текст окна`() {
        val paged = PageMarkers.stripAndIndex(PAGED_BOOK)
        val file = DocumentFile("book.md", paged.content, paged.pages)
        val chunks = FixedSizeChunker(chunkSize = 40, overlap = 0).chunk(document(file))

        assertEquals(listOf(10, 11), chunks[0].metadata.pages, "окно накрыло обе страницы")
        assertEquals(listOf(11), chunks[1].metadata.pages, "второе окно начинается на второй странице")
    }

    private companion object {
        val PAGED_BOOK = listOf(
            "# Документ", "", "<!-- page:10 -->", "", "## Раздел", "",
            "Первый абзац.", "", "<!-- page:11 -->", "", "Второй абзац.", ""
        ).joinToString("\n")
    }
}
