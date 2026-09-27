package com.osvin.aichallenge.memory

import com.osvin.aichallenge.agent.MemoryRecord
import com.osvin.aichallenge.agent.MemoryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Долговременная память в файле: профиль пользователя, решения и знания переживают
 * и удаление чата, и перезапуск сервера — иначе слой не был бы долговременным.
 *
 * Сохранение атомарное: содержимое пишется во временный файл и переименовывается
 * на место, поэтому сбой в момент записи не оставит половину памяти.
 *
 * @param file Файл памяти; каталог создаётся при первом сохранении.
 */
class JsonFileMemoryStore(private val file: File) : MemoryStore {

    private val scopes = ConcurrentHashMap<String, List<MemoryRecord>>(readAll())

    private val writeLock = Any()

    override fun get(scopeId: String): List<MemoryRecord> = scopes[scopeId].orEmpty()

    override fun put(scopeId: String, records: List<MemoryRecord>) {
        scopes[scopeId] = records
        save()
    }

    override fun clear(scopeId: String) {
        scopes.remove(scopeId)
        save()
    }

    /** Что лежит в файле: файла нет или он не разобрался — память пуста, а сервер работает. */
    private fun readAll(): Map<String, List<MemoryRecord>> {
        if (!file.isFile) return emptyMap()
        return try {
            JSON.decodeFromString<StoredMemory>(file.readText()).profiles
        } catch (error: Exception) {
            emptyMap()
        }
    }

    private fun save() {
        synchronized(writeLock) {
            val target = file.absoluteFile
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(JSON.encodeToString(StoredMemory(scopes.toMap())))
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    @Serializable
    private data class StoredMemory(val profiles: Map<String, List<MemoryRecord>> = emptyMap())

    companion object {

        /** Файл памяти по умолчанию: рядом с сервером, вне репозитория. */
        const val DEFAULT_PATH = "data/memory.json"

        /**
         * Файл памяти: `-Dmemory.file=…` или `MEMORY_FILE`, иначе [DEFAULT_PATH].
         *
         * Путь относительный — от рабочего каталога процесса: `:server:run` и тесты
         * запускаются из каталога модуля `server`, поэтому память ложится в `server/data`.
         */
        fun defaultFile(): File =
            File(System.getProperty("memory.file") ?: System.getenv("MEMORY_FILE") ?: DEFAULT_PATH)

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
