package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
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
                        maxTokens = settings.maxTokens,
                        stop = settings.stopWords.ifEmpty { null },
                        runs = settings.runs,
                        format = settings.responseFormat.key,
                        temperature = settings.temperature
                    )
                )
            }
            
            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()
                
                // Добавляем сообщение пользователя и ответ ассистента в историю
                _messages.value = _messages.value + 
                    ChatMessage(MessageRole.USER, message) +
                    ChatMessage(MessageRole.ASSISTANT, chatResponse.reply)
                
                _state.value = ChatUiState.Success(chatResponse.reply)
                _isServerOnline.value = true
            } else {
                _state.value = ChatUiState.Error("Ошибка сервера: ${response.status.value}")
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }
    
    /**
     * Очистка истории текущего диалога.
     */
    fun clearHistory() {
        _messages.value = emptyList()
        _state.value = ChatUiState.Idle
    }
}
