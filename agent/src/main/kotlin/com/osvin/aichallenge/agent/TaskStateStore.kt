package com.osvin.aichallenge.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Хранилище состояния задачи: у каждой сессии своё состояние, поэтому задачи разных
 * диалогов не смешиваются.
 *
 * Ключ — сессия, а не профиль, как у памяти ([MemoryStore]) и профиля ([ProfileStore]):
 * задача принадлежит диалогу, а не человеку. Этап и шаг описывают конкретную работу
 * в конкретной переписке, и работа в другом чате идёт своим ходом — читать её шаг
 * из чужого диалога незачем.
 */
interface TaskStateStore {

    /** Состояние задачи сессии; null — задачи в этом диалоге нет. */
    fun get(sessionId: String): TaskState?

    /** Сохраняет состояние задачи сессии: переход принят или задачу завели. */
    fun put(sessionId: String, state: TaskState)

    /** Забывает состояние сессии: задачу закрыли явно или диалог начали заново. */
    fun clear(sessionId: String)
}

/**
 * Состояния задач в памяти процесса: живут, пока работает сервер.
 *
 * Перезапуск сервера состояние стирает, и это не потеря: задачу открывает человек явным
 * действием, поэтому после перезапуска он открывает её тем же действием, а историю диалога
 * присылает клиент — по ней видно, на чём переписка остановилась. Файл для состояния задачи
 * был бы хуже: он пережил бы диалог, которого сервер не помнит, и задача продолжилась бы
 * с шага, к переписке уже не относящегося. Со сводкой истории ([InMemorySummaryStore]) по
 * сроку жизни выходит одно и то же, и по той же причине.
 */
class InMemoryTaskStateStore : TaskStateStore {

    private val tasks = ConcurrentHashMap<String, TaskState>()

    override fun get(sessionId: String): TaskState? = tasks[sessionId]

    override fun put(sessionId: String, state: TaskState) {
        tasks[sessionId] = state
    }

    override fun clear(sessionId: String) {
        tasks.remove(sessionId)
    }
}
