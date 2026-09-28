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
 * Один вызов инструмента: что вызвала модель, чем и что получила.
 *
 * Хранится вместе с сообщением чата: лента печатает вызов командой — именем и аргументами,
 * как их прислала модель, — и ответ инструмента, поэтому по одному имени её не показать.
 *
 * @param name Имя инструмента.
 * @param arguments Аргументы вызова строкой JSON, как их прислала модель; пустая строка —
 *        аргументов не было, и инструмент отвечал по своим умолчаниям.
 * @param result Ответ инструмента текстом, обрезанный для ленты; null — ответа не было.
 * @param failed Инструмент ответил отказом: данных нет, и модель отвечала без них.
 */
@Serializable
data class ToolCallRecord(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String = "",
    @SerialName("result") val result: String? = null,
    @SerialName("failed") val failed: Boolean = false
) {
    /** Команда вызова одной строкой: её печатает лента чата. */
    fun command(): String = if (arguments.isBlank()) name else "$name $arguments"
}
