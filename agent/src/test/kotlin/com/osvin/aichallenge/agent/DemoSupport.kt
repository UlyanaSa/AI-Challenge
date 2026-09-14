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
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Общая часть демонстраций: режим прогона, транспорт, печать строк и цены.
 *
 * Живой режим включается явно (`-Pdemo.live=1` или `DEEPSEEK_DEMO_LIVE=1` и ключ),
 * иначе прогон идёт на подставленном ответе: он не зависит от сети и не тратит бюджет.
 */

/** Модель демонстраций — та же, что у сервера по умолчанию. */
internal val DEMO_MODEL = AppConfig.DEFAULT_MODEL

/** Токены подставленного ответа: постоянные, чтобы рост цены объяснялся ростом входа. */
internal const val CANNED_REPLY_TOKENS = 150
internal const val CANNED_REASONING_TOKENS = 90

/** Живой режим включается явно: без него прогон не ходит в сеть и не тратит бюджет. */
internal val demoLive: Boolean =
    System.getProperty("demo.live") == "1" || System.getenv("DEEPSEEK_DEMO_LIVE") == "1"

/** Ключ: из задачи `demoLogs` (берёт `server/.env`), из окружения или из запуска в IDE. */
internal val demoApiKey: String? =
    System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

/** Идёт ли прогон на живом API: нужен и флаг, и ключ. */
internal val demoOnLiveApi: Boolean = demoLive && demoApiKey != null

/** Транспорт демонстрации: живой — только по явному запросу, иначе подставленный ответ. */
internal fun demoClient(reply: String, summary: String = reply): LlmClient =
    if (demoOnLiveApi) liveClient() else CannedClient(reply, summary)

/** Живой транспорт: тот же клиент, что у сервера, — отличается только таймаутами. */
internal fun liveClient(): LlmClient = DeepSeekClient(
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
 * Подставленный транспорт: сеть не нужна. Токены запроса считает тот же счётчик, что и
 * агент, поэтому «факт» равен оценке. Первый вызов — служебный запрос сводки (если он есть),
 * остальные — ответ модели: поэтому у демонстраций есть [reply] и [summary].
 */
internal class CannedClient(
    private val reply: String,
    private val summary: String
) : LlmClient {
    private var calls = 0

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        val answer = if (calls++ == 0) summary else reply
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

/** Печатает строку демонстрации в том же потоке, что и лог агента. */
internal fun log(line: String) = println("[agent] $line")

/** Заголовок этапа и режим прогона: сразу видно, живые числа или подставленные. */
internal fun stage(title: String) {
    val mode = if (demoOnLiveApi) {
        "живой API ($DEMO_MODEL)"
    } else {
        "подставленный ответ: сеть выключена, токены запроса считает локальный счётчик"
    }
    println()
    log("===== $title =====")
    log("режим: $mode")
}

/** Настройки демонстрации: модель, история диалога, сессия и переключатель сжатия. */
internal fun options(
    history: List<ChatMessage>,
    maxTokens: Int? = null,
    sessionId: String? = null,
    compressHistory: Boolean = false
) = AgentOptions(
    model = DEMO_MODEL,
    maxTokens = maxTokens,
    history = history,
    sessionId = sessionId,
    compressHistory = compressHistory
)

/** Стоимость вызова по тарифу модели: вход и ответ считаются отдельно. */
internal fun callCost(tokens: TokenReport): Double {
    val spec = ModelCatalog.spec(DEMO_MODEL)
    return tokens.promptTokens / 1_000_000.0 * (spec.inputPer1MUsd ?: 0.0) +
        tokens.replyTokens / 1_000_000.0 * (spec.outputPer1MUsd ?: 0.0)
}

/** Цена за 1 млн токенов; у модели без тарифа — прочерк. */
internal fun price(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

/** Деньги с точностью до микроцента: на 1M-окне это самые читаемые числа. */
internal fun money(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
