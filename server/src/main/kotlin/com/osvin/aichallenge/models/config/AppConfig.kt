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
}
