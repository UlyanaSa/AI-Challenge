package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.DialogBranch
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Одна и та же сцена на всех стратегиях управления контекстом.
 *
 * Сцена — сбор ТЗ на приложение: 14 сообщений, важные детали названы в первых.
 * В конце задаётся один и тот же вопрос «собери ТЗ», и по ответу видно, что стратегия
 * сохранила, а что потеряла. Отдельный этап — ветки: точка ветвления после восьмого
 * сообщения и два независимых продолжения от неё.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ContextStrategiesDemoTest {

    /** Стратегия 1: скользящее окно — последние сообщения, старшие отбрасываются. */
    @Test
    fun stage1_slidingWindow() = runBlocking {
        stage("Стратегия 1: скользящее окно (последние $WINDOW сообщений)")
        val scene = replay(ContextStrategy.SLIDING_WINDOW)
        printScene(scene)
        assertTrue(scene.dropped > 0, "окно должно отбросить старшие сообщения: $scene")
        assertTrue(
            scene.requestMessages <= WINDOW + 2,
            "в запросе не больше окна плюс system prompt и вопрос: ${scene.requestMessages}"
        )
    }

    /** Стратегия 2: память фактов — блок «ключ — значение» плюс последние сообщения. */
    @Test
    fun stage2_facts() = runBlocking {
        stage("Стратегия 2: память фактов (окно $WINDOW сообщений)")
        val scene = replay(ContextStrategy.FACTS)
        printScene(scene)
        assertTrue(scene.facts.isNotEmpty(), "память фактов должна наполниться: $scene")
        assertTrue(scene.dropped > 0, "окно всё равно отбрасывает старшие сообщения")
        assertTrue(
            scene.requestContents.any { it.startsWith("Память диалога (факты):") },
            "в запрос уходит блок фактов: ${scene.requestContents}"
        )
    }

    /** Стратегия 3: ветки диалога — точка ветвления и два независимых продолжения. */
    @Test
    fun stage3_branches() = runBlocking {
        stage("Стратегия 3: ветки диалога (общая часть — $CHECKPOINT_AT сообщений пользователя)")

        val trunk = mutableListOf<ChatMessage>()
        val llm = strategyClient()
        val agent = LlmAgent(llm, logger = AgentLogger.Silent)
        SCENARIO.take(CHECKPOINT_AT).forEach { message ->
            val result = agent.run(
                message,
                options(trunk.toList(), sessionId = SESSION, strategy = ContextStrategy.BRANCHES)
            )
            trunk += ChatMessage("user", message)
            trunk += ChatMessage("assistant", result.reply)
        }
        val branches = listOf(
            DialogBranch(SUBSCRIPTION, forkedAfter = trunk.size),
            DialogBranch(ONE_TIME, forkedAfter = trunk.size)
        )
        log("общая часть диалога до точки ветвления: ${trunk.size} сообщ.")

        // Обе ветки растут от одной точки и лежат в одной истории: в запрос должен
        // попасть только путь активной ветки, а продолжение соседней — нет.
        val tree = trunk.toMutableList()
        val spent = mutableMapOf<String, Double>()
        listOf(SUBSCRIPTION to OFFER_SUBSCRIPTION, ONE_TIME to OFFER_ONE_TIME).forEach { (branch, offer) ->
            val result = agent.run(
                offer,
                options(
                    tree.toList(),
                    sessionId = SESSION,
                    strategy = ContextStrategy.BRANCHES,
                    branches = branches,
                    activeBranchId = branch
                )
            )
            spent[branch] = (spent[branch] ?: 0.0) + sceneCost(result.tokens)
            tree += ChatMessage("user", offer, branch)
            tree += ChatMessage("assistant", result.reply, branch)
        }

        val results = listOf(SUBSCRIPTION, ONE_TIME).map { branch ->
            val probe = agent.run(
                PROBE,
                options(
                    tree,
                    sessionId = SESSION,
                    strategy = ContextStrategy.BRANCHES,
                    branches = branches,
                    activeBranchId = branch
                )
            )
            Branch(
                id = branch,
                name = if (branch == SUBSCRIPTION) "подписка" else "разовый платёж",
                answer = probe.reply,
                score = KeyDetails.scoreOf(probe.reply),
                requestContents = llm.requests.last().messages.map { it.content },
                branchMessages = tree.count { it.branchId == branch },
                excluded = probe.tokens.excludedMessages,
                promptTokens = probe.tokens.promptTokens,
                replyTokens = probe.tokens.replyTokens,
                runs = CHECKPOINT_AT + 2,
                cost = (spent[branch] ?: 0.0) + sceneCost(probe.tokens)
            )
        }

        results.forEach { branch ->
            log("")
            log("Ветка «${branch.name}»: в запросе ${branch.requestContents.size} сообщ. — общая часть ${trunk.size}, своя ветка ${branch.branchMessages}, вопрос")
            log("вне пути этой ветки: ${branch.excluded} сообщ. (продолжение соседней ветки)")
            log("вход ${branch.promptTokens} ток., цена ветки вместе с продолжением ${money(branch.cost)}")
            log("ответ: ${branch.answer}")
            log("важных деталей из начала диалога: ${branch.score} из ${KeyDetails.size}")
        }

        // Строка веток в итоговой таблице — по одной из веток: у второй вход отличается
        // только сообщениями своего продолжения, а общая часть у них одна.
        results.first().let { branch ->
            scenes[ContextStrategy.BRANCHES.wire] = Scene(
                strategy = ContextStrategy.BRANCHES,
                answer = branch.answer,
                score = branch.score,
                requestMessages = branch.requestContents.size,
                requestContents = branch.requestContents,
                promptTokens = branch.promptTokens,
                replyTokens = branch.replyTokens,
                cost = branch.cost,
                serviceTokens = 0,
                dropped = 0,
                facts = emptyList(),
                logs = emptyList(),
                calls = branch.runs
            )
        }

        // Независимость веток проверяется всегда: продолжение соседней ветки не уходит в запрос.
        assertTrue(results.all { it.excluded > 0 }, "сообщения соседней ветки не уходят в запрос: $results")
        assertTrue(
            results.first { it.id == SUBSCRIPTION }.requestContents.none { it.contains(OFFER_ONE_TIME) },
            "продолжение соседней ветки не попало в запрос: ${results.first().requestContents}"
        )
        assertTrue(
            results.first { it.id == ONE_TIME }.requestContents.none { it.contains(OFFER_SUBSCRIPTION) },
            "продолжение соседней ветки не попало в запрос: ${results.last().requestContents}"
        )
        assertTrue(results.all { it.requestContents.any { content -> content.contains(OFFER_SUBSCRIPTION) || content.contains(OFFER_ONE_TIME) } })
    }

    /** Итог: сравнение стратегий на одной сцене — детали, токены, цена. */
    @Test
    fun stage4_comparison() = runBlocking {
        stage("Сравнение стратегий на одной сцене (${SCENARIO.size} сообщений + вопрос «собери ТЗ»)")

        // Сцены предыдущих этапов переиспользуются: повторять их незачем, а на живом
        // прогоне каждый повтор — это ещё пятнадцать платных сообщений.
        val window = sceneOf(ContextStrategy.SLIDING_WINDOW)
        val facts = sceneOf(ContextStrategy.FACTS)
        val branches = sceneOf(ContextStrategy.BRANCHES)
        // Полная история и сжатие — точка отсчёта в таблице, в постановку дня они не входят,
        // поэтому на живом прогоне берём их только из уже посчитанного.
        val baseline = scenes[ContextStrategy.FULL.wire]
            ?: if (demoOnLiveApi) null else replay(ContextStrategy.FULL)
        val summary = scenes[ContextStrategy.SUMMARY.wire]
            ?: if (demoOnLiveApi) null else replay(ContextStrategy.SUMMARY)

        listOfNotNull(baseline, window, facts, branches, summary).forEach(::printRow)
        if (baseline == null) {
            log("")
            log("полная история и сжатие в таблицу не попали: в постановке дня три стратегии, и живой прогон их не считает")
        }
        log("")
        log("важные детали сцены: ${KeyDetails.entries.joinToString("; ") { it.title }}")
        log("вопрос к каждой стратегии: «$PROBE»")

        if (baseline != null) {
            assertTrue(
                baseline.requestMessages > window.requestMessages,
                "вся история длиннее окна: ${baseline.requestMessages} против ${window.requestMessages}"
            )
        }
        assertTrue(
            branches.requestMessages > window.requestMessages,
            "путь ветки длиннее окна: ${branches.requestMessages} против ${window.requestMessages}"
        )
    }

    /** Сцена стратегии: берём посчитанную на предыдущем этапе, иначе считаем заново. */
    private suspend fun sceneOf(strategy: ContextStrategy): Scene =
        scenes[strategy.wire] ?: replay(strategy)

    /** Прогоняет сцену целиком с заданной стратегией и возвращает её метрики. */
    private suspend fun replay(strategy: ContextStrategy): Scene {
        val llm = strategyClient()
        val logs = mutableListOf<String>()
        val agent = LlmAgent(llm, logger = AgentLogger { logs += it })
        val session = "$SESSION-${strategy.name.lowercase()}"
        val history = mutableListOf<ChatMessage>()
        var cost = 0.0
        var serviceTokens = 0

        SCENARIO.forEach { message ->
            val result = agent.run(
                message,
                options(history.toList(), sessionId = session, strategy = strategy, windowMessages = WINDOW)
            )
            cost += sceneCost(result.tokens)
            serviceTokens += result.tokens.factsUpdateTokens + result.tokens.compressionTokens
            history += ChatMessage("user", message)
            history += ChatMessage("assistant", result.reply)
        }

        val probe = agent.run(
            PROBE,
            options(history.toList(), sessionId = session, strategy = strategy, windowMessages = WINDOW)
        )
        cost += sceneCost(probe.tokens)
        serviceTokens += probe.tokens.factsUpdateTokens + probe.tokens.compressionTokens

        return Scene(
            strategy = strategy,
            answer = probe.reply,
            score = KeyDetails.scoreOf(probe.reply),
            requestMessages = llm.requests.last().messages.size,
            requestContents = llm.requests.last().messages.map { it.content },
            promptTokens = probe.tokens.promptTokens,
            replyTokens = probe.tokens.replyTokens,
            cost = cost,
            serviceTokens = serviceTokens,
            dropped = probe.tokens.droppedMessages,
            facts = probe.tokens.facts,
            logs = logs,
            calls = llm.requests.size
        ).also { scenes[strategy.wire] = it }
    }

    /** Печатает разбор одной сцены: что ушло в модель и что ответила модель. */
    private fun printScene(scene: Scene) {
        log("")
        scene.strategyBlock()?.let(::log)
        scene.requestBlock()?.let(::log)
        log("")
        log("что ушло в модель: ${scene.requestMessages} сообщ., вход ${scene.promptTokens} ток.")
        log("отброшено окном: ${scene.dropped} сообщ.")
        log("фактов в памяти: ${scene.facts.size}")
        scene.facts.forEach { log("- ${it.key}: ${it.value}") }
        log("служебные вызовы: ${scene.serviceTokens} ток.")
        log("цена сцены (${scene.calls} вызовов): ${money(scene.cost)}")
        log("")
        log("ответ на «собери ТЗ»: ${scene.answer}")
        log("важных деталей из начала диалога: ${scene.score} из ${KeyDetails.size}")
    }

    /** Печатает строку сводной таблицы стратегий: её же переносим в README. */
    private fun printRow(scene: Scene) {
        log(
            String.format(
                Locale.ROOT,
                "%-22s %5d %5d %5d %6d %10s %5s",
                scene.strategy.title,
                scene.requestMessages,
                scene.promptTokens,
                scene.replyTokens,
                scene.serviceTokens,
                money(scene.cost),
                "${scene.score}/${KeyDetails.size}"
            )
        )
    }

    /** Метрики одной сцены: ответ, память, токены и цена. */
    private data class Scene(
        val strategy: ContextStrategy,
        val answer: String,
        val score: Int,
        val requestMessages: Int,
        val requestContents: List<String>,
        val promptTokens: Int,
        val replyTokens: Int,
        val cost: Double,
        val serviceTokens: Int,
        val dropped: Int,
        val facts: List<Fact>,
        val logs: List<String>,
        val calls: Int
    ) {

        /** Блок лога стратегии: что именно она сделала с историей. */
        fun strategyBlock(): String? = logs.lastOrNull { entry ->
            entry.lineSequence().first() in STRATEGY_BLOCKS
        }

        /** Блок лога последнего запроса: стратегия, части запроса и окно модели. */
        fun requestBlock(): String? = logs.lastOrNull { it.startsWith(REQUEST_BLOCK) }
    }

    /** Метрики одной ветки. */
    private data class Branch(
        val id: String,
        val name: String,
        val answer: String,
        val score: Int,
        val requestContents: List<String>,
        val branchMessages: Int,
        val excluded: Int,
        val promptTokens: Int,
        val replyTokens: Int,
        val runs: Int,
        val cost: Double
    )

    private companion object {
        /** Сцены, уже посчитанные этапами: сравнение переиспользует их, а не повторяет вызовы. */
        val scenes = ConcurrentHashMap<String, Scene>()
    }
}

/** Цена всех вызовов сцены: и ответа модели, и служебного вызова стратегии. */
private fun sceneCost(tokens: TokenReport): Double =
    callCost(tokens) + (tokens.factsUpdateCostUsd ?: 0.0) + (tokens.compressionCostUsd ?: 0.0)

/** Заголовки блоков лога стратегий: по ним разбор сцены находит нужную запись. */
private val STRATEGY_BLOCKS = setOf("Скользящее окно истории", "Память фактов", "Ветки диалога", "Сжатие истории")

/** Начало записи лога с разбором запроса. */
private const val REQUEST_BLOCK = "Запрос → "

/**
 * Сцена демонстрации: 14 сообщений пользователя, собирающих ТЗ.
 * Важные детали названы в первых сообщениях — их и проверяем в финальном ответе.
 */
private val SCENARIO = listOf(
    "Собираем ТЗ на приложение. Цель — учёт личных расходов.",
    "Бюджет проекта — 300 000 рублей.",
    "Срок — 6 недель, дальше релиз.",
    "Основная платформа — Android, веб нужен как второй экран.",
    "Регистрации быть не должно: данные хранятся только на устройстве.",
    "Отчёт о расходах — графики по категориям за месяц.",
    "Графики рисуем на Compose, чтобы не тянуть сторонние библиотеки.",
    "Синхронизацию между устройствами в первую версию не берём.",
    "Раз в неделю по пятницам показываем сводку за неделю.",
    "Настройки — тёмная тема и выбор валюты.",
    "Экспорт данных — CSV, без облака.",
    "Уведомления — только про превышение лимита по категории.",
    "Тестируем на Android 10 и выше.",
    "Итого: собираем ТЗ на первую версию."
)

/** Вопрос, по ответу на который видно, что стратегия сохранила из начала диалога. */
private const val PROBE =
    "Собери короткое ТЗ: перечисли цель, бюджет, срок, платформу, ограничения, принятые решения " +
        "и договорённости. Только то, что мы зафиксировали, без новых идей."

/** Размер окна для стратегий, которые его используют. */
private const val WINDOW = 6

/** Сессия демонстрации: по ней живут сводка и память фактов. */
private const val SESSION = "demo-strategies"

/** Точка ветвления: после восьмого сообщения пользователя. */
private const val CHECKPOINT_AT = 8

/** Идентификаторы двух веток от одной точки ветвления. */
private const val SUBSCRIPTION = "subscription"
private const val ONE_TIME = "one-time"

private const val OFFER_SUBSCRIPTION = "Посчитаем вариант: подписка 99 ₽ в месяц."
private const val OFFER_ONE_TIME = "Посчитаем вариант: разовый платёж 490 ₽."

/**
 * Важные детали сцены: по ним считается, что ответ сохранил.
 * Деталь засчитана, если в ответе есть любое из её ключевых слов.
 */
private enum class KeyDetails(val title: String, val markers: List<String>) {
    GOAL("цель — учёт расходов", listOf("учёт", "учета", "расход")),
    BUDGET("бюджет 300 000 ₽", listOf("300 000", "300000")),
    TERM("срок 6 недель", listOf("6 недель", "шесть недель")),
    PLATFORM("платформа Android", listOf("android")),
    NO_SIGNUP("без регистрации", listOf("регистрац")),
    LOCAL_DATA("данные на устройстве", listOf("на устройстве", "без облака", "локально")),
    COMPOSE("графики на Compose", listOf("compose")),
    WEEKLY_REPORT("сводка по пятницам", listOf("пятниц", "раз в неделю")),
    CSV("экспорт CSV", listOf("csv")),
    NO_SYNC("без синхронизации", listOf("синхронизац"));

    companion object {
        val size: Int get() = entries.size

        /** Сколько важных деталей сцены попало в ответ. */
        fun scoreOf(answer: String): Int {
            val text = answer.lowercase(Locale.ROOT)
            return entries.count { detail -> detail.markers.any { text.contains(it) } }
        }
    }
}

/**
 * Подставленный транспорт демонстрации: служебные вызовы стратегий получают свои
 * ответы (память фактов — JSON, сводка — конспект), на запросы сцены отвечает
 * подставной ответ. Токены считает тот же счётчик, что и агент.
 */
private class CannedStrategyClient : LlmClient {
    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        val instruction = request.messages.first().content
        val answer = when {
            instruction.contains("память диалога") -> CANNED_FACTS
            instruction.contains("конспект") -> CANNED_SUMMARY
            else -> CANNED_REPLY
        }

        val promptTokens = EstimatingTokenCounter.countPrompt(request.messages)
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", answer), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = promptTokens,
                completionTokens = CANNED_REPLY_TOKENS,
                totalTokens = promptTokens + CANNED_REPLY_TOKENS,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(CANNED_REASONING_TOKENS)
            )
        )
    }
}

/** Транспорт демонстрации с записью запросов: по ним видно, что именно ушло в модель. */
private class RecordingClient(private val delegate: LlmClient) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return delegate.complete(request)
    }
}

/** Транспорт демонстрации: живой API — только по явному флагу, иначе подставленные ответы. */
private fun strategyClient(): RecordingClient =
    RecordingClient(if (demoOnLiveApi) liveClient() else CannedStrategyClient())

private const val CANNED_FACTS =
    """{"facts":[{"key":"цель","value":"учёт личных расходов"},{"key":"бюджет","value":"300 000 рублей"},""" +
        """{"key":"срок","value":"6 недель"},{"key":"платформа","value":"Android, веб вторым экраном"},""" +
        """{"key":"ограничение","value":"без регистрации, данные только на устройстве"},""" +
        """{"key":"решение","value":"графики на Compose"},{"key":"договорённость","value":"сводка раз в неделю по пятницам"}]}"""

private const val CANNED_SUMMARY =
    "Сводка: цель — учёт личных расходов; бюджет 300 000 рублей; срок 6 недель; платформа Android; " +
        "без регистрации, данные на устройстве; графики на Compose; экспорт CSV."

private const val CANNED_REPLY =
    "ТЗ: цель — учёт личных расходов; бюджет 300 000 рублей; срок 6 недель; платформа Android; " +
        "без регистрации, данные на устройстве; графики на Compose; экспорт CSV; синхронизации нет; " +
        "сводка раз в неделю по пятницам."
