package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Верхняя панель чата.
 *
 * Переключателя веток здесь нет: варианты продолжения живут на самих сообщениях
 * («‹ 2/3 ›» в [ChatBubble]), поэтому шапка остаётся про чат и связь.
 *
 * @param title Заголовок активного чата; null — чат ещё не выбран.
 * @param isOnline Статус подключения сервера.
 * @param onBack Возврат к списку чатов.
 * @param onTitleClick Обработчик нажатия на заголовок (для ручной проверки связи).
 * @param onNewChat Создание нового чата с настройкой агента.
 * @param onMemory Открытие шторки памяти чата.
 * @param onProfile Открытие шторки профиля пользователя.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String?,
    isOnline: Boolean?,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onNewChat: () -> Unit,
    onMemory: () -> Unit,
    onProfile: () -> Unit,
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
            // Память чата: настройки памяти открываются шторкой, поэтому лента
            // сообщений не занята панелью
            TextButton(onClick = onMemory) {
                Text("Память", style = MaterialTheme.typography.labelLarge)
            }
            // Профиль пользователя: та же шторка, что и память, — обе настройки
            // агента открываются поверх чата, а не занимают ленту сообщений
            TextButton(onClick = onProfile) {
                Text("Профиль", style = MaterialTheme.typography.labelLarge)
            }
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
