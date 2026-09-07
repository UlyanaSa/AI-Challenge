package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Представляет отдельное сообщение в чате.
 * @param role Кто отправил сообщение (пользователь, ассистент или система).
 * @param content Текст сообщения.
 * @param timestamp Время отправки в миллисекундах.
 * @param variantTitle Название варианта ответа (для сообщений-вариантов).
 * @param metrics Характеристики запуска (для сообщений-вариантов).
 * @param analysisEntries Характеристики всех запущенных вариантов (для сообщения-анализа).
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val variantTitle: String? = null,
    val metrics: AnswerMetrics? = null,
    val analysisEntries: List<AnalysisEntry>? = null
)

/**
 * Одна строка итогового анализа: название варианта и его характеристики.
 */
@Serializable
data class AnalysisEntry(
    val title: String,
    val metrics: AnswerMetrics
)
