package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.chunking.StructuralChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Фрагмент-ответ: интерфейс показывает не чанк целиком, а тот его абзац, который отвечает
 * на вопрос, — по этому абзацу человек и понимает, что поиск нашёл нужное место.
 */
class AnswerFragmentTest {

    @Test
    fun `фрагмент — абзац с наибольшим числом слов вопроса`() {
        val chunk = listOf(
            "## Заголовок раздела",
            "",
            "Этот абзац рассказывает про кошек и собак.",
            "",
            "Компонент объединяет классы, которые изменяются вместе.",
            ""
        ).joinToString("\n")

        val fragment = AnswerFragment.best(chunk, "какие классы объединять в компонент")

        assertEquals("Компонент объединяет классы, которые изменяются вместе.", fragment)
    }

    @Test
    fun `заголовок раздела не выигрывает у абзацев и во фрагмент не попадает`() {
        val chunk = "## Классы в компоненте\n\nАбзац совсем про другое."

        val fragment = AnswerFragment.best(chunk, "классы компонент")

        assertEquals("Абзац совсем про другое.", fragment)
    }

    @Test
    fun `когда общих слов с вопросом нет, показывается начало чанка`() {
        val chunk = "## Раздел\n\nПервый абзац чанка.\n\nВторой абзац чанка."

        val fragment = AnswerFragment.best(chunk, "совершенно другой вопрос")

        assertEquals("Первый абзац чанка.", fragment)
    }

    @Test
    fun `длинный абзац режется по границам предложений вокруг лучшего`() {
        val sentences = List(20) { "Это предложение номер $it о разном." } +
            "Компонент объединяет классы, изменяющиеся по одной причине."
        val chunk = "## Раздел\n\n" + sentences.joinToString(" ")

        val fragment = AnswerFragment.best(chunk, "какие классы объединять в компонент", maxChars = 90)

        assertTrue(fragment.contains("изменяющиеся по одной причине"), "лучшее предложение обязано остаться")
        assertTrue(fragment.length <= 90, "фрагмент не длиннее предела, получено ${fragment.length}")
        assertTrue(fragment.startsWith("Компонент объединяет классы"), "окно расширяется вокруг лучшего предложения")
    }

    @Test
    fun `фрагмент чанка корпуса содержит текст ответа на эталонный вопрос`() {
        // Файлы книги в репозиторий не попадают: без них проверять нечего, и тест пропускается.
        assumeTrue(Corpus.MISSING_FIXTURE, Corpus.available)
        val chunk = StructuralChunker().chunk(Corpus.documents.single())
            .first { it.metadata.section == "Принцип согласованного изменения" }
        val question = DemoQueries.all.first { it.section == "Принцип согласованного изменения" }.text

        val fragment = AnswerFragment.best(chunk.content, question)

        assertTrue(fragment.length <= 400)
        assertTrue(fragment.contains("класс", ignoreCase = true), "фрагмент обязан говорить о классах: $fragment")
        assertTrue(fragment.contains("принцип", ignoreCase = true), "и о принципе, о котором спросили: $fragment")
        assertTrue(chunk.content.contains(fragment), "фрагмент обязан быть куском самого чанка")
        assertTrue(!fragment.startsWith("#"), "заголовок раздела во фрагмент не входит")
    }
}
