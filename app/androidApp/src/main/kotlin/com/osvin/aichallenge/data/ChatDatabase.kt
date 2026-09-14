package com.osvin.aichallenge.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlin.random.Random

/**
 * Чат в таблице SQLite.
 * Идентификатор чата — он же идентификатор сессии агента, поэтому он живёт
 * в базе до удаления чата.
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Сообщение чата в таблице SQLite.
 * @param chatId Чат, которому принадлежит сообщение.
 */
@Entity(tableName = "chat_messages", indices = [Index("chatId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long,
    val chatId: String
)

/**
 * Доступ к таблице чатов.
 *
 * Композитная операция [deleteWithMessages] затрагивает ещё и таблицу сообщений,
 * поэтому в конструктор передаётся сама база.
 */
@Dao
abstract class ChatDao(private val db: ChatDatabase) {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    abstract suspend fun all(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    abstract suspend fun byId(chatId: String): ChatEntity?

    @Insert
    abstract suspend fun insert(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    abstract suspend fun retitle(chatId: String, title: String)

    @Query("DELETE FROM chats WHERE id = :chatId")
    abstract suspend fun delete(chatId: String)

    /** Удаляет чат и все его сообщения одной транзакцией. */
    @Transaction
    open suspend fun deleteWithMessages(chatId: String) {
        db.messages().deleteOfChat(chatId)
        delete(chatId)
    }
}

/**
 * Доступ к таблице сообщений.
 */
@Dao
abstract class ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY id ASC")
    abstract suspend fun ofChat(chatId: String): List<ChatMessageEntity>

    @Insert
    abstract suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    abstract suspend fun deleteOfChat(chatId: String)

    @Query("UPDATE chats SET updatedAt = :at WHERE id = :chatId")
    abstract suspend fun touchChat(chatId: String, at: Long)

    /** Дописывает сообщение и обновляет время чата одной транзакцией. */
    @Transaction
    open suspend fun append(chatId: String, message: ChatMessageEntity) {
        insert(message)
        touchChat(chatId, message.timestamp)
    }
}

/**
 * База чатов и их сообщений.
 */
@Database(entities = [ChatEntity::class, ChatMessageEntity::class], version = 2, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    abstract fun messages(): ChatMessageDao

    companion object {
        /**
         * Переход со схемы 1 на схему 2: появились чаты, а сообщения стали
         * принадлежать конкретному чату.
         *
         * Старые сообщения ничьи — идентификатор сессии тогда не сохранялся, поэтому
         * под них заводится новый чат со своим идентификатором: сводка на сервере
         * для него построится заново, а история сообщений на устройстве сохранится.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chats` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )

                val chat = oldDialog(db)
                if (chat != null) {
                    db.execSQL(
                        "INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (?, ?, ?, ?)",
                        arrayOf<Any?>(chat.id, oldDialogTitle(db), chat.createdAt, chat.updatedAt)
                    )
                }

                // Таблицу сообщений пересоздаём целиком: так в схеме не остаётся
                // ничего лишнего и она точно совпадает с ожиданиями Room.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`chatId` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO chat_messages_new (id, role, content, timestamp, chatId) " +
                        "SELECT id, role, content, timestamp, ? FROM chat_messages",
                    arrayOf(chat?.id ?: "")
                )
                db.execSQL("DROP TABLE chat_messages")
                db.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_chatId ON chat_messages (chatId)")
            }
        }

        /**
         * Новый чат для старой истории: null — сообщений не было, и чат не нужен.
         * Время создания и последнего сообщения берётся из самих сообщений.
         */
        private fun oldDialog(db: SupportSQLiteDatabase): OldDialog? =
            db.query("SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM chat_messages").use { cursor ->
                cursor.moveToFirst()
                if (cursor.getInt(0) == 0) {
                    null
                } else {
                    OldDialog(newChatId(), cursor.getLong(1), cursor.getLong(2))
                }
            }

        /** Заголовок старого диалога — начало первого непустого вопроса пользователя. */
        private fun oldDialogTitle(db: SupportSQLiteDatabase): String {
            val question = db.query(
                "SELECT content FROM chat_messages WHERE role = 'user' AND TRIM(content) <> '' " +
                    "ORDER BY id ASC LIMIT 1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            return question?.replace('\n', ' ')?.take(TITLE_LIMIT) ?: OLD_DIALOG_TITLE
        }

        /** Идентификатор чата: 32 hex-символа, как и у чатов, созданных в приложении. */
        private fun newChatId(): String = buildString {
            repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }

        /** Сколько символов первого вопроса попадает в заголовок. */
        private const val TITLE_LIMIT = 40

        /** Заголовок старого диалога, если вопрос пользователя не нашёлся. */
        private const val OLD_DIALOG_TITLE = "Старый диалог"
    }
}

/** Чат, заводимый для истории из схемы 1. */
private class OldDialog(val id: String, val createdAt: Long, val updatedAt: Long)
