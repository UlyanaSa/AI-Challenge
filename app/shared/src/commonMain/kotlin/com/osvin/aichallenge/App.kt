package com.osvin.aichallenge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.data.ChatMode
import com.osvin.aichallenge.data.ChatStore
import com.osvin.aichallenge.data.InMemoryChatStore
import com.osvin.aichallenge.data.ServiceChatList
import com.osvin.aichallenge.repository.ChatRepository

/**
 * Главная точка входа в Compose Multiplatform приложение.
 * Настраивает зависимости и раскладывает экраны: список чатов и чат.
 *
 * На широком экране — браузер и десктоп — список чатов и диалог стоят рядом: чат
 * открывается в правой панели, а список слева остаётся на виду, и переключаться между
 * чатами можно одним нажатием. На узком экране (телефон) места на две панели нет,
 * поэтому экраны по-прежнему сменяют друг друга, а из чата возвращает стрелка.
 * Порог [WIDE_WIDTH] измеряется в dp доступной ширины, а не в пикселях окна, поэтому
 * на экране с высокой плотностью панели не схлопываются.
 *
 * Режим «Сервис» меняет не раскладку, а содержимое панелей: в списке — чаты приватного
 * сервиса (`GET /api/chats`), в ленте — разговор выбранного сервисного чата. Читается
 * список при заходе в режим, а чат из адреса страницы (`?chat=<id>`) открывается сразу
 * при старте: этим и работает шаринг ссылкой — чат на сервисе один на всех клиентов с ключом.
 *
 * @param store Хранилище чатов. Android подставляет реализацию на Room, поэтому
 * чаты, их истории и сессии агента живут в БД устройства до удаления чата;
 * на остальных таргетах всё живёт в памяти процесса.
 */
@Composable
@Preview
fun App(store: ChatStore = InMemoryChatStore()) {
    // Адрес сервера агента: Android-эмулятор ходит через 10.0.2.2,
    // браузер и десктоп — через localhost
    val baseUrl = serverBaseUrl()

    // Инициализация репозитория и ViewModel через Lifecycle
    val repository = remember { ChatRepository(baseUrl, store) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository) }

    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val chatsLoaded by viewModel.chatsLoaded.collectAsStateWithLifecycle()

    // Список чатов сервиса: в режиме «Сервис» боковая панель показывает их, а не локальные, —
    // источник истины по ним сервис, и живут они в его SQLite, а не в БД устройства
    val serverSettings by viewModel.serverSettings.collectAsStateWithLifecycle()
    val serviceMode = serverSettings.mode == ChatMode.SERVICE
    val serviceChats by viewModel.serviceChats.collectAsStateWithLifecycle()
    val serviceChatsLoaded by viewModel.serviceChatsLoaded.collectAsStateWithLifecycle()
    val serviceChatsError by viewModel.serviceChatsError.collectAsStateWithLifecycle()

    // Список читается при заходе в режим «Сервис»: в локальном режиме запрос не делается
    // вовсе — стоит ли спрашивать сервис, решает репозиторий, а экран только просит прочитать
    LaunchedEffect(serviceMode) {
        if (serviceMode) viewModel.loadServiceChats()
    }

    // Чат из адреса (`?chat=<id>`): по ссылке второго клиента открывается тот же сервисный
    // чат, и режим переводится в «Сервис» — в локальном его не нашлось бы. Читается один раз
    // при старте: адрес страницы дальше не меняется
    val sharedChatId = remember { openedChatId() }
    LaunchedEffect(sharedChatId) {
        if (sharedChatId != null) viewModel.openSharedServiceChat(sharedChatId)
    }

    // Номер экрана нужен только узкой раскладке: на широкой оба экрана видно сразу
    var screen by remember { mutableStateOf(Screen.Chats) }

    MaterialTheme {
        BoxWithConstraints {
            if (maxWidth >= WIDE_WIDTH) {
                WideApp(
                    viewModel = viewModel,
                    chats = chats,
                    chatsLoaded = chatsLoaded,
                    activeChat = activeChat,
                    serviceMode = serviceMode,
                    serviceChats = serviceChats,
                    serviceChatsLoaded = serviceChatsLoaded,
                    serviceChatsError = serviceChatsError
                )
            } else {
                when (screen) {
                    Screen.Chats -> ChatsScreen(
                        chats = chats,
                        loaded = chatsLoaded,
                        onOpen = { id ->
                            viewModel.openChat(id)
                            screen = Screen.Chat
                        },
                        // Новый чат открывается сразу: настраивать перед первым сообщением
                        // нечего — провайдера и модель выбирает конфигурация сервера,
                        // а не человек в шторке
                        onNew = {
                            viewModel.createChat()
                            screen = Screen.Chat
                        },
                        onDelete = { id -> viewModel.deleteChat(id) },
                        // В режиме «Сервис» список и создание чата — сервисные: чат заводится
                        // на сервисе и открывается по его идентификатору
                        serviceMode = serviceMode,
                        serviceChats = serviceChats,
                        serviceChatsLoaded = serviceChatsLoaded,
                        serviceChatsError = serviceChatsError,
                        onServiceOpen = { id ->
                            viewModel.openServiceChat(id)
                            screen = Screen.Chat
                        },
                        onServiceNew = {
                            viewModel.createServiceChat()
                            screen = Screen.Chat
                        }
                    )

                    // Переход в чат сразу после выбора: сообщения поднимаются из БД
                    // асинхронно и появляются в списке, как только прочитаны
                    Screen.Chat -> ChatScreen(
                        viewModel = viewModel,
                        title = activeChat?.title,
                        onBack = { screen = Screen.Chats },
                        onNewChat = {
                            // Кнопка создания ведёт туда же, где живут чаты открытого режима
                            if (serviceMode) viewModel.createServiceChat() else viewModel.createChat()
                            screen = Screen.Chat
                        }
                    )
                }
            }
        }
    }
}

/**
 * Раскладка широкого экрана: список чатов слева, диалог справа.
 *
 * Выбранный чат держит модель представления ([ChatViewModel.activeChat]), поэтому панели
 * не пересказывают друг другу выбор: список открывает чат, а правая панель показывает
 * тот, что открыт. Пока чат не выбран — подсказка вместо пустой панели.
 *
 * @param viewModel Модель представления: общая для обеих панелей.
 * @param chats Сохранённые чаты для левой панели.
 * @param chatsLoaded Загружен ли список из БД.
 * @param activeChat Открытый чат; null — чат ещё не выбран, и правая панель пуста.
 * @param serviceMode Открыт ли режим «Сервис»: тогда в левой панели чаты сервиса, а в правой
 *        лента сервисного чата — она показывается и без локального активного чата, потому
 *        что сервисный чат адресуется собственным идентификатором.
 * @param serviceChats Чаты сервиса из `GET /api/chats` для левой панели.
 * @param serviceChatsLoaded Прочитан ли список чатов сервиса.
 * @param serviceChatsError Причина, по которой список сервиса не прочитался; null — отказа не было.
 */
@Composable
private fun WideApp(
    viewModel: ChatViewModel,
    chats: List<Chat>,
    chatsLoaded: Boolean,
    activeChat: Chat?,
    serviceMode: Boolean,
    serviceChats: ServiceChatList,
    serviceChatsLoaded: Boolean,
    serviceChatsError: String?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Список отделён от диалога и положен на поверхность: две области страницы
        // не сливаются в одну, когда у обеих одинаковый фон
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier
                .width(CHATS_PANE_WIDTH)
                .fillMaxHeight()
        ) {
            ChatsScreen(
                chats = chats,
                loaded = chatsLoaded,
                onOpen = { id -> viewModel.openChat(id) },
                onNew = { viewModel.createChat() },
                onDelete = { id -> viewModel.deleteChat(id) },
                serviceMode = serviceMode,
                serviceChats = serviceChats,
                serviceChatsLoaded = serviceChatsLoaded,
                serviceChatsError = serviceChatsError,
                onServiceOpen = { id -> viewModel.openServiceChat(id) },
                onServiceNew = { viewModel.createServiceChat() }
            )
        }
        VerticalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // В режиме «Сервис» лента показывается и без локального чата: сервисный чат
            // открывается своим идентификатором, и в списке локальных ему места нет
            if (serviceMode || activeChat != null) {
                ChatScreen(
                    viewModel = viewModel,
                    title = activeChat?.title,
                    // Возврата и второй кнопки создания чата в шапке нет: список
                    // с кнопкой «Новый чат» стоит рядом и остаётся на виду
                    onBack = {},
                    onNewChat = {},
                    showBack = false,
                    showNewChat = false
                )
            } else {
                Text(
                    text = "Выберите чат слева или создайте новый — «Новый чат».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            }
        }
    }
}

/** Ширина списка чатов на широком экране. */
private val CHATS_PANE_WIDTH = 320.dp

/**
 * С какой ширины экрана показывать чат и список рядом.
 *
 * Порог в 840 dp — планшетная ширина окна: на телефоне в портретной ориентации
 * помещается около 400 dp, и две панели там станут уже 300 dp друг на друге.
 */
private val WIDE_WIDTH = 840.dp

/** Экраны приложения. */
private enum class Screen { Chats, Chat }
