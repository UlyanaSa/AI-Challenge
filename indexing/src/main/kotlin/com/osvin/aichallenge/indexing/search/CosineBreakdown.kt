package com.osvin.aichallenge.indexing.search

import kotlin.math.abs
import kotlin.math.sqrt

/** Компонента вектора: измерение и его вес. */
data class VectorComponent(val dimension: Int, val weight: Double)

/**
 * Сводка вектора для показа: сколько в нём измерений, сколько ненулевых, какова норма и какие
 * компоненты самые тяжёлые.
 *
 * Вектор размерности 512 целиком человеку показывать нечем, а «ненулевых 87 из 512» и старшие
 * компоненты говорят о нём всё существенное: хешированный вектор разрежен, и близость определяют
 * именно тяжёлые компоненты.
 */
data class VectorSummary(
    val dimension: Int,
    val nonZero: Int,
    val norm: Double,
    val top: List<VectorComponent>
)

/**
 * Вклад одного измерения в скалярное произведение: веса обоих векторов по нему.
 *
 * [contribution] — произведение весов, то есть ровно то слагаемое, из которого складывается
 * близость: измерения, ненулевые только у одного вектора, в сумму не входят вовсе.
 */
data class SimilarityTerm(val dimension: Int, val left: Double, val right: Double) {

    /** Слагаемое скалярного произведения по этому измерению. */
    val contribution: Double get() = left * right
}

/**
 * Разбор близости двух векторов: из чего складывается косинус.
 *
 * [dot] — скалярное произведение по всем общим измерениям (у нормированных векторов оно и есть
 * косинус, а [similarity] это подтверждает независимым счётом). [terms] — только старшие слагаемые
 * (их число задаёт вызывающий), поэтому [shownSum] — сумма показанных, а не всего скалярного
 * произведения; разницу видно по [shared].
 */
data class SimilarityBreakdown(
    val leftNorm: Double,
    val rightNorm: Double,
    val shared: Int,
    val dot: Double,
    val similarity: Double,
    val terms: List<SimilarityTerm>,
    val shownSum: Double
)

/**
 * Разбор вектора и близости — для показа, а не для поиска.
 *
 * Поиск возвращает одно число (similarity), и по нему нельзя ни проверить счёт, ни понять, откуда
 * он взялся: одинаковые 0.46 могут получиться из одной тяжёлой общей компоненты и из сотни мелких.
 * Здесь те же векторы раскладываются на слагаемые — так число на странице можно сверить с самими
 * векторами, а не принимать на веру.
 *
 * Считается по `Double`, как и [CosineSimilarity]: у Float на хвосте терялась бы точность, и
 * сверка «сумма слагаемых = similarity» не сходилась бы на ровном месте.
 */
object CosineBreakdown {

    /**
     * Сводка [vector]: старшие [top] компонент по абсолютной величине веса.
     *
     * Сортировка по модулю веса, а не по весу: разреженный вектор с отрицательными компонентами
     * (возможны у любого провайдера, наш их гасит) интересен именно тяжёлыми компонентами.
     */
    fun summarize(vector: List<Float>, top: Int = 8): VectorSummary {
        require(top > 0) { "top должен быть положительным, получено $top" }

        var nonZero = 0
        var sumSquares = 0.0
        val components = ArrayList<VectorComponent>()
        for (dimension in vector.indices) {
            val weight = vector[dimension].toDouble()
            if (weight == 0.0) continue
            nonZero++
            sumSquares += weight * weight
            components += VectorComponent(dimension, weight)
        }
        return VectorSummary(
            dimension = vector.size,
            nonZero = nonZero,
            norm = sqrt(sumSquares),
            top = components.sortedByDescending { abs(it.weight) }.take(top)
        )
    }

    /**
     * Раскладывает близость [left] и [right] на слагаемые по общим измерениям.
     *
     * Несовпадение длин — ошибка, как и в [CosineSimilarity]: векторы разной длины посчитаны
     * в разных пространствах, и раскладывать нечего.
     */
    fun of(left: List<Float>, right: List<Float>, top: Int = 8): SimilarityBreakdown {
        require(left.size == right.size) {
            "Нельзя сравнить векторы разной длины: ${left.size} и ${right.size}"
        }
        require(top > 0) { "top должен быть положительным, получено $top" }

        var dot = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        val terms = ArrayList<SimilarityTerm>()
        for (dimension in left.indices) {
            val x = left[dimension].toDouble()
            val y = right[dimension].toDouble()
            dot += x * y
            leftSquares += x * x
            rightSquares += y * y
            if (x != 0.0 && y != 0.0) terms += SimilarityTerm(dimension, x, y)
        }

        val topTerms = terms.sortedByDescending { abs(it.contribution) }.take(top)
        return SimilarityBreakdown(
            leftNorm = sqrt(leftSquares),
            rightNorm = sqrt(rightSquares),
            shared = terms.size,
            dot = dot,
            similarity = CosineSimilarity.cosine(left, right),
            terms = topTerms,
            shownSum = topTerms.sumOf { it.contribution }
        )
    }
}
