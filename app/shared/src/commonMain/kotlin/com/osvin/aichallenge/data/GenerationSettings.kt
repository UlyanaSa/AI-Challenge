package com.osvin.aichallenge.data

/**
 * Модель DeepSeek, доступная в шторке настроек.
 * Три модели взяты из живого списка DeepSeek /v1/models:
 * начало, середина и конец списка (день 5: сравнение версий моделей).
 *
 * @param id Идентификатор модели в API DeepSeek.
 * @param title Короткое название для шторки.
 * @param subtitle Пояснение: уровень и типичное применение.
 */
data class ModelOption(
    val id: String,
    val title: String,
    val subtitle: String
)

/**
 * Настройки генерации ответа модели.
 * Заполняются в шторке настроек и передаются на сервер вместе с каждым сообщением.
 *
 * @param model Модель DeepSeek (см. [GenerationSettings.MODELS]).
 * @param maxTokens Максимальное количество токенов в ответе модели —
 *                  ограничение длины ответа.
 * @param stopWords Стоп-слова завершения: генерация останавливается, как только модель
 *                  начинает выдавать одно из этих слов.
 * @param systemPrompt Свой system prompt: общий контекст и правила поведения модели;
 *                     пустая строка — не передаётся, и тогда их задаёт первое
 *                     сообщение чата.
 * @param temperature Температура генерации (0.0–2.0): 0 — детерминированный ответ,
 *                    0.7 — баланс точности и креативности, 1.2 — креативный.
 * @param strategy Стратегия управления контекстом диалога: что из истории уходит
 *                 в модель (см. [ContextStrategy]).
 * @param windowMessages Сколько последних сообщений отправляют стратегии со скользящим
 *                 окном и памятью фактов: [ContextStrategy.SLIDING_WINDOW] и
 *                 [ContextStrategy.FACTS]. Остальные стратегии его не читают.
 */
data class GenerationSettings(
    val model: String = DEFAULT_MODEL,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stopWords: List<String> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE,
    val strategy: ContextStrategy = ContextStrategy.SUMMARY,
    val windowMessages: Int = DEFAULT_WINDOW_MESSAGES
) {
    companion object {
        /** Модель по умолчанию: самая быстрая и дешёвая из списка DeepSeek. */
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        /**
         * Модели DeepSeek в порядке живого списка API:
         * flash — начало списка (быстрая, дешёвая), pro — середина (сильная),
         * flash-vision — конец (экспериментальная, со зрением).
         */
        val MODELS = listOf(
            ModelOption(
                id = "deepseek-v4-flash",
                title = "V4 Flash — быстрая",
                subtitle = "Дешёвая модель для повседневных вопросов"
            ),
            ModelOption(
                id = "deepseek-v4-pro",
                title = "V4 Pro — сильная",
                subtitle = "Тяжёлая модель для сложных задач и рассуждений"
            ),
            ModelOption(
                id = "deepseek-v4-flash-vision-exp",
                title = "V4 Flash Vision (экспериментальная)",
                subtitle = "Flash со зрением; в текстовых задачах ведёт себя как Flash"
            )
        )

        /**
         * Значение по умолчанию повторяет серверное (AppConfig) и равно верхней
         * границе для клиента: у thinking-модели рассуждения идут из того же бюджета,
         * и на 2000 токенов обычный вопрос возвращался пустым ответом.
         */
        const val DEFAULT_MAX_TOKENS = 8192

        /** Верхняя граница max_tokens, которую принимает DeepSeek API. */
        const val MAX_TOKENS_LIMIT = 8192

        /** Максимум стоп-слов за один запрос (лимит DeepSeek API). */
        const val MAX_STOP_WORDS = 16

        /** Температура генерации по умолчанию. */
        const val DEFAULT_TEMPERATURE = 0.7

        /** Размер окна стратегий «скользящее окно» и «память фактов» по умолчанию. */
        const val DEFAULT_WINDOW_MESSAGES = 10

        /** Нижняя граница окна в шторке настроек: совсем короткий контекст бесполезен. */
        const val MIN_WINDOW_MESSAGES = 2

        /** Верхняя граница окна в шторке настроек. */
        const val MAX_WINDOW_MESSAGES = 100

        /**
         * Значения температуры, доступные в шторке настроек:
         * эксперимент дня 4 гоняет один и тот же запрос при 0 / 0.7 / 1.2.
         */
        val TEMPERATURE_OPTIONS = listOf(0.0, 0.7, 1.2)
    }
}
