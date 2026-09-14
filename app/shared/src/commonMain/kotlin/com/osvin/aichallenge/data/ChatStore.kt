package com.osvin.aichallenge.data

/**
 * Хранилище чатов и их сообщений, переживающее перезапуск приложения.
 *
 * У каждого чата своя история и своя сессия агента: идентификатор чата ([Chat.id])
 * одновременно служит идентификатором сессии, поэтому сообщения и сессия живут
 * ровно до удаления чата.
 *
 * Платформенные реализации: на Android — Room ([RoomChatStore]),
 * на остальных таргетах — [InMemoryChatStore].
 */
interface ChatStore {
    /** Все чаты: свежие сверху (по времени последнего сообщения). */
    suspend fun chats(): List<Chat>

    /** Чат по идентификатору; null — такого чата нет. */
    suspend fun chat(id: String): Chat?

    /** Создаёт чат. */
    suspend fun create(chat: Chat)

    /** Сообщения чата в порядке отправки. */
    suspend fun messages(chatId: String): List<ChatMessage>

    /** Дописывает сообщение в конец чата и обновляет время последнего сообщения. */
    suspend fun append(chatId: String, message: ChatMessage)

    /** Меняет заголовок чата. */
    suspend fun retitle(chatId: String, title: String)

    /** Удаляет чат вместе с его сообщениями и сессией. */
    suspend fun delete(chatId: String)
}

/**
 * Хранилище в памяти процесса: чаты и сообщения не переживают перезапуск.
 * Используется на таргетах, где недоступен Room (iOS, Web), и в тестах.
 */
class InMemoryChatStore : ChatStore {
    private val chats = mutableMapOf<String, Chat>()
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()

    override suspend fun chats(): List<Chat> = chats.values.sortedByDescending { it.updatedAt }

    override suspend fun chat(id: String): Chat? = chats[id]

    override suspend fun create(chat: Chat) {
        chats[chat.id] = chat
    }

    override suspend fun messages(chatId: String): List<ChatMessage> = messages[chatId].orEmpty().toList()

    override suspend fun append(chatId: String, message: ChatMessage) {
        messages.getOrPut(chatId) { mutableListOf() } += message
        chats[chatId]?.let { chats[chatId] = it.copy(updatedAt = message.timestamp) }
    }

    override suspend fun retitle(chatId: String, title: String) {
        chats[chatId]?.let { chats[chatId] = it.copy(title = title) }
    }

    override suspend fun delete(chatId: String) {
        chats.remove(chatId)
        messages.remove(chatId)
    }
}
