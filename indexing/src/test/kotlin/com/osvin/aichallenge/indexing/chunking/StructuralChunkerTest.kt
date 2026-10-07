package com.osvin.aichallenge.indexing.chunking

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Поведенческие тесты структурной нарезки: проверяется, что границы разделов, файлов и абзацев
 * действительно уважаются, а метаданные называют ровно тот раздел и файл, откуда взят текст.
 */
class StructuralChunkerTest {

    private fun document(vararg files: DocumentFile) =
        Document(id = "doc", title = "Документ", files = files.toList())

    @Test
    fun `заголовки открывают разделы и попадают в метаданные`() {
        val content = "# First\nAlpha paragraph.\n\n# Second\nBeta paragraph."
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", content)))

        assertEquals(listOf("First", "Second"), chunks.map { it.metadata.section })
        val first = chunks.first { it.metadata.section == "First" }
        assertTrue(first.content.contains("# First"), "сырой markdown заголовка остаётся в тексте")
        assertTrue(first.content.contains("Alpha paragraph."))
    }

    @Test
    fun `у преамбулы section равен null, у разделов заполнен`() {
        val content = "Intro line.\n\n# Title\nBody."
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", content)))

        assertTrue(chunks.any { it.metadata.section == null && it.content.contains("Intro line.") })
        assertTrue(chunks.any { it.metadata.section == "Title" && it.content.contains("Body.") })
    }

    @Test
    fun `ни один чанк не пересекает границу файлов`() {
        val document = document(
            DocumentFile("a.md", "alpha-only heading\nAAA body"),
            DocumentFile("b.md", "beta-only heading\nBBB body")
        )
        val chunks = StructuralChunker().chunk(document)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertFalse(
                chunk.content.contains("alpha-only") && chunk.content.contains("beta-only"),
                "чанк не должен содержать текст сразу из двух файлов"
            )
            val expected = if (chunk.content.contains("alpha-only")) "a.md" else "b.md"
            assertEquals(expected, chunk.metadata.source)
        }
    }

    @Test
    fun `длинный раздел делится на чанки не длиннее максимума и по абзацам`() {
        val alpha = "alpha ".repeat(10).trim()
        val beta = "beta ".repeat(10).trim()
        val gamma = "gamma ".repeat(10).trim()
        val content = "# Big\n\n$alpha\n\n$beta\n\n$gamma"

        val chunks = StructuralChunker(maxChunkSize = 100).chunk(document(DocumentFile("doc.md", content)))

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.content.length <= 100 }, "ни один чанк не превышает maxChunkSize")
        assertTrue(chunks.first().content.contains("# Big") && chunks.first().content.contains("alpha"))
        // Второй чанк обязан начинаться с начала абзаца, а не с середины слова.
        assertTrue(chunks[1].content.startsWith("beta"), "граница чанка проходит по абзацу")
    }

    @Test
    fun `слишком длинный одиночный абзац режется по границам строк`() {
        val content = "12345678\nabcdefgh\nABCDEFGH"
        val chunks = StructuralChunker(maxChunkSize = 20).chunk(document(DocumentFile("doc.md", content)))

        assertTrue(chunks.all { it.content.length <= 20 })
        assertEquals(2, chunks.size)
        assertEquals("ABCDEFGH", chunks[1].content, "вторая строка не разрезана посередине")
        assertTrue(chunks[0].content.endsWith("abcdefgh"))
    }

    @Test
    fun `блок кода не разрывается, пока помещается`() {
        val code = "```kotlin\n" + "val x = 1\n".repeat(5) + "```"
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", "# Code\n\n$code")))

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().content.contains(code), "весь fenced-блок лежит в одном чанке")
    }

    @Test
    fun `маленькие разделы не склеиваются между собой`() {
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", "# A\nshort a\n\n# B\nshort b")))

        assertEquals(2, chunks.size, "каждый раздел начинается с нового чанка")
        assertEquals(listOf("A", "B"), chunks.map { it.metadata.section })
        assertFalse(chunks[0].content.contains("short b"))
        assertFalse(chunks[1].content.contains("short a"))
    }

    @Test
    fun `заголовок без текста открывает чанк следующего раздела`() {
        val chunks = StructuralChunker().chunk(
            document(DocumentFile("doc.md", "# Часть\n\n## Глава\n\nТекст главы."))
        )

        // У заголовка части нет своего текста: он называет то, что идёт дальше, и попадает в чанк
        // главы, а не становится чанком из одной строки — искать в таком чанке нечего, а по близости
        // короткий чанк обгоняет содержательный.
        val chunk = chunks.single()
        assertEquals("Глава", chunk.metadata.section, "имя даёт самый глубокий заголовок группы")
        assertTrue(chunk.content.startsWith("# Часть"), "заголовок остаётся в тексте чанка")
        assertTrue(chunk.content.contains("Текст главы."))

        val tail = StructuralChunker().chunk(document(DocumentFile("doc.md", "Текст.\n\n# Хвост")))
        assertTrue(tail.last().content.contains("# Хвост"), "заголовок в конце файла не теряется")
    }

    @Test
    fun `маркеры списка не начинают новый чанк`() {
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", "# L\n- one\n- two\n- three")))

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().content.contains("- two"), "список остаётся одним блоком")
    }

    @Test
    fun `идентификаторы уникальны и нумеруются сквозным образом`() {
        val document = document(
            DocumentFile("a.md", "# One\nfirst"),
            DocumentFile("b.md", "# Two\nsecond")
        )
        val chunks = StructuralChunker().chunk(document)

        assertEquals(chunks.size, chunks.map { it.id }.toSet().size)
        assertEquals(listOf(0, 1), chunks.map { it.metadata.chunkIndex })
        assertTrue(chunks.all { it.metadata.strategy == ChunkingStrategyType.STRUCTURAL })
    }

    @Test
    fun `пустой файл не даёт чанков`() {
        assertTrue(StructuralChunker().chunk(document(DocumentFile("empty.md", ""))).isEmpty())
        assertTrue(StructuralChunker().chunk(document(DocumentFile("blank.md", "\n\n   \n"))).isEmpty())
        assertTrue(StructuralChunker().chunk(Document("doc", "Документ", emptyList())).isEmpty())
    }

    @Test
    fun `конструктор отвергает неположительный максимум`() {
        assertFailsWith<IllegalArgumentException> { StructuralChunker(maxChunkSize = 0) }
        assertFailsWith<IllegalArgumentException> { StructuralChunker(maxChunkSize = -10) }
    }

    @Test
    fun `чанк знает страницы книги, на которых лежит его текст`() {
        val paged = PageMarkers.stripAndIndex(PAGED_BOOK)
        val chunks = StructuralChunker().chunk(document(DocumentFile("book.md", paged.content, paged.pages)))

        assertEquals(2, chunks.size)
        // Заголовок документа стоит до первой размеченной страницы: он не лежит ни на одной.
        assertTrue(chunks[0].metadata.pages.isEmpty())
        assertEquals(listOf(10, 11), chunks[1].metadata.pages)
    }

    @Test
    fun `без карты страниц чанк честно сообщает, что страницы неизвестны`() {
        val chunks = StructuralChunker().chunk(document(DocumentFile("doc.md", "## Раздел\n\nАбзац.")))

        assertTrue(chunks.single().metadata.pages.isEmpty())
    }

    private companion object {
        // У заголовка документа есть свой абзац: заголовок без текста открывает чанк следующего
        // раздела, поэтому отдельным чанком до первой страницы остаётся только раздел с текстом.
        val PAGED_BOOK = listOf(
            "# Документ", "", "Вступление.", "", "<!-- page:10 -->", "", "## Раздел", "",
            "Первый абзац.", "", "<!-- page:11 -->", "", "Второй абзац.", ""
        ).joinToString("\n")
    }
}
