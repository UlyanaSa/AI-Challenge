package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Отчёт задачи в ответе нейросети: что агент сделал с задачей, пока отвечал.
 *
 * Это копия серверной модели, как и [MemoryReport] рядом: отчёт приходит в поле `task`
 * ответа `/v1/chat/completions`, и клиент по нему обновляет состояние полосы задачи без
 * отдельного запроса. Каталог этапов в отчёт не входит — он есть только в снимке
 * ([TaskSnapshot.stages]).
 *
 * @param stage Этап после ответа; null — задачи нет: её не заводили или она закрыта.
 * @param step Текущий шаг после ответа.
 * @param expectedAction Ожидаемое действие человека после ответа.
 * @param paused Состояние паузы после ответа.
 * @param moved Двигалась ли задача этим ответом: шаг сменился или этап перешёл дальше.
 * @param rejected Сколько записей в состояние задачи агент отклонил.
 * @param rejectReason Причина отказа записи; null — отказов не было.
 * @param tokens Токены, ушедшие на работу с задачей в этом ответе.
 * @param updateTokens Токены вызова, которым обновлялось состояние задачи.
 * @param updateCostUsd Цена этого вызова в USD; null, если тариф не опубликован.
 */
@Serializable
data class TaskReport(
    @SerialName("stage") val stage: String? = null,
    @SerialName("step") val step: String = "",
    @SerialName("expected_action") val expectedAction: String = "",
    @SerialName("paused") val paused: Boolean = false,
    @SerialName("moved") val moved: Boolean = false,
    @SerialName("rejected") val rejected: Int = 0,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("tokens") val tokens: Int = 0,
    @SerialName("update_tokens") val updateTokens: Int = 0,
    @SerialName("update_cost_usd") val updateCostUsd: Double? = null
)
