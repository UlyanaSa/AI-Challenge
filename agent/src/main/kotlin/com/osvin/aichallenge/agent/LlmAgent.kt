package com.osvin.aichallenge.agent

import com.osvin.aichallenge.GenerationFormat
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.config.AppConfig

/**
 * Настройки генерации, которые клиент может задать для одного запроса агента.
 * Все поля необязательны: агент сам подставляет значения по умолчанию
 * и приводит их к допустимым границам ([AppConfig]).
 *
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели.
 * @param history Предыдущие сообщения диалога (роли user/assistant), без текущего запроса.
 */
data class AgentOptions(
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val format: String? = null,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage> = emptyList()
)

/**
 * Итог работы агента.
 * @param reply Ответ модели.
 * @param promptTokens Токены запроса.
 * @param completionTokens Токены ответа.
 */
data class AgentResult(
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int
) {
    /** Расход токенов в формате ответа сервера. */
    val usage: Map<String, Int> = mapOf(
        "prompt_tokens" to promptTokens,
        "completion_tokens" to completionTokens,
        "total_tokens" to promptTokens + completionTokens
    )
}

/**
 * Агент — отдельная сущность, которая принимает набор параметров генерации,
 * собирает из них запрос к LLM, отправляет его через [LlmClient] и возвращает
 * ответ модели.
 *
 * Агент не знает про HTTP и про сервер: обращение к модели идёт через [LlmClient].
 * Внутри агента только подготовка запроса: нормализация настроек (модель, лимит
 * токенов, температура, стоп-слова, формат) и сборка сообщений — свой system prompt,
 * инструкция формата, история диалога и текущий запрос пользователя.
 *
 * @param llm Транспорт к LLM API.
 */
class LlmAgent(private val llm: LlmClient) {

    /**
     * Отправляет запрос пользователя в LLM и возвращает ответ модели.
     * @param userMessage Текст запроса.
     * @param options Настройки генерации; null-поля заменяются значениями по умолчанию.
     */
    suspend fun run(userMessage: String, options: AgentOptions = AgentOptions()): AgentResult {
        val format = GenerationFormat.fromKey(options.format)
        val model = options.model ?: AppConfig.DEFAULT_MODEL
        val maxTokens = (options.maxTokens ?: AppConfig.DEFAULT_MAX_TOKENS)
            .coerceIn(1, AppConfig.MAX_TOKEN_CEILING)
        val temperature = (options.temperature ?: AppConfig.DEFAULT_TEMPERATURE)
            .coerceIn(0.0, 2.0)
        val stopSequences = options.stop
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(AppConfig.MAX_STOP_SEQUENCES)
            ?.takeIf { it.isNotEmpty() }

        // Сообщения модели: свой system prompt, инструкция формата, история
        // диалога и текущий запрос пользователя — в этом порядке.
        val messages = buildList {
            options.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            format.instruction?.let { add(ChatMessage("system", it)) }
            options.history.forEach { message ->
                if (message.content.isNotBlank()) add(message)
            }
            add(ChatMessage("user", userMessage))
        }

        val response = llm.complete(
            DeepSeekRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
                stop = stopSequences,
                responseFormat = if (format.jsonMode) GenerationFormat.STRICT_JSON_MODE else null
            )
        )

        return AgentResult(
            reply = response.choices.first().message.content,
            promptTokens = response.usage?.promptTokens ?: 0,
            completionTokens = response.usage?.completionTokens ?: 0
        )
    }
}
