package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.config.AppConfig
import java.util.Locale

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
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage> = emptyList()
)

/**
 * Итог работы агента.
 * @param reply Ответ модели.
 * @param promptTokens Токены запроса.
 * @param completionTokens Токены ответа.
 * @param tokens Расход токенов по частям запроса, ответу и стоимости.
 */
data class AgentResult(
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val tokens: TokenReport
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
 * Внутри агента подготовка запроса (нормализация настроек, сборка сообщений)
 * и подсчёт токенов: по частям запроса — локальным счётчиком, по факту — из
 * `usage` ответа API. Если запрос вместе с бюджетом ответа не влезает в окно
 * модели, агент не обращается к API, а падает с [ContextOverflowException].
 *
 * @param llm Транспорт к LLM API.
 * @param tokenCounter Счётчик токенов для разбивки запроса и проверки контекста.
 * @param logger Лог агента: запрос, история диалога, ответ модели и ответ API при ошибке.
 */
class LlmAgent(
    private val llm: LlmClient,
    private val tokenCounter: TokenCounter = EstimatingTokenCounter,
    private val logger: AgentLogger = AgentLogger.Console
) {

    /**
     * Отправляет запрос пользователя в LLM и возвращает ответ модели.
     * @param userMessage Текст запроса.
     * @param options Настройки генерации; null-поля заменяются значениями по умолчанию.
     * @throws ContextOverflowException если запрос вместе с ответом не влезает в окно модели.
     * @throws EmptyReplyException если модель не вернула текст ответа.
     */
    suspend fun run(userMessage: String, options: AgentOptions = AgentOptions()): AgentResult {
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
        val spec = ModelCatalog.spec(model)

        // Сообщения модели: свой system prompt, история диалога и текущий
        // запрос пользователя — в этом порядке.
        val systemPrompt = options.systemPrompt?.takeIf { it.isNotBlank() }
        val history = options.history.filter { it.content.isNotBlank() }
        val messages = buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            addAll(history)
            add(ChatMessage("user", userMessage))
        }

        // Разбивка по частям: API отдаёт только общий prompt_tokens, поэтому
        // вклад system prompt, истории и текущего запроса считаем локально.
        val systemTokens = systemPrompt?.let(tokenCounter::count) ?: 0
        val historyTokens = history.sumOf { tokenCounter.count(it.content) }
        val requestTokens = tokenCounter.count(userMessage)
        val promptEstimate = tokenCounter.countPrompt(messages)

        logger.log(
            listOf(
                "Запрос → $model",
                "system prompt: $systemTokens ток.",
                "история: $historyTokens ток. (${history.size} сообщ.)",
                "текущий вопрос: $requestTokens ток.",
                "всего (оценка): $promptEstimate ток.",
                "бюджет ответа: $maxTokens ток.",
                "окно модели: ${spec.contextWindow} ток."
            ).joinToString("\n")
        )

        // Контекст ограничен суммой запроса и бюджета ответа — проверяем до
        // отправки, чтобы не платить за заведомо неуспешный запрос.
        if (promptEstimate + maxTokens > spec.contextWindow) {
            logger.log(
                listOf(
                    "Переполнение контекста",
                    "оценка запроса: $promptEstimate ток.",
                    "бюджет ответа: $maxTokens ток.",
                    "окно модели: ${spec.contextWindow} ток.",
                    "запрос не отправлен в API"
                ).joinToString("\n")
            )
            throw ContextOverflowException(promptEstimate, maxTokens, spec.contextWindow)
        }

        val response = try {
            llm.complete(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    stop = stopSequences
                )
            )
        } catch (apiError: LlmApiException) {
            // Печатаем ответ провайдера как есть: по нему видно, чего именно не хватило
            logger.log("Ошибка API (HTTP ${apiError.status})\n${apiError.message}")
            throw apiError
        }

        val choice = response.choices.first()
        val reply = choice.message.content
        val usage = response.usage
        val promptTokens = usage?.promptTokens ?: promptEstimate
        val replyTokens = usage?.completionTokens ?: tokenCounter.count(reply)
        val reasoningTokens = usage?.completionTokensDetails?.reasoningTokens ?: 0
        val windowShare = promptTokens.toDouble() / spec.contextWindow
        val cost = spec.cost(promptTokens, replyTokens)

        logger.log(
            listOf(
                "Ответ ← $model",
                "токенов запроса: $promptTokens (факт), $promptEstimate (оценка)",
                "токенов ответа: $replyTokens (рассуждения: $reasoningTokens)",
                "finish: ${choice.finishReason}",
                "окно занято: ${percent(windowShare)}%",
                "цена: ${costUsd(cost)}"
            ).joinToString("\n")
        )
        logger.log(
            if (reply.isBlank()) {
                "Текст ответа: пусто — бюджет ответа израсходован на рассуждения"
            } else {
                "Текст ответа: $reply"
            }
        )

        // Пустой ответ — не успех: в чате он выглядит пузырём без текста, и причину
        // не видно. Отдаём наверх исключение с числами, клиент показывает его текст.
        if (reply.isBlank()) {
            throw EmptyReplyException(replyTokens, reasoningTokens, choice.finishReason)
        }

        return AgentResult(
            reply = reply,
            promptTokens = promptTokens,
            completionTokens = replyTokens,
            tokens = TokenReport(
                systemPrompt = systemTokens,
                history = historyTokens,
                request = requestTokens,
                promptEstimate = promptEstimate,
                promptTokens = promptTokens,
                replyEstimate = tokenCounter.count(reply),
                replyTokens = replyTokens,
                replyReasoningTokens = reasoningTokens,
                contextWindow = spec.contextWindow,
                maxOutputTokens = spec.maxOutputTokens,
                promptWindowShare = windowShare,
                replyFinishReason = choice.finishReason,
                costUsd = cost
            )
        )
    }

    /** Доля окна в процентах: на окне в 1M токенов даже крупный диалог — доли процента. */
    private fun percent(share: Double): String = String.format(Locale.ROOT, "%.4f", share * 100)

    /** Стоимость запуска; у моделей без опубликованного тарифа — пометка вместо числа. */
    private fun costUsd(cost: Double?): String =
        cost?.let { String.format(Locale.ROOT, "\$%.6f", it) } ?: "тариф не опубликован"
}
