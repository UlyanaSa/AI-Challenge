package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Модель демонстрации — та же, что у сервера по умолчанию. */
private val DEMO_MODEL = AppConfig.DEFAULT_MODEL

/** Бюджет ответа стресс-этапов: прибавляется к запросу при проверке окна модели. */
private const val STRESS_MAX_TOKENS = 512

/** Токены подставленного ответа: постоянные, чтобы в таблице роста виден был вклад истории. */
private const val CANNED_REPLY_TOKENS = 150
private const val CANNED_REASONING_TOKENS = 90
private const val CANNED_REPLY =
    "Вы называли бюджет 5 000 рублей в месяц на дорогу: такси, метро и поезда к родителям."

/** Длинный вопрос этапов 1–2: разница между этапами только в истории, вопрос один и тот же. */
private const val TAXI_QUESTION = "Сколько стоит такси до работы за месяц?"

/** Короткий вопрос этапа 3: сам он крошечный, но история уезжает в API целиком. */
private const val BUDGET_QUESTION = "Какой бюджет я называл?"

/** Диалог о бюджете: семь пар сообщений, бюджет назван в первой паре. */
private val DEMO_DIALOG: List<ChatMessage> = listOf(
    ChatMessage(
        "user",
        "Составляю план трат на месяц. Мой бюджет на дорогу — 5 000 рублей: это такси до работы, " +
            "метро и пара поездов к родителям. Всё, что выйдет за эту сумму, придётся вычеркнуть."
    ),
    ChatMessage(
        "assistant",
        "Понял: бюджет 5 000 рублей в месяц на дорогу — такси, метро и поездки к родителям. " +
            "Всё сверх этой суммы под вопросом, дальше считаем по статьям."
    ),
    ChatMessage(
        "user",
        "Такси до работы стоит 420 рублей в одну сторону, езжу дважды в неделю. " +
            "Прикинь, сколько это съест из бюджета за месяц."
    ),
    ChatMessage(
        "assistant",
        "Восемь поездок в месяц по 420 рублей — это 3 360 рублей, то есть большая часть бюджета. " +
            "На метро и поезда остаётся 1 640 рублей."
    ),
    ChatMessage(
        "user",
        "Метро — 62 рубля за поездку, в месяц выходит около сорока поездок. " +
            "Сколько останется после такси и метро?"
    ),
    ChatMessage(
        "assistant",
        "Сорок поездок на метро — 2 480 рублей. Вместе с такси это 5 840 рублей: " +
            "бюджет уже превышен на 840 рублей."
    ),
    ChatMessage(
        "user",
        "Тогда поезда к родителям придётся урезать. Билет туда-обратно 1 200 рублей, " +
            "планировал две поездки. Что делать с бюджетом?"
    ),
    ChatMessage(
        "assistant",
        "Две поездки — 2 400 рублей, вместе с дорогой до работы выходит 8 240 рублей. " +
            "Варианты: одна поездка вместо двух, такси только до метро или поднять бюджет."
    ),
    ChatMessage(
        "user",
        "Договорились: оставляю одну поездку на поезде и убираю половину такси. " +
            "Пересчитай остаток бюджета."
    ),
    ChatMessage(
        "assistant",
        "Тогда такси 1 680, метро 2 480, поезд 1 200 — всего 5 360 рублей. " +
            "Перерасход 360 рублей закрывается отказом от двух поездок на такси."
    ),
    ChatMessage(
        "user",
        "А если я буду ходить пешком до метро, сколько сэкономлю за месяц и что останется в бюджете?"
    ),
    ChatMessage(
        "assistant",
        "Пешком экономится примерно 1 680 рублей, расходы падают до 3 680 рублей, " +
            "и в бюджете остаётся 1 320 рублей."
    ),
    ChatMessage(
        "user",
        "Запиши итог: сколько я планировал потратить на дорогу и сколько выходит по факту после урезаний?"
    ),
    ChatMessage(
        "assistant",
        "Планировали 5 000 рублей в месяц, после урезаний выходит 3 680 рублей, " +
            "свободный остаток — 1 320 рублей."
    )
)

/** Живой режим включается явно: без него прогон не ходит в сеть и не тратит бюджет. */
private val demoLive: Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/** Ключ: из задачи `demoLogs` (берёт `server/.env`), из окружения или из запуска в IDE. */
private val demoApiKey: String? =
    System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

/** Идёт ли прогон на живом API: нужен и флаг, и ключ. */
private val demoOnLiveApi: Boolean = demoLive && demoApiKey != null

/** Транспорт демонстрации: живой — только по явному запросу, иначе подставленный ответ. */
private fun demoClient(): LlmClient = if (demoOnLiveApi) liveClient() else CannedClient()

/** Живой транспорт: тот же клиент, что у сервера, — отличается только таймаутами. */
private fun liveClient(): LlmClient = DeepSeekClient(
    apiKey = demoApiKey!!,
    http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 300_000
        }
    }
)

/**
 * Подставленный транспорт: сеть не нужна. Токены запроса считает тот же счётчик,
 * что и агент, поэтому «факт» равен оценке, а ответ фиксированный — демонстрация
 * показывает рост входа, а не качество ответа.
 */
private class CannedClient : LlmClient {
    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        val promptTokens = EstimatingTokenCounter.countPrompt(request.messages)
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", CANNED_REPLY), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = promptTokens,
                completionTokens = CANNED_REPLY_TOKENS,
                totalTokens = promptTokens + CANNED_REPLY_TOKENS,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(CANNED_REASONING_TOKENS)
            )
        )
    }
}

/** Печатает строку демонстрации в том же потоке, что и лог агента. */
private fun log(line: String) = println("[agent] $line")

/** Заголовок этапа и режим прогона: сразу видно, живые числа или подставленные. */
private fun stage(title: String) {
    val mode = if (demoOnLiveApi) {
        "живой API ($DEMO_MODEL)"
    } else {
        "подставленный ответ: сеть выключена, токены запроса считает локальный счётчик"
    }
    println()
    log("===== $title =====")
    log("режим: $mode")
}

/** Настройки демонстрации: та же модель и история диалога; бюджет ответа — по умолчанию. */
private fun options(history: List<ChatMessage>, maxTokens: Int? = null) =
    AgentOptions(model = DEMO_MODEL, maxTokens = maxTokens, history = history)

/** Стоимость вызова по тарифу модели: вход и ответ считаются отдельно. */
private fun callCost(tokens: TokenReport): Double {
    val spec = ModelCatalog.spec(DEMO_MODEL)
    return tokens.promptTokens / 1_000_000.0 * (spec.inputPer1MUsd ?: 0.0) +
        tokens.replyTokens / 1_000_000.0 * (spec.outputPer1MUsd ?: 0.0)
}

/** Цена за 1 млн токенов; у модели без тарифа — прочерк. */
private fun price(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

/** Деньги с точностью до микроцента: на 1M-окне это самые читаемые числа. */
private fun money(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/**
 * Итог вызова по формулам демонстрации:
 * `totalInputTokens = systemPromptTokens + historyTokens + currentRequestTokens`,
 * `totalRequestTokens = totalInputTokens + responseTokens`,
 * `inputCost`/`outputCost` — по цене за 1 млн токенов, `requestCost` — их сумма.
 */
private fun logCallTotals(label: String, tokens: TokenReport) {
    val spec = ModelCatalog.spec(DEMO_MODEL)
    val inputCost = tokens.promptTokens / 1_000_000.0 * (spec.inputPer1MUsd ?: 0.0)
    val outputCost = tokens.replyTokens / 1_000_000.0 * (spec.outputPer1MUsd ?: 0.0)
    log("--- $label ---")
    log(
        "totalInputTokens = systemPromptTokens ${tokens.systemPrompt} + historyTokens ${tokens.history} + " +
            "currentRequestTokens ${tokens.request} = ${tokens.systemPrompt + tokens.history + tokens.request} (оценка)"
    )
    log("токенов запроса по данным API: ${tokens.promptTokens} — история входит сюда целиком")
    log(
        "totalRequestTokens = totalInputTokens + responseTokens = " +
            "${tokens.promptTokens} + ${tokens.replyTokens} = ${tokens.promptTokens + tokens.replyTokens}"
    )
    log("inputCost = ${tokens.promptTokens} / 1M × \$${price(spec.inputPer1MUsd)} = \$${money(inputCost)}")
    log("outputCost = ${tokens.replyTokens} / 1M × \$${price(spec.outputPer1MUsd)} = \$${money(outputCost)}")
    log("requestCost = inputCost + outputCost = \$${money(inputCost + outputCost)}")
}

/**
 * История-балласт: длинная кириллица без пробелов. Длину считаем от счётчика агента,
 * чтобы попасть в заданную долю окна модели (у счётчика кириллица — 2.5 символа на токен).
 */
private fun ballast(windowShare: Double): List<ChatMessage> {
    val sample = "я".repeat(1_000)
    val charsPerToken = sample.length.toDouble() / EstimatingTokenCounter.count(sample)
    val targetTokens = (ModelCatalog.CONTEXT_WINDOW * windowShare).toInt()
    return listOf(ChatMessage("user", "я".repeat((targetTokens * charsPerToken).toInt())))
}

/** Сколько токенов займёт запрос с такой историей, по мнению агента. */
private fun estimate(history: List<ChatMessage>, question: String): Int =
    EstimatingTokenCounter.countPrompt(history + ChatMessage("user", question))

/**
 * Демонстрация главной идеи: сообщение пользователя остаётся одинаково маленьким,
 * но каждый следующий вызов API дороже — вместе с ним снова отправляется вся история.
 *
 * Этапы: короткий диалог → длинный диалог → короткий вопрос при длинной истории →
 * таблица роста по ходам → стресс у границы окна → реальная ошибка API при превышении.
 *
 * Прогон с печатью логов в консоль:
 *
 *     ./gradlew :agent:demoLogs
 *     ./gradlew :agent:demoLogs -Pdemo.live=1   // живые вызовы, ключ из server/.env
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TokenBudgetDemoTest {

    /** Этап 1: диалог только начался, история маленькая. */
    @Test
    fun stage1_shortDialogHasSmallHistory() = runBlocking {
        stage("Этап 1. Короткий диалог: 2 сообщения, история маленькая")
        val result = LlmAgent(demoClient(), logger = AgentLogger.Console)
            .run(TAXI_QUESTION, options(DEMO_DIALOG.take(2)))
        logCallTotals("итог этапа 1", result.tokens)
    }

    /** Этап 2: тот же вопрос, но история выросла до 14 сообщений. */
    @Test
    fun stage2_longDialogHasBiggerHistory() = runBlocking {
        stage("Этап 2. Длинный диалог: 14 сообщений, история заметно выросла")
        val result = LlmAgent(demoClient(), logger = AgentLogger.Console)
            .run(TAXI_QUESTION, options(DEMO_DIALOG))
        logCallTotals("итог этапа 2", result.tokens)
    }

    /** Этап 3: вопрос короткий, а вход всё равно большой — в него входит вся история. */
    @Test
    fun stage3_shortQuestionStillSendsWholeHistory() = runBlocking {
        stage("Этап 3. Короткий вопрос «$BUDGET_QUESTION» при той же истории")
        val result = LlmAgent(demoClient(), logger = AgentLogger.Console)
            .run(BUDGET_QUESTION, options(DEMO_DIALOG))
        logCallTotals("итог этапа 3", result.tokens)
    }

    /** Разница между этапами: вопрос почти не меняется, а вход растёт вместе с историей. */
    @Test
    fun stage4_whatChangedBetweenStages() {
        stage("Итог этапов. Что изменилось: вопрос или история")
        val stages = listOf(
            "этап 1: 2 сообщения, вопрос «$TAXI_QUESTION»" to estimate(DEMO_DIALOG.take(2), TAXI_QUESTION),
            "этап 2: 14 сообщений, тот же вопрос" to estimate(DEMO_DIALOG, TAXI_QUESTION),
            "этап 3: 14 сообщений, короткий вопрос «$BUDGET_QUESTION»" to estimate(DEMO_DIALOG, BUDGET_QUESTION)
        )
        stages.forEach { (title, promptTokens) -> log("$title → вход $promptTokens ток. (оценка)") }
        val questionTokens = listOf(TAXI_QUESTION, BUDGET_QUESTION).map(EstimatingTokenCounter::count)
        log("вопрос весит ${questionTokens.min()}–${questionTokens.max()} ток., а вход вырос в " +
            "${"%.1f".format(Locale.ROOT, stages.last().second.toDouble() / stages.first().second)} раза: " +
            "история уезжает в API на каждом ходу целиком")
    }

    /** Таблица роста: один и тот же короткий вопрос на каждом ходу диалога. */
    @Test
    fun stage5_sameQuestionEveryTurn() = runBlocking {
        stage("Таблица роста. Один и тот же короткий вопрос на каждом ходу")
        val questionTokens = EstimatingTokenCounter.count(BUDGET_QUESTION)
        val replyNote = if (demoOnLiveApi) "ответ — из ответа API" else "ответ постоянный: $CANNED_REPLY_TOKENS ток."
        log("вопрос «$BUDGET_QUESTION» = $questionTokens ток. на любом ходу; $replyNote")
        log(String.format(Locale.ROOT, "%7s %9s %8s %9s %8s %8s %11s", "сообщ.", "история", "вопрос", "вход", "ответ", "всего", "цена"))
        val agent = LlmAgent(demoClient(), logger = AgentLogger.Silent)
        var totalCost = 0.0
        for (messages in 2..DEMO_DIALOG.size step 2) {
            val tokens = agent.run(BUDGET_QUESTION, options(DEMO_DIALOG.take(messages))).tokens
            val cost = callCost(tokens)
            totalCost += cost
            log(
                String.format(
                    Locale.ROOT, "%7d %9d %8d %9d %8d %8d  \$%.6f",
                    messages, tokens.history, tokens.request, tokens.promptEstimate,
                    tokens.replyTokens, tokens.promptTokens + tokens.replyTokens, cost
                )
            )
        }
        log("итого за ${DEMO_DIALOG.size / 2} вызовов одного и того же вопроса: \$${money(totalCost)}")
    }

    /** Стресс: история подбирается к окну модели — агент останавливает запрос до API. */
    @Test
    fun stage6_contextOverflowStopsBeforeApi() = runBlocking {
        stage("Стресс 1. История у границы окна: запрос не уходит в API")
        val history = ballast(windowShare = 1.2)
        log("оценка запроса: ${estimate(history, BUDGET_QUESTION)} ток., окно модели: ${ModelCatalog.CONTEXT_WINDOW} ток.")
        val agent = LlmAgent(demoClient(), logger = AgentLogger.Console)
        val failure = assertFailsWith<ContextOverflowException> {
            agent.run(BUDGET_QUESTION, options(history, maxTokens = STRESS_MAX_TOKENS))
        }
        log("исключение: ${failure.message}")
    }

    /** Стресс: оценка проходит проверку агента, а переполнение ловит уже сам провайдер. */
    @Test
    fun stage7_providerRejectsOversizedRequest() = runBlocking {
        stage("Стресс 2. Оценка проходит проверку, переполнение ловит API")
        assumeTrue("нужен живой режим: ./gradlew :agent:demoLogs -Pdemo.live=1", demoOnLiveApi)
        val spec = ModelCatalog.spec(DEMO_MODEL)
        val history = ballast(windowShare = 0.99)
        val promptEstimate = estimate(history, BUDGET_QUESTION)
        log(
            "оценка запроса: $promptEstimate ток. + бюджет ответа $STRESS_MAX_TOKENS ток. " +
                "≤ окна ${spec.contextWindow} ток. — проверка агента пропускает запрос"
        )
        assertTrue(
            promptEstimate + STRESS_MAX_TOKENS <= spec.contextWindow,
            "балласт должен проходить проверку агента, иначе провайдер не получит запрос"
        )
        val agent = LlmAgent(demoClient(), logger = AgentLogger.Console)
        val failure = assertFailsWith<LlmApiException> {
            agent.run(BUDGET_QUESTION, options(history, maxTokens = STRESS_MAX_TOKENS))
        }
        log("статус ответа API: ${failure.status} — тела ответа провайдера напечатал лог агента выше")
    }
}
