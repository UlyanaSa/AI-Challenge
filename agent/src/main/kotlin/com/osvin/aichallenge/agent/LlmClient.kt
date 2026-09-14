package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse

/**
 * Транспорт к LLM API.
 *
 * Агент ([LlmAgent]) не знает, как именно устроен сетевой вызов: он формулирует
 * [DeepSeekRequest] и разбирает [DeepSeekResponse], а доставку запроса выполняет
 * реализация этого интерфейса. Благодаря этому логика агента тестируется без сети
 * (подменный [LlmClient]), а смена провайдера не трогает агента.
 */
interface LlmClient {
    /**
     * Отправляет запрос к LLM API и возвращает разобранный ответ.
     * @throws LlmApiException если API вернул неуспешный статус.
     */
    suspend fun complete(request: DeepSeekRequest): DeepSeekResponse
}

/**
 * Провайдер ответил неуспешным статусом.
 * Ошибки 4xx — вина запроса (например, переполнение контекста), 5xx — сбой API.
 *
 * @param status HTTP-статус ответа провайдера.
 * @param message Сообщение провайдера как есть, с его числами токенов.
 */
class LlmApiException(val status: Int, message: String) : IllegalStateException(message)
