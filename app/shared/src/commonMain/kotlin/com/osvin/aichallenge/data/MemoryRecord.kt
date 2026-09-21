package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Запись памяти агента: слой, в котором она лежит, и её текст. Ключа у записи нет —
 * запись адресуется парой «слой + текст», поэтому одну и ту же фразу нельзя
 * запомнить дважды, и в интерфейсе нечего вводить кроме самой фразы.
 */
@Serializable
data class MemoryRecord(val layer: String, val value: String)

/** Память агента из отчёта: что ушло в модель по слоям и сколько это стоило. */
@Serializable
data class MemoryReport(
    val working: List<MemoryRecord> = emptyList(),
    @SerialName("long_term") val longTerm: List<MemoryRecord> = emptyList(),
    @SerialName("working_tokens") val workingTokens: Int = 0,
    @SerialName("long_term_tokens") val longTermTokens: Int = 0,
    @SerialName("short_term_messages") val shortTermMessages: Int = 0,
    @SerialName("short_term_dropped") val shortTermDropped: Int = 0,
    val rejected: Int = 0,
    val evicted: Int = 0,
    @SerialName("update_tokens") val updateTokens: Int = 0,
    @SerialName("update_cost_usd") val updateCostUsd: Double? = null
)

/**
 * Снимок памяти чата: что лежит в слоях сейчас и какие типы памяти сервер знает.
 * Тот же ответ приходит на запись и удаление записи, поэтому состояние шторки
 * всегда берётся из ответа сервера, а не собирается на клиенте.
 *
 * @param working Рабочий слой: данные текущей задачи, общие для всех чатов профиля.
 * @param longTerm Долговременный слой: профиль, решения, знания.
 * @param types Каталог типов: порядок разделов шторки и их подписи.
 */
@Serializable
data class MemoryLayers(
    val working: List<MemoryRecord> = emptyList(),
    @SerialName("long_term") val longTerm: List<MemoryRecord> = emptyList(),
    val types: List<MemoryType> = emptyList()
)

/**
 * Тип памяти из каталога сервера: [layer] — значение для записи и удаления,
 * [title] и [hint] — подпись и пояснение, [writable] — можно ли писать в него
 * вручную. Своей таблицы типов у клиента нет: каталог — единственный источник
 * и выбора типа, и его подписи. Краткосрочная память приходит в каталоге
 * с `writable = false`: её пишет сам чат, сообщениями диалога.
 */
@Serializable
data class MemoryType(
    val layer: String,
    val title: String,
    val hint: String,
    val writable: Boolean
)

/**
 * Явная запись в память: [layer] и [value] уезжают на сервер как есть. Сессии
 * в записи нет: оба писаемых типа живут по профилю и видны из любого чата,
 * поэтому адресовать запись чату нечем и незачем.
 */
@Serializable
data class MemoryWriteRequest(
    val layer: String,
    val value: String
)
