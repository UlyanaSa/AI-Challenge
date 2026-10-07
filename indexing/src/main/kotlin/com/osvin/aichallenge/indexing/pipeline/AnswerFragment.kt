package com.osvin.aichallenge.indexing.pipeline

/**
 * Кусок чанка, в котором лежит ответ на вопрос.
 *
 * Чанк — единица поиска, а не единица чтения: у фиксированной стратегии в него входит 1500
 * символов, и ответ в них занимает одно предложение. Показывать человеку весь чанк — значит
 * прятать ответ среди соседних абзацев, поэтому интерфейс показывает фрагмент: абзац, у которого
 * больше всего общего с вопросом.
 *
 * Мера — те же слова, что и в оценке релевантности ([TextOverlap.tokens]): сколько различных
 * слов вопроса встретилось в абзаце. Не частота и не длина: абзац отвечает на вопрос, если
 * говорит о том же, а не если повторяет слово чаще. Совпадений нет — возвращается начало чанка:
 * это честный «ничего похожего», а не пустая строка.
 *
 * Абзац длиннее [maxChars] режется по предложениям вокруг лучшего из них: обрезать абзац по
 * символам значило бы показать половину ответа, а обрезка по границе предложения остаётся
 * читаемой.
 *
 * Выбор идёт по словам, а не по смыслу: у раздела принципа почти каждый абзац повторяет его
 * термины, и «самый близкий» фрагмент может оказаться не первым абзацем определения — все они
 * остаются ответом. Поэтому фрагмент — подсказка, где читать, а не замена найденному чанку:
 * чанк целиком лежит в индексе, и страницы в метаданных указывают на место в книге.
 */
object AnswerFragment {

    private val paragraphBreak = Regex("\\n\\s*\\n")
    private val sentenceBreak = Regex("(?<=[.!?…])\\s+")

    /**
     * Лучший фрагмент чанка [chunk] для вопроса [question], не длиннее [maxChars] символов.
     *
     * Заголовок раздела в фрагмент не входит: он уже показан отдельно, а в тексте чанка стоит
     * первым блоком и всегда «выигрывал» бы у абзацев за счёт общих слов с вопросом.
     */
    fun best(chunk: String, question: String, maxChars: Int = 400): String {
        require(maxChars > 0) { "maxChars должен быть положительным, получено $maxChars" }

        val query = TextOverlap.tokens(question)
        val paragraphs = paragraphs(chunk)
        if (paragraphs.isEmpty()) return chunk.trim().take(maxChars)

        val best = paragraphs.maxByOrNull { score(it, query) }
        // Все абзацы без общих слов с вопросом: показываем начало — по нему видно, что чанк не тот.
        if (best == null || score(best, query) == 0) return (paragraphs.firstOrNull() ?: chunk).take(maxChars)

        return shrink(best, query, maxChars)
    }

    /**
     * Абзацы текста [text], которые отвечают на вопрос [question]: не больше [limit], в порядке
     * книги и каждый не длиннее [maxChars].
     *
     * Отличие от [best] — в том, сколько абзацев бывает ответом. Ответ бывает и одной строкой, и
     * несколькими абзацами подряд (формулировка и её разбор), а [best] в обоих случаях показал бы
     * один — и на перечислении выдал бы первую строку за весь ответ. Поэтому берутся абзацы, близкие
     * к лучшему: не слабее половины его оценки. Лучший проходит порог всегда, а абзац с одним общим
     * словом отсеивается — он отвечает на вопрос не больше, чем соседний: на страницах эталона
     * слово вопроса встречается почти в каждом абзаце, и «отвечает» по одному слову значило бы
     * «отвечает» почти везде.
     *
     * Показываются они в том порядке, в каком стоят в тексте, хотя отбираются по оценке: связный
     * ответ читается сверху вниз, и перестановка по оценке разорвала бы его.
     *
     * Абзацы без общих слов с вопросом не возвращаются: пустой список — честное «ничего похожего»,
     * и вызывающий решает, что показать вместо него.
     */
    fun matches(text: String, question: String, limit: Int = 5, maxChars: Int = 400): List<String> {
        require(limit > 0) { "limit должен быть положительным, получено $limit" }

        val query = TextOverlap.tokens(question)
        val paragraphs = paragraphs(text)
        if (query.isEmpty() || paragraphs.isEmpty()) return emptyList()

        val candidates = paragraphs.withIndex()
            .map { (index, paragraph) -> Triple(index, paragraph, score(paragraph, query)) }
            .filter { (_, _, matches) -> matches > 0 }
        // Пустой список кандидатов — не ошибка: эталонных страниц может и не быть в корпусе.
        if (candidates.isEmpty()) return emptyList()

        val best = candidates.maxOf { it.third }
        val scored = candidates
            .filter { (_, _, matches) -> matches * 2 >= best }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(limit)

        return scored.sortedBy { it.first }.map { shrink(it.second, query, maxChars) }
    }

    /** Абзацы текста: заголовок раздела в них не входит, пустые строки отброшены. */
    private fun paragraphs(text: String): List<String> = text.split(paragraphBreak)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Сколько различных слов вопроса встретилось в абзаце. */
    private fun score(paragraph: String, query: Set<String>): Int =
        if (query.isEmpty()) 0 else TextOverlap.tokens(paragraph).count { it in query }

    /** Режет абзац вокруг предложения с наибольшим числом слов вопроса. */
    private fun shrink(paragraph: String, query: Set<String>, maxChars: Int): String {
        if (paragraph.length <= maxChars) return paragraph

        val sentences = paragraph.split(sentenceBreak)
        val bestIndex = sentences.indices.maxByOrNull { score(sentences[it], query) } ?: 0
        var from = bestIndex
        var to = bestIndex
        var length = sentences[bestIndex].length

        // Расширяем окно вперёд, потом назад: продолжение ответа обычно идёт следом за лучшим
        // предложением, а не перед ним.
        while (to + 1 < sentences.size && length + 1 + sentences[to + 1].length <= maxChars) {
            to++
            length += 1 + sentences[to].length
        }
        while (from > 0 && length + 1 + sentences[from - 1].length <= maxChars) {
            from--
            length += 1 + sentences[from].length
        }

        return sentences.subList(from, to + 1).joinToString(" ").take(maxChars)
    }
}
