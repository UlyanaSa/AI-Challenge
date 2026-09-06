package com.osvin.aichallenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием экрана чата.
 * Поддерживает жизненный цикл и хранит данные при изменениях конфигурации.
 */
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    
    // Экспонируем потоки данных из репозитория для UI
    val messages: StateFlow<List<ChatMessage>> = repository.messages
    val uiState: StateFlow<ChatUiState> = repository.state
    val isOnline: StateFlow<Boolean?> = repository.isServerOnline

    // Текущие настройки генерации из шторки настроек
    private val _settings = MutableStateFlow(GenerationSettings())
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    init {
        // Проверяем связь при создании ViewModel
        checkHealth()
    }

    /**
     * Применение настроек генерации из шторки настроек.
     */
    fun updateSettings(settings: GenerationSettings) {
        _settings.value = settings
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
     * Отправка сообщения с текущими настройками генерации.
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            repository.sendMessage(text, _settings.value)
        }
    }

    /**
     * Очистка истории переписки.
     */
    fun clearHistory() {
        repository.clearHistory()
    }
}
