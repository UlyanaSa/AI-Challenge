package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Панель ввода сообщения.
 * @param text Текущий текст в поле ввода.
 * @param onTextChange Вызывается при изменении текста.
 * @param onSend Вызывается при нажатии кнопки отправки.
 * @param onOpenCompare Вызывается при нажатии кнопки открытия шторки сравнения
 *                      способов решения.
 * @param isLoading Состояние ожидания ответа.
 * @param enabled Активна ли панель (зависит от статуса сервера).
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenCompare: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text("Сообщение...") },
                enabled = !isLoading && enabled,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.width(4.dp))

            // Кнопка открытия шторки сравнения способов решения
            IconButton(
                onClick = onOpenCompare,
                enabled = !isLoading && enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = "⚖",
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = onSend,
                enabled = !isLoading && text.isNotBlank() && enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("➤", fontSize = 18.sp)
                }
            }
        }
    }
}
