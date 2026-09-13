package com.osvin.aichallenge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osvin.aichallenge.data.ChatHistoryStore
import com.osvin.aichallenge.data.InMemoryChatHistoryStore
import com.osvin.aichallenge.repository.ChatRepository

/**
 * Главная точка входа в Compose Multiplatform приложение.
 * Настраивает зависимости, экран создания чата и основной экран.
 *
 * @param historyStore Хранилище истории диалога. Android подставляет реализацию на Room,
 * поэтому диалог продолжается после перезапуска приложения; на остальных таргетах
 * история живёт в памяти процесса.
 */
@Composable
@Preview
fun App(historyStore: ChatHistoryStore = InMemoryChatHistoryStore()) {
    // Конфигурация базового URL сервера
    // 10.0.2.2 используется эмулятором Android для обращения к localhost компьютера
    val baseUrl = "http://10.0.2.2:8080"

    // Инициализация репозитория и ViewModel через Lifecycle
    val repository = remember { ChatRepository(baseUrl, historyStore) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository) }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val historyLoaded by viewModel.historyLoaded.collectAsStateWithLifecycle()

    // null — ещё не знаем, есть ли сохранённый диалог
    var isCreatingChat by remember { mutableStateOf<Boolean?>(null) }
    val closeNewChat = { isCreatingChat = false }

    // Первый экран выбираем один раз, когда история загружена:
    // есть сохранённые сообщения — продолжаем диалог, иначе настраиваем агента
    LaunchedEffect(historyLoaded) {
        if (isCreatingChat == null && historyLoaded) {
            isCreatingChat = messages.isEmpty()
        }
    }

    MaterialTheme {
        if (isCreatingChat == true) {
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
