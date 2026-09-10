package com.osvin.aichallenge.agent

import com.osvin.aichallenge.GenerationFormat
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.config.AppConfig
import com.osvin.aichallenge.roundUpTokens
import java.util.Locale

/**
 * Настройки генерации, которые клиент может задать для одного запроса агента.
 * Все поля необязательны: агент сам подставляет значения по умолчанию
 * и приводит их к допустимым границам ([AppConfig]).
 */
data class AgentOptions(
    val model: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val runs: Int? = null,
    val format: String? = null,
    val temperature: Double? = null
)

/**
 * Итог работы агента.
 * @param reply Готовый текст для интерфейса: шапка с настройками, сверка прогонов и ответ модели.
 * @param promptTokens Токены запроса последнего прогона.
 * @param completionTokens Токены ответа последнего прогона.
 */
data class AgentResult(
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int
) {
    /** Расход токенов последнего прогона в формате ответа сервера. */
    val usage: Map<String, Int> = mapOf(
        "prompt_tokens" to promptTokens,
        "completion_tokens" to completionTokens,
        "total_tokens" to promptTokens + completionTokens
    )
}

/**
 * Агент — отдельная сущность, инкапсулирующая полный цикл обработки запроса:
 * принять сообщение пользователя, собрать запрос к LLM, получить ответ,
 * проверить его и отдать готовый результат интерфейсу.
 *
 * Агент не знает про HTTP и про сервер: обращение к модели идёт через [LlmClient].
 * Внутри агента остаются:
 *  - нормализация настроек (модель, лимит токенов, температура, стоп-слова, формат);
 *  - сборка сообщений (системная инструкция формата + запрос пользователя);
 *  - прогоны одного и того же запроса с повтором при обрыве ответа по лимиту токенов;
 *  - сверка ответа с заданным форматом и сборка текстового отчёта.
 *
 * @param llm Транспорт к LLM API.
 */
class LlmAgent(private val llm: LlmClient) {

    /**
     * Обрабатывает запрос пользователя и возвращает готовый ответ.
     * @param userMessage Текст запроса.
     * @param options Настройки генерации; null-поля заменяются значениями по умолчанию.
     */
    suspend fun run(userMessage: String, options: AgentOptions = AgentOptions()): AgentResult {
        val format = GenerationFormat.fromKey(options.format)
        val model = options.model ?: AppConfig.DEFAULT_MODEL
        val runs = (options.runs ?: AppConfig.DEFAULT_RUNS).coerceIn(1, AppConfig.MAX_RUNS)
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

        // Сообщения модели: инструкция о формате ответа. История диалога
        // не передаётся — каждый вопрос (все его прогоны) проверяется изолированно.
        val messages = buildList {
            format.instruction?.let { add(ChatMessage("system", it)) }
            add(ChatMessage("user", userMessage))
        }

        val outcomes = (1..runs).map {
            generate(model, messages, format, maxTokens, temperature, stopSequences)
        }

        val report = buildReport(model, format, runs, maxTokens, temperature, stopSequences, outcomes)
        val last = outcomes.last()
        return AgentResult(
            reply = report,
            promptTokens = last.promptTokens,
            completionTokens = last.completionTokens
        )
    }

    /**
     * Сборка текста для интерфейса: сверху — настройки и статус сверки по каждому
     * прогону, ниже — тела ответов (только неудачные прогоны, либо последний
     * успешный, если все прогоны прошли сверку).
     */
    private fun buildReport(
        model: String,
        format: GenerationFormat,
        runs: Int,
        maxTokens: Int,
        temperature: Double,
        stopSequences: List<String>?,
        outcomes: List<RunOutcome>
    ): String {
        val stopLine = stopSequences?.joinToString(", ") ?: "нет"
        val header = buildString {
            appendLine("=== Настройки ===")
            appendLine("Модель: $model")
            appendLine("Формат: ${format.label}")
            appendLine("Прогонов одного вопроса: $runs")
            appendLine("Максимум токенов: $maxTokens")
            appendLine("Температура: $temperature")
            appendLine("Стоп-слова: $stopLine")
            appendLine()
            appendLine("=== Сверка формата с заданным ===")
            outcomes.forEachIndexed { index, outcome ->
                val status = if (outcome.ok) "OK" else "ОШИБКА: ${outcome.reason}"
                val suffix = when {
                    outcome.truncatedAtCap ->
                        " (обрыв по лимиту даже при ${outcome.finalBudget})"
                    outcome.attempts > 1 ->
                        " (лимит увеличен до ${outcome.finalBudget})"
                    else -> ""
                }
                appendLine("Прогон ${index + 1} — $status$suffix")
                appendLine("   " + runMetricsLine(model, outcome))
            }
            appendLine()
            appendLine("=== Ответ ===")
        }

        val failed = outcomes.mapIndexedNotNull { index, outcome ->
            if (outcome.ok) null else index + 1 to outcome
        }

        return if (failed.isEmpty()) {
            header +
                "--- Прогон ${outcomes.size} (последний успешный) ---\n" +
                outcomes.last().reply
        } else {
            header + buildString {
                appendLine("--- Неудачные прогоны (формат не совпал) ---")
                failed.forEachIndexed { index, (runNumber, outcome) ->
                    if (index > 0) appendLine()
                    appendLine("--- Прогон $runNumber ---")
                    appendLine(outcome.reply)
                }
            }
        }
    }

    /**
     * Один прогон вопроса: запрос к LLM с автоувеличением лимита токенов.
     * Если модель оборвала ответ по лимиту (finish_reason = length), бюджет
     * округляется вверх и увеличивается, пока ответ не завершится осмысленно
     * или не будет достигнут максимальный лимит.
     */
    private suspend fun generate(
        model: String,
        messages: List<ChatMessage>,
        format: GenerationFormat,
        initialMaxTokens: Int,
        temperature: Double,
        stopSequences: List<String>?
    ): RunOutcome {
        var budget = initialMaxTokens
        var attempts = 0
        var reply = ""
        var finishReason: String? = null
        var usage: DeepSeekResponse.Usage? = null
        val startedAt = System.currentTimeMillis()

        while (true) {
            attempts++
            val response = llm.complete(
                DeepSeekRequest(
                    model = model,
                    messages = messages,
                    maxTokens = budget,
                    temperature = temperature,
                    stop = stopSequences,
                    responseFormat = if (format.jsonMode) GenerationFormat.STRICT_JSON_MODE else null
                )
            )

            usage = response.usage
            reply = response.choices.first().message.content
            finishReason = response.choices.first().finishReason

            // Ответ оборвался по лимиту и лимит ещё можно увеличить —
            // повторяем с округлённым вверх бюджетом до смыслового завершения
            if (finishReason == "length" && budget < AppConfig.MAX_TOKEN_CEILING) {
                budget = minOf(AppConfig.MAX_TOKEN_CEILING, roundUpTokens(budget * 3 / 2))
                continue
            }
            break
        }

        val mismatchReason = format.verify(reply)
        return RunOutcome(
            reply = reply,
            ok = mismatchReason == null,
            reason = mismatchReason,
            attempts = attempts,
            finalBudget = budget,
            truncatedAtCap = finishReason == "length" && budget >= AppConfig.MAX_TOKEN_CEILING,
            elapsedMs = System.currentTimeMillis() - startedAt,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0
        )
    }
}

/**
 * Результат одного прогона вопроса.
 * @param reply Текст ответа модели.
 * @param ok Прошёл ли ответ сверку формата.
 * @param reason Причина рассогласования формата (если не прошёл).
 * @param attempts Сколько запросов к LLM потребовалось (повторы при обрыве по лимиту).
 * @param finalBudget Итоговый лимит токенов после округления и повышения.
 * @param truncatedAtCap Ответ всё ещё обрывается по лимиту даже на максимальном бюджете.
 * @param elapsedMs Время прогона в миллисекундах (включая повторы при обрыве по лимиту).
 * @param promptTokens Токены запроса последнего ответа.
 * @param completionTokens Токены ответа последнего ответа.
 */
private data class RunOutcome(
    val reply: String,
    val ok: Boolean,
    val reason: String?,
    val attempts: Int,
    val finalBudget: Int,
    val truncatedAtCap: Boolean,
    val elapsedMs: Long,
    val promptTokens: Int,
    val completionTokens: Int
)

/**
 * Строка с замерами одного прогона для отчёта: время, токены, скорость,
 * стоимость. Стоимость считается по тарифу модели (см. AppConfig.MODEL_PRICES_USD_PER_1M);
 * для моделей без опубликованного тарифа выводится прочерк.
 */
private fun runMetricsLine(model: String, outcome: RunOutcome): String {
    val seconds = outcome.elapsedMs / 1000.0
    val speed = if (seconds > 0) (outcome.completionTokens / seconds).toInt() else 0
    val cost = AppConfig.MODEL_PRICES_USD_PER_1M[model]?.let { (priceIn, priceOut) ->
        val usd = (outcome.promptTokens * priceIn + outcome.completionTokens * priceOut) / 1_000_000.0
        String.format(Locale.US, " · ≈\$%.5f", usd)
    } ?: " · стоимость: нет опубликованного тарифа"
    return String.format(
        Locale.US,
        "время %.1f с · токены %d\u2192%d · скорость %d ток/с",
        seconds, outcome.promptTokens, outcome.completionTokens, speed
    ) + cost
}
