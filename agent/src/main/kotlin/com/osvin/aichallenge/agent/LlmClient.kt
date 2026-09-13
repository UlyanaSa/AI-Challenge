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
     * @throws IllegalStateException если API вернул неуспешный статус.
     */
    suspend fun complete(request: DeepSeekRequest): DeepSeekResponse
}
