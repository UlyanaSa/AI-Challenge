package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Верхняя панель чата.
 * @param isOnline Статус подключения сервера.
 * @param onTitleClick Обработчик нажатия на заголовок (для ручной проверки связи).
 * @param onNewChat Создание нового чата с настройкой агента.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    isOnline: Boolean?,
    onTitleClick: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTitleClick() }
            ) {
                Text(
                    text = "DeepSeek AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                ConnectionStatus(isOnline)
            }
        },
        actions = {
            // Кнопка создания нового чата: возвращает к настройке агента
            IconButton(onClick = onNewChat) {
                Text("＋", fontSize = 20.sp)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}
