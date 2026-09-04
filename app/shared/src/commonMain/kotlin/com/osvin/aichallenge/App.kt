package com.osvin.aichallenge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.osvin.aichallenge.repository.ChatRepository

@Composable
@Preview
fun App() {
    // 1. "http://10.0.2.2:8080" - стандартный адрес для эмулятора Android
    // 2. "http://localhost:8080" - для iOS симулятора или Desktop
    val baseUrl = "http://10.0.2.2:8080"
    
    val repository = remember { ChatRepository(baseUrl) }
    
    MaterialTheme {
        ChatScreen(repository)
    }
}