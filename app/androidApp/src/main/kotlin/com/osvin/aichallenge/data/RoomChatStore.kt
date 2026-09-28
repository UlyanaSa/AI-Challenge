package com.osvin.aichallenge.data

import android.content.Context
import androidx.room.Room
import kotlinx.serialization.json.Json

/**
 * Хранилище чатов на Room: чаты, их сообщения и ветки диалога лежат в SQLite
 * и переживают перезапуск приложения, пока чат не удалён вместе со своей
 * сессией агента.
 */
class RoomChatStore(context: Context) : ChatStore {

    private val db = Room
        .databaseBuilder(context, ChatDatabase::class.java, "chat-history.db")
        .addMigrations(
            ChatDatabase.MIGRATION_1_2,
            ChatDatabase.MIGRATION_2_3,
            ChatDatabase.MIGRATION_3_4,
            ChatDatabase.MIGRATION_4_5,
            ChatDatabase.MIGRATION_5_6
        )
        .build()

    private val chatDao = db.chats()

    private val messageDao = db.messages()

    private val branchDao = db.branches()

    override suspend fun chats(): List<Chat> = chatDao.all().map { it.toChat() }

    override suspend fun chat(id: String): Chat? = chatDao.byId(id)?.toChat()

    override suspend fun create(chat: Chat) {
        chatDao.insert(
            ChatEntity(
                id = chat.id,
                title = chat.title,
                createdAt = chat.createdAt,
                updatedAt = chat.updatedAt,
                activeBranchId = chat.activeBranchId,
                strategy = chat.strategy,
                pinned = chat.pinned
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
                chatId = chatId,
                branchId = message.branchId,
                toolCalls = message.tools.toJsonColumn()
            )
        )
    }

    override suspend fun trimMessages(chatId: String, keep: Int) {
        messageDao.trimOfChat(chatId, keep)
    }

    override suspend fun retitle(chatId: String, title: String) {
        chatDao.retitle(chatId, title)
    }

    override suspend fun delete(chatId: String) {
        chatDao.deleteWithData(chatId)
    }

    override suspend fun branches(chatId: String): List<DialogBranch> =
        branchDao.ofChat(chatId).map { it.toBranch() }

    override suspend fun activeBranch(chatId: String): String? = chatDao.byId(chatId)?.activeBranchId

    override suspend fun createBranch(chatId: String, branch: DialogBranch) {
        branchDao.insert(
            DialogBranchEntity(
                id = branch.id,
                chatId = chatId,
                parentId = branch.parentId,
                forkedAfter = branch.forkedAfter,
                createdAt = branch.createdAt
            )
        )
    }

    override suspend fun setActiveBranch(chatId: String, branchId: String?) {
        chatDao.setActiveBranch(chatId, branchId)
    }

    override suspend fun setStrategy(chatId: String, strategy: String) {
        chatDao.setStrategy(chatId, strategy)
    }
}

private fun ChatEntity.toChat(): Chat = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    activeBranchId = activeBranchId,
    strategy = strategy,
    pinned = pinned
)

private fun ChatMessageEntity.toMessage(): ChatMessage = ChatMessage(
    role = MessageRole.entries.first { it.wire == this.role },
    content = content,
    timestamp = timestamp,
    branchId = branchId,
    tools = toolCalls.toToolCalls()
)

/**
 * Разбор списка вызовов для колонки [ChatMessageEntity.toolCalls].
 *
 * ignoreUnknownKeys — чтобы запись, сделанная более новой версией приложения,
 * читалась старой: незнакомые поля отбрасываются, а история не пропадает.
 */
private val TOOL_CALLS_JSON = Json { ignoreUnknownKeys = true }

/** Вызовы в JSON-строку для хранения; пустой список — null, а не «[]». */
private fun List<ToolCallRecord>.toJsonColumn(): String? =
    if (isEmpty()) null else TOOL_CALLS_JSON.encodeToString(this)

/**
 * Обратный разбор вызовов: null и битая строка дают пустой список — чтение истории
 * не должно падать из-за одной испорченной записи.
 */
private fun String?.toToolCalls(): List<ToolCallRecord> =
    if (isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { TOOL_CALLS_JSON.decodeFromString<List<ToolCallRecord>>(this) }
            .getOrDefault(emptyList())
    }

private fun DialogBranchEntity.toBranch(): DialogBranch = DialogBranch(
    id = id,
    parentId = parentId,
    forkedAfter = forkedAfter,
    createdAt = createdAt
)
