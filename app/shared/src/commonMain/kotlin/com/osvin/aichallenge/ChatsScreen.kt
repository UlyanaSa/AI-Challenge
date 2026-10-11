package com.osvin.aichallenge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.ui.components.CloseIcon
import com.osvin.aichallenge.ui.components.PlusIcon

/**
 * Экран списка чатов: каждый чат — отдельный диалог со своей сессией агента.
 *
 * Закреплённых чатов здесь нет: закреплённым приходил чат курсов валют, который ведёт
 * само приложение, а не человек, — его лента наполнялась по расписанию, а писать в неё
 * было нечем. В списке диалогов такой чат только занимал первую строку и сбивал
 * с толку, поэтому он скрыт целиком: его не заводит и не обновляет ни модель
 * представления, ни эта страница.
 *
 * @param chats Сохранённые чаты, свежие сверху.
 * @param loaded Загружен ли список из БД: до этого показывать нечего.
 * @param onOpen Открыть чат и продолжить его диалог.
 * @param onNew Создать новый чат и открыть его сразу: настраивать перед первым
 *        сообщением нечего — провайдера и модель выбирает конфигурация сервера.
 * @param onDelete Удалить чат вместе с его историей и сессией.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    chats: List<Chat>,
    loaded: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Чат, у которого подтверждают удаление: удаление необратимо, спрашиваем
    var pendingDelete by remember { mutableStateOf<Chat?>(null) }
    // Диалоги человека: закреплённые чаты ведёт приложение, и в этом списке их нет
    val dialogues = chats.filterNot { it.pinned }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Чаты",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "У каждого чата своя сессия агента",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Кнопка названа словом, а не только знаком: один значок в шапке
                    // читался как украшение, и найти создание чата по нему было нельзя
                    TextButton(onClick = onNew) {
                        PlusIcon(dimension = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Новый чат", style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                // Список ещё читается из БД — пустой экран лучше мигания «нет чатов»
                !loaded -> Unit

                dialogues.isEmpty() -> Text(
                    text = "Пока нет чатов.\nНачните новый — кнопка «Новый чат» в правом верхнем углу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(dialogues, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            onOpen = { onOpen(chat.id) },
                            onDelete = { pendingDelete = chat }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }

    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить чат?") },
            text = {
                Text("«${chat.title}» и его сессия агента будут удалены безвозвратно.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(chat.id)
                        pendingDelete = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

/**
 * Строка списка чатов: заголовок, идентификатор сессии агента и когда в чате
 * писали последний раз.
 */
@Composable
private fun ChatRow(
    chat: Chat,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "сессия ${chat.id.take(8)}… · ${lastActivity(chat.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onDelete) {
                CloseIcon(color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Когда это было: «только что», «12 мин назад», «5 дней назад».
 * Дату считаем от момента [at], календарь для этого не нужен: чат показывает
 * так время последнего сообщения.
 */
internal fun lastActivity(at: Long, now: Long = nowMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        else -> daysAgo(minutes / (24 * 60))
    }
}

/** «1 день назад», «3 дня назад», «5 дней назад» — по правилам русского языка. */
private fun daysAgo(days: Long): String = when {
    days % 10 == 1L && days % 100 != 11L -> "$days день назад"
    days % 10 in 2L..4L && days % 100 !in 12L..14L -> "$days дня назад"
    else -> "$days дней назад"
}
