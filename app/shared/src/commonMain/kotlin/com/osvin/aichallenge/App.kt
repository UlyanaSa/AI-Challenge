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
import com.osvin.aichallenge.data.ChatStore
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.repository.ChatRepository

/**
 * Главная точка входа в Compose Multiplatform приложение.
 * Настраивает зависимости и переключает экраны: список чатов, чат и настройку агента.
 *
 * @param store Хранилище чатов. Android подставляет реализацию на Room, поэтому
 * чаты, их истории и сессии агента живут в БД устройства до удаления чата;
 * на остальных таргетах всё живёт в памяти процесса.
 */
@Composable
@Preview
fun App(store: ChatStore = InMemoryChatStore()) {
    // Адрес сервера агента: Android-эмулятор ходит через 10.0.2.2,
    // браузер и десктоп — через localhost
    val baseUrl = serverBaseUrl()

    // Инициализация репозитория и ViewModel через Lifecycle
    val repository = remember { ChatRepository(baseUrl, store) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository) }

    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val chatsLoaded by viewModel.chatsLoaded.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.Chats) }

    MaterialTheme {
        when (screen) {
            Screen.Chats -> ChatsScreen(
                chats = chats,
                loaded = chatsLoaded,
                onOpen = { id ->
                    viewModel.openChat(id)
                    screen = Screen.Chat
                },
                onNew = { screen = Screen.NewChat },
                onDelete = { id -> viewModel.deleteChat(id) }
            )

            Screen.NewChat -> NewChatScreen(
                initial = settings,
                onStart = { newSettings ->
                    viewModel.createChat(newSettings)
                    screen = Screen.Chat
                },
                onCancel = { screen = Screen.Chats }
            )

            // Переход в чат сразу после выбора: сообщения поднимаются из БД
            // асинхронно и появляются в списке, как только прочитаны
            Screen.Chat -> ChatScreen(
                viewModel = viewModel,
                title = activeChat?.title,
                onBack = { screen = Screen.Chats },
                onNewChat = { screen = Screen.NewChat }
            )
        }
    }
}

/** Экраны приложения. */
private enum class Screen { Chats, Chat, NewChat }
