package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Запрос на запуск одного варианта ответа.
 * @param message Текст задачи.
 * @param variant Ключ варианта (direct / step_by_step / composed_prompt / expert_group).
 */
@Serializable
data class VariantRequest(
    val message: String,
    val variant: String
)

/**
 * Ответ на запуск одного варианта: решение и его характеристики.
 * @param title Название варианта.
 * @param content Текст решения.
 * @param metrics Основные характеристики запуска.
 */
@Serializable
data class VariantResponse(
    val success: Boolean,
    val title: String,
    val content: String,
    val metrics: AnswerMetrics
)

/**
 * Основные характеристики одного запуска варианта.
 * Стоимость приходит готовыми строками (тарифы и округление — на сервере).
 */
@Serializable
data class AnswerMetrics(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val elapsedMillis: Long,
    val tokensPerSecond: Double,
    val chars: Int,
    val words: Int,
    val costUsdText: String,
    val costRubText: String,
    val accuracy: Int? = null,
    val depth: Int? = null
)

/**
 * Запрос вердикта судьи по решениям одной задачи.
 */
@Serializable
data class AnalysisRequest(
    val question: String,
    val solutions: List<SolutionRef>
)

/**
 * Одно решение задачи для судьи.
 */
@Serializable
data class SolutionRef(
    val title: String,
    val content: String
)

/**
 * Ответ судьи.
 */
@Serializable
data class AnalysisResponse(
    val success: Boolean,
    val verdict: String
)
