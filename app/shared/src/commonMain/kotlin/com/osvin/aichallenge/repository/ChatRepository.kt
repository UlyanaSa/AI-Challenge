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

class ChatRepository(private val baseUrl: String) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _state = MutableStateFlow<ChatState>(ChatState.Idle)
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()
    
    suspend fun checkHealth() {
        try {
            // Тестовый запрос к HTTPS ресурсу
            val response = client.get("https://www.google.com")
            println("ChatRepository: Google check status: ${response.status}")
            
            val realResponse = client.get("$baseUrl/v1/health")
            _isServerOnline.value = realResponse.status.isSuccess()
        } catch (e: Exception) {
            println("ChatRepository: Health check failed: ${e.message}")
            e.printStackTrace()
            _isServerOnline.value = false
        }
    }

    suspend fun sendMessage(message: String) {
        _state.value = ChatState.Loading
        
        try {
            val response = client.post("$baseUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message, _messages.value))
            }
            
            _isServerOnline.value = response.status.isSuccess()

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()
                _messages.value = _messages.value + 
                    ChatMessage("user", message) +
                    ChatMessage("assistant", chatResponse.reply)
                _state.value = ChatState.Success(chatResponse)
            } else {
                _state.value = ChatState.Error("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            _state.value = ChatState.Error(e.message ?: "Unknown error")
        }
    }
    
    fun clearHistory() {
        _messages.value = emptyList()
        _state.value = ChatState.Idle
    }
}