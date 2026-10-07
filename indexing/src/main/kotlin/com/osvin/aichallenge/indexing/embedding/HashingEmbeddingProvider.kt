package com.osvin.aichallenge.indexing.embedding

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Вид признака: слово целиком или триграмма внутри слова. */
enum class FeatureKind { WORD, TRIGRAM }

/**
 * Признак текста таким, каким он входит в вектор: измерение, куда его отправил хеш, и вес со знаком.
 *
 * [weight] — вклад признака до нормировки вектора: `знак × (1 + ln(частоты))`. Список признаков
 * нужен там, где вектор показывают человеку: измерение хешированного вектора само по себе —
 * просто число, и прочитать его можно только по именам признаков, которые в него попали.
 */
data class Feature(
    val text: String,
    val kind: FeatureKind,
    val dimension: Int,
    val weight: Double
)

/**
 * Локальный embedding-провайдер на хешировании признаков (hashing trick).
 *
 * Почему так, а не внешняя модель: прогон эксперимента обязан воспроизводиться. У хеширования нет
 * ни весов, ни словаря, которые нужно скачивать, — вектор целиком выводится из текста и фиксированной
 * хеш-функции, поэтому один и тот же текст даёт один и тот же вектор при каждом запуске.
 *
 * Признаки — это слова целиком (unigram) плюс символьные триграммы длинных слов. Триграммы дают
 * частичное совпадение форм слова и опечаток: «coroutine» и «coroutines» делят триграммы, хотя
 * unigram у них разный.
 *
 * Знак признака берётся из чётности хеша: `hash % dimension` даёт индекс, но без знака все признаки
 * только складывались бы, и вектор терял бы способность различать контексты. Знак — не украшение,
 * а вторая половина того же хеша.
 */
class HashingEmbeddingProvider(
    override val dimension: Int = 512
) : EmbeddingProvider {

    init {
        // Нулевая длина сделала бы деление по модулю бессмысленным, а вектор — пустым.
        require(dimension > 0) { "dimension должен быть положительным, получено $dimension" }
    }

    /**
     * Считает embedding: bag of features, взвешенный субфинейным TF и приведённый к L2-норме.
     *
     * Пустой текст (или текст без токенов подходящей длины) даёт нулевой вектор, а не NaN: у
     * нулевой нормы нет направления, и делить на неё нельзя.
     */
    override suspend fun embed(text: String): List<Float> {
        val vector = FloatArray(dimension)
        var any = false
        for (feature in features(text)) {
            vector[feature.dimension] += feature.weight.toFloat()
            any = true
        }
        if (!any) return List(dimension) { 0f }

        var sumSquares = 0.0
        for (value in vector) sumSquares += value.toDouble() * value.toDouble()
        // Признаки были, но после коллизий со знаком вектор мог обнулиться — это тот же нулевой случай.
        if (sumSquares == 0.0) return List(dimension) { 0f }

        val norm = sqrt(sumSquares).toFloat()
        // abs до записи: близость в задании трактуется как 0..1, а знак уже сделал свою работу
        // (различил контексты внутри скалярного произведения) и наружу не выносится.
        return vector.map { abs(it / norm) }
    }

    /**
     * Признаки текста с их измерениями и весами — то, из чего собран вектор.
     *
     * Открыто наружу ради показа, а не ради вычислений: [embed] этим списком и пользуется, так что
     * второй копии хеширования нет. Измерение хешированного вектора безымянно, и объяснить вес
     * компоненты можно только признаками, которые в неё попали, — их и отдаёт этот метод.
     *
     * Порядок — по первому появлению признака в тексте: он воспроизводим, и в показе признаки
     * стоят в том порядке, в каком их читает человек в самом тексте.
     */
    fun features(text: String): List<Feature> = countFeatures(text).map { (name, count) ->
        val hash = hash32(name)
        Feature(
            text = name.substringAfter(':'),
            kind = if (name.startsWith(TRIGRAM_PREFIX)) FeatureKind.TRIGRAM else FeatureKind.WORD,
            // Отрицательный remainder возможен, потому что hash — Int со знаком; приводим к [0, dimension).
            dimension = ((hash % dimension) + dimension) % dimension,
            // Знак берётся из чётности хеша: без него признаки только складывались бы, и вектор
            // терял бы способность различать контексты. Субфинейный TF `1 + ln(count)`: повтор слова
            // поднимает вес, но не линейно — иначе частый термин один определял бы направление вектора.
            weight = (if (hash.countOneBits() and 1 == 1) -1.0 else 1.0) * (1.0 + ln(count.toDouble()))
        )
    }

    /**
     * Считает признаки текста вместе с их частотой.
     *
     * Нижний регистр — чтобы «Flow» и «flow» были одним признаком. Токены короче двух символов
     * отбрасываются: одиночная буква почти не несёт смысла, а шум в хеш-пространстве создаёт.
     * Порядок вставки сохраняется (`LinkedHashMap`): по нему идёт и [features], и показ вектора.
     */
    private fun countFeatures(text: String): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (match in tokenRegex.findAll(text.lowercase())) {
            val token = match.value
            if (token.length < 2) continue
            counts.merge(UNIGRAM_PREFIX + token, 1, Int::plus)
            // Триграммы — только для достаточно длинных слов: у коротких их почти нет, и признак
            // лишь повторял бы unigram.
            if (token.length >= TRIGRAM_MIN_LENGTH) {
                for (i in 0..token.length - TRIGRAM_SIZE) {
                    counts.merge(TRIGRAM_PREFIX + token.substring(i, i + TRIGRAM_SIZE), 1, Int::plus)
                }
            }
        }
        return counts
    }

    /**
     * FNV-1a с финальным перемешиванием MurmurHash3.
     *
     * FNV даёт быстрый и детерминированный старт, но её старшие биты слабые, а нам из хеша нужен
     * ещё и бит знака; финальный mixer разгоняет все биты и разводит похожие признаки по разным
     * индексам. Реализация своя, без внешних зависимостей, чтобы прогон не зависел от версии
     * чужой библиотеки.
     */
    private fun hash32(feature: String): Int {
        var h = 0x811c9dc5u.toInt()
        for (ch in feature) {
            h = h xor ch.code
            h *= 0x01000193u.toInt()
        }
        h = h xor (h ushr 16)
        h *= 0x85ebca6bu.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35u.toInt()
        h = h xor (h ushr 16)
        return h
    }

    private companion object {
        /** Слова — последовательности Unicode-букв и цифр; знаки препинания границей не являются
         *  «словом», а разделителем. */
        val tokenRegex = Regex("[\\p{L}\\p{N}]+")

        const val UNIGRAM_PREFIX = "w:"
        const val TRIGRAM_PREFIX = "t:"
        const val TRIGRAM_SIZE = 3
        const val TRIGRAM_MIN_LENGTH = 5
    }
}
