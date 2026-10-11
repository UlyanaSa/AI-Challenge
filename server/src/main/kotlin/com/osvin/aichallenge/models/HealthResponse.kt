package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Статус здоровья сервера и провайдера модели.
 *
 * @param status `ok` — провайдер ответил; `error` — причина в [message].
 * @param deepseek Состояние облачного провайдера: `online`, `offline`, `auth_failed`,
 *        `unknown` — или `local`, когда сервер работает с локальной моделью и облачный
 *        API не проверялся вовсе. Поле оставлено прежним для совместимости с клиентом,
 *        который смотрит только на [status]; провайдер то же состояние называет точнее.
 * @param provider Кто отвечает за генерацию: `local` или `cloud`.
 * @param model Имя модели провайдера: у локального — та, что загружена в Ollama.
 *        null — облачный режим, где модель выбирает клиент по каждому запросу.
 * @param message Причина отказа словами.
 * @param code HTTP-статус, которым ответил внешний сервис, если он ответил неуспешно.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val deepseek: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val message: String? = null,
    val code: Int? = null
)
