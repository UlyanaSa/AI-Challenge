package com.osvin.aichallenge.agent

/**
 * Стратегия управления контекстом диалога: что из присланной клиентом истории уходит
 * в модель и какие слои памяти ([MemoryLayer]) она ведёт. Переключается настройкой
 * запроса ([AgentOptions.strategy]).
 *
 * Память — часть стратегии, а не общая надстройка: стратегия, которой хватает
 * всей истории, память не ведёт вовсе, а та, что теряет сообщения, ведёт хотя бы
 * рабочую. Поэтому в отчёте видно, какие слои наполнил именно этот диалог.
 *
 * @param wire Значение стратегии в запросе клиента и в отчёте агента.
 * @param title Название для логов, отчёта и интерфейса.
 * @param memory Слои памяти, которые стратегия ведёт: читает в запрос и обновляет после ответа.
 */
enum class ContextStrategy(val wire: String, val title: String, val memory: Set<MemoryLayer>) {

    /** Вся история как есть: ничего не теряется, память не нужна. */
    FULL("full", "вся история", emptySet()),

    /**
     * Скользящее окно: в модель уходят последние [AgentOptions.windowMessages]
     * сообщений. Старшие отбрасываются, поэтому важное из них держит рабочая память.
     */
    SLIDING_WINDOW("sliding_window", "скользящее окно", setOf(MemoryLayer.WORKING)),

    /**
     * Память агента: рабочая память задачи и долговременная память о пользователе.
     * В запрос уходят оба слоя и последние сообщения окна.
     */
    MEMORY("memory", "память агента", setOf(MemoryLayer.WORKING, MemoryLayer.LONG_TERM)),

    /**
     * Ветки диалога: в модель уходит только путь активной ветки — общая часть
     * с родителем до точки ветвления плюс её собственные сообщения.
     */
    BRANCHES("branches", "ветки диалога", emptySet()),

    /** Сжатие истории (день 9): старшие сообщения свёрнуты в сводку. */
    SUMMARY("summary", "сжатие в сводку", emptySet());

    /** Отправляет ли стратегия только последние сообщения окна. */
    val usesWindow: Boolean get() = this == SLIDING_WINDOW || this == MEMORY

    companion object {

        /** Стратегия дня 10: тогда память фактов была отдельным значением того же переключателя. */
        private const val LEGACY_FACTS = "facts"

        /** Стратегия по значению из запроса; неизвестное значение — [SUMMARY] (поведение дня 9). */
        fun fromWire(value: String?): ContextStrategy {
            val wire = value?.trim()?.lowercase()
            return entries.firstOrNull { it.wire == wire }
                ?: if (wire == LEGACY_FACTS) MEMORY else SUMMARY
        }
    }
}
