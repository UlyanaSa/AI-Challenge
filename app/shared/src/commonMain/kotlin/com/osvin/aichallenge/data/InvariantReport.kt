package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Отчёт по инвариантам в ответе нейросети: что ушло в запрос и чем закончилась
 * проверка запроса на конфликт с правилами.
 *
 * Это копия серверной модели, как и [MemoryReport] рядом: отчёт приходит в поле
 * `invariants` ответа `/v1/chat/completions`, поэтому по нему видно и блок правил
 * в запросе, и причину отказа — без отдельного запроса. Каталог видов в отчёт
 * не входит: он есть только в снимке ([InvariantSnapshot.kinds]).
 *
 * @param invariants Правила, которые ушли в запрос системным сообщением.
 * @param tokens Токены блока инвариантов и сообщения проверки.
 * @param verdict Вердикт проверки; null — проверки не было: правил нет или ответ
 *        служебного вызова не разобрался. Тогда правило отказа в запрос не добавлялось,
 *        и ассистент отвечает как обычно.
 * @param violated Виды нарушенных инвариантов; пусто, если проверка запрос разрешила.
 * @param reason Объяснение проверки: чем именно запрос противоречит правилам.
 * @param updateTokens Токены служебного вызова проверки.
 * @param updateCostUsd Цена этого вызова в USD; null, если тариф не опубликован.
 */
@Serializable
data class InvariantReport(
    @SerialName("invariants") val invariants: List<Invariant> = emptyList(),
    @SerialName("tokens") val tokens: Int = 0,
    @SerialName("verdict") val verdict: String? = null,
    @SerialName("violated") val violated: List<String> = emptyList(),
    @SerialName("reason") val reason: String? = null,
    @SerialName("update_tokens") val updateTokens: Int = 0,
    @SerialName("update_cost_usd") val updateCostUsd: Double? = null
)
