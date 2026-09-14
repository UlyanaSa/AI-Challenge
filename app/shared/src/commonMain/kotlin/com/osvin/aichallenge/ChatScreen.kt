package com.osvin.aichallenge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.ui.components.*

/**
 * Экран одного чата: история активного чата и обмен сообщениями с агентом.
 * Соединяет ViewModel с пользовательским интерфейсом.
 *
 * @param title Заголовок активного чата для верхней панели.
 * @param onBack Возврат к списку чатов.
 * @param onNewChat Переход к экрану создания нового чата с настройкой агента.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    title: String?,
    onBack: () -> Unit,
    onNewChat: () -> Unit
) {
    // Подписка на состояния из ViewModel
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    // Локальное состояние ввода
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Автопрокрутка к последнему сообщению при обновлении списка
    LaunchedEffect(messages.size, uiState) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            ChatTopBar(
                title = title,
                isOnline = isOnline,
                onBack = onBack,
                onTitleClick = { viewModel.checkHealth() },
                onNewChat = onNewChat
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                isLoading = uiState is ChatUiState.Loading,
                enabled = isOnline == true
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Пустой чат: подсказка вместо пустого экрана
                if (messages.isEmpty() && uiState !is ChatUiState.Loading) {
                    item {
                        Text(
                            text = "Напишите первое сообщение — у этого чата своя история " +
                                "и своя сессия агента, отдельная от остальных чатов.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp)
                        )
                    }
                }

                // Список сообщений
                items(messages) { message ->
                    ChatBubble(message = message)
                }
                
                // Отображение индикаторов загрузки или ошибок
                when (uiState) {
                    is ChatUiState.Loading -> {
                        item { TypingIndicator() }
                    }
                    is ChatUiState.Error -> {
                        item { ErrorMessage((uiState as ChatUiState.Error).message) }
                    }
                    else -> {}
                }
            }
        }
    }
}
