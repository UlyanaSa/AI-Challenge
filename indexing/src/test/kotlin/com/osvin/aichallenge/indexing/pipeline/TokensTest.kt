package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Счёт токенов: слово — последовательность букв и цифр, всё остальное (знаки, разметка, пробелы)
 * токеном не считается. По этому счёту видно объём работы embedding, поэтому проверяется он
 * ровно на тех случаях, которые в корпусе и встречаются: markdown-разметка, дефис, цифры.
 */
class TokensTest {

    @Test
    fun `разметка и знаки препинания токенами не считаются`() {
        assertEquals(6, Tokens.count("## Компоненты — это единицы развертывания.\n\n- Первый\n- Второй"))
    }

    @Test
    fun `цифры считаются, дефис слово делит`() {
        assertEquals(6, Tokens.count("p. 115, RFC-2119 — 1 стандарт"))
    }

    @Test
    fun `служебные слова не отбрасываются`() {
        // В отличие от TextOverlap.tokens: там «и» и «в» отброшены как не различающие смысл.
        assertEquals(2, Tokens.count("и в"))
        assertEquals(emptySet<String>(), TextOverlap.tokens("и в"))
    }

    @Test
    fun `токены документа складываются из файлов`() {
        val document = Document(
            id = "doc",
            title = null,
            files = listOf(
                DocumentFile("a.md", "альфа бета"),
                DocumentFile("b.md", "гамма")
            )
        )

        assertEquals(3, Tokens.count(document))
        assertEquals(3, Tokens.count(listOf(document)))
        assertEquals(2, Tokens.count(document.files.first()))
    }

    @Test
    fun `пустой текст токенов не даёт`() {
        // Разметка страниц снимается при чтении корпуса, поэтому сюда попадает только текст.
        assertEquals(0, Tokens.count("   \n\n\t --- *** "))
    }
}
