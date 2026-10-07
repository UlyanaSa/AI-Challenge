package com.osvin.aichallenge.indexing.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки корпуса: набор документов — часть условия задачи, и эксперимент имеет смысл лишь
 * тогда, когда обе стратегии получают один и тот же непустой набор, а у каждого запроса есть
 * раздел-эталон. Если корпус разойдётся с запросами, оценка Top-3 посчитает шум.
 */
class CorpusTest {

    /**
     * Без фикстуры проверять нечего — весь класс про неё.
     *
     * Файлы книги в репозиторий не попадают (авторское право, `.gitignore`), поэтому на свежем
     * клоне весь класс помечается пропущенным, а не падает.
     */
    @Before
    fun requireFixture() {
        assumeTrue(Corpus.MISSING_FIXTURE, Corpus.available)
    }

    @Test
    fun `set contains one document with four chapter files`() {
        val documents = Corpus.documents

        assertEquals(1, documents.size)
        assertEquals(
            listOf(
                "part-iv-intro.md",
                "chapter-12-components.md",
                "chapter-13-component-cohesion.md",
                "chapter-14-component-coupling.md"
            ),
            documents.single().files.map { it.name }
        )
    }

    @Test
    fun `file names are unique across the set`() {
        val names = Corpus.documents.flatMap { it.files }.map { it.name }

        assertEquals(names.size, names.distinct().size, "source обязан называть ровно один файл")
    }

    @Test
    fun `every file is a markdown document with a title`() {
        Corpus.documents.flatMap { it.files }.forEach { file ->
            assertTrue(file.content.startsWith("# "), "${file.name}: файл начинается не с заголовка")
        }
    }

    @Test
    fun `chapter files carry section headings and the corpus is large enough`() {
        val chapters = Corpus.documents.single().files.filter { it.name.startsWith("chapter-") }

        assertEquals(3, chapters.size)
        chapters.forEach { file ->
            assertTrue(file.content.contains("\n## "), "${file.name}: нет ни одного раздела второго уровня")
            assertTrue(file.content.length > 10_000, "${file.name}: слишком короткая глава (${file.content.length})")
        }
        val chars = Corpus.documents.sumOf { document -> document.files.sumOf { it.content.length } }
        assertTrue(chars > 50_000, "корпус слишком мал для сравнения стратегий: $chars символов")
    }

    @Test
    fun `converted listing stays a fenced code block`() {
        val chapter = Corpus.documents.single().files.first { it.name == "chapter-12-components.md" }

        assertTrue(chapter.content.contains("```\n*200"), "листинг PDP-8 не сохранён код-блоком")
    }

    @Test
    fun `every demo query has its reference section with an answer`() {
        val documents = Corpus.documents

        DemoQueries.all.forEach { spec ->
            val answer = assertNotNull(Sections.text(documents, spec.source, spec.section), "нет раздела «${spec.section}» в ${spec.source}")
            assertTrue(answer.length > 200, "${spec.source}/${spec.section}: текст раздела слишком короткий для эталона")
        }
    }

    @Test
    fun `reference sections are distinct so queries do not share one answer`() {
        val sections = DemoQueries.all.map { "${it.source}::${it.section}" }

        assertEquals(sections.size, sections.distinct().size)
    }

    @Test
    fun `queries cover every document of the set and all three chapters`() {
        val queried = DemoQueries.all.map { spec ->
            val document = Corpus.documents.firstOrNull { document -> document.files.any { it.name == spec.source } }
            assertNotNull(document, "запрос ссылается на файл вне корпуса: ${spec.source}")
            document.id
        }.toSet()

        assertEquals(Corpus.documents.map { it.id }.toSet(), queried, "запросы должны покрывать все документы набора")
        assertEquals(
            setOf(
                "chapter-12-components.md",
                "chapter-13-component-cohesion.md",
                "chapter-14-component-coupling.md"
            ),
            DemoQueries.all.map { it.source }.toSet(),
            "запросы должны покрывать все главы части"
        )
        assertTrue(DemoQueries.all.size >= 5, "задание требует минимум пять запросов")
    }
}
