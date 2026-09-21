package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DialogBranch
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
 * @param sessionId Идентификатор сессии диалога: по нему живёт сводка истории (стратегия
 *        «сжатие в сводку»). Памяти он не нужен: её слои лежат по профилю.
 * @param strategy Стратегия управления контекстом: что из истории уходит в модель
 *        и какие слои памяти она ведёт.
 * @param windowMessages Сколько последних сообщений отправляют стратегии
 *        «скользящее окно» и «память агента»; null — значение по умолчанию.
 * @param branches Ветки диалога: структура и точки ветвления (стратегия «ветки диалога»).
 * @param activeBranchId Активная ветка, чей путь уходит в модель; null — основная линия.
 */
data class AgentOptions(
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val sessionId: String? = null,
    val strategy: ContextStrategy = ContextStrategy.FULL,
    val windowMessages: Int? = null,
    val branches: List<DialogBranch> = emptyList(),
    val activeBranchId: String? = null
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
 * Управление контекстом: стратегию выбирает клиент ([AgentOptions.strategy]).
 *
 * - [ContextStrategy.FULL] — вся история как есть;
 * - [ContextStrategy.SLIDING_WINDOW] — последние [AgentOptions.windowMessages] сообщений;
 *   важное из отброшенных держит рабочая память ([MemoryExtractor], [MemoryStore]);
 * - [ContextStrategy.MEMORY] — три типа памяти: краткосрочная (история окна), рабочая
 *   (данные текущей задачи) и долговременная (профиль, решения, знания). Тип записи
 *   называет модель, а код проверяет, что тип известен и что стратегия его ведёт;
 * - [ContextStrategy.BRANCHES] — только путь активной ветки от точки ветвления
 *   ([DialogBranches]), поэтому продолжения веток не смешиваются;
 * - [ContextStrategy.SUMMARY] — последние сообщения как есть, старшие свёрнуты
 *   в сводку ([HistoryCompressor], [SummaryStore]).
 *
 * Слои памяти хранятся отдельно: краткосрочная — сообщения у клиента, рабочая и
 * долговременная — по профилю ([DEFAULT_PROFILE]), поэтому обе видны из любого чата.
 * Различаются эти два слоя не областью, а сроком жизни: рабочая лежит в памяти процесса
 * и перезапуск сервера её обнуляет, долговременная — в файле и перезапуск переживает.
 * Сообщения присылает клиент, а слои памяти и сводки живут на сервере.
 *
 * @param llm Транспорт к LLM API.
 * @param tokenCounter Счётчик токенов для разбивки запроса и проверки контекста.
 * @param logger Лог агента: запрос, стратегия, история диалога, ответ модели и ответ API при ошибке.
 * @param compressor Правило сжатия истории: сколько сообщений не трогать и когда строить сводку.
 * @param summaryStore Хранилище сводок. Сервер передаёт общее на все запросы, иначе
 *        сводка живёт только внутри одного запуска агента.
 * @param memoryExtractor Правило памяти: как обновлять записи слоёв.
 * @param workingMemory Хранилище рабочей памяти по профилю: память процесса
 *        ([InMemoryMemoryStore]), поэтому перезапуск сервера её обнуляет.
 * @param longTermMemory Хранилище долговременной памяти по профилям.
 */
class LlmAgent(
    private val llm: LlmClient,
    private val tokenCounter: TokenCounter = EstimatingTokenCounter,
    private val logger: AgentLogger = AgentLogger.Console,
    private val compressor: HistoryCompressor = HistoryCompressor(),
    private val summaryStore: SummaryStore = InMemorySummaryStore(),
    private val memoryExtractor: MemoryExtractor = MemoryExtractor(),
    private val workingMemory: MemoryStore = InMemoryMemoryStore(),
    private val longTermMemory: MemoryStore = InMemoryMemoryStore()
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

        // Сообщения модели: system prompt, сводка, слои памяти (долговременный, затем
        // рабочий), история диалога и текущий запрос — в этом порядке. Свой system prompt из настроек важнее: если он задан,
        // берём его. Если нет — его роль играет первое сообщение диалога: инструкция
        // из него остаётся в силе и тогда, когда старшие сообщения отброшены.
        val history = options.history.filter { it.content.isNotBlank() }
        // Свой system prompt важнее: если он задан, берём его. Если нет — его роль
        // играет первое сообщение диалога, а на первом ходу — текущий запрос,
        // поэтому system prompt есть всегда.
        val systemPrompt: String = options.systemPrompt?.takeIf { it.isNotBlank() }
            ?: history.firstOrNull { it.role == USER_ROLE }?.content
            ?: userMessage

        // Что из истории уходит в модель, решает выбранная стратегия: окно, путь
        // активной ветки, сводка вместо свёрнутых сообщений — и слои памяти, которые
        // эта стратегия ведёт.
        val strategy = options.strategy
        val window = (options.windowMessages ?: DEFAULT_WINDOW_MESSAGES)
            .coerceIn(MIN_WINDOW_MESSAGES, MAX_WINDOW_MESSAGES)
        val context = planContext(model, strategy, history, window, options, userMessage)
        val historyForRequest = context.history
        val summary = context.summary
        // Блоки памяти: слой уходит в запрос, только если его ведёт стратегия и он не пуст.
        val longTermMessage = memoryBlock(MemoryLayer.LONG_TERM, context, strategy)
        val workingMessage = memoryBlock(MemoryLayer.WORKING, context, strategy)

        val messages = buildList {
            add(ChatMessage(SYSTEM_ROLE, systemPrompt))
            summary?.let { add(compressor.summaryMessage(it)) }
            longTermMessage?.let { add(it) }
            workingMessage?.let { add(it) }
            // Метка ветки — служебная: в API сообщения уходят без неё.
            addAll(withoutBranchTags(historyForRequest))
            add(ChatMessage(USER_ROLE, userMessage))
        }

        // Разбивка по частям: API отдаёт только общий prompt_tokens, поэтому
        // вклад system prompt, слоёв памяти и истории считаем локально.
        val systemTokens = tokenCounter.count(systemPrompt)
        val summaryTokens = summary?.tokens ?: 0
        val longTermTokens = longTermMessage?.let { tokenCounter.count(it.content) } ?: 0
        val workingTokens = workingMessage?.let { tokenCounter.count(it.content) } ?: 0
        val memoryTokens = longTermTokens + workingTokens
        val recentTokens = historyForRequest.sumOf { tokenCounter.count(it.content) }
        val historyTokens = summaryTokens + memoryTokens + recentTokens
        val historyRawTokens = history.sumOf { tokenCounter.count(it.content) }
        val requestTokens = tokenCounter.count(userMessage)
        val promptEstimate = tokenCounter.countPrompt(messages)

        // Лог стратегии: что именно ушло в модель и чего это стоило.
        context.log(strategy, window, historyRawTokens, historyTokens, memoryTokens)?.let(logger::log)

        logger.log(
            listOf(
                "Запрос → $model",
                "стратегия контекста: ${strategy.title}",
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
                strategy = strategy.wire,
                windowMessages = if (strategy.usesWindow) window else 0,
                droppedMessages = context.droppedMessages,
                excludedMessages = context.excludedMessages,
                branchId = context.branchId,
                summaryTokens = summaryTokens,
                foldedMessages = summary?.foldedMessages ?: 0,
                compressionTokens = if (strategy == ContextStrategy.SUMMARY) context.service?.totalTokens ?: 0 else 0,
                compressionCostUsd = if (strategy == ContextStrategy.SUMMARY) context.service?.costUsd else null,
                memory = MemoryReport(
                    working = context.working,
                    longTerm = context.longTerm,
                    workingTokens = workingTokens,
                    longTermTokens = longTermTokens,
                    shortTermMessages = historyForRequest.size,
                    shortTermDropped = context.droppedMessages + context.excludedMessages,
                    rejected = context.rejected,
                    evicted = context.evicted,
                    updateTokens = if (strategy.memory.isNotEmpty()) context.service?.totalTokens ?: 0 else 0,
                    updateCostUsd = if (strategy.memory.isNotEmpty()) context.service?.costUsd else null
                )
            )
        )
    }

    /**
     * Что уходит в модель по выбранной стратегии и чего это стоило.
     *
     * @param history Сообщения диалога — краткосрочная память; столько, сколько их пустила стратегия.
     * @param working Записи рабочей памяти, которые ушли в запрос.
     * @param longTerm Записи долговременной памяти, которые ушли в запрос.
     * @param rejected Сколько записей отклонено: неизвестный вид или слой, которого стратегия не ведёт.
     * @param evicted Сколько записей вытеснено пределами слоя.
     * @param summary Сводка, заменившая свёрнутые сообщения: только для сжатия.
     * @param service Служебный вызов стратегии: построение сводки или обновление памяти.
     * @param droppedMessages Сколько сообщений отброшено окном.
     * @param excludedMessages Сколько сообщений диалога не попало в путь активной ветки.
     * @param branchId Активная ветка: null — основная линия диалога.
     * @param sharedMessages Сколько сообщений в пути до точки ветвления.
     */
    private data class ContextPlan(
        val history: List<ChatMessage>,
        val working: List<MemoryRecord> = emptyList(),
        val longTerm: List<MemoryRecord> = emptyList(),
        val rejected: Int = 0,
        val evicted: Int = 0,
        val summary: StoredSummary? = null,
        val service: ServiceCall? = null,
        val droppedMessages: Int = 0,
        val excludedMessages: Int = 0,
        val branchId: String? = null,
        val sharedMessages: Int = 0
    ) {

        /** Записи слоя, которые ушли в запрос. */
        fun recordsOf(layer: MemoryLayer): List<MemoryRecord> = when (layer) {
            MemoryLayer.WORKING -> working
            MemoryLayer.LONG_TERM -> longTerm
            MemoryLayer.SHORT_TERM -> emptyList()
        }

        /**
         * Лог стратегии: одна запись со всеми числами — что ушло в модель и что
         * не ушло. null — стратегии «вся история» собственный блок не нужен.
         */
        fun log(
            strategy: ContextStrategy,
            window: Int,
            rawTokens: Int,
            sentTokens: Int,
            memoryTokens: Int
        ): String? {
            // Токены истории без памяти: по ним видно, чего стоило окно само по себе.
            val recentTokens = sentTokens - memoryTokens
            val lines = when (strategy) {
                ContextStrategy.FULL -> return null
                ContextStrategy.SLIDING_WINDOW -> buildList {
                    add("Скользящее окно истории")
                    add("окно: $window сообщ. — в запросе ${history.size} ($recentTokens ток.)")
                    add("отброшено сообщений: $droppedMessages (${rawTokens - recentTokens} ток.)")
                    add("история без окна: $rawTokens ток.")
                    addAll(memoryLines(memoryTokens))
                }
                ContextStrategy.MEMORY -> buildList {
                    add("Память агента")
                    addAll(memoryLines(memoryTokens))
                    add("окно: $window сообщ. — в запросе ${history.size} ($recentTokens ток.)")
                    add("отброшено сообщений: $droppedMessages (${rawTokens - recentTokens} ток.)")
                    add("история без памяти: $rawTokens ток.")
                }
                ContextStrategy.BRANCHES -> listOf(
                    "Ветки диалога",
                    "активная ветка: ${branchId ?: "основная линия"}",
                    "общий путь до точки ветвления: $sharedMessages сообщ.",
                    "в запросе: ${history.size} ($sentTokens ток.)",
                    "вне пути активной ветки: $excludedMessages сообщ. (${rawTokens - sentTokens} ток.)"
                )
                ContextStrategy.SUMMARY -> listOf(
                    "Сжатие истории",
                    "свёрнуто сообщений: ${summary?.foldedMessages ?: 0} (сводка ${summary?.tokens ?: 0} ток.)",
                    "последних сообщений как есть: ${history.size} (${sentTokens - (summary?.tokens ?: 0)} ток.)",
                    "история без сжатия: $rawTokens ток.",
                    "история к отправке: $sentTokens ток.",
                    "экономия: ${rawTokens - sentTokens} ток. (${percent1(share(rawTokens - sentTokens, rawTokens))}%)",
                    service?.let {
                        "служебный вызов сводки: ${it.totalTokens} ток. " +
                            "(вход ${it.promptTokens}, ответ ${it.replyTokens}), цена ${costUsd(it.costUsd)}"
                    }
                )
            }
            return lines.filterNotNull().joinToString("\n")
        }

        /** Строки лога про слои памяти: что в них лежит, сколько стоит и что не сохранилось. */
        private fun memoryLines(memoryTokens: Int): List<String> = buildList {
            add("рабочая память: ${working.size} записей")
            working.forEach { add("  ${MemoryExtractor.line(it)}") }
            add("долговременная память: ${longTerm.size} записей")
            longTerm.forEach { add("  ${MemoryExtractor.line(it)}") }
            add("память в запросе: $memoryTokens ток.")
            if (rejected > 0) add("отклонено записей: $rejected")
            if (evicted > 0) add("вытеснено записей: $evicted")
            service?.let {
                add(
                    "обновление памяти: ${it.totalTokens} ток. " +
                        "(вход ${it.promptTokens}, ответ ${it.replyTokens}), цена ${costUsd(it.costUsd)}"
                )
            }
        }
    }

    /** Служебный вызов стратегии стоит токенов: они тоже попадают в отчёт. */
    private data class ServiceCall(
        val promptTokens: Int,
        val replyTokens: Int,
        val costUsd: Double?
    ) {
        /** Полная цена служебного вызова: запрос и ответ. */
        val totalTokens: Int get() = promptTokens + replyTokens
    }

    /** Ответ служебного вызова: текст для разбора и его цена. */
    private data class ServiceReply(val text: String, val call: ServiceCall)

    /** Сжатие истории: история к отправке, актуальная сводка и цена служебного вызова. */
    private data class SummaryPlan(val history: List<ChatMessage>, val summary: StoredSummary?, val call: ServiceCall?)

    /**
     * Что из истории уходит в модель по выбранной стратегии.
     *
     * Стратегии с состоянием обновляют его отдельным служебным вызовом модели, поэтому
     * метод `suspend`. Сбой вызова диалог не ломает: стратегия откатывается к тому,
     * что уже знает, — прежней сводке или прежним записям памяти.
     */
    private suspend fun planContext(
        model: String,
        strategy: ContextStrategy,
        history: List<ChatMessage>,
        window: Int,
        options: AgentOptions,
        userMessage: String
    ): ContextPlan {
        // Ветки: в модель уходит путь активной ветки, а не весь диалог целиком.
        val branchId = options.activeBranchId?.takeIf { strategy == ContextStrategy.BRANCHES }
        val path = if (strategy == ContextStrategy.BRANCHES) {
            DialogBranches.activePath(history, options.branches, branchId)
        } else {
            history
        }
        // Окно и слои памяти отправляют только последние сообщения диалога.
        val windowed = if (strategy.usesWindow) path.takeLast(window) else path

        // Сессию держит только сводка истории: рабочей памяти она не нужна — слой лежит
        // по профилю, поэтому читается и пишется без неё.
        val sessionId = options.sessionId?.takeIf { it.isNotBlank() }
        if (strategy == ContextStrategy.SUMMARY && sessionId == null) {
            logger.log("Сжатие истории: сессия не названа — сводка не ведётся, в модель уходит вся история")
        }

        val summary = if (strategy == ContextStrategy.SUMMARY && sessionId != null) {
            planSummary(model, sessionId, history)
        } else {
            null
        }
        val memory = if (strategy.memory.isNotEmpty()) {
            updateMemory(model, strategy.memory, windowed, userMessage)
        } else {
            // Стратегия слои не ведёт: памяти нет, контекст собирается по истории.
            MemoryPlan()
        }

        return ContextPlan(
            history = summary?.history ?: windowed,
            working = memory.working,
            longTerm = memory.longTerm,
            rejected = memory.rejected,
            evicted = memory.evicted,
            summary = summary?.summary,
            service = summary?.call ?: memory.call,
            droppedMessages = if (strategy.usesWindow) path.size - windowed.size else 0,
            excludedMessages = if (strategy == ContextStrategy.BRANCHES) history.size - path.size else 0,
            branchId = branchId,
            sharedMessages = if (strategy == ContextStrategy.BRANCHES) {
                path.size - history.count { it.branchId == branchId }
            } else {
                0
            }
        )
    }

    /**
     * Сжатие истории (день 9): последние сообщения уходят как есть, старшие — сводкой.
     *
     * Сбой служебного вызова не должен ломать диалог: в этом случае сводка не
     * обновляется, а запрос уходит с полной историей.
     */
    private suspend fun planSummary(model: String, sessionId: String, history: List<ChatMessage>): SummaryPlan {
        val plan = compressor.plan(history, summaryStore.get(sessionId))
        if (plan.toFold.isEmpty()) {
            return SummaryPlan(if (plan.summary != null) plan.recent else history, plan.summary, null)
        }

        val reply = callService("сводка", compressor.summaryRequest(model, plan.summary, plan.toFold))
            ?: return SummaryPlan(history, null, null)
        if (reply.text.isEmpty()) {
            logger.log("Сжатие истории не удалось (модель вернула пустую сводку) — контекст отправлен как есть")
            return SummaryPlan(history, null, reply.call)
        }

        val summary = StoredSummary(
            text = reply.text,
            foldedMessages = (plan.summary?.foldedMessages ?: 0) + plan.toFold.size,
            tokens = tokenCounter.count(reply.text)
        )
        summaryStore.put(sessionId, summary)
        return SummaryPlan(plan.recent, summary, reply.call)
    }

    /**
     * Типы памяти: прежние записи обоих типов уходят в служебный запрос, а в ответе
     * модели остаются только записи известных типов, которые ведёт стратегия.
     *
     * Записи типа, который стратегия не ведёт, не сохраняются: спрашиваем про свои типы,
     * а лишнее считаем отклонённым. Сбой вызова память не портит — остаются прежние записи.
     *
     * Сессия здесь не нужна: оба слоя живут по профилю ([DEFAULT_PROFILE]), поэтому
     * обновление видно из любого чата.
     */
    private suspend fun updateMemory(
        model: String,
        layers: Set<MemoryLayer>,
        history: List<ChatMessage>,
        userMessage: String
    ): MemoryPlan {
        val working = if (MemoryLayer.WORKING in layers) workingMemory.get(DEFAULT_PROFILE) else emptyList()
        val longTerm = if (MemoryLayer.LONG_TERM in layers) longTermMemory.get(DEFAULT_PROFILE) else emptyList()
        val fresh = history.takeLast(MEMORY_CONTEXT_MESSAGES) + ChatMessage(USER_ROLE, userMessage)
        val reply = callService("память", memoryExtractor.request(model, layers, working, longTerm, fresh))
            ?: return MemoryPlan(working, longTerm)
        val parsed = memoryExtractor.parse(reply.text)
        if (parsed == null) {
            logger.log("Память не обновилась (модель вернула не JSON) — оставлены прежние записи")
            return MemoryPlan(working, longTerm, call = reply.call)
        }

        val (supported, unsupported) = parsed.records.partition { record ->
            val layer = MemoryLayer.ofWire(record.layer)
            layer != null && layer in layers
        }
        val written = supported.groupBy { MemoryLayer.ofWire(it.layer)!! }
        // Каждый слой сливается и сохраняется отдельно: слои не смешиваются даже здесь.
        val workingMerge = if (MemoryLayer.WORKING in layers) {
            MemoryRules.merge(working, written[MemoryLayer.WORKING].orEmpty(), MemoryLayer.WORKING)
                .also { workingMemory.put(DEFAULT_PROFILE, it.records) }
        } else {
            MemoryMerge(working, 0)
        }
        val longTermMerge = if (MemoryLayer.LONG_TERM in layers) {
            MemoryRules.merge(longTerm, written[MemoryLayer.LONG_TERM].orEmpty(), MemoryLayer.LONG_TERM)
                .also { longTermMemory.put(DEFAULT_PROFILE, it.records) }
        } else {
            MemoryMerge(longTerm, 0)
        }
        return MemoryPlan(
            working = workingMerge.records,
            longTerm = longTermMerge.records,
            rejected = parsed.rejected + unsupported.size,
            evicted = workingMerge.evicted + longTermMerge.evicted,
            call = reply.call
        )
    }

    /**
     * Служебный вызов модели: ответ текстом и его цена.
     * Сбой вызова диалог не ломает — вернётся null, стратегия обойдётся без обновления.
     */
    private suspend fun callService(label: String, request: DeepSeekRequest): ServiceReply? {
        return try {
            val response = llm.complete(request)
            val usage = response.usage
            val promptTokens = usage?.promptTokens ?: tokenCounter.countPrompt(request.messages)
            val replyTokens = usage?.completionTokens ?: 0
            ServiceReply(
                text = response.choices.first().message.content.trim(),
                call = ServiceCall(
                    promptTokens = promptTokens,
                    replyTokens = replyTokens,
                    costUsd = ModelCatalog.spec(request.model).cost(promptTokens, replyTokens)
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.log(
                "Служебный вызов «$label» не удался (${error.message ?: error::class.simpleName}) — " +
                    "контекст отправлен как есть"
            )
            null
        }
    }

    /** Слои памяти после обновления и цена служебного вызова. */
    private data class MemoryPlan(
        val working: List<MemoryRecord> = emptyList(),
        val longTerm: List<MemoryRecord> = emptyList(),
        val rejected: Int = 0,
        val evicted: Int = 0,
        val call: ServiceCall? = null
    )

    /** Блок слоя для запроса: null — слой не ведёт стратегия или он пуст. */
    private fun memoryBlock(layer: MemoryLayer, context: ContextPlan, strategy: ContextStrategy): ChatMessage? =
        if (layer in strategy.memory) memoryExtractor.message(layer, context.recordsOf(layer)) else null

    /** В API сообщения уходят без метки ветки: она нужна только стратегии веток. */
    private fun withoutBranchTags(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.none { it.branchId != null }) messages else messages.map { ChatMessage(it.role, it.content) }

    private companion object {
        /** Роли сообщений в запросе к модели. */
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"

        /** Окно по умолчанию: столько последних сообщений отправляют стратегии окна и памяти. */
        const val DEFAULT_WINDOW_MESSAGES = 10

        /** Меньше двух сообщений окно теряет смысл: вопрос без ответа не контекст. */
        const val MIN_WINDOW_MESSAGES = 2

        /** Верхняя граница окна: больше — уже не управление контекстом, а вся история. */
        const val MAX_WINDOW_MESSAGES = 100

        /** Сколько последних сообщений показываем памяти вместе с новым вопросом. */
        const val MEMORY_CONTEXT_MESSAGES = 2

        /** Доля части от целого: 0.0–1.0; при нулевом целом — ноль. */
        fun share(part: Int, total: Int): Double = if (total == 0) 0.0 else part.toDouble() / total

        /** Экономия в процентах с одним знаком: доля окна в логе и так идёт с четырьмя. */
        fun percent1(share: Double): String = String.format(Locale.ROOT, "%.1f", share * 100)

        /** Доля окна в процентах: на окне в 1M токенов даже крупный диалог — доли процента. */
        fun percent(share: Double): String = String.format(Locale.ROOT, "%.4f", share * 100)

        /** Стоимость запуска; у моделей без опубликованного тарифа — пометка вместо числа. */
        fun costUsd(cost: Double?): String =
            cost?.let { String.format(Locale.ROOT, "\$%.6f", it) } ?: "тариф не опубликован"
    }
}
