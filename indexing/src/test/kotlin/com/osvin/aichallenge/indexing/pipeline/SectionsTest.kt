package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Границы раздела: эталон для оценки поиска обязан обрываться на следующем заголовке того же
 * уровня и включать подзаголовки — иначе в эталон попал бы соседний раздел, и «релевантность»
 * считалась бы по чужому тексту.
 */
class SectionsTest {

    private val documents = listOf(
        Document(
            id = "doc",
            title = "Doc",
            files = listOf(
                DocumentFile(
                    "a.md",
                    """
                    # Top

                    Intro of the top section.

                    ## Middle

                    Body of the middle section.

                    ### Nested

                    Body of the nested subsection.

                    ## Next

                    Body of the next section.
                    """.trimIndent()
                )
            )
        )
    )

    @Test
    fun `section text stops at the next heading of any level`() {
        val text = Sections.text(documents, "a.md", "Middle")

        assertEquals("Body of the middle section.", text)
    }

    @Test
    fun `top level section does not swallow the following subsections`() {
        val text = Sections.text(documents, "a.md", "Top")

        assertEquals("Intro of the top section.", text)
    }

    @Test
    fun `nested subsection is its own section`() {
        val text = Sections.text(documents, "a.md", "Nested")

        assertEquals("Body of the nested subsection.", text)
    }

    @Test
    fun `unknown section or file gives null`() {
        assertNull(Sections.text(documents, "a.md", "Absent"))
        assertNull(Sections.text(documents, "absent.md", "Middle"))
    }
}
