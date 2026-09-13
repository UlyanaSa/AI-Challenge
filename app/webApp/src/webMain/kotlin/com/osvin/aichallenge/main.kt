package com.osvin.aichallenge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osvin.aichallenge.data.InMemoryChatHistoryStore
import com.osvin.aichallenge.repository.ChatRepository

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val repository = ChatRepository(
        baseUrl = "http://localhost:8080",
        historyStore = InMemoryChatHistoryStore()
    )

    renderComposable(rootElementId = "root") {
        MaterialTheme {
            ChatScreen(
                viewModel = viewModel { ChatViewModel(repository) },
                onNewChat = {}
            )
        }
    }
}
