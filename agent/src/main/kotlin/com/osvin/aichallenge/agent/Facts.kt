package com.osvin.aichallenge.agent

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Факт диалога: короткий ключ и одна фраза значения.
 * Ключи повторяемые — «цель», «ограничение», «предпочтение», «решение», «договорённость».
 *
 * @param key Ключ: что именно запомнили.
 * @param value Значение: одна фраза без вступлений.
 */
@Serializable
data class Fact(val key: String, val value: String)

/**
 * Память диалога: важное, что переживает отброшенные сообщения.
 * Факты держатся списком, а не прозой, поэтому их видно и в логе, и в интерфейсе.
 *
 * @param items Факты в порядке появления.
 */
data class Facts(val items: List<Fact> = emptyList()) {

    /** Память пуста — в запрос блок фактов не добавляем. */
    val isEmpty: Boolean get() = items.isEmpty()

    /** Блок фактов для запроса: строки «ключ: значение»; пустая память — null. */
    fun block(): String? = items.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- ${it.key}: ${it.value}" }

    companion object {
        /** Сколько фактов держим: дальше память сама становится дороже истории. */
        const val MAX_FACTS = 20

        /** Предел длины значения: память — выжимка, а не пересказ. */
        const val MAX_FACT_CHARS = 200

        /** Приводит факты к границам памяти: без пустых, с обрезкой значений и без повторов ключей. */
        fun of(raw: List<Fact>): Facts {
            val normalized = raw
                .map { Fact(it.key.trim(), it.value.trim()) }
                .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
                .associateBy { it.key.lowercase() }
                .values
                .map { Fact(it.key.take(MAX_FACT_CHARS), it.value.take(MAX_FACT_CHARS)) }
            return Facts(normalized.takeLast(MAX_FACTS))
        }
    }
}

/**
 * Хранилище памяти диалога: у каждой сессии свои факты, поэтому диалоги не смешиваются.
 * Клиент присылает сообщения, а факты живут здесь — как сводки в дне 9.
 */
interface FactsStore {
    /** Факты сессии; пустая память — если фактов ещё нет. */
    fun get(sessionId: String): Facts

    /** Сохраняет память сессии. */
    fun put(sessionId: String, facts: Facts)

    /** Забывает память сессии: диалог удалили. */
    fun clear(sessionId: String)
}

/**
 * Факты в памяти процесса: живут, пока работает сервер. Клиент присылает всю
 * историю, поэтому после перезапуска сервера память просто собирается заново.
 */
class InMemoryFactsStore : FactsStore {
    private val facts = ConcurrentHashMap<String, Facts>()

    override fun get(sessionId: String): Facts = facts[sessionId] ?: Facts()

    override fun put(sessionId: String, facts: Facts) {
        this.facts[sessionId] = facts
    }

    override fun clear(sessionId: String) {
        facts.remove(sessionId)
    }
}
