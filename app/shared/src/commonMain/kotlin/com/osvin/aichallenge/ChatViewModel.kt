package com.osvin.aichallenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием чатов.
 * Поддерживает жизненный цикл и хранит данные при изменениях конфигурации.
 */
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    // Список чатов и активный чат: у каждого чата своя история и своя сессия агента
    val chats: StateFlow<List<Chat>> = repository.chats
    val activeChat: StateFlow<Chat?> = repository.activeChat

    // Экспонируем потоки данных из репозитория для UI
    val messages: StateFlow<List<ChatMessage>> = repository.messages
    val uiState: StateFlow<ChatUiState> = repository.state
    val isOnline: StateFlow<Boolean?> = repository.isServerOnline

    // Текущие настройки генерации из шторки настроек
    private val _settings = MutableStateFlow(GenerationSettings())
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    // Признак того, что список сохранённых чатов уже загружен из хранилища
    private val _chatsLoaded = MutableStateFlow(false)
    val chatsLoaded: StateFlow<Boolean> = _chatsLoaded.asStateFlow()

    init {
        // Восстанавливаем чаты, затем проверяем связь
        viewModelScope.launch {
            repository.loadChats()
            _chatsLoaded.value = true
        }
        checkHealth()
    }

    /**
     * Запуск проверки доступности сервера.
     */
    fun checkHealth() {
        viewModelScope.launch {
            repository.checkHealth()
        }
    }

    /**
     * Открытие чата из списка: продолжаем его историю и его сессию агента.
     */
    fun openChat(id: String) {
        viewModelScope.launch {
            repository.openChat(id)
        }
    }

    /**
     * Создание нового чата с настроенным агентом и переход в него.
     */
    fun createChat(settings: GenerationSettings) {
        _settings.value = settings
        viewModelScope.launch {
            repository.createChat()
        }
    }

    /**
     * Удаление чата: история на устройстве и сессия агента на сервере.
     */
    fun deleteChat(id: String) {
        viewModelScope.launch {
            repository.deleteChat(id)
        }
    }

    /**
     * Отправка сообщения с текущими настройками генерации.
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            repository.sendMessage(text, _settings.value)
        }
    }
}
