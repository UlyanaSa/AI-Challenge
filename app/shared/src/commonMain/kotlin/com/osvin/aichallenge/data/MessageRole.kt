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
 */
@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM
}
