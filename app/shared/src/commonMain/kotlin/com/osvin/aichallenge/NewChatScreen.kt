package com.osvin.aichallenge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.ui.components.GenerationSettingsForm

/**
 * Экран создания чата: агент настраивается до первого сообщения.
 *
 * @param initial Текущие настройки агента для предзаполнения формы.
 * @param onStart Запуск чата с настроенным агентом.
 * @param onCancel Возврат к текущему чату; null — возвращаться некуда (чатов ещё нет).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    initial: GenerationSettings,
    onStart: (GenerationSettings) -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Новый чат",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Настройте агента перед началом",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Text("←", fontSize = 20.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        GenerationSettingsForm(
            initial = initial,
            applyLabel = "Начать чат",
            onApply = onStart,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
