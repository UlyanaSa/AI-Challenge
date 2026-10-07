package com.osvin.aichallenge.rag

import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.ui.DayCorpus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Тесты набора контрольных вопросов: он обязан быть полным и подтверждённым базой.
 *
 * Набор — это измерительный прибор сравнения, и ошибка в нём не видна в отчёте: вопрос с фактом,
 * которого в книге нет, снизил бы оценку обоим режимам, а вопрос со страницей из другого места —
 * оценку поиску. Поэтому набор проверяется против самого корпуса: каждый признак факта должен
 * найтись на той странице, откуда ответ, — тем же сопоставлением, которым потом сверяются ответы
 * модели.
 *
 * Корпус для проверки собирается тем же путём, что в прогоне, из встроенного PDF. PDF не лежит
 * в репозитории, поэтому без него проверка пропускается, а не притворяется пройденной.
 */
class ControlsTest {

    @Test
    fun `в наборе десять вопросов с номерами, фактами, ожиданием и страницами`() {
        val questions = Controls.questions

        assertEquals(10, questions.size, "набор дня — десять вопросов")
        assertEquals(questions.size, questions.map { it.id }.distinct().size, "номера вопросов уникальны")
        assertEquals(questions.size, questions.map { it.question }.distinct().size, "вопросы не повторяются")
        assertEquals(1, questions.count { it.absent }, "в наборе ровно один вопрос без ответа в базе")
        for (question in questions) {
            assertTrue(question.question.trim().endsWith("?"), "${question.id}: вопрос сформулирован как вопрос")
            assertTrue(question.expected.isNotBlank(), "${question.id}: у вопроса есть ожидаемый ответ словами")
            assertTrue(question.section.isNotBlank(), "${question.id}: назван раздел книги")
            assertTrue(question.note.isNotBlank(), "${question.id}: в наборе объяснено, зачем вопрос")
            assertTrue(question.facts.isNotEmpty(), "${question.id}: у вопроса есть ожидание")
            if (question.absent) {
                assertTrue(question.pages.isEmpty(), "${question.id}: у вопроса без ответа не может быть страниц")
            } else {
                assertTrue(
                    question.pages.all { it in DayCorpus.PAGES },
                    "${question.id}: страницы вопроса входят в корпус дня ${DayCorpus.PAGES}"
                )
            }
            for (fact in question.facts) {
                assertTrue(fact.text.isNotBlank(), "${question.id}: ожидание описано словами")
                assertTrue(
                    fact.keywords.isNotEmpty() && fact.keywords.all { it.isNotBlank() },
                    "${question.id}: у факта «${fact.text}» есть признаки для проверки"
                )
            }
        }
    }

    @Test
    fun `каждый факт подтверждается текстом своих страниц`() = runBlocking {
        val dir = Files.createTempDirectory("rag-controls")
        val base = Corpus.read(dir)
        assumeTrue(MISSING_CORPUS, base != null)
        // assumeTrue прерывает тест при отсутствии корпуса; здесь только сужение типа.
        val corpus = base ?: return@runBlocking
        val file = corpus.document.files.single()
        val text = pagesOf(file)
        val whole = Check.normalize(file.content)

        for (question in Controls.questions) {
            for (fact in question.facts) {
                val found = question.pages.filter { page ->
                    val pageText = text[page]
                    pageText != null && fact.keywords.any { Check.matches(Check.normalize(pageText), it) }
                }
                if (question.absent) {
                    // Признак отказа не должен встречаться в корпусе вовсе: иначе «в источниках нет
                    // ответа» засчитывалось бы по тексту из базы, и вопрос не проверял бы ничего.
                    assertTrue(
                        fact.keywords.none { Check.matches(whole, it) },
                        "${question.id}: признак «${fact.text}» встречается в корпусе — эталон нельзя проверить"
                    )
                } else {
                    assertTrue(
                        found.isNotEmpty(),
                        "${question.id}: признак факта «${fact.text}» не найден на страницах ${question.pages}"
                    )
                }
            }
        }
    }

    /** Текст по страницам: страницы размечены смещениями в тексте файла, а не в самом тексте. */
    private fun pagesOf(file: DocumentFile): Map<Int, String> {
        val marks = file.pages.sortedBy { it.offset }
        return marks.mapIndexed { index, mark ->
            val end = marks.getOrNull(index + 1)?.offset ?: file.content.length
            mark.page to file.content.substring(mark.offset, end)
        }.toMap()
    }

    private companion object {
        const val MISSING_CORPUS =
            "корпус дня не собран: положите PDF в indexing-ui/src/main/resources/book.pdf"
    }
}
