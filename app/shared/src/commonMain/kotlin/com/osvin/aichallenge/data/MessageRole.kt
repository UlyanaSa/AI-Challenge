package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Роли участников диалога.
 * Используется для корректной сериализации в JSON для API DeepSeek.
 *
 * USER - Передает запросы, вопросы и инструкции от пользователя модели
 *
 * ASSISTANT - Содержит ответы модели. Может использоваться для передачи истории диалога
 *
 * SYSTEM - Задает общий контекст, инструкции и правила поведения
 * для модели на протяжении всего диалога
 *
 * @param wire Значение роли в API DeepSeek и в хранилище истории.
 */
@Serializable
enum class MessageRole(val wire: String) {
    @SerialName("user") USER("user"),
    @SerialName("assistant") ASSISTANT("assistant"),
    @SerialName("system") SYSTEM("system")
}
