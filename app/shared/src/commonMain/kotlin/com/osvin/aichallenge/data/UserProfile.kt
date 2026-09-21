package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Профиль пользователя: объявленные предпочтения — их пишет сам пользователь,
 * а не извлекает модель из диалога ([MemoryRecord] — наоборот, память).
 *
 * Это копия серверной модели, как и остальные модели дня ([MemoryLayers],
 * [MemoryType]): клиент читает профиль с сервера и отдаёт его назад целиком,
 * поэтому поля и их имена совпадают с серверными, а разбор не расходится.
 *
 * Профиля по умолчанию здесь нет намеренно: умолчание — профиль KMP-разработчика —
 * живёт на сервере, и вторая его копия на клиенте разошлась бы с первой. Пустые
 * строки означают «профиль с сервера ещё не пришёл», и такую форму пользователь
 * может заполнить сам.
 *
 * @param role Кто пользователь.
 * @param stack Стек и инструменты, в которых он работает.
 * @param style Стиль ответов.
 * @param format Формат ответов.
 * @param constraints Ограничения, которые надо соблюдать.
 * @param signOff Строка, которую ассистент пишет в конце каждого ответа.
 */
@Serializable
data class UserProfile(
    @SerialName("role") val role: String = "",
    @SerialName("stack") val stack: String = "",
    @SerialName("style") val style: String = "",
    @SerialName("format") val format: String = "",
    @SerialName("constraints") val constraints: String = "",
    @SerialName("sign_off") val signOff: String = ""
)
