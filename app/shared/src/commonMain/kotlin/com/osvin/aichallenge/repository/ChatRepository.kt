package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
import com.osvin.aichallenge.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Репозиторий чатов: список диалогов, активный чат и обмен сообщениями с сервером.
 *
 * Каждый чат — отдельная сессия агента: идентификатор чата ([Chat.id]) уходит на
 * сервер как `sessionId`, поэтому сводка истории на сервере своя у каждого чата.
 * Идентификатор хранится в БД устройства ([ChatStore]), пока чат не удалён.
 *
 * Диалог может ветвиться ([DialogBranch]): сообщения всех веток лежат в хранилище,
 * но в чате ([messages]) и в запросе к модели участвует только путь активной ветки
 * (см. [DialogBranches]).
 */
class ChatRepository(
    private val baseUrl: String,
    private val store: ChatStore,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
) {
    // Внутренние потоки данных (StateFlow)
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _activeChat = MutableStateFlow<Chat?>(null)
    val activeChat: StateFlow<Chat?> = _activeChat.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _branches = MutableStateFlow<List<DialogBranch>>(emptyList())
    val branches: StateFlow<List<DialogBranch>> = _branches.asStateFlow()

    private val _activeBranchId = MutableStateFlow<String?>(null)
    val activeBranchId: StateFlow<String?> = _activeBranchId.asStateFlow()

    private val _facts = MutableStateFlow<List<Fact>>(emptyList())
    val facts: StateFlow<List<Fact>> = _facts.asStateFlow()

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

    /**
     * Проверка доступности сервера.
     */
    suspend fun checkHealth() {
        try {
            val response = client.get("$baseUrl/v1/health")
            _isServerOnline.value = response.status.isSuccess()
        } catch (e: Exception) {
            _isServerOnline.value = false
        }
    }

    /**
     * Загрузка списка сохранённых чатов (например, после перезапуска приложения).
     */
    suspend fun loadChats() {
        _chats.value = store.chats()
    }

    /**
     * Новый чат со своей историей и своей сессией агента.
     * Диалог начинается с основной линии: веток ещё нет, активной ветки тоже.
     */
    suspend fun createChat(): Chat {
        val chat = Chat(id = newHexId(), title = NEW_CHAT_TITLE)
        store.create(chat)
        _activeChat.value = chat
        _messages.value = emptyList()
        _branches.value = emptyList()
        _activeBranchId.value = null
        _facts.value = emptyList()
        _state.value = ChatUiState.Idle
        _chats.value = store.chats()
        return chat
    }

    /**
     * Открытие чата: поднимаем его сообщения из БД вместе с ветками.
     * Дальше запросы уходят с идентификатором этого чата, поэтому и сессия агента,
     * и сводка истории на сервере — те же, что были в прошлый раз. Диалог
     * продолжается в той ветке, в которой пользователь остановился.
     */
    suspend fun openChat(id: String) {
        val chat = store.chat(id) ?: return
        val branches = store.branches(id)
        _activeChat.value = chat
        _branches.value = branches
        _activeBranchId.value = chat.activeBranchId
        _facts.value = emptyList()
        _messages.value = DialogBranches.activePath(store.messages(id), branches, chat.activeBranchId)
        _state.value = ChatUiState.Idle
    }

    /**
     * Удаление чата вместе с историей, ветками и сессией агента.
     * На сервере сессию тоже чистим — иначе сводки и память фактов копились бы
     * в его памяти; если сервер недоступен, чат на устройстве всё равно удалён.
     */
    suspend fun deleteChat(id: String) {
        store.delete(id)
        if (_activeChat.value?.id == id) {
            _activeChat.value = null
            _messages.value = emptyList()
            _branches.value = emptyList()
            _activeBranchId.value = null
            _facts.value = emptyList()
            _state.value = ChatUiState.Idle
        }
        _chats.value = store.chats()
        clearServerSession(id)
    }

    /**
     * Ветка от сообщения активного пути: точка ветвления — само это сообщение,
     * поэтому ветка начинается сразу после него и продолжается отдельно от соседей.
     * Созданная ветка сразу становится активной: пользователь продолжает диалог в ней.
     */
    suspend fun createBranchFrom(message: ChatMessage) {
        val chat = _activeChat.value ?: return
        val path = DialogBranches.activePath(
            history = store.messages(chat.id),
            branches = store.branches(chat.id),
            activeBranchId = _activeBranchId.value
        )
        val index = path.indexOfFirst { it == message }
        if (index < 0) return

        val branch = DialogBranch(
            id = newHexId(),
            // Родитель — ветка самого сообщения: у сообщения основной линии его нет
            parentId = message.branchId,
            // Общих с родителем сообщений ровно столько, сколько в пути до этого сообщения
            forkedAfter = index + 1
        )
        store.createBranch(chat.id, branch)
        switchBranch(branch.id)
    }

    /**
     * Переключение активной ветки диалога; null — возврат на основную линию.
     * Дальше и экран, и запросы к модели видят только путь новой ветки,
     * а сообщения соседних веток остаются в хранилище со своими метками.
     */
    suspend fun switchBranch(branchId: String?) {
        val chat = _activeChat.value ?: return
        val branches = store.branches(chat.id)
        val target = branchId?.takeIf { id -> branches.any { it.id == id } }
        store.setActiveBranch(chat.id, target)
        _activeChat.value = chat.copy(activeBranchId = target)
        _branches.value = branches
        _activeBranchId.value = target
        _messages.value = DialogBranches.activePath(store.messages(chat.id), branches, target)
    }

    /**
     * Отправка сообщения нейросети в активный чат.
     *
     * На сервер уезжает вся история чата: агент сам решает, что из неё уходит
     * в модель, — по выбранной стратегии ([GenerationSettings.strategy]) и по
     * пути активной ветки (см. [DialogBranches]). Метки веток при истории
     * сохраняются, иначе агент не собрал бы этот путь, а `branchId`/`branches`
     * нужны только стратегии «ветки диалога».
     *
     * @param message Текст сообщения пользователя.
     * @param settings Настройки генерации из шторки настроек.
     */
    suspend fun sendMessage(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return

        val chat = _activeChat.value
        if (chat == null) {
            _state.value = ChatUiState.Error("Чат не выбран")
            return
        }

        _state.value = ChatUiState.Loading

        // Ветка, в которой продолжается диалог: её метку получат оба новых сообщения
        val activeBranchId = _activeBranchId.value
        val branches = if (settings.strategy == ContextStrategy.BRANCHES) store.branches(chat.id) else null

        // История чата до этого вопроса: все ветки вместе, у каждой своя метка
        val history = store.messages(chat.id)

        try {
            val response = client.post("$baseUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        model = settings.model,
                        maxTokens = settings.maxTokens,
                        stop = settings.stopWords.ifEmpty { null },
                        temperature = settings.temperature,
                        systemPrompt = settings.systemPrompt.ifBlank { null },
                        history = history.takeIf { it.isNotEmpty() },
                        sessionId = chat.id,
                        strategy = settings.strategy.wire,
                        windowMessages = settings.windowMessages,
                        branchId = activeBranchId.takeIf { branches != null },
                        branches = branches
                    )
                )
            }

            // Вопрос пользователя попадает в диалог в любом случае: и при ответе,
            // и при ошибке — иначе при пустом ответе модели он пропадает с экрана.
            val userMessage = ChatMessage(MessageRole.USER, message, branchId = activeBranchId)
            _messages.value += userMessage
            store.append(chat.id, userMessage)

            // Заголовок чата берём из первого вопроса пользователя за всю переписку:
            // в списке чатов видно, о чём каждый диалог. Считаем по всей истории,
            // а не по активному пути: в ветке вопрос уже не первый.
            if (history.none { it.role == MessageRole.USER }) {
                val title = titleFrom(message)
                store.retitle(chat.id, title)
                _activeChat.value = _activeChat.value?.copy(title = title)
            }
            _chats.value = store.chats()

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()

                // Отчёт агента печатаем в лог платформы (на Android — в logcat):
                // строки те же, что агент пишет на сервере, но видны рядом с приложением.
                // Память фактов оттуда же попадает в блок на экране чата.
                chatResponse.tokens?.let { report ->
                    platformLog("agent", report.logEntry(settings.model))
                    _facts.value = report.facts
                }

                val assistantMessage = ChatMessage(MessageRole.ASSISTANT, chatResponse.reply, branchId = activeBranchId)
                _messages.value = _messages.value + assistantMessage
                store.append(chat.id, assistantMessage)
                _chats.value = store.chats()

                _state.value = ChatUiState.Success(chatResponse.reply)
                _isServerOnline.value = true
            } else {
                // Сервер объясняет отказ в теле ответа (переполнение контекста,
                // пустой ответ модели, сбой провайдера) — в чате показываем его
                // текст, а не только код статуса.
                val reason = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                val error = reason ?: "Ошибка сервера: ${response.status.value}"
                platformLog("agent", "[agent] Ошибка: $error")
                _state.value = ChatUiState.Error(error)
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            platformLog("agent", "[agent] Сервер недоступен: ${e.message ?: "нет соединения"}")
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Убирает сессию чата на сервере: сама история лежит на устройстве, а на
     * сервере по сессии хранится только сводка, и после удаления чата она не нужна.
     */
    private suspend fun clearServerSession(id: String) {
        runCatching { client.delete("$baseUrl/v1/chats/$id") }
            .onSuccess { response ->
                if (!response.status.isSuccess()) {
                    platformLog("agent", "[agent] Сессия $id на сервере не удалена: ${response.status.value}")
                }
            }
            .onFailure { e ->
                platformLog("agent", "[agent] Сессия $id на сервере не удалена: ${e.message ?: "нет соединения"}")
            }
    }

    /**
     * Заголовок чата — начало первого сообщения пользователя.
     */
    private fun titleFrom(message: String): String {
        val line = message.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= TITLE_LIMIT) line else line.take(TITLE_LIMIT - 1).trimEnd() + "…"
    }

    /**
     * Идентификатор чата и ветки диалога: 32 hex-символа. Идентификатор чата
     * уходит на сервер как идентификатор сессии, поэтому у чатов он разный,
     * а у одного чата — один и тот же всегда; идентификатор ветки — её метка
     * на сообщениях и в структуре веток.
     */
    private fun newHexId(): String = buildString {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private companion object {
        /** Заголовок чата до первого сообщения. */
        const val NEW_CHAT_TITLE = "Новый чат"

        /** Сколько символов первого сообщения попадает в заголовок списка. */
        const val TITLE_LIMIT = 40
    }
}
