package com.osvin.aichallenge.agent

/**
 * Ограничения и тариф модели DeepSeek.
 *
 * @param contextWindow Контекстное окно: сколько токенов влезает в запрос вместе с ответом.
 * @param maxOutputTokens Максимальное значение `max_tokens`.
 * @param inputPer1MUsd Цена входных токенов за 1 млн, USD; null — тариф не опубликован.
 * @param outputPer1MUsd Цена выходных токенов за 1 млн, USD; null — тариф не опубликован.
 */
data class ModelSpec(
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPer1MUsd: Double?,
    val outputPer1MUsd: Double?
) {
    /** Есть ли опубликованный тариф у модели. */
    val hasPrice: Boolean get() = inputPer1MUsd != null && outputPer1MUsd != null

    /** Стоимость запуска в USD; null, если тариф не опубликован. */
    fun cost(promptTokens: Int, replyTokens: Int): Double? {
        val input = inputPer1MUsd ?: return null
        val output = outputPer1MUsd ?: return null
        return promptTokens / 1_000_000.0 * input + replyTokens / 1_000_000.0 * output
    }
}

/**
 * Каталог моделей DeepSeek: границы и тарифы.
 *
 * Контекст и максимум `max_tokens` измерены на живом API (день 8):
 * ошибка переполнения сообщает окно 1 048 576 токенов, а на `max_tokens`
 * за границей API отвечает «the valid range of max_tokens is [1, 393216]».
 * Тарифы — из сравнения моделей дня 5 (Flash $0.22/$0.66, Pro $0.66/$1.98
 * за 1 млн токенов; у экспериментальной vision-модели тариф не опубликован).
 */
object ModelCatalog {
    /** Контекстное окно V4-моделей: 1 048 576 токенов (1M). */
    const val CONTEXT_WINDOW = 1_048_576

    /** Верхняя граница `max_tokens`, которую принимает API V4. */
    const val MAX_OUTPUT_TOKENS = 393_216

    val FLASH = ModelSpec(CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, 0.22, 0.66)

    val PRO = ModelSpec(CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, 0.66, 1.98)

    /** Экспериментальная vision-модель: тариф не опубликован. */
    val FLASH_VISION = ModelSpec(CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, null, null)

    /** Границы и тариф модели по её идентификатору. */
    fun spec(model: String): ModelSpec = when {
        model.contains("vision") -> FLASH_VISION
        model.contains("pro") -> PRO
        else -> FLASH
    }
}
