package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.UserProfile

/**
 * Шторка профиля пользователя: что о нём объявлено и правка этих полей.
 *
 * Это не память: профиль не извлекается из диалога и не сливается с записями —
 * что пользователь написал, то и уезжает на сервер целиком, одним профилем.
 * Поэтому здесь нет ни типов, ни удаления записей: профиль один, и его поля
 * не зависят друг от друга.
 *
 * Своего состояния у шторки нет: черновик живёт на экране чата, иначе правки
 * терялись бы при перерисовке ленты сообщений.
 *
 * @param draft Черновик: то, что набрано сейчас и уйдёт на сервер по «Сохранить».
 * @param saved Профиль с сервера; null — профиль ещё не загружен или сервер недоступен.
 * @param error Отказ сервера на чтение или запись профиля; null — отказа не было.
 * @param onDraftChange Правка поля черновика.
 * @param onSave Сохранение профиля целиком.
 */
@Composable
fun ProfileSheet(
    draft: UserProfile,
    saved: UserProfile?,
    error: String?,
    onDraftChange: (UserProfile) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Профиль пользователя",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))
        profileText("Это объявленные предпочтения: их учитывает каждый ответ, независимо от стратегии контекста")

        // Поля идут в том же порядке, что и в блоке профиля для промпта: так шторка
        // и текст, который видит модель, читаются одинаково
        ProfileField(
            label = "кто",
            hint = "кто вы и чем занимаетесь",
            value = draft.role,
            onValueChange = { onDraftChange(draft.copy(role = it)) }
        )
        ProfileField(
            label = "стек",
            hint = "языки, платформы и инструменты",
            value = draft.stack,
            onValueChange = { onDraftChange(draft.copy(stack = it)) }
        )
        ProfileField(
            label = "стиль",
            hint = "как отвечать: коротко, по делу, с примерами",
            value = draft.style,
            onValueChange = { onDraftChange(draft.copy(style = it)) }
        )
        ProfileField(
            label = "формат",
            hint = "как оформлять: шаги, код, таблицы",
            value = draft.format,
            onValueChange = { onDraftChange(draft.copy(format = it)) }
        )
        ProfileField(
            label = "ограничения",
            hint = "чего не делать в ответах",
            value = draft.constraints,
            onValueChange = { onDraftChange(draft.copy(constraints = it)) }
        )
        // Настройка с наблюдаемым следом: эта строка печатается в конце каждого ответа
        ProfileField(
            label = "в конце каждого ответа",
            hint = "строка, которую ассистент пишет отдельной строкой в конце",
            value = draft.signOff,
            onValueChange = { onDraftChange(draft.copy(signOff = it)) }
        )

        // Кнопка доступна, пока есть что сохранять: сохранять неизменённый профиль нечем
        Button(
            onClick = onSave,
            enabled = draft != saved
        ) {
            Text("Сохранить")
        }

        // Строка состояния обязательна: без неё «Сохранить» с погашенной кнопкой
        // выглядел бы сломанным, а несохранённые правки — потерянными молча
        Spacer(Modifier.height(8.dp))
        profileText(
            if (draft == saved) "Профиль сохранён на сервере" else "Есть несохранённые правки"
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Поле профиля: подпись та же, что в блоке для промпта, поэтому шторка и модель видят одно и то же. */
@Composable
private fun ProfileField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(hint) },
        maxLines = 3
    )
}

/** Строка шторки профиля: тот же стиль, что у подписей в шторке памяти. */
@Composable
private fun profileText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
