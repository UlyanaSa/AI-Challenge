package com.osvin.aichallenge.models.config

/**
 * Конфигурация сервера по умолчанию.
 */
object AppConfig {
    const val DEFAULT_MODEL = "deepseek-v4-flash"
    const val DEFAULT_TEMPERATURE = 0.7
    const val DEFAULT_MAX_TOKENS = 2000
    const val DEFAULT_RUNS = 3
    const val MAX_RUNS = 10
    const val MAX_TOKEN_CEILING = 8192
    const val TOKEN_ROUND_STEP = 100
    const val MAX_STOP_SEQUENCES = 16
    const val DEFAULT_PORT = 8080
    const val DEFAULT_HOST = "0.0.0.0"

    /**
     * Тарифы DeepSeek V4 в USD за 1 млн токенов (вход / выход).
     * Действуют на 07.09.2026; источник — публичная витрина цен DeepSeek.
     * Экспериментальная модель deepseek-v4-flash-vision-exp в тарифной карте
     * не публикуется, поэтому её стоимость не рассчитывается.
     */
    val MODEL_PRICES_USD_PER_1M: Map<String, Pair<Double, Double>> = mapOf(
        "deepseek-v4-flash" to (0.22 to 0.66),
        "deepseek-v4-pro" to (0.66 to 1.98)
    )
}
