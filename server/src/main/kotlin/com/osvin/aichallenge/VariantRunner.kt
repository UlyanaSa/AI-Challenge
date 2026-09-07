package com.osvin.aichallenge

import com.osvin.aichallenge.models.AnswerMetrics
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.SolutionRef
import com.osvin.aichallenge.models.config.AppConfig
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Запуск одного варианта ответа на вопрос.
 *
 * Варианты:
 *  1. direct — прямой ответ без дополнительных инструкций;
 *  2. step_by_step — инструкция «решай пошагово»;
 *  3. composed_prompt — модель сначала составляет промпт, затем решает по нему;
 *  4. expert_group — группа экспертов (аналитик, инженер, критик): решение каждого.
 *
 * У каждого запуска измеряются основные характеристики: количество затраченных
 * токенов, скорость ответа, длина ответа, стоимость ответа, а точность и глубина
 * объяснения оцениваются моделью по шкале 0–10.
 *
 * Судья ([judgeSolutions]) сравнивает готовые решения одной задачи по параметрам
 * правильность/полнота/обоснованность/ясность и называет наиболее точное.
 */
internal object VariantRunner {

    private const val STEP_INSTRUCTION =
        "Решай задачу пошагово: подробно объясняй каждый шаг, в конце приведи итоговый ответ."

    private const val PROMPT_BUILDER =
        "Ты — эксперт по составлению промптов. Составь подробный промпт, следуя которому " +
            "любая модель корректно решит задачу."

    private const val ANALYST =
        "Ты — аналитик. Тщательно проанализируй задачу: разбери условия и ограничения, " +
            "затем дай обоснованное решение."

    private const val ENGINEER =
        "Ты — инженер. Реши задачу практически: продумай алгоритм или подход, " +
            "приведи конкретное решение."

    private const val CRITIC =
        "Ты — критик. Изучи задачу, найди возможные ошибки и слабые места в рассуждениях, " +
            "затем предложи собственное проверенное решение."

    private const val EVALUATOR_SYSTEM =
        "Ты — эксперт по оценке качества ответов ИИ. Оцени ответ на задачу по двум шкалам: " +
            "точность ответа (насколько верно и полно решена задача) и глубина объяснения " +
            "(насколько подробно, логично и глубоко раскрыто решение). " +
            "Верни ровно две строки без пояснений:\n" +
            "Точность: <целое число от 0 до 10>\n" +
            "Глубина: <целое число от 0 до 10>"

    private const val JUDGE_SYSTEM =
        "Ты — независимый судья, сравнивающий решения одной и той же задачи, полученные " +
            "разными способами. НЕ решай задачу заново. Оценивай строго по следующим " +
            "параметрам: 1) правильность; 2) полнота; 3) обоснованность; 4) ясность. " +
            "Для каждого решения выпиши оценку по каждому из четырёх параметров кратко " +
            "(например «правильность: высокая, полнота: средняя, обоснованность: высокая, " +
            "ясность: высокая»). В конце укажи, какое решение наиболее точное и почему."

    /** Инструкции-персоны для варианта «группа экспертов». */
    private val EXPERT_PERSONAS = listOf(
        "Аналитик" to ANALYST,
        "Инженер" to ENGINEER,
        "Критик" to CRITIC
    )

    /**
     * Результат запуска одного варианта.
     */
    internal data class SolvedVariant(
        val title: String,
        val content: String,
        val metrics: AnswerMetrics
    )

    /** Название варианта по ключу. */
    fun variantTitle(variantKey: String): String = when (variantKey) {
        "step_by_step" -> "Инструкция «решай пошагово»"
        "composed_prompt" -> "Сначала промпт для решения, затем решение по нему"
        "expert_group" -> "Группа экспертов: аналитик, инженер, критик"
        else -> "Прямой ответ без дополнительных инструкций"
    }

    /**
     * Запуск одного варианта: решение с накоплением токенов и времени,
     * оценка точности и глубины объяснения, сбор характеристик.
     */
    suspend fun solveVariant(
        apiKey: String,
        model: String,
        question: String,
        variantKey: String
    ): SolvedVariant {
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var elapsedMillis = 0L

        fun accumulate(outcome: CallOutcome) {
            promptTokens += outcome.usage.promptTokens
            completionTokens += outcome.usage.completionTokens
            totalTokens += outcome.usage.totalTokens
            elapsedMillis += outcome.elapsedMillis
        }

        suspend fun call(system: String? = null, userSuffix: String = ""): CallOutcome =
            deepSeekComplete(
                apiKey = apiKey,
                model = model,
                messages = buildList {
                    system?.let { add(ChatMessage("system", it)) }
                    add(ChatMessage("user", question + userSuffix))
                }
            ).also { accumulate(it) }

        val content = when (variantKey) {
            "direct" -> call().text

            "step_by_step" -> call(system = STEP_INSTRUCTION).text

            "composed_prompt" -> {
                val promptOutcome = call(
                    system = PROMPT_BUILDER,
                    userSuffix = "\n\nВерни только текст промпта, без пояснений и вступлений."
                )
                val solutionOutcome = deepSeekComplete(
                    apiKey = apiKey,
                    model = model,
                    messages = listOf(ChatMessage("user", promptOutcome.text))
                ).also { accumulate(it) }
                buildString {
                    appendLine("Составленный моделью промпт:")
                    appendLine(promptOutcome.text)
                    appendLine()
                    appendLine("Решение по составленному промпту:")
                    appendLine(solutionOutcome.text)
                }
            }

            "expert_group" -> buildString {
                EXPERT_PERSONAS.forEachIndexed { index, (persona, instruction) ->
                    if (index > 0) appendLine().appendLine()
                    appendLine("$persona:")
                    append(call(system = instruction).text.trim())
                }
            }

            else -> error("Неизвестный вариант ответа: $variantKey")
        }

        val (accuracy, depth) = evaluate(apiKey, model, question, content)

        val chars = content.length
        val words = content.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val tokensPerSecond =
            if (elapsedMillis > 0) completionTokens * 1000.0 / elapsedMillis else 0.0
        val costUsd = (promptTokens * AppConfig.PRICE_INPUT_PER_1M_USD +
            completionTokens * AppConfig.PRICE_OUTPUT_PER_1M_USD) / 1_000_000.0

        return SolvedVariant(
            title = variantTitle(variantKey),
            content = content.trim(),
            metrics = AnswerMetrics(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                elapsedMillis = elapsedMillis,
                tokensPerSecond = tokensPerSecond,
                chars = chars,
                words = words,
                costUsdText = moneyText(costUsd, decimals = 6),
                costRubText = moneyText(costUsd * AppConfig.RUB_PER_USD, decimals = 2),
                accuracy = accuracy,
                depth = depth
            )
        )
    }

    /**
     * Вердикт судьи по готовым решениям одной задачи.
     * @param question Текст задачи.
     * @param solutions Решения запущенных вариантов (с названиями).
     */
    suspend fun judgeSolutions(
        apiKey: String,
        model: String,
        question: String,
        solutions: List<SolutionRef>
    ): String {
        val material = buildString {
            appendLine("Задача: $question")
            appendLine()
            appendLine("Решения одной и той же задачи разными способами:")
            solutions.forEachIndexed { index, solution ->
                appendLine()
                appendLine("--- Решение ${index + 1}: ${solution.title} ---")
                appendLine(solution.content)
            }
        }
        return deepSeekComplete(
            apiKey = apiKey,
            model = model,
            messages = buildList {
                add(ChatMessage("system", JUDGE_SYSTEM))
                add(ChatMessage("user", material))
            }
        ).text
    }

    /**
     * Оценка ответа моделью-оценщиком: точность ответа и глубина объяснения (0–10).
     */
    private suspend fun evaluate(
        apiKey: String,
        model: String,
        question: String,
        answer: String
    ): Pair<Int?, Int?> {
        val outcome = deepSeekComplete(
            apiKey = apiKey,
            model = model,
            messages = buildList {
                add(ChatMessage("system", EVALUATOR_SYSTEM))
                add(ChatMessage("user", "Задача:\n$question\n\nОтвет модели:\n$answer"))
            }
        )
        return parseScores(outcome.text)
    }

    /**
     * Достаёт из текста оценщика числа точности и глубины.
     * Ищет «Точность: N» и «Глубина: N», при неудаче — первые два числа.
     */
    private fun parseScores(text: String): Pair<Int?, Int?> {
        val accuracy =
            Regex("(?i)Точность\\s*:?\\s*(\\d{1,2})").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val depth =
            Regex("(?i)Глубина\\s*:?\\s*(\\d{1,2})").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (accuracy != null && depth != null) return accuracy to depth

        val numbers = Regex("\\d{1,2}").findAll(text).map { it.value.toIntOrNull() }
            .filterNotNull().toList()
        return when {
            numbers.size >= 2 -> numbers[0] to numbers[1]
            numbers.size == 1 -> numbers[0] to null
            else -> null to null
        }
    }

    /**
     * Форматирование денежной суммы: заданное число знаков после запятой,
     * хвостовые нули убираются (0.000273, 0.07, 2).
     */
    private fun moneyText(value: Double, decimals: Int): String =
        BigDecimal.valueOf(value)
            .setScale(decimals, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
