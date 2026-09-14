package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.config.AppConfig
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Настройки генерации, которые клиент может задать для одного запроса агента.
 * Все поля необязательны: агент сам подставляет значения по умолчанию
 * и приводит их к допустимым границам ([AppConfig]).
 *
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели.
 *        Не задан — его роль играет первое сообщение диалога (см. [LlmAgent]).
 * @param history Предыдущие сообщения диалога (роли user/assistant), без текущего запроса.
 * @param sessionId Идентификатор сессии диалога: по нему находится сводка истории.
 * @param compressHistory Сжимать историю: последние сообщения идут как есть, старшие — сводкой.
 */
data class AgentOptions(
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val sessionId: String? = null,
    val compressHistory: Boolean = false
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
 * Управление контекстом: если клиент просит сжатие ([AgentOptions.compressHistory])
 * и назвал сессию, агент держит последние сообщения как есть, а старшие сворачивает
 * в сводку ([HistoryCompressor]) и хранит её отдельно от сообщений в [SummaryStore].
 * Сводка уходит в запрос вместо свёрнутых сообщений, поэтому история не растёт.
 *
 * System prompt берётся из настроек ([AgentOptions.systemPrompt]), а если он не задан —
 * его роль играет первое сообщение диалога ([AgentOptions.history]); на первом ходу
 * диалога это текущий запрос. Так инструкция из первого сообщения остаётся в силе
 * и после того, как старшие сообщения свёрнуты в сводку.
 *
 * @param llm Транспорт к LLM API.
 * @param tokenCounter Счётчик токенов для разбивки запроса и проверки контекста.
 * @param logger Лог агента: запрос, история диалога, ответ модели и ответ API при ошибке.
 * @param compressor Правило сжатия истории: сколько сообщений не трогать и когда строить сводку.
 * @param summaryStore Хранилище сводок. Сервер передаёт общее на все запросы, иначе
 *        сводка живёт только внутри одного запуска агента.
 */
class LlmAgent(
    private val llm: LlmClient,
    private val tokenCounter: TokenCounter = EstimatingTokenCounter,
    private val logger: AgentLogger = AgentLogger.Console,
    private val compressor: HistoryCompressor = HistoryCompressor(),
    private val summaryStore: SummaryStore = InMemorySummaryStore()
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

        // Сообщения модели: system prompt, сводка истории, последние
        // сообщения диалога и текущий запрос пользователя — в этом порядке.
        // Свой system prompt из настроек важнее: если он задан, берём его.
        // Если нет — его роль играет первое сообщение диалога: инструкция из него
        // остаётся в силе и тогда, когда старшие сообщения свёрнуты в сводку.
        val history = options.history.filter { it.content.isNotBlank() }
        val systemPrompt = options.systemPrompt?.takeIf { it.isNotBlank() }
            ?: history.firstOrNull { it.role == USER_ROLE }?.content
            ?: userMessage

        // Сжатие истории: последние сообщения уходят как есть, старшие заменяются
        // сводкой. Сводка живёт в хранилище отдельно от сообщений — по сессии.
        val sessionId = options.sessionId?.takeIf { options.compressHistory && it.isNotBlank() }
        val plan = if (sessionId == null) {
            HistoryPlan(history, emptyList(), null, 0)
        } else {
            compressor.plan(history, summaryStore.get(sessionId))
        }
        val compression = if (sessionId == null) {
            null
        } else {
            plan.toFold.takeIf { it.isNotEmpty() }?.let { folded ->
                compress(model, plan.summary, folded).also { attempt ->
                    attempt.summary?.let { summaryStore.put(sessionId, it) }
                }
            }
        }
        // Сводку не удалось построить — отправляем историю целиком: диалог не теряем.
        val summary = when {
            compression != null && compression.summary == null -> null
            else -> compression?.summary ?: plan.summary
        }
        val historyForRequest = if (summary != null) plan.recent else history

        val messages = buildList {
            systemPrompt?.let { add(ChatMessage(SYSTEM_ROLE, it)) }
            summary?.let { add(compressor.summaryMessage(it)) }
            addAll(historyForRequest)
            add(ChatMessage(USER_ROLE, userMessage))
        }

        // Разбивка по частям: API отдаёт только общий prompt_tokens, поэтому
        // вклад system prompt, истории и текущего запроса считаем локально.
        val systemTokens = systemPrompt?.let(tokenCounter::count) ?: 0
        val summaryTokens = summary?.tokens ?: 0
        val recentTokens = historyForRequest.sumOf { tokenCounter.count(it.content) }
        val historyTokens = summaryTokens + recentTokens
        val historyRawTokens = history.sumOf { tokenCounter.count(it.content) }
        val requestTokens = tokenCounter.count(userMessage)
        val promptEstimate = tokenCounter.countPrompt(messages)

        if (summary != null) {
            logger.log(
                listOf(
                    "Сжатие истории",
                    "свёрнуто сообщений: ${summary.foldedMessages} (сводка $summaryTokens ток.)",
                    "последних сообщений как есть: ${historyForRequest.size} ($recentTokens ток.)",
                    "история без сжатия: $historyRawTokens ток.",
                    "история к отправке: $historyTokens ток.",
                    "экономия: ${historyRawTokens - historyTokens} ток. (${percent1(share(historyRawTokens - historyTokens, historyRawTokens))}%)",
                    compression?.let {
                        "служебный вызов сводки: ${it.totalTokens} ток. " +
                            "(вход ${it.promptTokens}, ответ ${it.replyTokens}), цена ${costUsd(it.costUsd)}"
                    }
                ).filterNotNull().joinToString("\n")
            )
        }

        logger.log(
            listOf(
                "Запрос → $model",
                "system prompt: $systemTokens ток.",
                "история: $historyTokens ток. (${historyForRequest.size} сообщ.)",
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
                costUsd = cost,
                historyRawTokens = historyRawTokens,
                summaryTokens = summaryTokens,
                foldedMessages = summary?.foldedMessages ?: 0,
                compressionTokens = compression?.totalTokens ?: 0,
                compressionCostUsd = compression?.costUsd
            )
        )
    }

    /** Сводка строится отдельным вызовом и стоит токенов: они тоже попадают в отчёт. */
    private data class SummaryAttempt(
        val summary: StoredSummary?,
        val promptTokens: Int,
        val replyTokens: Int,
        val costUsd: Double?
    ) {
        /** Полная цена служебного вызова: запрос сводки и её текст. */
        val totalTokens: Int get() = promptTokens + replyTokens
    }

    /**
     * Сворачивает сообщения в сводку отдельным вызовом модели.
     *
     * Сбой служебного вызова не должен ломать диалог: в этом случае сводка не
     * обновляется (вернётся null), а запрос уходит с полной историей.
     */
    private suspend fun compress(
        model: String,
        previous: StoredSummary?,
        messages: List<ChatMessage>
    ): SummaryAttempt {
        val request = compressor.summaryRequest(model, previous, messages)
        return try {
            val response = llm.complete(request)
            val usage = response.usage
            val promptTokens = usage?.promptTokens ?: tokenCounter.countPrompt(request.messages)
            val replyTokens = usage?.completionTokens ?: 0
            val cost = ModelCatalog.spec(model).cost(promptTokens, replyTokens)
            val text = response.choices.first().message.content.trim()

            if (text.isEmpty()) {
                logger.log("Сжатие истории не удалось (модель вернула пустую сводку) — контекст отправлен как есть")
                return SummaryAttempt(null, promptTokens, replyTokens, cost)
            }

            SummaryAttempt(
                summary = StoredSummary(
                    text = text,
                    foldedMessages = (previous?.foldedMessages ?: 0) + messages.size,
                    tokens = tokenCounter.count(text)
                ),
                promptTokens = promptTokens,
                replyTokens = replyTokens,
                costUsd = cost
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.log(
                "Сжатие истории не удалось (${error.message ?: error::class.simpleName}) — контекст отправлен как есть"
            )
            SummaryAttempt(null, 0, 0, null)
        }
    }

    /** Доля части от целого: 0.0–1.0; при нулевом целом — ноль. */
    private fun share(part: Int, total: Int): Double = if (total == 0) 0.0 else part.toDouble() / total

    /** Экономия в процентах с одним знаком: доля окна в логе и так идёт с четырьмя. */
    private fun percent1(share: Double): String = String.format(Locale.ROOT, "%.1f", share * 100)

    /** Доля окна в процентах: на окне в 1M токенов даже крупный диалог — доли процента. */
    private fun percent(share: Double): String = String.format(Locale.ROOT, "%.4f", share * 100)

    /** Стоимость запуска; у моделей без опубликованного тарифа — пометка вместо числа. */
    private fun costUsd(cost: Double?): String =
        cost?.let { String.format(Locale.ROOT, "\$%.6f", it) } ?: "тариф не опубликован"

    private companion object {
        /** Роли сообщений в запросе к модели. */
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"
    }
}
