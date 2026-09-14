package com.osvin.aichallenge.data

import android.content.Context
import androidx.room.Room

/**
 * Хранилище чатов на Room: чаты и их сообщения лежат в SQLite и переживают
 * перезапуск приложения, пока чат не удалён вместе со своей сессией агента.
 */
class RoomChatStore(context: Context) : ChatStore {

    private val db = Room
        .databaseBuilder(context, ChatDatabase::class.java, "chat-history.db")
        .addMigrations(ChatDatabase.MIGRATION_1_2)
        .build()

    private val chatDao = db.chats()

    private val messageDao = db.messages()

    override suspend fun chats(): List<Chat> = chatDao.all().map { it.toChat() }

    override suspend fun chat(id: String): Chat? = chatDao.byId(id)?.toChat()

    override suspend fun create(chat: Chat) {
        chatDao.insert(
            ChatEntity(
                id = chat.id,
                title = chat.title,
                createdAt = chat.createdAt,
                updatedAt = chat.updatedAt
            )
        )
    }

    override suspend fun messages(chatId: String): List<ChatMessage> =
        messageDao.ofChat(chatId).map { it.toMessage() }

    override suspend fun append(chatId: String, message: ChatMessage) {
        messageDao.append(
            chatId = chatId,
            message = ChatMessageEntity(
                role = message.role.wire,
                content = message.content,
                timestamp = message.timestamp,
                chatId = chatId
            )
        )
    }

    override suspend fun retitle(chatId: String, title: String) {
        chatDao.retitle(chatId, title)
    }

    override suspend fun delete(chatId: String) {
        chatDao.deleteWithMessages(chatId)
    }
}

private fun ChatEntity.toChat(): Chat = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun ChatMessageEntity.toMessage(): ChatMessage = ChatMessage(
    role = MessageRole.entries.first { it.wire == this.role },
    content = content,
    timestamp = timestamp
)
