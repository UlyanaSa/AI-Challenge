package com.osvin.aichallenge.memory

import com.osvin.aichallenge.agent.MemoryRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Долговременная память в файле: тип памяти, который обязан переживать перезапуск сервера.
 * Проверяется без сети и без агента — только хранилище.
 */
class JsonFileMemoryStoreTest {

    /** Записи профиля переживают пересоздание хранилища: это и делает память долговременной. */
    @Test
    fun recordsSurviveStoreRecreation() {
        val file = File.createTempFile("memory", ".json").also { it.delete() }
        val records = listOf(
            MemoryRecord("long_term", "язык — русский"),
            MemoryRecord("long_term", "хранилище — Room")
        )

        JsonFileMemoryStore(file).put("local", records)

        val recreated = JsonFileMemoryStore(file)
        assertEquals(records, recreated.get("local"), "после перезапуска память на месте")
        file.delete()
    }

    /** Области не смешиваются: у каждого профиля своя память в том же файле. */
    @Test
    fun scopesAreSeparate() {
        val file = File.createTempFile("memory", ".json").also { it.delete() }
        val store = JsonFileMemoryStore(file)

        store.put("local", listOf(MemoryRecord("long_term", "имя — Иван")))
        store.put("другой", listOf(MemoryRecord("long_term", "имя — Пётр")))

        assertEquals(listOf(MemoryRecord("long_term", "имя — Иван")), store.get("local"))
        assertEquals(listOf(MemoryRecord("long_term", "имя — Пётр")), store.get("другой"))
        assertEquals(listOf(MemoryRecord("long_term", "имя — Иван")), JsonFileMemoryStore(file).get("local"))
        file.delete()
    }

    /** Отсутствующий или испорченный файл — пустая память, а не падение сервера. */
    @Test
    fun brokenFileMeansEmptyMemory() {
        val file = File.createTempFile("memory", ".json")
        file.writeText("не JSON")

        assertTrue(JsonFileMemoryStore(file).get("local").isEmpty())
        assertTrue(JsonFileMemoryStore(File(file.parentFile, "нет-такого-файла.json")).get("local").isEmpty())
        file.delete()
    }
}
