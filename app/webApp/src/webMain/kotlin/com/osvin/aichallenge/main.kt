package com.osvin.aichallenge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

/**
 * Точка входа web-приложения.
 *
 * [ComposeViewport] — компоуз-точка входа для web: она ждёт готовности DOM,
 * находит контейнер по id (`root`) и разворачивает в нём canvas композиции,
 * подгоняя его размер под размер контейнера. Контейнер должен существовать
 * в index.html, иначе точка входа падает с ошибкой.
 *
 * Хранилище живёт в памяти вкладки: чаты не переживают перезагрузку страницы.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "root") {
        App()
    }
}
