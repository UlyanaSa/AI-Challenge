package com.osvin.aichallenge.data

/**
 * Хранилище истории диалога, переживающее перезапуск приложения.
 * Платформенные реализации: на Android — Room ([RoomChatHistoryStore]),
 * на остальных таргетах — [InMemoryChatHistoryStore].
 */
interface ChatHistoryStore {
    /** Вся сохранённая история в порядке отправки. */
    suspend fun load(): List<ChatMessage>

    /** Дописывает сообщение в конец истории. */
    suspend fun append(message: ChatMessage)

    /** Удаляет всю историю. */
    suspend fun clear()
}

/**
 * Хранилище в памяти процесса: история не переживает перезапуск.
 * Используется на таргетах, где недоступен Room (iOS, Web).
 */
class InMemoryChatHistoryStore : ChatHistoryStore {
    private val messages = mutableListOf<ChatMessage>()

    override suspend fun load(): List<ChatMessage> = messages.toList()

    override suspend fun append(message: ChatMessage) {
        messages += message
    }

    override suspend fun clear() {
        messages.clear()
    }
}
