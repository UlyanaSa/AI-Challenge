package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модель сообщения для внутреннего обмена сообщениями на сервере.
 *
 * @param role Роль автора сообщения: user, assistant или system; `tool` — результат
 *        инструмента, который модель вызвала.
 * @param content Текст сообщения. Пустая строка — не «сообщения нет», а сообщение без
 *        текста: так приходит ответ модели, которая вместо текста просит вызов, —
 *        API отдаёт у него `content: null`, а разборщик подставляет умолчание
 *        (`coerceInputValues` в настройках JSON клиента).
 * @param branchId Ветка диалога, к которой относится сообщение: null — основная
 *        линия. Метка служебная: клиент помечает ею сообщения, чтобы агент собрал
 *        путь активной ветки, а в API сообщения уходят без неё.
 * @param toolCalls Вызовы инструментов, которые просит модель: есть только у сообщения
 *        ассистента и только в ответе на запрос с инструментами. Повторяются в следующем
 *        запросе как есть — без них API не примет ответ инструмента.
 * @param toolCallId Вызов, на который отвечает сообщение: есть только у сообщения
 *        с ролью `tool`. По нему API понимает, какой из вызовов закрыт результатом.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val branchId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)
