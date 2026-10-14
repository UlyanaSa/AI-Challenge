package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ChatMode
import com.osvin.aichallenge.data.ConnectionReport
import com.osvin.aichallenge.data.ServerConnectionState
import com.osvin.aichallenge.data.ServerSettings

/**
 * Шторка приватного сервиса: адрес, ключ, модель, рамки запроса и режим чата.
 *
 * Это единственное место, где вводится ключ доступа ([ServerSettings.apiKey]). Ключ
 * показывается скрытым ([PasswordVisualTransformation]): шторка правится поверх чата,
 * и открытый ключ на экране — лишний повод показать его через плечо. Хранит его
 * только память сессии приложения (см. [ServerSettings]), поэтому «Сохранить» здесь
 * не пишет ничего на устройство, а переносит черновик в репозиторий.
 *
 * Переключатель режима — в этой же шторке, а не отдельной кнопкой в шапке: режим —
 * часть тех же настроек, и держать его врозь значило бы правки адреса и ключа
 * принимать, а выбор пути — где-то ещё. Текущий режим показан выбранным.
 *
 * Состояние проверки показывается словами вместе с причиной от сервиса ([ConnectionReport.message]):
 * «Недоступен» без причины не подсказывает, править адрес, ключ или имя модели.
 *
 * Своего состояния у шторки почти нет: черновик живёт на экране чата, иначе правки
 * терялись бы при перерисовке ленты. Исключение — числовые поля: их текст держится
 * здесь, потому что набранное «0.» ещё не число, и переводить его в черновик
 * было бы нечем, — а показывать вместо набранного округлённое значит не дать
 * ввести десятые.
 *
 * @param draft Черновик настроек: то, что уйдёт в репозиторий по «Сохранить».
 * @param connection Итог последней проверки подключения: состояние и причина словами.
 * @param onDraftChange Правка поля черновика.
 * @param onCheck Проверка подключения по черновику (живость без ключа, затем список моделей с ключом).
 * @param onSave Сохранение настроек целиком.
 */
@Composable
fun ServerSheet(
    draft: ServerSettings,
    connection: ConnectionReport,
    onDraftChange: (ServerSettings) -> Unit,
    onCheck: (ServerSettings) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Числовые поля: текст, набранный сейчас. Инициализируются из черновика один раз —
    // когда шторка открылась, — и живут до её закрытия
    var temperatureText by rememberSaveable { mutableStateOf(draft.temperature.toString()) }
    var maxTokensText by rememberSaveable { mutableStateOf(draft.maxTokens.toString()) }
    var contextWindowText by rememberSaveable { mutableStateOf(draft.contextWindow.toString()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Приватный сервис",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))
        serverText(
            "Адрес, ключ и рамки запроса хранятся на устройстве и переживают перезапуск: " +
                "браузер помнит их в localStorage, Android — в настройках приложения. " +
                "Ключ лежит там же и открывает весь сервис"
        )

        Spacer(Modifier.height(12.dp))
        // Режим: сегодняшний путь агента или приватный сервис. Выбор виден по выбранной
        // плашке, а не только по подписи, чтобы не путать, куда уйдёт следующее сообщение
        serverText("Режим чата")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.mode == ChatMode.CURRENT,
                onClick = { onDraftChange(draft.copy(mode = ChatMode.CURRENT)) },
                label = { Text("Сейчас") }
            )
            FilterChip(
                selected = draft.mode == ChatMode.SERVICE,
                onClick = { onDraftChange(draft.copy(mode = ChatMode.SERVICE)) },
                label = { Text("Сервис") }
            )
        }

        Spacer(Modifier.height(8.dp))
        ServerField(
            label = "Адрес сервера",
            hint = "например, http://10.0.2.2:8080",
            value = draft.baseUrl,
            onValueChange = { onDraftChange(draft.copy(baseUrl = it)) }
        )
        // Ключ показывается скрытым: шторка открывается поверх чата, и ключ на экране
        // виден всякому, кто смотрит на него
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            supportingText = { Text("Уходит заголовком X-API-Key только на адрес сервиса") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        ServerField(
            label = "Модель",
            hint = "имя модели сервиса, например qwen3:1.7b",
            value = draft.model,
            onValueChange = { onDraftChange(draft.copy(model = it)) }
        )
        // Числовые поля: текст держится отдельно, а в черновик уходит только разбор,
        // когда он получается. Иначе промежуточное «0.» не донести до черновика
        ServerField(
            label = "Temperature",
            hint = "температура генерации, 0…2",
            value = temperatureText,
            keyboard = KeyboardType.Decimal,
            onValueChange = {
                temperatureText = it
                it.toDoubleOrNull()?.let { parsed -> onDraftChange(draft.copy(temperature = parsed)) }
            }
        )
        ServerField(
            label = "Max tokens",
            hint = "бюджет ответа; сервис обрежет до своего потолка",
            value = maxTokensText,
            keyboard = KeyboardType.Number,
            onValueChange = {
                maxTokensText = it
                it.toIntOrNull()?.let { parsed -> onDraftChange(draft.copy(maxTokens = parsed)) }
            }
        )
        ServerField(
            label = "Context window",
            hint = "потолок окна; сервис обрежет до своего потолка",
            value = contextWindowText,
            keyboard = KeyboardType.Number,
            onValueChange = {
                contextWindowText = it
                it.toIntOrNull()?.let { parsed -> onDraftChange(draft.copy(contextWindow = parsed)) }
            }
        )

        Spacer(Modifier.height(8.dp))
        Button(onClick = { onCheck(draft) }) {
            Text("Проверить подключение")
        }

        // Состояние проверки словами вместе с причиной от сервиса: без причины
        // «Недоступен» не подсказал бы, что править — адрес, ключ или модель
        Spacer(Modifier.height(4.dp))
        serverText(connectionStateLabel(connection.state))
        Spacer(Modifier.height(4.dp))
        Text(
            text = connection.message,
            style = MaterialTheme.typography.labelSmall,
            color = if (connection.state == ServerConnectionState.CONNECTED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        // Модели сервиса: отсюда их и берут — «Модель недоступна» без списка не подсказывает,
        // какое имя вписать, а имя модели вводится руками и угадыванию не поддаётся
        if (connection.models.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            serverText("Модели сервиса: " + connection.models.joinToString(", "))
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onSave) {
            Text("Сохранить")
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Состояние подключения словами: ровно те четыре исхода, которые человек различает
 * в шторке. Проверка и её отсутствие названы отдельно — иначе до первого нажатия
 * шторка выглядела бы как отказ.
 */
private fun connectionStateLabel(state: ServerConnectionState): String = when (state) {
    ServerConnectionState.UNKNOWN -> "Проверка не выполнялась"
    ServerConnectionState.CHECKING -> "Проверка…"
    ServerConnectionState.CONNECTED -> "Подключён"
    ServerConnectionState.AUTH_ERROR -> "Ошибка авторизации"
    ServerConnectionState.UNAVAILABLE -> "Недоступен"
    ServerConnectionState.MODEL_MISSING -> "Модель недоступна"
}

/** Поле шторки сервиса: тот же стиль, что у полей профиля, — шторки правятся одинаково. */
@Composable
private fun ServerField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
}

/** Строка шторки сервиса: тот же стиль, что у подписей в шторке профиля. */
@Composable
private fun serverText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
