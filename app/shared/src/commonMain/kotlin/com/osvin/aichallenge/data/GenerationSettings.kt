package com.osvin.aichallenge.data

/**
 * Формат ответа модели, выбираемый в шторке настроек.
 * Варианты взаимоисключающие: активен ровно один, по умолчанию — FREE_FORM.
 *
 * @param key Канонический ключ формата, передаётся на сервер; сервер по нему
 *            подбирает инструкцию модели и правило сверки ответа.
 * @param label Подпись варианта в шторке настроек.
 */
enum class ResponseFormat(
    val key: String,
    val label: String
) {
    /** Свободная форма — без дополнительных требований к построению ответа. */
    FREE_FORM("FREE_FORM", "Свободная форма"),

    /** Нумерованные пункты, каждый пункт — одно предложение. */
    BULLET_SENTENCE("BULLET_SENTENCE", "Нумерованные пункты, каждый пункт — одно предложение"),

    /** Чёткий JSON с полями о породе. */
    STRICT_JSON(
        "STRICT_JSON",
        "Чёткий JSON (breed, lifespan, color, origin, temperament)"
    )
}

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
 * @param responseFormat Формат ответа (см. [ResponseFormat]); по умолчанию — свободная форма.
 * @param runs Количество прогонов одного и того же вопроса: сервер повторяет запрос
 *             заданное число раз и сверяет, что формат ответа совпадает с заданным.
 * @param temperature Температура генерации (0.0–2.0): 0 — детерминированный ответ,
 *                    0.7 — баланс точности и креативности, 1.2 — креативный.
 */
data class GenerationSettings(
    val model: String = DEFAULT_MODEL,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stopWords: List<String> = emptyList(),
    val responseFormat: ResponseFormat = ResponseFormat.FREE_FORM,
    val runs: Int = DEFAULT_RUNS,
    val temperature: Double = DEFAULT_TEMPERATURE
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

        /** Значение по умолчанию повторяет серверное (AppConfig), чтобы поведение чата не изменилось. */
        const val DEFAULT_MAX_TOKENS = 2000

        /** Верхняя граница max_tokens, которую принимает DeepSeek API. */
        const val MAX_TOKENS_LIMIT = 8192

        /** Максимум стоп-слов за один запрос (лимит DeepSeek API). */
        const val MAX_STOP_WORDS = 16

        /** Количество прогонов одного вопроса по умолчанию. */
        const val DEFAULT_RUNS = 3

        /** Нижняя граница прогонов. */
        const val MIN_RUNS = 1

        /** Верхняя граница прогонов (защита от перерасхода API). */
        const val MAX_RUNS = 10

        /** Температура генерации по умолчанию. */
        const val DEFAULT_TEMPERATURE = 0.7

        /**
         * Значения температуры, доступные в шторке настроек:
         * эксперимент дня 4 гоняет один и тот же запрос при 0 / 0.7 / 1.2.
         */
        val TEMPERATURE_OPTIONS = listOf(0.0, 0.7, 1.2)
    }
}
