package com.osvin.aichallenge.models.config

/**
 * Конфигурация приложения по умолчанию: значения агента (модель, лимиты,
 * температура, тарифы) и параметры запуска сервера.
 */
object AppConfig {
    const val DEFAULT_MODEL = "deepseek-v4-flash"
    const val DEFAULT_TEMPERATURE = 0.7
    const val DEFAULT_MAX_TOKENS = 2000
    const val MAX_TOKEN_CEILING = 8192
    const val MAX_STOP_SEQUENCES = 16
    const val DEFAULT_PORT = 8080
    const val DEFAULT_HOST = "0.0.0.0"
}
