package com.osvin.aichallenge.data

/**
 * Вариант ответа модели на задачу.
 * @param key Ключ для передачи на сервер.
 * @param title Название для отображения.
 */
enum class AnswerVariant(val key: String, val title: String) {
    DIRECT("direct", "Прямой ответ без дополнительных инструкций"),
    STEP_BY_STEP("step_by_step", "Инструкция «решай пошагово»"),
    COMPOSED_PROMPT("composed_prompt", "Сначала промпт для решения, затем решение по нему"),
    EXPERT_GROUP("expert_group", "Группа экспертов: аналитик, инженер, критик");

    companion object {
        fun fromKey(key: String?): AnswerVariant? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * Способ запуска выбранных вариантов ответа.
 * @param key Ключ для передачи на сервер.
 * @param label Название для отображения.
 */
enum class RunMode(val key: String, val label: String) {
    SEQUENTIAL(
        "sequential",
        "Все последовательно: один отчёт, у каждого варианта характеристики, в конце вердикт судьи"
    ),
    SEPARATE(
        "separate",
        "По отдельности: каждый выбранный вариант запускается отдельным ответом в чате"
    );

    companion object {
        fun fromKey(key: String?): RunMode =
            entries.firstOrNull { it.key == key } ?: SEQUENTIAL
    }
}

/**
 * Настройки вариантов ответа, задаются в шторке ⚖.
 *
 * Если список [variants] пуст — чат работает как обычный: один прямой ответ
 * модели без дополнительных инструкций и без характеристик.
 *
 * @param variants Выбранные варианты ответа (порядок = порядок запуска).
 * @param runMode Способ запуска: все последовательно или по отдельности.
 */
data class GenerationSettings(
    val variants: List<AnswerVariant> = emptyList(),
    val runMode: RunMode = RunMode.SEQUENTIAL
)
