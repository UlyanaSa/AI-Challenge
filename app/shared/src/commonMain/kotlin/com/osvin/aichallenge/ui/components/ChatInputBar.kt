package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Панель ввода сообщения.
 *
 * Отправка — и кнопкой, и клавишей: в браузере Enter отправляет сообщение, а Shift+Enter
 * переносит строку. Без обработчика клавиш Enter печатал перенос: поле многострочное
 * ([MAX_LINES] строк), и в браузере нажатие на Enter не отправляло ничего, хотя в чате
 * это первое, что пробует человек.
 *
 * @param text Текущий текст в поле ввода.
 * @param onTextChange Вызывается при изменении текста.
 * @param onSend Вызывается при нажатии кнопки отправки.
 * @param isLoading Состояние ожидания ответа.
 * @param enabled Активна ли панель (зависит от статуса сервера).
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    // Отправлять можно только непустое сообщение и только когда панель активна:
    // правило одно на кнопку, IME-действие и клавишу — иначе Enter отправлял бы
    // пустую строку или второй запрос поверх идущего
    val canSend = !isLoading && enabled && text.isNotBlank()

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Поле ввода и лента сообщений одной ширины: на широком окне
                    // строка не растягивается на весь экран и остаётся читаемой
                    .widthIn(max = CONTENT_MAX_WIDTH)
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
                        .clip(RoundedCornerShape(24.dp))
                        // Enter отправляет, Shift+Enter переносит строку: обработчик стоит
                        // до поля, поэтому клавиша не доходит до вставки переноса
                        .onPreviewKeyEvent { event ->
                            val enter = event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            if (enter && !event.isShiftPressed) {
                                if (canSend) {
                                    onSend()
                                }
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("Напишите сообщение...") },
                    enabled = !isLoading && enabled,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    // Действие клавиатуры телефона — та же отправка: на Android и iOS
                    // аппаратного Enter нет, и без него сообщение отправлялось бы
                    // только кнопкой
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (canSend) onSend() }
                    ),
                    maxLines = MAX_LINES
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onSend,
                    enabled = canSend,
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
                        SendIcon(color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

/** Сколько строк растёт поле ввода до появления прокрутки. */
private const val MAX_LINES = 4

/** Предельная ширина ленты сообщений и поля ввода на широком окне. */
internal val CONTENT_MAX_WIDTH = 820.dp

