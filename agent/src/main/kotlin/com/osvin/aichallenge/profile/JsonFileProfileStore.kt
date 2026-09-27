package com.osvin.aichallenge.profile

import com.osvin.aichallenge.agent.ProfileStore
import com.osvin.aichallenge.agent.UserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Профиль пользователя в файле: объявленные предпочтения должны переживать
 * перезапуск сервера — иначе персонализация держалась бы до первой остановки,
 * и пользователь правил бы её заново после каждого запуска.
 *
 * Сохранение атомарное: содержимое пишется во временный файл и переименовывается
 * на место, поэтому сбой в момент записи не оставит половину профиля.
 *
 * @param file Файл профиля; каталог создаётся при первом сохранении.
 */
class JsonFileProfileStore(private val file: File) : ProfileStore {

    private val profiles = ConcurrentHashMap<String, UserProfile>(readAll())

    private val writeLock = Any()

    override fun get(profileId: String): UserProfile? = profiles[profileId]

    override fun put(profileId: String, profile: UserProfile) {
        profiles[profileId] = profile
        save()
    }

    /**
     * Что лежит в файле: файла нет или он не разобрался — профиль не задан, а сервер
     * работает и отдаёт профиль по умолчанию. Испорченный профиль — не повод отказать
     * в обслуживании: предпочтения пользователя восстановимы, чат важнее.
     */
    private fun readAll(): Map<String, UserProfile> {
        if (!file.isFile) return emptyMap()
        return try {
            JSON.decodeFromString<StoredProfiles>(file.readText()).profiles
        } catch (error: Exception) {
            emptyMap()
        }
    }

    private fun save() {
        synchronized(writeLock) {
            val target = file.absoluteFile
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(JSON.encodeToString(StoredProfiles(profiles.toMap())))
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    @Serializable
    private data class StoredProfiles(val profiles: Map<String, UserProfile> = emptyMap())

    companion object {

        /** Файл профиля по умолчанию: рядом с сервером, вне репозитория. */
        const val DEFAULT_PATH = "data/profile.json"

        /**
         * Файл профиля: `-Dprofile.file=…` или `PROFILE_FILE`, иначе [DEFAULT_PATH].
         *
         * Путь относительный — от рабочего каталога процесса: `:server:run` и тесты
         * запускаются из каталога модуля `server`, поэтому профиль ложится в `server/data`.
         */
        fun defaultFile(): File =
            File(System.getProperty("profile.file") ?: System.getenv("PROFILE_FILE") ?: DEFAULT_PATH)

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
