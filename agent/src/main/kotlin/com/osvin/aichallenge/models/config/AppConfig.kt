package com.osvin.aichallenge.models.config

/**
 * Конфигурация приложения по умолчанию: значения агента (модель, лимиты,
 * температура, тарифы) и параметры запуска сервера.
 */
object AppConfig {
    const val DEFAULT_MODEL = "deepseek-v4-flash"
    const val DEFAULT_TEMPERATURE = 0.7

    /**
     * Бюджет ответа по умолчанию. Взят по верхней границе для клиента (8192),
     * потому что у thinking-модели рассуждения тратят тот же бюджет: на 2000
     * токенов обычный вопрос («какие позиции в волейболе ты знаешь?») не успевал
     * дойти до текста, и ответ приходил пустым.
     */
    const val DEFAULT_MAX_TOKENS = 8192
    const val MAX_TOKEN_CEILING = 8192
    const val MAX_STOP_SEQUENCES = 16
    const val DEFAULT_PORT = 8080
    const val DEFAULT_HOST = "0.0.0.0"
}
