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
 * Настройки генерации ответа модели.
 * Заполняются в шторке настроек и передаются на сервер вместе с каждым сообщением.
 *
 * @param maxTokens Максимальное количество токенов в ответе модели —
 *                  ограничение длины ответа.
 * @param stopWords Стоп-слова завершения: генерация останавливается, как только модель
 *                  начинает выдавать одно из этих слов.
 * @param responseFormat Формат ответа (см. [ResponseFormat]); по умолчанию — свободная форма.
 * @param runs Количество прогонов одного и того же вопроса: сервер повторяет запрос
 *             заданное число раз и сверяет, что формат ответа совпадает с заданным.
 * @param dogsOnly Отвечать только на вопросы о собаках; на любые другие вопросы
 *                 модель вежливо отказывается (выключается системная инструкция).
 */
data class GenerationSettings(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stopWords: List<String> = emptyList(),
    val responseFormat: ResponseFormat = ResponseFormat.FREE_FORM,
    val runs: Int = DEFAULT_RUNS,
    val dogsOnly: Boolean = true
) {
    companion object {
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

        /**
         * Системная инструкция чата о собаках: отправляется модели как
         * дополнительное системное сообщение, когда включён [GenerationSettings.dogsOnly].
         */
        const val DOGS_ONLY_SYSTEM_PROMPT =
            "Ты — эксперт по породам собак. Отвечай только на вопросы о породах собак. " +
                "Если вопрос не про породу собаки, вежливо откажись отвечать."
    }
}
