package com.osvin.aichallenge.models.config

/**
 * Конфигурация сервера по умолчанию.
 */
object AppConfig {
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_TEMPERATURE = 0.7
    const val DEFAULT_MAX_TOKENS = 2000
    const val DEFAULT_RUNS = 3
    const val MAX_RUNS = 10
    const val MAX_TOKEN_CEILING = 8192
    const val TOKEN_ROUND_STEP = 100
    const val MAX_STOP_SEQUENCES = 16
    const val DEFAULT_PORT = 8080
    const val DEFAULT_HOST = "0.0.0.0"

    /** Цена DeepSeek deepseek-chat за 1M токенов, USD. */
    const val PRICE_INPUT_PER_1M_USD = 0.27
    const val PRICE_OUTPUT_PER_1M_USD = 1.10

    /** Курс для отображения стоимости в рублях. */
    const val RUB_PER_USD = 90.0
}
