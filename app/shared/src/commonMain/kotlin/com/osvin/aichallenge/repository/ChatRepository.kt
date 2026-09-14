package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
import com.osvin.aichallenge.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

/**
 * Репозиторий для работы с сетевым API чата.
 * Отвечает за отправку сообщений и получение ответов от DeepSeek.
 */
class ChatRepository(
    private val baseUrl: String,
    private val historyStore: ChatHistoryStore,
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
     * Загрузка сохранённой истории диалога (например, после перезапуска приложения).
     */
    suspend fun loadHistory() {
        _messages.value = historyStore.load()
    }

    /**
     * Отправка сообщения нейросети.
     * @param message Текст сообщения пользователя.
     * @param settings Настройки генерации из шторки настроек.
     */
    suspend fun sendMessage(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return
        
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
                        history = _messages.value.takeIf { it.isNotEmpty() }
                    )
                )
            }

            // Вопрос пользователя попадает в диалог в любом случае: и при ответе,
            // и при ошибке — иначе при пустом ответе модели он пропадает с экрана.
            val userMessage = ChatMessage(MessageRole.USER, message)
            _messages.value += userMessage
            historyStore.append(userMessage)

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()

                // Отчёт агента печатаем в лог платформы (на Android — в logcat):
                // строки те же, что агент пишет на сервере, но видны рядом с приложением.
                chatResponse.tokens?.let { report ->
                    platformLog("agent", report.logEntry(settings.model))
                }

                val assistantMessage = ChatMessage(MessageRole.ASSISTANT, chatResponse.reply)
                _messages.value = _messages.value + assistantMessage
                historyStore.append(assistantMessage)

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
     * Очистка истории текущего диалога: и в памяти, и в хранилище.
     */
    suspend fun clearHistory() {
        _messages.value = emptyList()
        _state.value = ChatUiState.Idle
        historyStore.clear()
    }
}
