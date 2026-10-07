package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.search.SearchResult

/**
 * Эталонный ответ на поисковый запрос: раздел, в котором лежит ответ, и его текст.
 *
 * Проверка качества поиска идёт по тексту раздела, а не по метаданным чанка. Если бы «попаданием»
 * считался только чанк с нужным `section`, фиксированная стратегия не набрала бы ни одного балла
 * по построению — она разделов не размечает, — и сравнение ничего не говорило бы о качестве
 * поиска, только о полноте метаданных. Поэтому релевантность измеряется по содержимому: ответ
 * засчитан, если вернувшийся чанк действительно рассказывает о том же, о чём раздел.
 */
data class AnswerKey(
    val query: String,
    val source: String,
    val section: String,
    val answerText: String
)

/** Результаты одного запроса, полученные по одному индексу. */
data class QueryOutcome(
    val key: AnswerKey,
    val results: List<SearchResult>
)

/**
 * Оценка стратегии по набору запросов.
 *
 * [relevantInTop3] — сколько запросов нашли свой ответ в первых трёх чанках, [relevantAtTop1] —
 * сколько поставили его первым, [meanReciprocalRank] — средний обратный ранг первого релевантного
 * результата (0, если в первых трёх его нет): он различает «нашёл первым» и «нашёл третьим»,
 * чего счёт по Top-3 не видит.
 */
data class StrategyScore(
    val queries: Int,
    val relevantInTop3: Int,
    val relevantAtTop1: Int,
    val meanReciprocalRank: Double
)

/**
 * Пример разреза: чанк фиксированной стратегии оказался строго внутри чанка структурной, то есть
 * граница фиксированного чанка прошла по логически связанному фрагменту.
 *
 * [cutFrom] — конец текста, оставшийся в предыдущем фиксированном чанке, [cutInto] — начало
 * разрезанного чанка: по ним видно, где прошла граница. [startsMidSection] отличает разрез,
 * у которого фиксированный чанк ещё и начался в середине раздела.
 */
data class CutExample(
    val fixedChunkId: String,
    val structuralChunkId: String,
    val source: String,
    val section: String?,
    val startsMidSection: Boolean,
    val cutFrom: String,
    val cutInto: String
)

/**
 * Сравнение стратегий: оценка результатов поиска и поиск мест, где фиксированная нарезка
 * разрезала логически цельный фрагмент, а структурная сохранила его целиком.
 */
object StrategyComparison {

    /**
     * Порог релевантности: какую долю слов эталонного раздела чанк содержит (без служебных слов).
     *
     * Доля от эталона, а не Jaccard: Jaccard наказывает чанк за лишние слова, а у фиксированной
     * стратегии чанк всегда длиннее раздела — она бы проигрывала не из-за плохого ранжирования,
     * а из-за размера. Здесь же вопрос ставится так: рассказал ли чанк то, что нужно, — и длина
     * чанка мере не мешает. 0.6 — это «больше половины нужных слов», то есть чанк действительно
     * про этот раздел, а не задел его краем.
     */
    const val RELEVANCE_THRESHOLD = 0.6

    /** Считает оценку стратегии по результатам запросов. */
    fun score(outcomes: List<QueryOutcome>): StrategyScore {
        val relevantAtTop1 = outcomes.count { outcome ->
            outcome.results.firstOrNull()?.let { isRelevant(it.chunk, outcome.key) } == true
        }
        val relevantInTop3 = outcomes.count { outcome ->
            outcome.results.take(3).any { isRelevant(it.chunk, outcome.key) }
        }
        val reciprocalRankSum = outcomes.sumOf { outcome ->
            val rank = outcome.results.take(3).indexOfFirst { isRelevant(it.chunk, outcome.key) }
            if (rank < 0) 0.0 else 1.0 / (rank + 1)
        }

        return StrategyScore(
            queries = outcomes.size,
            relevantInTop3 = relevantInTop3,
            relevantAtTop1 = relevantAtTop1,
            meanReciprocalRank = if (outcomes.isEmpty()) 0.0 else reciprocalRankSum / outcomes.size
        )
    }

    /**
     * Релевантен ли чанк эталонному разделу: содержит не меньше [RELEVANCE_THRESHOLD] его слов.
     *
     * Считается только содержание. Чанк фиксированной стратегии, пересёкший границу файлов,
     * содержит ответ и обязан считаться ответом; его дефект метаданных (`source` называет
     * предыдущий файл) виден отдельной строкой отчёта и не должен наказывать стратегию второй раз.
     * Обратная сторона решения — чанк из чужого файла с теми же словами тоже засчитывается, поэтому
     * порог проверен по всему корпусу: у чанков-ответов 0.62–1.00 слов раздела, у самых похожих
     * чужих чанков — 0.56, и 0.6 проходит по зазору между ними. Разделы (`section`) в оценке
     * не участвуют по той же причине: у фиксированной стратегии их нет по построению.
     */
    fun isRelevant(chunk: DocumentChunk, key: AnswerKey): Boolean =
        TextOverlap.answerCoverage(chunk.content, key.answerText) >= RELEVANCE_THRESHOLD

    /**
     * Находит чанки структурного индекса, которые фиксированная нарезка разрезала.
     *
     * Признак разреза — фиксированный чанк целиком и строго внутри структурного: значит,
     * структурная стратегия держала этот фрагмент как одно целое, а фиксированная разрезала его
     * границей. Один структурный чанк может быть порезан несколькими фиксированными — в примерах
     * каждый такой фиксированный чанк даёт отдельную строку, а ограничение [limit] берёт самые
     * показательные (те, что начинаются в середине раздела).
     */
    fun cuts(
        fixedChunks: List<DocumentChunk>,
        structuralChunks: List<DocumentChunk>
    ): List<CutExample> {
        val examples = mutableListOf<CutExample>()

        for (fixed in fixedChunks) {
            val container = structuralChunks.firstOrNull { structural ->
                structural.content.length > fixed.content.length && structural.content.contains(fixed.content)
            } ?: continue

            val startIndex = container.content.indexOf(fixed.content)
            examples += CutExample(
                fixedChunkId = fixed.id,
                structuralChunkId = container.id,
                source = container.metadata.source,
                section = container.metadata.section,
                startsMidSection = startIndex > 0,
                cutFrom = container.content.substring(0, startIndex).takeLast(80),
                cutInto = fixed.content.take(80)
            )
        }

        return examples
    }
}

/**
 * Слова текста и мера того, насколько чанк рассказал то же, что эталонный раздел.
 *
 * Слова — последовательности Unicode-букв и цифр длиной от трёх символов, нижним регистром, без
 * служебных слов: они есть в любом тексте и размывали бы меру. Токены от трёх символов, а не от
 * двух, по той же причине — «в», «и», «не» не различают предмет. Служебные слова перечислены для
 * обоих языков корпуса: книга русская, но термины и названия в ней английские.
 */
object TextOverlap {

    private val wordPattern = Regex("[\\p{L}\\p{N}]{3,}")

    private val stopWords = setOf(
        // русские служебные слова и местоимения
        "для", "или", "что", "как", "это", "этот", "эта", "эти", "этих", "этом", "этой", "этого",
        "эту", "этому", "этим", "этими", "того", "тому", "тем", "тех", "так", "также", "такой",
        "такая", "такое", "такие", "таких", "таким", "таком", "при", "над", "под", "без", "через",
        "между", "после", "перед", "когда", "если", "чтобы", "чем", "кроме", "хотя", "ведь", "лишь",
        "который", "которая", "которое", "которые", "которых", "которым", "которой", "которого",
        "которому", "они", "она", "оно", "его", "ему", "их", "них", "него", "нее", "ними", "нам",
        "нас", "вам", "вас", "себя", "себе", "свой", "свои", "своей", "своих", "есть", "быть",
        "был", "была", "было", "были", "будет", "будут", "может", "могут", "должен", "должны",
        "должно", "нужно", "надо", "более", "менее", "очень", "все", "всё", "всех", "всем", "всей",
        "уже", "еще", "ещё", "только", "даже", "тоже", "один", "одна", "одно", "одни", "два", "три",
        "нет", "весь", "вся", "сам", "сама", "сами", "кто", "про",
        // английские служебные слова: часть терминов в корпусе английская
        "the", "and", "for", "with", "that", "this", "are", "was", "were", "from", "into",
        "you", "your", "can", "how", "does", "what", "when", "which", "not", "but", "its",
        "they", "them", "their", "have", "has", "had", "will", "would", "should",
        "one", "two", "all", "any", "each", "more", "most", "other", "some", "such", "than",
        "then", "there", "these", "those", "use", "used", "using", "also", "only", "just"
    )

    /** Слова текста без служебных. */
    fun tokens(text: String): Set<String> =
        wordPattern.findAll(text.lowercase())
            .map { it.value }
            .filter { it !in stopWords }
            .toSet()

    /**
     * Доля слов эталонного ответа [answer], которые встречаются в чанке [chunk].
     *
     * Знаменатель — только эталон: сколько бы лишнего чанк ни содержал, «рассказал ли он нужное»
     * мера не понижает. Пустой эталон или пустой чанк дают 0.0 — попадания без содержания нет.
     */
    fun answerCoverage(chunk: String, answer: String): Double {
        val answerTokens = tokens(answer)
        if (answerTokens.isEmpty()) return 0.0
        val chunkTokens = tokens(chunk)
        if (chunkTokens.isEmpty()) return 0.0
        return answerTokens.count { it in chunkTokens }.toDouble() / answerTokens.size
    }
}
