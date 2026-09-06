package com.osvin.aichallenge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osvin.aichallenge.repository.ChatRepository

/**
 * Главная точка входа в Compose Multiplatform приложение.
 * Настраивает зависимости и запускает основной экран.
 */
@Composable
@Preview
fun App() {
    // Конфигурация базового URL сервера
    // 10.0.2.2 используется эмулятором Android для обращения к localhost компьютера
    val baseUrl = "http://10.0.2.2:8080"
    
    // Инициализация репозитория и ViewModel через Lifecycle
    val repository = remember { ChatRepository(baseUrl) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository) }
    
    // Применяем тему Material 3 и отображаем экран чата
    MaterialTheme {
        ChatScreen(viewModel)
    }
}
