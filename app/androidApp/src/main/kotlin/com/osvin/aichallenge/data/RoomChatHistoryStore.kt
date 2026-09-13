package com.osvin.aichallenge.data

import android.content.Context
import androidx.room.Room

/**
 * Хранилище истории диалога на Room: сообщения лежат в SQLite
 * и переживают перезапуск приложения.
 */
class RoomChatHistoryStore(context: Context) : ChatHistoryStore {

    private val messages = Room
        .databaseBuilder(context, ChatHistoryDatabase::class.java, "chat-history.db")
        .build()
        .messages()

    override suspend fun load(): List<ChatMessage> = messages.all().map { entity ->
        ChatMessage(
            role = MessageRole.entries.first { it.wire == entity.role },
            content = entity.content,
            timestamp = entity.timestamp
        )
    }

    override suspend fun append(message: ChatMessage) {
        messages.insert(
            ChatMessageEntity(
                role = message.role.wire,
                content = message.content,
                timestamp = message.timestamp
            )
        )
    }

    override suspend fun clear() {
        messages.deleteAll()
    }
}
