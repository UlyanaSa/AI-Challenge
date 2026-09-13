package com.osvin.aichallenge.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Сообщение диалога в таблице SQLite.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Доступ к сохранённой истории диалога.
 */
@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    suspend fun all(): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

/**
 * База истории диалога.
 */
@Database(entities = [ChatMessageEntity::class], version = 1, exportSchema = false)
abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun messages(): ChatMessageDao
}
