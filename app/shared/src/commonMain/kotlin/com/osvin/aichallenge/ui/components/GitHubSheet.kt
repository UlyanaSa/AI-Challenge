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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.GitHubConnection
import com.osvin.aichallenge.data.GitHubTool
import com.osvin.aichallenge.data.GitHubToolArgument

/**
 * Шторка GitHub: подключение к MCP-серверу инструментов, доступ к GitHub и вызов
 * инструмента человеком.
 *
 * Это не память и не профиль: подключение — свойство сервера приложения, общее для всех
 * чатов, а инструменты приходят от отдельного MCP-сервера, который ходит в GitHub REST API
 * от имени владельца доступа. Поэтому здесь нет ни разделов чата, ни истории: шторка
 * показывает одно соединение и его инструменты.
 *
 * Своего состояния подключения у шторки нет: снимок приходит с сервера, и после
 * подключения, отключения и вызова видно то, что ответил сервер. Поля для токена здесь
 * тоже нет намеренно: доступ к GitHub сервер ищет сам на той машине, где запущен
 * (окружение, файл, связка ключей, `gh`, `git credential`), поэтому набирать на клиенте
 * нечего, а сохранённый на устройстве секрет пережил бы отзыв токена и врал бы о доступе.
 * Вместо токена шторка называет результат поиска словами: кто доступен, какие у доступа
 * права и откуда он взят, — а если доступа нет, то почему и что сделать.
 *
 * @param connection Снимок подключения; null — снимка ещё не было или сервер недоступен.
 * @param error Причина последнего отказа; null — отказа не было.
 * @param onConnect Подключение. Повторное нажатие после «доступа нет» не лишнее:
 *        отсутствие доступа сервер не кэширует, поэтому токен, подложенный на машине
 *        за это время, подхватится без перезапуска приложения.
 * @param onDisconnect Отключение от MCP-сервера.
 * @param onCall Вызов инструмента: имя и аргументы строкой JSON, как их набрал человек.
 */
@Composable
fun GitHubSheet(
    connection: GitHubConnection?,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCall: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "GitHub",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))
        githubText(
            "Инструменты приходят от отдельного MCP-сервера: он ходит в GitHub REST API " +
                "от имени владельца доступа, найденного на машине сервера. Ассистент вызывает " +
                "их сам, когда просьба этого требует; здесь их можно вызвать руками " +
                "и увидеть тот же ответ."
        )

        val snapshot = connection
        if (snapshot == null) {
            // Отдельная строка вместо пустого списка: «инструментов нет» и «снимок не
            // пришёл» — разные вещи, и по пустому экрану их не различить
            Spacer(Modifier.height(8.dp))
            githubText("Состояние подключения не прочитано: сервер не ответил")
        } else if (snapshot.connected) {
            val server = snapshot.server ?: "имя сервера не названо"
            val version = snapshot.version ?: "версия не названа"
            Spacer(Modifier.height(8.dp))
            githubText("Подключено: $server $version")

            // Доступ — первое, что нужно человеку после подключения: он решает, будет ли
            // инструмент ходить в GitHub, и потому идёт раньше списка и кнопки отключения
            Spacer(Modifier.height(8.dp))
            githubAccess(connection = snapshot, onConnect = onConnect)

            Spacer(Modifier.height(8.dp))
            // Отключение — не удаление инструментов с экрана: список остаётся от
            // последнего снимка, поэтому состояние подключения сказано словами
            OutlinedButton(onClick = onDisconnect) {
                Text("Отключить")
            }

            Spacer(Modifier.height(12.dp))
            if (snapshot.tools.isEmpty()) {
                githubText("Сервер подключён, но инструментов не назвал")
            } else {
                githubText("Инструменты сервера")
                snapshot.tools.forEach { tool ->
                    GitHubToolSection(tool = tool, onCall = onCall)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            githubText("Не подключено: инструменты появятся здесь после подключения")
            Spacer(Modifier.height(8.dp))
            // Кнопка без поля ввода: набирать нечего — доступ к GitHub сервер ищет сам
            // на своей машине, и подключение здесь означает лишь рукопожатие с ним
            Button(onClick = onConnect) {
                Text("Подключить")
            }
        }

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

/**
 * Раздел инструмента: имя и описание, объявленные аргументы и поле вызова.
 *
 * Аргументы печатаются объявлением сервера, а не разбором схемы на клиенте: имя, тип,
 * обязательность, пояснение и допустимые значения уже пришли готовыми, и второй разбор
 * JSON-схемы разошёлся бы с тем, что сервер обещает модели. Аргументы вводятся строкой
 * JSON, а не полями по одному: так вызов выглядит так же, как его присылает модель,
 * и подстановка значений с проверкой типа не нужна.
 *
 * Набранные аргументы — обычное состояние на инструмент с ключом по имени: список
 * инструментов переспрашивается при переподключении, и без ключа строка переехала бы
 * на чужой инструмент. Переживать пересоздание экрана ей незачем: она уходит вызовом
 * или пропадает.
 */
@Composable
private fun GitHubToolSection(
    tool: GitHubTool,
    onCall: (String, String) -> Unit
) {
    var arguments by remember(tool.name) { mutableStateOf("") }

    Spacer(Modifier.height(12.dp))
    Text(
        text = tool.name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (tool.description != null) {
        githubText(tool.description)
    }
    if (tool.arguments.isEmpty()) {
        githubText("аргументов нет: инструмент вызывается без них")
    } else {
        tool.arguments.forEach { argument ->
            githubText(argument.label())
        }
    }

    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = arguments,
        onValueChange = { arguments = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Аргументы (JSON)") },
        supportingText = { Text(tool.argumentsHint()) },
        maxLines = 3
    )
    Button(onClick = { onCall(tool.name, arguments) }) {
        Text("Выполнить")
    }
}

/** Строка шторки GitHub: тот же стиль, что у остальных подписей блока. */
@Composable
private fun githubText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Состояние доступа к GitHub: словами о том, кто доступен, или о том, почему его нет.
 *
 * Токена в этих строках нет и быть не может: снимок его не несёт, а [GitHubConnection.source]
 * называет только место, откуда доступ взят, — по этой пометке человек сверяет, тем ли
 * доступом он ходит. Права печатаются по заголовку (`X-OAuth-Scopes`): если заголовка не
 * было, сказать «прав нет» нельзя — так ведут себя fine-grained токены, и пустой список
 * прав означал бы здесь совсем другое ([GitHubConnection.scopesReported]).
 *
 * Повторное подключение после «доступа нет» — это не «повторить запрос», а «прочитать
 * доступ заново»: отсутствие доступа сервер не кэширует, поэтому токен, подложенный
 * на машине за это время, подхватится без перезапуска приложения.
 */
@Composable
private fun githubAccess(connection: GitHubConnection, onConnect: () -> Unit) {
    if (!connection.authorized) {
        githubText("Доступ к GitHub не найден")
        // Подсказка — часть состояния, а не ошибка: сервер сам объясняет, где искал
        // доступ и что положить, чтобы он появился; выдумывать это на клиенте нечем
        connection.hint?.let { githubText(it) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onConnect) {
            Text("Проверить снова")
        }
        return
    }

    // Логин приписан к доступу, а не к серверу: он подтверждает, чей токен найден,
    // и без него строка «доступ есть» не отвечала бы на вопрос «а чей именно»
    val who = connection.login ?: "имя не сообщено"
    val rights = if (connection.scopesReported) {
        // Пустой заголовок со списком прав — это «прав нет», а не «права не сообщены»:
        // после двоеточия иначе осталась бы пустота, и строка читалась бы оборванной
        connection.scopes.joinToString(separator = ", ").ifEmpty { "прав нет" }
    } else {
        "права не сообщены"
    }
    githubText("Доступен как $who · права: $rights")
    connection.source?.let { githubText("источник: $it") }
}

/**
 * Строка аргумента инструмента: имя, тип, обязательность, пояснение и допустимые значения —
 * ровно то, что о нём сказал сервер. Отсутствующий тип печатается словами, а не пропуском:
 * на месте пустоты читалось бы, что тип не важен.
 */
private fun GitHubToolArgument.label(): String {
    val head = "$name (${type ?: "тип не назван"}, ${if (required) "обязательный" else "необязательный"})"
    val details = listOfNotNull(
        description,
        values.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = " | ", prefix = "значения: ")
    )
    return if (details.isEmpty()) head else "$head — ${details.joinToString("; ")}"
}

/**
 * Подсказка к полю аргументов: пример собирается из объявления самого инструмента, а не
 * пишется литералом. Литерал рано или поздно назвал бы поле, которого у инструмента нет,
 * и человек набирал бы по подсказке заведомо неверный вызов.
 */
private fun GitHubTool.argumentsHint(): String {
    val sample = arguments.firstOrNull()
        ?: return "Аргументов у инструмента нет: поле можно оставить пустым"
    val value = sample.values.firstOrNull()?.let { "\"$it\"" }
        ?: when (sample.type) {
            "integer", "number" -> "0"
            "boolean" -> "true"
            // Тип не назван или это строка: подставляется слово-заглушка в кавычках,
            // потому что без кавычек пример строки выглядел бы нерабочим JSON
            else -> "\"значение\""
        }
    return "Например: {\"${sample.name}\": $value}; пусто — без аргументов"
}
