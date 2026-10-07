package com.osvin.aichallenge.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты сверки ответа с ожиданием: засчитанным считается факт, а не совпадение строк.
 *
 * Здесь проверяются случаи, где ошибка проверки была бы незаметна в отчёте и перевернула бы
 * вывод сравнения: слово в другой форме (модель пересказывает своими словами), «ё» и регистр,
 * словосочетание против отдельных слов, ссылка на фрагмент, которого модели не давали, страница,
 * названная диапазоном, и попадание в базу по тексту, а не по номеру страницы. Оценки и итоги
 * складываются из этих ответов, поэтому цена ошибки здесь — неверный вывод обо всей работе дня.
 */
class CheckTest {

    private fun question(facts: List<Fact>, pages: List<Int> = listOf(8, 9)) = ControlQuestion(
        id = "q",
        question = "вопрос",
        expected = "ожидание",
        facts = facts,
        pages = pages,
        section = "Глава первая",
        note = "тест"
    )

    private fun source(rank: Int, pages: List<Int>) = Source(
        rank = rank,
        similarity = 0.5,
        text = "текст",
        id = "chunk-$rank",
        source = "book.md",
        title = null,
        pages = pages,
        section = "Глава первая",
        chunkIndex = rank
    )

    @Test
    fun `факт засчитывается по основе слова в любой форме`() {
        assertTrue(Check.matches(Check.normalize("Воспитание сына было поручено ему."), "воспитани"))
        assertTrue(Check.matches(Check.normalize("Он занимался воспитанием сына."), "воспитани"))
        // Основа — не корень: «воспитатель» и «воспитание» разошлись, и признак их не путает.
        assertFalse(Check.matches(Check.normalize("Он стал воспитателем сына."), "воспитани"))
    }

    @Test
    fun `факт не засчитывается, если в ответе нет ни одного признака`() {
        assertFalse(Check.matches(Check.normalize("Она заботилась о нём долгие годы."), "двадцать два"))
    }

    @Test
    fun `выражение ищется выражением, а не двумя словами по отдельности`() {
        assertTrue(Check.matches(Check.normalize("Он созывал гостей два раза в год."), "два раза в год"))
        assertFalse(
            Check.matches(Check.normalize("Дважды в год он принимал гостей."), "два раза в год"),
            "синоним не ловится, и это известная граница проверки"
        )
    }

    @Test
    fun `регистр и ё не мешают сопоставлению`() {
        assertTrue(Check.matches(Check.normalize("Высокая, ЖЁЛТАЯ женщина."), "желтая"))
        assertTrue(Check.matches(Check.normalize("высокая, желтая женщина"), "жёлтая"))
    }

    @Test
    fun `ссылка на невыданный фрагмент источником не считается`() {
        val sources = listOf(source(1, listOf(8)), source(2, listOf(10)))

        assertEquals(listOf(1, 2), Check.citedFragments("Опирался на [1] и [2].", sources))
        assertEquals(listOf(1), Check.citedFragments("Опирался на [1] и [7].", sources))
    }

    @Test
    fun `страницы в ответе разбираются и диапазоном`() {
        assertEquals(listOf(8), Check.citedPages("Об этом сказано на стр. 8."))
        assertEquals(listOf(8, 9), Check.citedPages("Об этом сказано на стр. 8–9."))
        assertEquals(listOf(25), Check.citedPages("См. странице 25."))
        assertEquals(emptyList(), Check.citedPages("Об этом в книге не сказано."))
    }

    @Test
    fun `попадание в базу считается по тексту фрагмента, а не по странице`() {
        val control = question(
            facts = listOf(Fact("двадцать два года", listOf("двадцать два"))),
            pages = listOf(10)
        )
        val neighbour = source(1, listOf(10)).copy(text = "На соседней странице о другом.")
        val answer = source(2, listOf(19)).copy(text = "Нянчилась с ним двадцать два года.")

        val byNeighbour = Check.evaluate(control, "", listOf(neighbour))
        assertEquals(listOf(10), byNeighbour.retrievedPages, "нужная страница в выдаче есть")
        assertEquals(listOf(false), byNeighbour.retrievedFacts, "но ответа в найденном тексте нет")

        val byAnswer = Check.evaluate(control, "", listOf(answer))
        assertEquals(emptyList(), byAnswer.retrievedPages, "страница не та")
        assertEquals(listOf(true), byAnswer.retrievedFacts, "а текст с ответом найден")
    }

    @Test
    fun `попадание в базу и ссылка на неё считаются раздельно`() {
        val control = question(
            facts = listOf(Fact("сына", listOf("сын"))),
            pages = listOf(8, 10)
        )
        val sources = listOf(source(1, listOf(8)), source(2, listOf(13)))

        val ignored = Check.evaluate(control, "Жена оставила ему сына.", sources)
        assertEquals(listOf(true), ignored.facts)
        assertEquals(listOf(8), ignored.retrievedPages, "нужная страница нашлась в выдаче")
        assertEquals(emptyList(), ignored.usedPages, "но ответ на неё не сослался")

        val cited = Check.evaluate(control, "На стр. 10 сказано про сына.", sources)
        assertEquals(listOf(10), cited.usedPages, "страница названа словами в ответе")
    }

    @Test
    fun `оценка идёт по шкале задания и считается из фактов`() {
        val twoFacts = question(
            facts = listOf(Fact("сына", listOf("сын")), Fact("в Париже", listOf("париж"))),
            pages = listOf(8)
        )
        val found = listOf(source(1, listOf(8)).copy(text = "Жена умерла в Париже, оставив сына."))

        assertEquals(0, Check.evaluate(twoFacts, "", found).score, "пустой ответ — ноль")
        assertEquals(0, Check.evaluate(twoFacts, "О жене не сказано.", found).score, "без фактов — ноль")
        assertEquals(1, Check.evaluate(twoFacts, "Остался сын.", found).score, "половина фактов — один балл")
        assertEquals(2, Check.evaluate(twoFacts, "Остался сын, жена умерла в Париже.", found).score, "все факты — два")
    }

    @Test
    fun `ошибка поиска и ошибка генерации различаются`() {
        val control = question(facts = listOf(Fact("сына", listOf("сын"))), pages = listOf(8))
        val empty = emptyList<Source>()
        val found = listOf(source(1, listOf(8)).copy(text = "Жена оставила ему сына."))

        val notFound = Check.evaluate(control, "В романе у него был сын.", empty)
        assertEquals(Outcome.RETRIEVAL_ERROR, Check.outcome(control, notFound), "нужного текста в выдаче не было")

        val wrongAnswer = Check.evaluate(control, "У него не было детей.", found)
        assertEquals(Outcome.GENERATION_ERROR, Check.outcome(control, wrongAnswer), "текст был, ответ неверный")

        val correct = Check.evaluate(control, "Остался сын.", found)
        assertEquals(Outcome.ANSWERED, Check.outcome(control, correct))
    }

    @Test
    fun `вопрос без ответа в базе проверяет отказ, а не поиск`() {
        val absent = question(
            facts = listOf(Fact("в источниках нет ответа", listOf("недостаточно данн", "не говорит"))),
            pages = emptyList()
        )
        assertTrue(absent.absent)

        val refusal = Check.evaluate(absent, "Во фрагментах об этом не говорится.", emptyList())
        assertEquals(2, refusal.score)
        assertEquals(Outcome.ANSWERED, Check.outcome(absent, refusal), "признать нехватку сведений — правильный ответ")

        val invented = Check.evaluate(absent, "Пётр Степанович бежал за границу.", emptyList())
        assertEquals(0, invented.score)
        assertEquals(
            Outcome.GENERATION_ERROR,
            Check.outcome(absent, invented),
            "ответ по памяти там, где базы не хватило, — ошибка генерации"
        )
    }
}
