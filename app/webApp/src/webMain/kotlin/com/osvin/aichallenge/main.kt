package com.osvin.aichallenge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val repository = ChatRepository(
        baseUrl = "http://localhost:8080"
    )

    renderComposable(rootElementId = "root") {
        MaterialTheme {
            ChatScreen(repository = repository)
        }
    }
}