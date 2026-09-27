package com.osvin.aichallenge.invariants

import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Инварианты проекта в файле: правила, которым обязан соответствовать ассистент,
 * задаёт человек, и перезапуск сервера их не снимает — иначе ограничения исчезали бы
 * вместе с процессом, и ассистент молча выполнял бы просьбы, от которых его уберегали.
 *
 * Хранилище отдельное, а не поле профиля или памяти: инварианты не описывают
 * пользователя и не извлекаются из диалога — это условие задачи, которое проверяется
 * до ответа. Общий у трёх хранилищ только адрес: идентификатор профиля, по которому
 * правила лежат рядом с профилем и памятью того же профиля.
 *
 * Отличие от прочих сторов — в том, что здесь важно не только содержимое, но и сам
 * факт наличия записи: здесь различаются «правил ещё не задавали» (null — работают
 * умолчания проекта, [Invariant.DEFAULT]) и «правил нет» (пустой список — человек
 * убрал все, поэтому проверки конфликта и блока инвариантов в запросе не будет).
 * Поэтому пустой список сохраняется как пустой список и переживает перезапуск,
 * а не читается обратно как отсутствие записи.
 *
 * Сохранение атомарное: содержимое пишется во временный файл и переименовывается
 * на место, поэтому сбой в момент записи не оставит половину правил.
 *
 * @param file Файл инвариантов; каталог создаётся при первом сохранении.
 */
class JsonFileInvariantStore(private val file: File) : InvariantStore {

    private val byProfile = ConcurrentHashMap<String, List<Invariant>>(readAll())

    private val writeLock = Any()

    /** Правила профиля; null — их ещё не задавали, пустой список — задали пустыми. */
    override fun get(profileId: String): List<Invariant>? = byProfile[profileId]

    override fun put(profileId: String, invariants: List<Invariant>) {
        byProfile[profileId] = invariants
        save()
    }

    /**
     * Что лежит в файле: файла нет или он не разобрался — правил не задавали, значит
     * работают умолчания проекта, а сервер продолжает обслуживать запросы. Испорченный
     * файл — не повод отказать в работе: правила восстановимы, диалог важнее.
     */
    private fun readAll(): Map<String, List<Invariant>> {
        if (!file.isFile) return emptyMap()
        return try {
            JSON.decodeFromString<StoredInvariants>(file.readText()).profiles
        } catch (error: Exception) {
            emptyMap()
        }
    }

    private fun save() {
        synchronized(writeLock) {
            val target = file.absoluteFile
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(JSON.encodeToString(StoredInvariants(byProfile.toMap())))
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /** Записи по профилю: наличие ключа значит «правила заданы», в том числе пустыми. */
    @Serializable
    private data class StoredInvariants(val profiles: Map<String, List<Invariant>> = emptyMap())

    companion object {

        /** Файл инвариантов по умолчанию: рядом с сервером, вне репозитория. */
        const val DEFAULT_PATH = "data/invariants.json"

        /**
         * Файл инвариантов: `-Dinvariant.file=…` или `INVARIANT_FILE`, иначе [DEFAULT_PATH].
         *
         * Путь относительный — от рабочего каталога процесса: `:server:run` и тесты
         * запускаются из каталога модуля `server`, поэтому правила ложатся в `server/data`.
         */
        fun defaultFile(): File =
            File(System.getProperty("invariant.file") ?: System.getenv("INVARIANT_FILE") ?: DEFAULT_PATH)

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
