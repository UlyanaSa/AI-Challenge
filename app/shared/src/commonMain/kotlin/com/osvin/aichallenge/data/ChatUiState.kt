package com.osvin.aichallenge.data

/**
 * Возможные состояния пользовательского интерфейса чата.
 */
sealed interface ChatUiState {
    /** Начальное состояние */
    object Idle : ChatUiState
    
    /** Процесс отправки сообщения и ожидания ответа */
    object Loading : ChatUiState
    
    /** Успешное получение ответа */
    data class Success(val reply: String) : ChatUiState
    
    /** Ошибка при взаимодействии с сервером */
    data class Error(val message: String) : ChatUiState
}
