package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
     * @param settings Настройки вариантов ответа из шторки ⚖.
     */
    suspend fun sendMessage(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return

        _state.value = ChatUiState.Loading

        try {
            // Варианты не выбраны — обычный чат: один прямой ответ
            if (settings.variants.isEmpty()) {
                val reply = requestPlainChat(message)
                appendMessages(
                    listOf(ChatMessage(MessageRole.USER, message)) +
                        ChatMessage(MessageRole.ASSISTANT, reply)
                )
                return
            }

            // Добавляем сообщение пользователя сразу, затем каждый вариант
            // приходит отдельным сообщением по мере готовности
            appendMessages(listOf(ChatMessage(MessageRole.USER, message)))

            val entries = mutableListOf<AnalysisEntry>()
            val solutions = mutableListOf<SolutionRef>()

            settings.variants.forEach { variant ->
                val response = requestVariant(message, variant.key)
                appendMessages(
                    listOf(
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = response.content,
                            variantTitle = response.title,
                            metrics = response.metrics
                        )
                    )
                )
                entries += AnalysisEntry(response.title, response.metrics)
                solutions += SolutionRef(response.title, response.content)
            }

            // В режиме «все последовательно» при двух и более вариантах —
            // отдельное сообщение-анализ: таблица характеристик и вердикт судьи
            if (settings.runMode == RunMode.SEQUENTIAL && settings.variants.size >= 2) {
                val verdict = requestAnalysis(message, solutions)
                appendMessages(
                    listOf(
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = verdict,
                            analysisEntries = entries
                        )
                    )
                )
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Обычный запрос к чат-серверу.
     */
    private suspend fun requestPlainChat(message: String): String {
        val response = client.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = message))
        }
        ensureSuccess(response)
        return response.body<ChatResponse>().reply
    }

    /**
     * Запрос на запуск одного варианта ответа.
     */
    private suspend fun requestVariant(message: String, variant: String): VariantResponse {
        val response = client.post("$baseUrl/v1/chat/variant") {
            contentType(ContentType.Application.Json)
            setBody(VariantRequest(message = message, variant = variant))
        }
        ensureSuccess(response)
        return response.body<VariantResponse>()
    }

    /**
     * Запрос вердикта судьи по решениям одной задачи.
     */
    private suspend fun requestAnalysis(question: String, solutions: List<SolutionRef>): String {
        val response = client.post("$baseUrl/v1/chat/analyze") {
            contentType(ContentType.Application.Json)
            setBody(AnalysisRequest(question = question, solutions = solutions))
        }
        ensureSuccess(response)
        return response.body<AnalysisResponse>().verdict
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Ошибка сервера: ${response.status.value}")
        }
    }

    /**
     * Добавление сообщений в историю одним обновлением состояния.
     */
    private fun appendMessages(newMessages: List<ChatMessage>) {
        _messages.value = _messages.value + newMessages
        val last = newMessages.lastOrNull()?.content
        if (last != null) {
            _state.value = ChatUiState.Success(last)
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
