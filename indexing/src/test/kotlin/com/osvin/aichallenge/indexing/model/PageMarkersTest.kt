package com.osvin.aichallenge.indexing.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разметка страниц: маркеры снимаются с текста, а страницы остаются привязанными к своим блокам.
 *
 * Проверяется наблюдаемое: текст корпуса без маркеров совпадает с исходным, а смещение страницы
 * указывает на тот блок, который на этой странице начинается, — по этим смещениям чанк и узнаёт
 * свои страницы.
 */
class PageMarkersTest {

    private val raw = listOf(
        "# Документ",
        "",
        "<!-- page:10 -->",
        "",
        "## Раздел",
        "",
        "Первый абзац.",
        "",
        "<!-- page:11 -->",
        "",
        "Второй абзац.",
        ""
    ).joinToString("\n")

    @Test
    fun `маркеры снимаются с текста, а страницы остаются с своими блоками`() {
        val paged = PageMarkers.stripAndIndex(raw)

        assertEquals("# Документ\n\n## Раздел\n\nПервый абзац.\n\nВторой абзац.\n", paged.content)
        assertEquals(listOf(10, 11), paged.pages.map { it.page })
        assertTrue(paged.content.substring(paged.pages[0].offset).startsWith("## Раздел"))
        assertTrue(paged.content.substring(paged.pages[1].offset).startsWith("Второй абзац."))
    }

    @Test
    fun `текст без маркеров остаётся прежним и страниц не даёт`() {
        val paged = PageMarkers.stripAndIndex("Просто абзац без вёрстки.")

        assertEquals("Просто абзац без вёрстки.", paged.content)
        assertTrue(paged.pages.isEmpty())
    }

    @Test
    fun `файл знает страницу по смещению и все страницы диапазона`() {
        val paged = PageMarkers.stripAndIndex(raw)
        val file = DocumentFile("book.md", paged.content, paged.pages)

        assertNull(file.pageAt(0), "до первой размеченной страницы страницы неизвестны")
        assertEquals(10, file.pageAt(paged.pages[0].offset))
        assertEquals(11, file.pageAt(paged.pages[1].offset + 5))
        assertEquals(listOf(10), file.pagesIn(paged.pages[0].offset, paged.pages[0].offset + 3))
        assertEquals(
            listOf(10, 11),
            file.pagesIn(paged.pages[0].offset, paged.pages[1].offset),
            "диапазон через разрыв страницы обязан вернуть обе страницы"
        )
        assertTrue(file.pagesIn(0, 3).isEmpty())
    }

    @Test
    fun `подпись страниц читается диапазоном, перечислением и пустой не бывает`() {
        assertEquals("стр. 115", PageMarkers.format(listOf(115)))
        assertEquals("стр. 115–116", PageMarkers.format(listOf(116, 115)))
        assertEquals("стр. 115, 118", PageMarkers.format(listOf(115, 118, 115)))
        assertNull(PageMarkers.format(emptyList()))
    }
}
