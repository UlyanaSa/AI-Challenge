package com.osvin.aichallenge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // На Web хранилище живёт в памяти вкладки: чаты не переживают перезагрузку страницы
    renderComposable(rootElementId = "root") {
        App()
    }
}
