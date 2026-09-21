package com.osvin.aichallenge.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Явная запись в память: пользователь называет тип памяти и пишет одну фразу.
 *
 * Проверяется то, что видно снаружи: куда попала запись, что стало с прежней, что
 * вернул снимок и какие записи не прошли вовсе. Внутреннее устройство хранилищ здесь
 * не проверяется — оно уже закрыто тестами типов памяти.
 */
class MemoryWriterTest {

    /** Хранилища одного теста: рабочая память и долговременная лежат врозь — как в сервере. */
    private class Stores {
        val working = InMemoryMemoryStore()
        val longTerm = InMemoryMemoryStore()
        val writer = MemoryWriter(working, longTerm)
    }

    /** Тип выбирает человек: запись ложится в названный тип и только в него. */
    @Test
    fun explicitWriteLandsInNamedType() {
        val stores = Stores()

        val decision = assertIs<MemoryWrite.Written>(
            stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — Room")
        )
        assertEquals(MemoryLayer.LONG_TERM, decision.layer, "долговременная память переживает диалог")
        assertEquals(MemoryRecord("long_term", "хранилище — Room"), decision.record)
        assertTrue(stores.writer.layers().working.isEmpty(), "в рабочую память запись не попала")

        val task = assertIs<MemoryWrite.Written>(
            stores.writer.remember(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        )
        assertEquals(MemoryLayer.WORKING, task.layer)
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            stores.writer.layers().longTerm,
            "запись задачи долговременную память не тронула"
        )
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            stores.writer.layers().working
        )
    }

    /** Рабочая запись живёт по профилю: она общая для всех чатов, как и долговременная. */
    @Test
    fun workingEntryIsVisibleFromEveryChat() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — Room")

        // Снимок один на профиль: чат в него не входит, поэтому в любом чате видно одно и то же.
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            stores.writer.layers().working,
            "рабочая запись принадлежит профилю, а не чату"
        )
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            stores.writer.layers().longTerm,
            "долговременная запись видна оттуда же, откуда рабочая"
        )
    }

    /** Сессия рабочей записи больше не нужна: она ложится в профиль и видна из любого чата. */
    @Test
    fun workingEntryNeedsNoSession() {
        val stores = Stores()

        val written = assertIs<MemoryWrite.Written>(
            stores.writer.remember(MemoryLayer.WORKING.wire, "цель — ТЗ")
        )
        assertEquals(MemoryLayer.WORKING, written.layer)
        assertEquals(
            listOf(MemoryRecord("working", "цель — ТЗ")),
            stores.writer.layers().working,
            "запись попала в общую область: чат для неё не нужен"
        )
        assertEquals(
            listOf(MemoryRecord("working", "цель — ТЗ")),
            stores.working.get(DEFAULT_PROFILE),
            "рабочий слой лежит по профилю"
        )
    }

    /** Слои различаются хранилищем, а значит и сроком жизни: память процесса против файла. */
    @Test
    fun layersLiveInSeparateStores() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — Room")

        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            stores.working.get(DEFAULT_PROFILE),
            "рабочая запись легла в своё хранилище"
        )
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            stores.longTerm.get(DEFAULT_PROFILE),
            "долговременная запись легла в своё хранилище"
        )

        // Хранилища разные, поэтому перезапуск сервера роняет рабочую память и не трогает долговременную.
        stores.working.clear(DEFAULT_PROFILE)
        assertTrue(stores.writer.layers().working.isEmpty(), "рабочая память процесса перезапуск не переживает")
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            stores.writer.layers().longTerm,
            "долговременная запись перезапуск переживает"
        )
    }

    /** Отказ приходит причиной: пустая память вместо записи выглядела бы как успех. */
    @Test
    fun unknownTypeAndBlankValueAreRejected() {
        val stores = Stores()

        val unknown = assertIs<MemoryWrite.Rejected>(
            stores.writer.remember("настроение", "боевой")
        )
        assertEquals(MemoryWriter.UNKNOWN_LAYER, unknown.reason)
        val shortTerm = assertIs<MemoryWrite.Rejected>(
            stores.writer.remember(MemoryLayer.SHORT_TERM.wire, "пользователь поздоровался")
        )
        assertEquals(MemoryWriter.NOT_WRITABLE, shortTerm.reason, "краткосрочную память пишет сам чат")
        listOf("", "   ").forEach { value ->
            val blank = assertIs<MemoryWrite.Rejected>(stores.writer.remember(MemoryLayer.WORKING.wire, value))
            assertEquals(MemoryWriter.EMPTY_VALUE, blank.reason)
        }
        assertEquals(MemoryLayers(types = MemoryLayers.catalogue()), stores.writer.layers(), "память пуста")
    }

    /** Та же фраза не плодит записей, а пересказанная становится второй: ключа у записи нет. */
    @Test
    fun repeatedTextDoesNotAddRecord() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — Room")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "ХРАНИЛИЩЕ — ROOM")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — SQLite")

        assertEquals(
            listOf(
                MemoryRecord("long_term", "ХРАНИЛИЩЕ — ROOM"),
                MemoryRecord("long_term", "хранилище — SQLite")
            ),
            stores.writer.layers().longTerm,
            "повтор узнаётся по тексту; пересказ — уже другая запись"
        )
    }

    /** Предел типа действует и на явную запись: вытеснение видно в итоге записи. */
    @Test
    fun typeLimitEvictsOldestRecord() {
        val stores = Stores()
        repeat(MemoryRules.MAX_WORKING) { index ->
            stores.writer.remember(MemoryLayer.WORKING.wire, "запись $index")
        }
        val written = assertIs<MemoryWrite.Written>(
            stores.writer.remember(MemoryLayer.WORKING.wire, "запись сверх предела")
        )

        assertEquals(1, written.evicted, "лишняя запись вытеснена")
        val working = stores.writer.layers().working
        assertEquals(MemoryRules.MAX_WORKING, working.size)
        assertTrue(working.none { it.value == "запись 0" }, "вытесняется самая старая запись: $working")
        assertEquals("запись сверх предела", working.last().value)
    }

    /** Забывание убирает одну запись, остальные не трогает и повторяется без ошибок. */
    @Test
    fun forgetRemovesOneRecordAndRepeatsQuietly() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        stores.writer.remember(MemoryLayer.WORKING.wire, "срок — 6 недель")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "хранилище — Room")

        val afterFirst = assertIs<MemoryForget.Forgotten>(
            stores.writer.forget(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        ).layers
        assertEquals(listOf(MemoryRecord("working", "срок — 6 недель")), afterFirst.working)
        assertEquals(1, afterFirst.longTerm.size, "чужой тип забывание не задело")

        val repeated = assertIs<MemoryForget.Forgotten>(
            stores.writer.forget(MemoryLayer.WORKING.wire, "цель — учёт расходов")
        ).layers
        assertEquals(afterFirst, repeated, "повторное удаление ничего не меняет")

        assertEquals(
            MemoryWriter.UNKNOWN_LAYER,
            assertIs<MemoryForget.Rejected>(stores.writer.forget("настроение", "боевой")).reason,
            "тип не из задания — отказ"
        )
        assertEquals(
            MemoryWriter.NOT_WRITABLE,
            assertIs<MemoryForget.Rejected>(
                stores.writer.forget(MemoryLayer.SHORT_TERM.wire, "пользователь поздоровался")
            ).reason,
            "в краткосрочной памяти нечего забывать"
        )
    }

    /** Удаление действует на общую область: забытая запись пропадает из всех чатов разом. */
    @Test
    fun forgetRemovesSharedEntry() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.WORKING.wire, "цель — учёт расходов")

        assertIs<MemoryForget.Forgotten>(stores.writer.forget(MemoryLayer.WORKING.wire, "цель — учёт расходов"))

        assertTrue(stores.writer.layers().working.isEmpty(), "рабочая запись не осталась ни в одном чате")
        assertTrue(stores.working.get(DEFAULT_PROFILE).isEmpty(), "и в хранилище профиля её тоже нет")
    }

    /** Снимок отдаёт оба типа целиком и каталог типов: по нему рисуется выбор в интерфейсе. */
    @Test
    fun snapshotCarriesBothTypesAndCatalogue() {
        val stores = Stores()
        stores.writer.remember(MemoryLayer.WORKING.wire, "сводка — по пятницам")
        stores.writer.remember(MemoryLayer.LONG_TERM.wire, "проект — Kotlin Multiplatform")

        val layers = stores.writer.layers()

        assertEquals(listOf(MemoryRecord("working", "сводка — по пятницам")), layers.working)
        assertEquals(listOf(MemoryRecord("long_term", "проект — Kotlin Multiplatform")), layers.longTerm)
        assertEquals(
            listOf(
                MemoryType("short_term", "краткосрочная", "текущий диалог", writable = false),
                MemoryType("working", "рабочая", "данные текущей задачи — общие для всех чатов", writable = true),
                MemoryType("long_term", "долговременная", "профиль, решения, знания", writable = true)
            ),
            layers.types,
            "каталог — те же три типа и пояснения, что в задании"
        )
    }
}
