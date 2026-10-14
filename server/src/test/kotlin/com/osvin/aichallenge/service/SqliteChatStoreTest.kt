package com.osvin.aichallenge.service

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище сессий на SQLite.
 *
 * Каждый тест работает на своём временном файле и убирает его за собой: база — не заглушка,
 * а настоящий файл, в котором проверяется и повторное открытие после перезапуска, а такие
 * проверки на разделяемом файле мешали бы друг другу. Файл именно временный, а не `:memory:`,
 * потому что память не переживает закрытие соединения — то есть не проверяет главное
 * обещание хранилища.
 */
class SqliteChatStoreTest {

    @Test
    fun `сессия создаётся, находится, а пустое имя заменяется умолчанием`() {
        withStore { store ->
            val named = store.create(title = "Рабочий чат", nowMillis = 1_000)
            assertEquals("Рабочий чат", named.title)
            assertEquals(1_000, named.createdAt)
            assertEquals(1_000, named.updatedAt)
            assertEquals(32, named.id.length)
            assertTrue(named.id.matches(ID_PATTERN), "идентификатор не шестнадцатеричный: ${named.id}")
            assertEquals(named, store.find(named.id))

            val unnamed = store.create(title = null, nowMillis = 2_000)
            assertEquals(ChatSession.DEFAULT_TITLE, unnamed.title)
            val blank = store.create(title = "   ", nowMillis = 3_000)
            assertEquals(ChatSession.DEFAULT_TITLE, blank.title)
            assertNotEquals(unnamed.id, blank.id)

            assertNull(store.find("такого-чата-нет"))
        }
    }

    @Test
    fun `сообщения читаются в порядке записи, чужая сессия пуста`() {
        withStore { store ->
            val session = store.create(title = null, nowMillis = 1)
            val written = listOf(
                store.append(session.id, role = "user", content = "первый", nowMillis = 10),
                store.append(session.id, role = "assistant", content = "второй", nowMillis = 10),
                store.append(session.id, role = "user", content = "третий", nowMillis = 11)
            )

            assertEquals(written, store.messages(session.id))
            assertEquals(listOf("первый", "второй", "третий"), store.messages(session.id).map { it.content })
            assertEquals(listOf("user", "assistant", "user"), store.messages(session.id).map { it.role })
            assertEquals(emptyList(), store.messages("чужой-чат"))
        }
    }

    @Test
    fun `время сессии растёт после каждой записи`() {
        withStore { store ->
            val session = store.create(title = null, nowMillis = 100)
            assertEquals(100, store.find(session.id)?.updatedAt)

            store.append(session.id, role = "user", content = "вопрос", nowMillis = 500)
            val afterQuestion = store.find(session.id)
            assertEquals(500, afterQuestion?.updatedAt)
            assertTrue((afterQuestion?.updatedAt ?: 0) > session.updatedAt)

            store.append(session.id, role = "assistant", content = "ответ", nowMillis = 900)
            val afterAnswer = store.find(session.id)
            assertEquals(900, afterAnswer?.updatedAt)
            assertTrue((afterAnswer?.updatedAt ?: 0) > (afterQuestion?.updatedAt ?: 0))
            // Создание не переписывается: время последнего сообщения — не время начала диалога.
            assertEquals(100, afterAnswer?.createdAt)
        }
    }

    @Test
    fun `удаление возвращает число сообщений, а после него сессии нет`() {
        withStore { store ->
            val session = store.create(title = null, nowMillis = 1)
            store.append(session.id, role = "user", content = "раз", nowMillis = 2)
            store.append(session.id, role = "assistant", content = "два", nowMillis = 3)
            store.append(session.id, role = "user", content = "три", nowMillis = 4)

            assertEquals(3, store.delete(session.id))
            assertNull(store.find(session.id))
            assertEquals(emptyList(), store.messages(session.id))
            // Повтор — не «удалено ноль», а «удалять было нечего»: по этому различию
            // маршрут отвечает 404, а не успехом.
            assertEquals(-1, store.delete(session.id))
            assertEquals(-1, store.delete("чужой-чат"))
            assertEquals(StoreCounts(sessions = 0, messages = 0), store.counts())
        }
    }

    @Test
    fun `перезапуск на том же файле видит те же сессии и сообщения`() {
        val directory = Files.createTempDirectory("chat-store-restart")
        val database = directory.resolve("chats.db")
        try {
            val first = SqliteChatStore(database)
            val session = first.create(title = "До перезапуска", nowMillis = 1_000)
            first.append(session.id, role = "user", content = "вопрос", nowMillis = 2_000)
            first.append(session.id, role = "assistant", content = "ответ", nowMillis = 3_000)
            first.close()
            // Второй вызов закрытия — то, что делает остановка процесса: он не должен падать.
            first.close()

            val second = SqliteChatStore(database)
            try {
                assertEquals(
                    ChatSession(id = session.id, title = "До перезапуска", createdAt = 1_000, updatedAt = 3_000),
                    second.find(session.id)
                )
                assertEquals(listOf("вопрос", "ответ"), second.messages(session.id).map { it.content })
            } finally {
                second.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `размеры хранилища считают все сессии и все сообщения`() {
        withStore { store ->
            assertEquals(StoreCounts(sessions = 0, messages = 0), store.counts())

            val first = store.create(title = null, nowMillis = 1)
            assertEquals(StoreCounts(sessions = 1, messages = 0), store.counts())

            store.append(first.id, role = "user", content = "раз", nowMillis = 2)
            store.append(first.id, role = "assistant", content = "два", nowMillis = 3)
            val second = store.create(title = null, nowMillis = 4)
            store.append(second.id, role = "user", content = "три", nowMillis = 5)

            assertEquals(StoreCounts(sessions = 2, messages = 3), store.counts())

            store.delete(first.id)
            assertEquals(StoreCounts(sessions = 1, messages = 1), store.counts())
        }
    }

    @Test
    fun `каталог базы создаётся при открытии, если его не было`() {
        val directory = Files.createTempDirectory("chat-store-nested")
        // Путь по умолчанию (`data/service-chats.db`) задаёт человек, и каталога под файлом
        // может не быть: открытие обязано его создать, а не упасть с «файл не найден».
        val database = directory.resolve("nested").resolve("deeper").resolve("chats.db")
        try {
            SqliteChatStore(database).use { store ->
                val session = store.create(title = null, nowMillis = 1)
                store.append(session.id, role = "user", content = "вопрос", nowMillis = 2)
                assertEquals(listOf("вопрос"), store.messages(session.id).map { it.content })
            }
            assertTrue(Files.exists(database), "база не создана: $database")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `список чатов упорядочен по свежести, а прошлым становится свежий чат с сообщениями`() {
        withStore { store ->
            val first = store.create(title = "Первый", nowMillis = 1_000)
            val second = store.create(title = "Второй", nowMillis = 4_000)
            val empty = store.create(title = "Пустой", nowMillis = 6_000)
            store.append(first.id, role = "user", content = "раз", nowMillis = 3_000)
            store.append(first.id, role = "assistant", content = "два", nowMillis = 3_500)
            store.append(second.id, role = "user", content = "три", nowMillis = 5_000)

            val listed = store.list(limit = 10)
            assertEquals(listOf(empty.id, second.id, first.id), listed.map { it.id })
            // Число сообщений приходит тем же запросом, что и сам список.
            assertEquals(listOf(0, 1, 2), listed.map { it.messageCount })
            assertEquals(listOf(empty.id), store.list(limit = 1).map { it.id })

            // Прошлый чат — свежий из тех, у кого есть сообщения; пустой им не считается.
            assertEquals(second.id, store.latestWithMessages(excluding = "")?.id)
            assertEquals(first.id, store.latestWithMessages(excluding = second.id)?.id)
            assertEquals(second.id, store.latestWithMessages(excluding = first.id)?.id)

            store.setSummary(second.id, previousChatId = first.id, summary = "Сводка", messages = 2)
            val withSummary = store.list(limit = 10).first { it.id == second.id }
            assertEquals("Сводка", withSummary.summary)
            assertEquals(first.id, withSummary.previousChatId)
            assertEquals(2, withSummary.summaryMessages)
            // Сводка не двигает чат в списке: порядок задаёт время последнего сообщения.
            assertEquals(5_000, withSummary.updatedAt)

            store.delete(first.id)
            assertNull(store.latestWithMessages(excluding = second.id), "сворачивать больше нечего")
        }
    }

    @Test
    fun `миграция добавляет колонки сводки существующей базе, а сводка переживает перезапуск`() {
        val directory = Files.createTempDirectory("chat-store-migration")
        val database = directory.resolve("chats.db")
        val legacyId = "a".repeat(32)
        try {
            // База прежней схемы: так выглядит файл, записанный до появления сводок.
            DriverManager.getConnection("jdbc:sqlite:$database").use { legacy ->
                legacy.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE chat_sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
                    )
                    statement.execute(
                        "INSERT INTO chat_sessions(id, title, created_at, updated_at) " +
                            "VALUES ('$legacyId', 'Старый чат', 1000, 2000)"
                    )
                }
            }

            SqliteChatStore(database).use { store ->
                // Колонки добавлены, строка прежней базы читается, сводки в ней ещё нет.
                assertEquals(
                    ChatSession(legacyId, "Старый чат", createdAt = 1_000, updatedAt = 2_000),
                    store.find(legacyId)
                )
                store.setSummary(
                    chatId = legacyId,
                    previousChatId = "b".repeat(32),
                    summary = "Сводка прошлого",
                    messages = 3
                )
            }

            // Повторное открытие проходит ту же миграцию: колонки уже есть, и это не ошибка.
            SqliteChatStore(database).use { store ->
                val restored = store.find(legacyId)
                assertEquals("Сводка прошлого", restored?.summary)
                assertEquals("b".repeat(32), restored?.previousChatId)
                assertEquals(3, restored?.summaryMessages)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Временный каталог с базой: открывается на время проверки и удаляется вместе с файлами WAL. */
    private fun withStore(block: (SqliteChatStore) -> Unit) {
        val directory = Files.createTempDirectory("chat-store")
        try {
            SqliteChatStore(directory.resolve("chats.db")).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {

        /** Вид идентификатора сессии из контракта: ровно 32 шестнадцатеричных символа. */
        val ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}
