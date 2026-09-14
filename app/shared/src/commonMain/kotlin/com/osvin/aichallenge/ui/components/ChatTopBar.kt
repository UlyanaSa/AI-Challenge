package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Верхняя панель чата.
 * @param title Заголовок активного чата; null — чат ещё не выбран.
 * @param isOnline Статус подключения сервера.
 * @param onBack Возврат к списку чатов.
 * @param onTitleClick Обработчик нажатия на заголовок (для ручной проверки связи).
 * @param onNewChat Создание нового чата с настройкой агента.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String?,
    isOnline: Boolean?,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 20.sp)
            }
        },
        title = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTitleClick() }
            ) {
                Text(
                    text = title ?: "DeepSeek AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
