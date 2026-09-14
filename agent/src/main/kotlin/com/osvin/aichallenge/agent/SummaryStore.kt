package com.osvin.aichallenge.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Сводка истории диалога. Хранится отдельно от сообщений: в запрос уходит
 * вместо тех сообщений, которые в неё свёрнуты.
 *
 * @param text Что было в свёрнутых сообщениях: факты, числа, договорённости.
 * @param foldedMessages Сколько сообщений диалога в неё свёрнуто.
 * @param tokens Токены сводки по локальному счётчику.
 */
data class StoredSummary(
    val text: String,
    val foldedMessages: Int,
    val tokens: Int
)

/**
 * Хранилище сводок: у каждой сессии своя сводка, поэтому диалоги не смешиваются.
 * Клиент присылает сообщения, а сводки живут здесь — отдельно от истории.
 */
interface SummaryStore {
    /** Сводка сессии; null — сводки ещё нет. */
    fun get(sessionId: String): StoredSummary?

    /** Сохраняет сводку сессии. */
    fun put(sessionId: String, summary: StoredSummary)

    /** Забывает сводку сессии: диалог начали заново. */
    fun clear(sessionId: String)
}

/**
 * Сводки в памяти процесса: живут, пока работает сервер.
 *
 * Клиент присылает всю историю диалога, поэтому после перезапуска сервера
 * сводка просто строится заново — сообщения от этого не теряются.
 */
class InMemorySummaryStore : SummaryStore {
    private val summaries = ConcurrentHashMap<String, StoredSummary>()

    override fun get(sessionId: String): StoredSummary? = summaries[sessionId]

    override fun put(sessionId: String, summary: StoredSummary) {
        summaries[sessionId] = summary
    }

    override fun clear(sessionId: String) {
        summaries.remove(sessionId)
    }
}
