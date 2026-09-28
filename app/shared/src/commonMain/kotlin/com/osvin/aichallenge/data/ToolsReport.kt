package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Инструменты, которые вызывала модель, и цена этих вызовов.
 *
 * Появляется в отчёте вместе с первым вызовом: запуск без инструментов отвечает одним
 * запросом, а с ними — несколькими, и без этого отчёта рост расхода выглядел бы ничем
 * не объяснённым.
 *
 * @param calls Что вызывалось и чем кончилось — по порядку вызовов.
 * @param rounds Сколько раундов общения с моделью заняли вызовы; 0 — их не было.
 * @param tokens Токены всех раундов, кроме первого: первый раунд — обычный запрос.
 */
@Serializable
data class ToolsReport(
    @SerialName("calls") val calls: List<ToolCallRecord> = emptyList(),
    @SerialName("rounds") val rounds: Int = 0,
    @SerialName("tokens") val tokens: Int = 0
)

/**
 * Один вызов инструмента: что вызвала модель и получила ли она данные.
 *
 * @param name Имя инструмента.
 * @param failed Инструмент ответил отказом: данных нет, и модель отвечала без них.
 */
@Serializable
data class ToolCallRecord(
    @SerialName("name") val name: String,
    @SerialName("failed") val failed: Boolean = false
)
