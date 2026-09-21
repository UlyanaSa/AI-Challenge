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
 * @param profile Профиль пользователя: объявленные предпочтения, которые модель учитывает
 *        в каждом ответе. Уходит отдельным системным сообщением сразу после system prompt
 *        и при любой стратегии контекста. Частью памяти профиль не является: память
 *        наполняет модель, и стратегия то ведёт её, то нет, а профиль заявлен человеком
 *        и должен дойти до модели всегда. Пустой профиль сообщения не добавляет.
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
    val profile: UserProfile? = null,
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
 * Профиль пользователя ([AgentOptions.profile]) стоит вне этого выбора: он уходит
 * системным сообщением сразу после system prompt при любой из стратегий. Системным —
 * потому что это не реплика в диалоге, а правила, которым подчиняется каждый ответ;
 * после system prompt и до сводки с блоками памяти — потому что это единственное место,
 * которое стратегия не переставляет: сводку она строит, память ведёт или не ведёт,
 * историю режет, а профиль остаётся на месте. В память профиль не годится: память
 * модель достаёт из переписки и переписывает по стратегии, а профиль заявлен
 * пользователем и в запросе обязан быть всегда.
 *
 * Слои памяти хранятся отдельно: краткосрочная — сообщения у клиента, рабочая и
 * долговременная — по профилю ([DEFAULT_PROFILE]), поэтому обе видны из любого чата.
 * Различаются эти два слоя не областью, а сроком жизни: рабочая лежит в памяти процесса
 * и перезапуск сервера её обнуляет, долговременная — в файле и перезапуск переживает.
 * Сообщения присылает клиент, а слои памяти и сводки живут на сервере.
 *
 * Состояние задачи ([TaskState]) стоит вне стратегии контекста так же, как профиль:
 * задачу ведёт агент, а не стратегия. Пока задачи нет, её не ведёт никто — задачу заводит
 * человек явным действием ([TaskWriter]), и тогда состояния не существует, служебных
 * вызовов к модели нет вовсе и поведение прежних дней не меняется. Появившись, состояние
 * уходит системным сообщением сразу после профиля в каждом запросе этого диалога: этап,
 * текущий шаг и ожидаемое действие — то, по чему модель продолжает работу, а не начинает
 * её заново. Переход предлагает модель, а принимает код ([TaskRules]): запрещённый переход
 * отклоняется причиной, и состояние остаётся прежним. На паузе служебного вызова нет:
 * состояние заморожено, и после снятия паузы работа идёт с того же шага.
 *
 * Инварианты ([Invariant]) стоят вне стратегии контекста, как профиль и задача, но по другой
 * причине: это не часть диалога и не следствие его, а правила над ним. Их не извлекает модель —
 * их объявляет человек ([InvariantWriter]), — и лежат они своим хранилищем ([InvariantStore]),
 * а не в истории сообщений: история ровно то, что стратегия режет, ветвит и переписывает,
 * а правило обязано дойти до модели в каждом запросе. Пока правил нет (человек убрал их все),
 * ни блока, ни служебного вызова нет вовсе, и поведение прежних дней не меняется. Если правила
 * есть, они уходят системным сообщением сразу после профиля, а конфликт запроса с ними проверяет
 * служебный вызов ([InvariantGuard]): по его вердикту в запрос добавляется системное сообщение
 * с правилом отказа — отказ формулирует сама модель, но причина у него та, которую назвал код,
 * и в отчёте видно, какой инвариант нарушен и чем. Сбой проверки или неразобранный ответ диалог
 * не ломает: вердикта нет, правило отказа не добавляется, а блок инвариантов всё равно в промпте.
 *
 * @param llm Транспорт к LLM API.
 * @param tokenCounter Счётчик токенов для разбивки запроса и проверки контекста.
 * @param logger Лог агента: запрос, стратегия, история диалога, ответ модели и ответ API при ошибке.
 * @param compressor Правило сжатия истории: сколько сообщений не трогать и когда строить сводку.
 * @param summaryStore Хранилище сводок. Сервер передаёт общее на все запросы, иначе
 *        сводка живёт только внутри одного запуска агента.
 * @param taskStateStore Хранилище состояний задач. Ключ — сессия диалога
 *        ([AgentOptions.sessionId]), потому что задача принадлежит диалогу, а не профилю:
 *        сервер передаёт общее на все запросы, иначе состояние живёт внутри одного запуска.
 * @param memoryExtractor Правило памяти: как обновлять записи слоёв.
 * @param taskExtractor Правило задачи: как обновлять этап, текущий шаг и ожидаемое действие.
 * @param workingMemory Хранилище рабочей памяти по профилю: память процесса
 *        ([InMemoryMemoryStore]), поэтому перезапуск сервера её обнуляет.
 * @param longTermMemory Хранилище долговременной памяти по тому же профилю: файл, поэтому
 *        перезапуск сервера её не роняет.
 * @param invariantStore Хранилище инвариантов проекта по профилю ([DEFAULT_PROFILE]): правила
 *        объявляет человек явным действием ([InvariantWriter]), а набор проекта кладёт туда
 *        сервер при первом запуске, поэтому «их ещё не задавали» (`null`) означает для агента
 *        ровно то же, что пустой список, — работы без правил. Сервер передаёт хранилище общим
 *        на все запросы, иначе правила живут внутри одного запуска агента.
 * @param invariantGuard Правило проверки: как спросить модель о конфликте запроса с правилами.
 */
class LlmAgent(
    private val llm: LlmClient,
    private val tokenCounter: TokenCounter = EstimatingTokenCounter,
    private val logger: AgentLogger = AgentLogger.Console,
    private val compressor: HistoryCompressor = HistoryCompressor(),
    private val summaryStore: SummaryStore = InMemorySummaryStore(),
    private val taskStateStore: TaskStateStore = InMemoryTaskStateStore(),
    private val memoryExtractor: MemoryExtractor = MemoryExtractor(),
    private val taskExtractor: TaskExtractor = TaskExtractor(),
    private val workingMemory: MemoryStore = InMemoryMemoryStore(),
    private val longTermMemory: MemoryStore = InMemoryMemoryStore(),
    private val invariantStore: InvariantStore = InMemoryInvariantStore(),
    private val invariantGuard: InvariantGuard = InvariantGuard()
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

        // Сообщения модели: system prompt, профиль пользователя, инварианты проекта, состояние
        // задачи, сообщение проверки инвариантов, сводка, слои памяти (долговременный, затем
        // рабочий), история диалога и текущий запрос — в этом порядке.
        // Свой system prompt из настроек важнее: если он задан,
        // берём его. Если нет — его роль играет первое сообщение диалога: инструкция
        // из него остаётся в силе и тогда, когда старшие сообщения отброшены.
        val history = options.history.filter { it.content.isNotBlank() }
        // Свой system prompt важнее: если он задан, берём его. Если нет — его роль
        // играет первое сообщение диалога, а на первом ходу — текущий запрос,
        // поэтому system prompt есть всегда.
        val systemPrompt: String = options.systemPrompt?.takeIf { it.isNotBlank() }
            ?: history.firstOrNull { it.role == USER_ROLE }?.content
            ?: userMessage

        // Профиль пользователя — системное сообщение сразу после system prompt: он не часть
        // диалога, а правила ответа, и уходит в запрос при любой стратегии контекста.
        // Пустой профиль (как и его отсутствие) сообщения не добавляет: пустой блок
        // в промпте читается моделью как незаполненное требование.
        val profileMessage = options.profile
            ?.takeIf { !it.isEmpty }
            ?.let { ChatMessage(SYSTEM_ROLE, it.render()) }

        // Что из истории уходит в модель, решает выбранная стратегия: окно, путь
        // активной ветки, сводка вместо свёрнутых сообщений — и слои памяти, которые
        // эта стратегия ведёт.
        val strategy = options.strategy
        val window = (options.windowMessages ?: DEFAULT_WINDOW_MESSAGES)
            .coerceIn(MIN_WINDOW_MESSAGES, MAX_WINDOW_MESSAGES)
        val context = planContext(model, strategy, history, window, options, userMessage)
        val historyForRequest = context.history
        val summary = context.summary
        // Состояние задачи — системное сообщение сразу после профиля и правил: этап, текущий шаг
        // и ожидаемое действие. От стратегии контекста оно не зависит: стратегия решает,
        // что уходит из истории, а задача ведётся по диалогу целиком.
        val taskState = context.task.state
        val taskMessage = taskState?.let { ChatMessage(SYSTEM_ROLE, it.render()) }
        // Отказ перехода — системное сообщение сразу после состояния задачи: этап остался
        // прежним, и без объяснения модель приняла бы это за молчание и попробовала бы
        // перепрыгнуть снова. Причину назвал код ([TaskRules]), а не модель.
        val rejectionMessage = context.task.rejection?.let { ChatMessage(SYSTEM_ROLE, it) }
        // Инварианты — системное сообщение сразу после профиля и до состояния задачи: правило
        // действует над диалогом, а не обсуждается в нём, поэтому его место среди правил ответа,
        // а не среди работы ([Invariant]). Правил нет — сообщения нет: пустой блок читался бы
        // моделью как требование, о котором забыли.
        val invariantMessage = context.invariants.block
            .takeIf { it.isNotEmpty() }
            ?.let { ChatMessage(SYSTEM_ROLE, it) }
        // Правило отказа — отдельное системное сообщение после состояния задачи: оно появляется
        // только тогда, когда проверка была и нашла конфликт, и объясняет отказ причиной, которую
        // назвал код ([InvariantGuard]).
        val invariantCheckMessage = context.invariants.check?.let { ChatMessage(SYSTEM_ROLE, it) }
        // Блоки памяти: слой уходит в запрос, только если его ведёт стратегия и он не пуст.
        val longTermMessage = memoryBlock(MemoryLayer.LONG_TERM, context, strategy)
        val workingMessage = memoryBlock(MemoryLayer.WORKING, context, strategy)

        val messages = buildList {
            add(ChatMessage(SYSTEM_ROLE, systemPrompt))
            profileMessage?.let { add(it) }
            invariantMessage?.let { add(it) }
            taskMessage?.let { add(it) }
            rejectionMessage?.let { add(it) }
            invariantCheckMessage?.let { add(it) }
            summary?.let { add(compressor.summaryMessage(it)) }
            longTermMessage?.let { add(it) }
            workingMessage?.let { add(it) }
            // Метка ветки — служебная: в API сообщения уходят без неё.
            addAll(withoutBranchTags(historyForRequest))
            add(ChatMessage(USER_ROLE, userMessage))
        }

        // Разбивка по частям: API отдаёт только общий prompt_tokens, поэтому
        // вклад system prompt, профиля, слоёв памяти и истории считаем локально.
        val systemTokens = tokenCounter.count(systemPrompt)
        val profileTokens = profileMessage?.let { tokenCounter.count(it.content) } ?: 0
        val taskTokens = listOfNotNull(taskMessage, rejectionMessage)
            .sumOf { tokenCounter.count(it.content) }
        // Инварианты считаются вместе: блок правил и сообщение проверки — обе части одного
        // правила ответа, и в отчёте они идут одним числом ([InvariantReport.tokens]).
        val invariantTokens = invariantMessage?.let { tokenCounter.count(it.content) } ?: 0
        val invariantCheckTokens = invariantCheckMessage?.let { tokenCounter.count(it.content) } ?: 0
        val invariantsTokens = invariantTokens + invariantCheckTokens
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
        // Лог задачи: где работа стоит, чем кончился переход и чего стоил служебный вызов.
        context.task.log(taskTokens)?.let(logger::log)
        // Лог инвариантов: что ушло в запрос, чем кончилась проверка и чего она стоила.
        context.invariants.log(invariantTokens)?.let(logger::log)

        logger.log(
            listOf(
                "Запрос → $model",
                "стратегия контекста: ${strategy.title}",
                "system prompt: $systemTokens ток.",
                "профиль: $profileTokens ток.",
                "инварианты: $invariantsTokens ток.",
                "задача: $taskTokens ток.",
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
                profileTokens = profileTokens,
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
                ),
                task = TaskReport(
                    stage = taskState?.stage,
                    step = taskState?.step.orEmpty(),
                    expectedAction = taskState?.expectedAction.orEmpty(),
                    paused = taskState?.paused == true,
                    moved = context.task.moved,
                    rejected = context.task.rejected,
                    rejectReason = context.task.rejectReason,
                    tokens = taskTokens,
                    updateTokens = context.task.call?.totalTokens ?: 0,
                    updateCostUsd = context.task.call?.costUsd
                ),
                invariants = InvariantReport(
                    invariants = context.invariants.invariants,
                    tokens = invariantsTokens,
                    verdict = context.invariants.verdict?.verdict,
                    violated = context.invariants.verdict?.violations?.map { it.kind }.orEmpty(),
                    // Причины идут в том же порядке, что и виды нарушений, поэтому по отчёту
                    // видно, чем именно запрос противоречит каждому правилу.
                    reason = context.invariants.verdict?.violations
                        ?.joinToString("; ") { it.reason }
                        ?.takeIf { it.isNotEmpty() },
                    updateTokens = context.invariants.call?.totalTokens ?: 0,
                    updateCostUsd = context.invariants.call?.costUsd
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
     * @param task Состояние задачи после этого сообщения и цена его обновления.
     * @param invariants Правила проекта этого запроса, вердикт проверки и цена вызова.
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
        val sharedMessages: Int = 0,
        val task: TaskPlan = TaskPlan(),
        val invariants: InvariantPlan = InvariantPlan()
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

        // Сессию держат сводка истории и состояние задачи: рабочей памяти она не нужна —
        // слой лежит по профилю, поэтому читается и пишется без неё.
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
        // Задача ведётся независимо от стратегии: состояние — не часть контекста, а работа,
        // которую агент ведёт сам, поэтому стратегия на него не влияет.
        val task = planTask(model, sessionId, windowed, userMessage)
        // Инварианты читаются после задачи и тоже независимо от стратегии: правило — не часть
        // контекста, а условие над ним, поэтому в промпт оно уходит при любой стратегии.
        val invariants = planInvariants(model, windowed, userMessage)

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
            },
            task = task,
            invariants = invariants
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

    /**
     * Состояние задачи после этого сообщения и цена его обновления.
     *
     * @param state Состояние, которое уходит в запрос и остаётся в сторе; null — задачи нет.
     * @param moved Этап сменился этим ответом.
     * @param rejected Сколько переходов отклонено: этап неизвестен или запрещён таблицей.
     * @param rejectReason Причина последнего отказа.
     * @param rejection Сообщение ассистенту об отклонённом переходе; null — отказа не было.
     *        Этап не сдвинулся, поэтому без него модель увидела бы только прежний блок
     *        состояния и попробовала бы перепрыгнуть этап снова ([TaskState.renderRejection]).
     * @param call Служебный вызов обновления состояния; null — вызова не было.
     */
    private data class TaskPlan(
        val state: TaskState? = null,
        val moved: Boolean = false,
        val rejected: Int = 0,
        val rejectReason: String? = null,
        val rejection: String? = null,
        val call: ServiceCall? = null
    ) {

        /**
         * Блок лога «Задача»: где работа стоит, чем кончился переход и чего стоил
         * служебный вызов. null — задачи в диалоге нет, и рассказывать нечего.
         */
        fun log(tokens: Int): String? {
            val task = state ?: return null
            return buildList {
                add("Задача")
                add("этап: ${task.stageOf()?.title ?: task.stage}${if (moved) " (этап сменился)" else ""}")
                add("текущий шаг: ${task.step.ifEmpty { "не назван" }}")
                add("ожидаемое действие: ${task.expectedAction.ifEmpty { "не названо" }}")
                add("блок задачи в запросе: $tokens ток.")
                rejectReason?.let { add("переход отклонён: $it") }
                if (task.paused) add("задача на паузе: служебного вызова нет — состояние заморожено")
                call?.let {
                    add(
                        "обновление задачи: ${it.totalTokens} ток. " +
                            "(вход ${it.promptTokens}, ответ ${it.replyTokens}), цена ${costUsd(it.costUsd)}"
                    )
                }
            }.joinToString("\n")
        }
    }

    /**
     * Состояние задачи: где работа стоит после этого сообщения (день 13).
     *
     * Задачу заводит человек явным действием ([TaskWriter]) — до этого состояния нет,
     * и спрашивать модель не о чем: служебных вызовов не будет вовсе, поэтому поведение
     * прежних дней не меняется. Дальше переход предлагает модель, а принимает код
     * ([TaskRules]): запрещённый переход отклоняется причиной, и состояние остаётся прежним.
     *
     * На паузе вызова нет: состояние заморожено, и работа продолжится с того же шага —
     * в этом и смысл паузы. Сбой вызова диалог не ломает: остаётся прежнее состояние.
     */
    private suspend fun planTask(
        model: String,
        sessionId: String?,
        history: List<ChatMessage>,
        userMessage: String
    ): TaskPlan {
        // Состояние задачи адресуется диалогом: без имени сессии читать его негде, а значит
        // и вести нечего. Строка в логе объясняет, почему задачи в запросе не будет.
        if (sessionId == null) {
            logger.log("Задача: у запроса нет sessionId — состояние задачи не ведётся")
            return TaskPlan()
        }
        val previous = taskStateStore.get(sessionId) ?: return TaskPlan()
        // Пауза: состояние заморожено, поэтому модель о переходе не спрашивают вовсе —
        // иначе ответ на паузе сдвинул бы этап, которого человек не продолжал.
        if (previous.paused) return TaskPlan(previous)

        val fresh = history.takeLast(MEMORY_CONTEXT_MESSAGES) + ChatMessage(USER_ROLE, userMessage)
        val reply = callService("задача", taskExtractor.request(model, previous, fresh))
            ?: return TaskPlan(previous)
        val proposal = taskExtractor.parse(reply.text)
        if (proposal == null) {
            logger.log(
                "Состояние задачи не обновилось (модель вернула не JSON или сказала, что задачи нет) — " +
                    "оставлено прежнее состояние"
            )
            return TaskPlan(previous, call = reply.call)
        }

        return when (val decision = TaskRules.accept(previous, proposal)) {
            is TaskDecision.Accepted -> {
                taskStateStore.put(sessionId, decision.state)
                TaskPlan(decision.state, moved = decision.moved, call = reply.call)
            }

            is TaskDecision.Rejected -> TaskPlan(
                previous,
                rejected = 1,
                rejectReason = decision.reason,
                rejection = previous.renderRejection(decision.reason),
                call = reply.call
            )
        }
    }

    /**
     * Инварианты этого запроса: правила проекта и вердикт проверки на конфликт с ними (день 14).
     *
     * Правил нет — ни блока, ни вызова: спрашивать модель не о чем, проверять нечего, и в отчёте
     * остаётся пустой [InvariantReport], поэтому поведение прежних дней не меняется. Умолчание
     * проекта агент себе не подставляет: правила кладёт в хранилище сервер, и агент без них просто
     * работает без правил. Правила есть — блок уходит в запрос при любой стратегии, а служебный
     * вызов проверяет, не противоречит ли им запрос. Проверка идёт на каждом ходу, даже когда
     * правило в диалоге не упоминали: конфликт видно по самой просьбе, а не по тому, назвал ли
     * её человек.
     *
     * Вызов идёт от того же списка, из которого собран блок: правила, вид которых агент не знает,
     * в промпт не уходят, поэтому проверять их нечем ([InvariantRules.render]). Сбой вызова или
     * неразобранный ответ вердикта не дают ([GuardVerdict]): правило отказа в запрос не добавляется,
     * блок правил остаётся, и диалог продолжается как обычно.
     */
    private suspend fun planInvariants(
        model: String,
        history: List<ChatMessage>,
        userMessage: String
    ): InvariantPlan {
        val invariants = invariantStore.get(DEFAULT_PROFILE).orEmpty()
        val block = InvariantRules.render(invariants)
        if (block.isEmpty()) return InvariantPlan()

        val fresh = history.takeLast(MEMORY_CONTEXT_MESSAGES) + ChatMessage(USER_ROLE, userMessage)
        val reply = callService("инварианты", invariantGuard.request(model, invariants, fresh))
            ?: return InvariantPlan(invariants, block)
        val verdict = invariantGuard.parse(reply.text)
        if (verdict == null) {
            logger.log(
                "Проверка инвариантов не удалась (модель вернула не JSON или вердикт не из двух " +
                    "значений) — вердикта нет, правила ушли в запрос как есть"
            )
            return InvariantPlan(invariants, block, call = reply.call)
        }

        return InvariantPlan(
            invariants = invariants,
            block = block,
            verdict = verdict,
            check = invariantGuard.message(verdict, invariants),
            call = reply.call
        )
    }

    /**
     * Инварианты этого запроса: правила, вердикт проверки и цена служебного вызова.
     *
     * @param invariants Правила, которые ушли в запрос системным сообщением.
     * @param block Блок правил для системного сообщения; пуст — правил нет.
     * @param verdict Вердикт проверки; null — проверки не было или её ответ не разобрался.
     * @param check Сообщение проверки для запроса; null — проверять нечего или вердикта нет.
     * @param call Служебный вызов проверки; null — вызова не было.
     */
    private data class InvariantPlan(
        val invariants: List<Invariant> = emptyList(),
        val block: String = "",
        val verdict: GuardVerdict? = null,
        val check: String? = null,
        val call: ServiceCall? = null
    ) {

        /**
         * Блок лога «Инварианты»: что ушло в запрос, вердикт проверки, нарушенные правила
         * с причиной и цена служебного вызова. null — правил нет, и рассказывать нечего.
         */
        fun log(blockTokens: Int): String? {
            if (block.isEmpty()) return null
            val lines = invariants.mapNotNull { InvariantRules.line(it) }
            return buildList {
                add("Инварианты")
                add("блок в запросе: ${lines.size} — $blockTokens ток.")
                lines.forEach { add("  $it") }
                if (verdict == null) {
                    add("проверка инвариантов: вердикта нет — вызов не удался или ответ не разобрался")
                } else {
                    add("вердикт проверки: ${verdict.verdict}")
                    verdict.violations.forEach { add("нарушен инвариант: ${InvariantGuard.violation(it, invariants)}") }
                }
                call?.let {
                    add(
                        "проверка инвариантов: ${it.totalTokens} ток. " +
                            "(вход ${it.promptTokens}, ответ ${it.replyTokens}), цена ${costUsd(it.costUsd)}"
                    )
                }
            }.joinToString("\n")
        }
    }

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
