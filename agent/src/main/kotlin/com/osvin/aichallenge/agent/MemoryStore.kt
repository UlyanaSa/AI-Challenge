package com.osvin.aichallenge.agent

import java.util.concurrent.ConcurrentHashMap

/** Профиль пользователя: в приложении он один, поэтому и ключ один. */
const val DEFAULT_PROFILE = "local"

/**
 * Хранилище записей одного слоя памяти.
 *
 * Область у рабочей и долговременной памяти одна — профиль пользователя: обе видны из любого
 * чата и переживают его закрытие. Разными хранилищами слои не смешиваются, и разными они
 * остаются по сроку жизни: рабочая ложится в память процесса и перезапуск сервера её обнуляет,
 * долговременная ложится в файл и перезапуск переживает.
 */
interface MemoryStore {

    /** Записи области слоя; пустой список — там ещё ничего нет. */
    fun get(scopeId: String): List<MemoryRecord>

    /** Сохраняет записи области целиком. */
    fun put(scopeId: String, records: List<MemoryRecord>)

    /** Забывает область: профиль стёрли или слой убрали целиком. */
    fun clear(scopeId: String)
}

/**
 * Записи в памяти процесса: годятся для рабочей памяти — она собирается заново из сообщений
 * диалога, поэтому перезапуск сервера её не роняет: следующая задача наполнит слой снова.
 *
 * Долговременной памяти этого мало: её не собрать из одного диалога, поэтому на сервере
 * для неё есть файловое хранилище (`JsonFileMemoryStore`).
 */
class InMemoryMemoryStore : MemoryStore {

    private val scopes = ConcurrentHashMap<String, List<MemoryRecord>>()

    override fun get(scopeId: String): List<MemoryRecord> = scopes[scopeId].orEmpty()

    override fun put(scopeId: String, records: List<MemoryRecord>) {
        scopes[scopeId] = records
    }

    override fun clear(scopeId: String) {
        scopes.remove(scopeId)
    }
}
