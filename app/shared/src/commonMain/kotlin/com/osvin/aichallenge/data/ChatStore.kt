package com.osvin.aichallenge.data

/**
 * Хранилище чатов и их сообщений, переживающее перезапуск приложения.
 *
 * У каждого чата своя история и своя сессия агента: идентификатор чата ([Chat.id])
 * одновременно служит идентификатором сессии, поэтому сообщения и сессия живут
 * ровно до удаления чата. Ветки диалога ([DialogBranch]) тоже принадлежат чату:
 * сообщение помечается веткой ([ChatMessage.branchId]), а активная ветка хранится
 * в самом чате ([Chat.activeBranchId]). Стратегия управления контекстом
 * ([Chat.strategy]) — там же: от неё зависит, читает ли модель память, записанную
 * в этом чате, а не в соседнем.
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

    /** Сообщения чата в порядке отправки: все ветки вместе, у каждого своя метка. */
    suspend fun messages(chatId: String): List<ChatMessage>

    /** Дописывает сообщение в конец чата и обновляет время последнего сообщения. */
    suspend fun append(chatId: String, message: ChatMessage)

    /** Меняет заголовок чата. */
    suspend fun retitle(chatId: String, title: String)

    /** Удаляет чат вместе с его сообщениями, ветками и сессией. */
    suspend fun delete(chatId: String)

    /** Ветки чата в порядке создания. */
    suspend fun branches(chatId: String): List<DialogBranch>

    /** Активная ветка чата; null — основная линия диалога. */
    suspend fun activeBranch(chatId: String): String?

    /** Добавляет ветку чата, не переключая на неё диалог. */
    suspend fun createBranch(chatId: String, branch: DialogBranch)

    /** Делает ветку активной; null — возвращает диалог на основную линию. */
    suspend fun setActiveBranch(chatId: String, branchId: String?)

    /** Меняет стратегию управления контекстом чата. */
    suspend fun setStrategy(chatId: String, strategy: String)
}

/**
 * Хранилище в памяти процесса: чаты, сообщения и ветки не переживают перезапуск.
 * Используется на таргетах, где недоступен Room (iOS, Web), и в тестах.
 */
class InMemoryChatStore : ChatStore {
    private val chats = mutableMapOf<String, Chat>()
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val branches = mutableMapOf<String, MutableList<DialogBranch>>()

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
        branches.remove(chatId)
    }

    override suspend fun branches(chatId: String): List<DialogBranch> = branches[chatId].orEmpty().toList()

    override suspend fun activeBranch(chatId: String): String? = chats[chatId]?.activeBranchId

    override suspend fun createBranch(chatId: String, branch: DialogBranch) {
        branches.getOrPut(chatId) { mutableListOf() } += branch
    }

    override suspend fun setActiveBranch(chatId: String, branchId: String?) {
        chats[chatId]?.let { chats[chatId] = it.copy(activeBranchId = branchId) }
    }

    override suspend fun setStrategy(chatId: String, strategy: String) {
        chats[chatId]?.let { chats[chatId] = it.copy(strategy = strategy) }
    }
}
