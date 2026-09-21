package com.osvin.aichallenge.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Хранилище профиля пользователя по идентификатору области.
 *
 * Область у профиля та же, что у памяти, — [DEFAULT_PROFILE]: пользователь в приложении
 * один. Сущность при этом другая, и хранилище своё: память наполняет модель записями
 * ([MemoryStore]), а профиль объявляет человек и лежит он целиком ([UserProfile]).
 *
 * Чистки области в интерфейсе нет намеренно: профиль не забывают по записи, его заменяют
 * целиком — [put] и есть замена.
 */
interface ProfileStore {

    /** Профиль области; null — пользователь своего профиля ещё не задавал. */
    fun get(profileId: String): UserProfile?

    /** Сохраняет профиль области целиком: частичного обновления у профиля нет. */
    fun put(profileId: String, profile: UserProfile)
}

/**
 * Профиль в памяти процесса: годится тем, кому профиль не нужно переживать перезапуск, —
 * тестам и локальному прогону. Кто подставляет серверу долговременное хранилище, решает
 * сам сервер: [ProfileStore] от этого не зависит.
 */
class InMemoryProfileStore : ProfileStore {

    private val profiles = ConcurrentHashMap<String, UserProfile>()

    override fun get(profileId: String): UserProfile? = profiles[profileId]

    override fun put(profileId: String, profile: UserProfile) {
        profiles[profileId] = profile
    }
}
