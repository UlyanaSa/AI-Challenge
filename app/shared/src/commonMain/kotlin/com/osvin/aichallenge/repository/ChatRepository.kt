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
     */
    suspend fun createChat(): Chat {
        val chat = Chat(id = newChatId(), title = NEW_CHAT_TITLE)
        store.create(chat)
        _activeChat.value = chat
        _messages.value = emptyList()
        _state.value = ChatUiState.Idle
        _chats.value = store.chats()
        return chat
    }

    /**
     * Открытие чата: поднимаем его сообщения из БД.
     * Дальше запросы уходят с идентификатором этого чата, поэтому и сессия агента,
     * и сводка истории на сервере — те же, что были в прошлый раз.
     */
    suspend fun openChat(id: String) {
        val chat = store.chat(id) ?: return
        _activeChat.value = chat
        _messages.value = store.messages(id)
        _state.value = ChatUiState.Idle
    }

    /**
     * Удаление чата вместе с историей и сессией агента.
     * На сервере сессию тоже чистим — иначе сводки копились бы в его памяти;
     * если сервер недоступен, чат на устройстве всё равно удалён.
     */
    suspend fun deleteChat(id: String) {
        store.delete(id)
        if (_activeChat.value?.id == id) {
            _activeChat.value = null
            _messages.value = emptyList()
            _state.value = ChatUiState.Idle
        }
        _chats.value = store.chats()
        clearServerSession(id)
    }

    /**
     * Отправка сообщения нейросети в активный чат.
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
                        history = _messages.value.takeIf { it.isNotEmpty() },
                        sessionId = chat.id
                    )
                )
            }

            // Вопрос пользователя попадает в диалог в любом случае: и при ответе,
            // и при ошибке — иначе при пустом ответе модели он пропадает с экрана.
            val userMessage = ChatMessage(MessageRole.USER, message)
            _messages.value += userMessage
            store.append(chat.id, userMessage)

            // Заголовок чата берём из первого вопроса: в списке чатов видно,
            // о чём каждый диалог.
            if (_messages.value.count { it.role == MessageRole.USER } == 1) {
                val title = titleFrom(message)
                store.retitle(chat.id, title)
                _activeChat.value = chat.copy(title = title)
            }
            _chats.value = store.chats()

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()

                // Отчёт агента печатаем в лог платформы (на Android — в logcat):
                // строки те же, что агент пишет на сервере, но видны рядом с приложением.
                chatResponse.tokens?.let { report ->
                    platformLog("agent", report.logEntry(settings.model))
                }

                val assistantMessage = ChatMessage(MessageRole.ASSISTANT, chatResponse.reply)
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
     * Идентификатор чата: 32 hex-символа. Он же уходит на сервер как идентификатор
     * сессии, поэтому у чатов он разный, а у одного чата — один и тот же всегда.
     */
    private fun newChatId(): String = buildString {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private companion object {
        /** Заголовок чата до первого сообщения. */
        const val NEW_CHAT_TITLE = "Новый чат"

        /** Сколько символов первого сообщения попадает в заголовок списка. */
        const val TITLE_LIMIT = 40
    }
}
