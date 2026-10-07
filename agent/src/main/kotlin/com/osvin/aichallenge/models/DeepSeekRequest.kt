package com.osvin.aichallenge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Запрос, отправляемый напрямую в DeepSeek API.
 *
 * @param tools Инструменты, которые предлагаются модели: пустой список и `null` —
 *        одно и то же, ключа `tools` в запросе нет (значение по умолчанию не пишется).
 * @param reasoningEffort Режим рассуждения модели: `null` — как настроено у модели (по умолчанию
 *        она рассуждает), `none` — без рассуждения. Рассуждение приходит отдельным полем ответа,
 *        но тратит тот же бюджет завершения, что и текст ответа, поэтому на механических задачах
 *        (переписать вопрос в ключевые слова, расставить оценки фрагментам) оно не помогает, а вредит:
 *        у дня 23 бюджет уходил в мысли, ответ приходил пустым, и этап молча ничего не делал. Здесь
 *        параметр общий, а решение о его значении принимает вызывающий: у ответа на вопрос есть
 *        рассуждение по делу, у извлечения ключевых слов — нет.
 * @param responseFormat Требование к формату ответа. Дню 24 нужен разбор по частям (ответ, цитаты,
 *        номера фрагментов), а просьба «ответь JSON-объектом» в тексте промпта — только просьба:
 *        живой прогон дня 23 показал, что модель отвечает не тем, что у неё просили, если формат
 *        описан лишь словами. Здесь формат задаётся уже на уровне API, а разбор всё равно проверяет
 *        ответ и считает непонятный отказом — параметр повышает долю разобранных ответов, но не
 *        становится единственной защитой.
 */
@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
    val tools: List<ToolDeclaration>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/**
 * Требование к формату ответа модели.
 *
 * Отдельным типом, потому что это описание формата, а не часть запроса: у API формат — объект,
 * и второй такой же объект в другом запросе обязан собираться тем же конструктором, иначе
 * `json_object` в одном месте и опечатка в другом разойдутся молча.
 */
@Serializable
data class ResponseFormat(val type: String) {

    companion object {

        /** Ответ — один объект JSON. Значение задаёт API, а не мы: строка должна совпадать. */
        val JSON_OBJECT = ResponseFormat("json_object")
    }
}
