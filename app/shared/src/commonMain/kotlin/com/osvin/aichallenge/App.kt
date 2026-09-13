package com.osvin.aichallenge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osvin.aichallenge.repository.ChatRepository

/**
 * Главная точка входа в Compose Multiplatform приложение.
 * Настраивает зависимости, экран создания чата и основной экран.
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

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Стартуем с создания чата: агент настраивается до первого сообщения
    var isCreatingChat by remember { mutableStateOf(true) }
    val closeNewChat = { isCreatingChat = false }

    MaterialTheme {
        if (isCreatingChat) {
            NewChatScreen(
                initial = settings,
                onStart = { newSettings ->
                    viewModel.startNewChat(newSettings)
                    isCreatingChat = false
                },
                onCancel = if (messages.isEmpty()) null else closeNewChat
            )
        } else {
            ChatScreen(
                viewModel = viewModel,
                onNewChat = { isCreatingChat = true }
            )
        }
    }
}
