package com.osvin.aichallenge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osvin.aichallenge.data.ChatMode
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.CompareFeed
import com.osvin.aichallenge.data.InvariantSnapshot
import com.osvin.aichallenge.data.ServerConnectionState
import com.osvin.aichallenge.data.ServerSettings
import com.osvin.aichallenge.data.ServiceShareLink
import com.osvin.aichallenge.data.UserProfile
import com.osvin.aichallenge.ui.components.*

/**
 * Экран одного чата: история активного чата и обмен сообщениями с агентом.
 * Соединяет ViewModel с пользовательским интерфейсом.
 *
 * Показывается только путь активной ветки диалога: сообщения соседних веток лежат
 * в хранилище, но в чате не мешаются. Самих веток в интерфейсе нет — ни переключателя
 * вариантов ответа, ни кнопки «ветка от этого сообщения»: диалог ведётся одной линией
 * (см. [com.osvin.aichallenge.data.DialogBranches]).
 *
 * Профиль пользователя открывается шторкой из верхней панели: он общий для всех чатов
 * и подставляется в каждый запрос к модели. Инварианты — второй: это правила проекта,
 * которые ассистент нарушать не имеет права, и они тоже уходят в каждый запрос, поэтому
 * правятся поверх чата. Третья шторка — «Сервер»: адрес и ключ приватного сервиса,
 * модель и рамки запроса и переключатель режима. В режиме сервиса лента показывает
 * разговор сервисной сессии ([ChatViewModel.serviceMessages]), а не локальный чат:
 * это разные разговоры с разными хранилищами. Там же над лентой стоит сводка прошлого
 * чата ([ServiceSummaryNote]) и кнопки «Обновить» и «Поделиться» ([ChatTopBar]): первый
 * перечитывает список чатов и историю открытого — так показывается написанное другим
 * клиентом, второй даёт ссылку на тот же чат. Шторок памяти агента и подключения
 * к GitHub здесь нет: чат ведёт
 * обычную историю диалога ([com.osvin.aichallenge.data.ContextStrategy.FULL]), а инструменты
 * MCP локальному провайдеру не предлагаются.
 *
 * Задачи чата в интерфейсе нет: ни полосы состояния работы над полосой ввода, ни кнопок
 * «Взять в работу», паузы и «забыть». Состояние задачи на сервере живёт по-прежнему
 * (см. [com.osvin.aichallenge.repository.ChatRepository.loadTask]) — из интерфейса убрано
 * только управление им.
 *
 * Модель, которой ответил сервер, видна в шапке под статусом связи: её называет ответ,
 * а не настройки, — провайдера и модель выбирает конфигурация сервера.
 *
 * @param title Заголовок активного чата для верхней панели.
 * @param onBack Возврат к списку чатов.
 * @param onNewChat Создание нового чата и переход в него.
 * @param showBack Показывать ли возврат к списку: на узком экране список — отдельный
 *        экран, на широком он стоит рядом и возвращаться некуда.
 * @param showNewChat Показывать ли создание чата в шапке: на широком экране кнопка
 *        есть в списке чатов рядом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    title: String?,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    showBack: Boolean = true,
    showNewChat: Boolean = true
) {
    // Настройки сервиса и итог его проверки: от режима зависит, чью ленту показывать
    // и когда отправка доступна, поэтому они читаются до ленты
    val serverSettings by viewModel.serverSettings.collectAsStateWithLifecycle()
    val serviceConnection by viewModel.serviceConnection.collectAsStateWithLifecycle()
    val serviceMode = serverSettings.mode == ChatMode.SERVICE

    // Открытый чат сервиса: заголовок и сводку прошлого чата называет сервис, поэтому
    // в режиме «Сервис» шапка и строка над лентой берут их отсюда, а не из локального чата,
    // который может быть открыт в соседней панели
    val serviceChatTitle by viewModel.serviceChatTitle.collectAsStateWithLifecycle()
    val serviceSummary by viewModel.serviceSummary.collectAsStateWithLifecycle()
    val serviceSummaryError by viewModel.serviceSummaryError.collectAsStateWithLifecycle()

    // Подписка на состояния из ViewModel. В режиме сервиса лента — разговор сервисной
    // сессии, а не локального чата: у них разные хранилища и разные сессии, и одна
    // лента на двоих показала бы реплики одного разговора в другом
    val messages by (if (serviceMode) viewModel.serviceMessages else viewModel.messages)
        .collectAsStateWithLifecycle()
    // Профиль пользователя: что лежит на сервере и чем закончилось чтение или запись
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val profileError by viewModel.profileError.collectAsStateWithLifecycle()
    // Инварианты проекта: действующие правила вместе с каталогом видов и чем
    // закончилось последнее обращение к серверу
    val invariants by viewModel.invariants.collectAsStateWithLifecycle()
    val invariantsError by viewModel.invariantsError.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val answerModel by viewModel.answerModel.collectAsStateWithLifecycle()
    val answerProvider by viewModel.answerProvider.collectAsStateWithLifecycle()
    // Сравнение сборок модели: включено ли оно и что ответили окна
    val compare by viewModel.compare.collectAsStateWithLifecycle()
    val compareMode by viewModel.compareMode.collectAsStateWithLifecycle()

    // Локальное состояние ввода
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Ссылка «Поделиться» и уложилась ли она в буфер обмена: показывается окном, потому
    // что буфера на платформе может не быть — тогда её копируют выделением текста
    var shareLink by remember { mutableStateOf<ServiceShareLink?>(null) }

    // Подсказка пустой ленты: у сервисного чата она про сервис, а не про сессию агента
    // устройства — разговоры у них в разных хранилищах
    val emptyHint = if (serviceMode) {
        "Напишите первое сообщение — чат заведётся на приватном сервисе и будет общим " +
            "у всех клиентов, которых вы позовёте ссылкой."
    } else {
        "Напишите первое сообщение — у этого чата своя история и своя сессия агента, " +
            "отдельная от остальных чатов."
    }

    // Форма добавления инварианта и признак открытой шторки: как и у профиля, они
    // живут на экране, поэтому перерисовка списка сообщений не теряет набранный текст
    var invariantKind by rememberSaveable { mutableStateOf<String?>(null) }
    var invariantValue by rememberSaveable { mutableStateOf("") }
    var invariantsOpen by rememberSaveable { mutableStateOf(false) }
    val invariantsSheetState = rememberModalBottomSheetState()

    // Черновик профиля — один объект, а не шесть строк: так его видно целиком,
    // и его достаточно сравнить с профилем с сервера, чтобы понять, есть ли правки.
    // Он переживает пересоздание экрана: набранное не теряется при повороте
    var profileDraft by rememberSaveable(stateSaver = USER_PROFILE_SAVER) {
        mutableStateOf(UserProfile())
    }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    val profileSheetState = rememberModalBottomSheetState()

    // Черновик настроек сервиса и признак открытой шторки: как и у профиля, живут
    // на экране, поэтому перерисовка ленты сообщений не теряет набранное
    var serverDraft by rememberSaveable(stateSaver = SERVER_SETTINGS_SAVER) {
        mutableStateOf(ServerSettings())
    }
    var serverOpen by rememberSaveable { mutableStateOf(false) }
    val serverSheetState = rememberModalBottomSheetState()

    // Шторка сервиса открывается с сохранёнными настройками, а не с прошлым черновиком:
    // настройки могли не меняться, но ключ в памяти сессии один, и показывать в поле
    // что-то кроме него значило бы расходиться с тем, чем подписаны запросы
    LaunchedEffect(serverOpen, serverSettings) {
        if (serverOpen) {
            serverDraft = serverSettings
        }
    }

    // Шторка открывается с тем, что лежит на сервере, а не с прошлыми правками;
    // профиль мог прийти уже после открытия (медленная сеть) — тогда черновик
    // наполняется, как только он придёт
    LaunchedEffect(profileOpen, profile) {
        // Профиль читается через делегат: в локальной переменной он проверяется
        // на null и тут же переносится в черновик
        val fromServer = profile
        if (profileOpen && fromServer != null) {
            profileDraft = fromServer
        }
    }

    // Вид инварианта тоже выбирается только из каталога сервера: по умолчанию —
    // первый вид каталога. Пока каталога нет, вида нет, и добавить правило некуда.
    LaunchedEffect(invariants?.kinds) {
        val kinds = invariants?.kinds.orEmpty()
        if (kinds.none { it.kind == invariantKind }) {
            invariantKind = kinds.firstOrNull()?.kind
        }
    }

    // Автопрокрутка к последнему сообщению при обновлении списка
    LaunchedEffect(messages.size, uiState) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            ChatTopBar(
                // В режиме сервиса заголовок — имя сервисного чата: локальный чат
                // в соседней панели к этой ленте отношения не имеет
                title = if (serviceMode) serviceChatTitle else title,
                isOnline = isOnline,
                // В сравнении одной модели в шапке нет: отвечают две сборки сразу,
                // и назвать одну из них значило бы назвать ту, которой ответа не было
                model = answerModel.takeIf { !compareMode },
                provider = answerProvider,
                compareMode = compareMode,
                // Сравнение сборок — способ посмотреть ответы пути `/v1`, поэтому
                // в режиме сервиса кнопки нет: вопрос уходит туда, где сравнения не бывает
                showCompare = !serviceMode,
                onToggleCompare = { viewModel.toggleCompare() },
                // Лента сервиса: «Обновить» перечитывает список и историю открытого чата —
                // так видно написанное другим клиентом; «Поделиться» даёт ссылку на тот же чат
                serviceMode = serviceMode,
                onRefresh = { viewModel.refreshService() },
                onShare = { shareLink = viewModel.shareServiceChat() },
                onBack = onBack,
                onTitleClick = { viewModel.checkHealth() },
                onNewChat = onNewChat,
                onInvariants = { invariantsOpen = true },
                onProfile = { profileOpen = true },
                onServer = { serverOpen = true },
                showBack = showBack,
                showNewChat = showNewChat
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                // Ожидание сравнения считается тем же ожиданием: два ответа идут одним
                // запросом, и второй вопрос, отправленный поверх первого, их перепутал бы
                isLoading = uiState is ChatUiState.Loading || compare.loading,
                // В режиме сервиса отправка открыта только после успешной проверки связи:
                // без неё нельзя решить, верен ли ключ и есть ли нужная модель, —
                // и вопрос ушёл бы впустую. На пути `/v1` условие прежнее
                enabled = if (serviceMode) {
                    serviceConnection.state == ServerConnectionState.CONNECTED
                } else {
                    isOnline == true
                }
            )
        }
    ) { padding ->
        if (compareMode && !serviceMode) {
            CompareMode(
                feed = compare,
                onClear = { viewModel.clearCompare() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            )
            return@Scaffold
        }
        Box(
            // Лента по центру и не шире строки чтения: на широком экране браузера
            // сообщение во всю ширину окна читать нельзя — глаз теряет начало строки
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = CONTENT_MAX_WIDTH),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Сводка прошлого чата — строкой над лентой, а не сообщением: её подставил
                // сервис в запрос к модели, и в диалоге её репликой быть не должно (§5)
                if (serviceMode && (serviceSummary != null || serviceSummaryError != null)) {
                    item {
                        ServiceSummaryNote(
                            summary = serviceSummary,
                            error = serviceSummaryError
                        )
                    }
                }

                // Пустой чат: подсказка вместо пустого экрана
                if (messages.isEmpty() && uiState !is ChatUiState.Loading) {
                    item {
                        Text(
                            text = emptyHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp)
                        )
                    }
                }

                // Список сообщений: у каждого — только его текст, вызовы инструментов
                // и служебные карточки. Ветки диалога в интерфейсе не показываются
                itemsIndexed(messages) { _, message ->
                    ChatBubble(message = message)
                }
                
                // Отображение индикаторов загрузки или ошибок
                when (uiState) {
                    is ChatUiState.Loading -> {
                        item { TypingIndicator() }
                    }
                    is ChatUiState.Error -> {
                        item { ErrorMessage((uiState as ChatUiState.Error).message) }
                    }
                    else -> {}
                }
            }
        }
    }

    // Шторка инвариантов: правила уходят в модель с каждым запросом, поэтому их видно
    // и правят там же, где остальные настройки агента. Снимок читается при создании
    // и открытии чата; без снимка видно, что каталог видов не пришёл.
    if (invariantsOpen) {
        ModalBottomSheet(
            onDismissRequest = { invariantsOpen = false },
            sheetState = invariantsSheetState
        ) {
            InvariantsSheet(
                snapshot = invariants ?: EMPTY_INVARIANTS,
                error = invariantsError,
                selectedKind = invariantKind,
                valueText = invariantValue,
                onKindSelected = { invariantKind = it },
                onValueChange = { invariantValue = it },
                onRemember = {
                    invariantKind?.let { viewModel.rememberInvariant(it, invariantValue) }
                    // Текст очищается сразу, вид остаётся выбранным:
                    // так подряд добавляют несколько правил одного вида
                    invariantValue = ""
                },
                onForget = { kind, value -> viewModel.forgetInvariant(kind, value) }
            )
        }
    }

    // Шторка профиля: предпочтения, которые сервер подставляет в каждый запрос к модели,
    // правятся поверх чата, и правки видны строкой состояния до самого сохранения
    if (profileOpen) {
        ModalBottomSheet(
            onDismissRequest = { profileOpen = false },
            sheetState = profileSheetState
        ) {
            ProfileSheet(
                draft = profileDraft,
                saved = profile,
                error = profileError,
                onDraftChange = { profileDraft = it },
                onSave = { viewModel.saveProfile(profileDraft) }
            )
        }
    }

    // Шторка сервиса: адрес, ключ и режим чата. Отсюда и проверяют связь —
    // без успешной проверки отправка в режиме сервиса закрыта, и причина видна здесь же
    if (serverOpen) {
        ModalBottomSheet(
            onDismissRequest = { serverOpen = false },
            sheetState = serverSheetState
        ) {
            ServerSheet(
                draft = serverDraft,
                connection = serviceConnection,
                onDraftChange = { serverDraft = it },
                onCheck = { viewModel.checkServiceConnection(serverDraft) },
                onSave = { viewModel.saveServerSettings(serverDraft) }
            )
        }
    }

    // Ссылка на общий чат: её показываем окном, а не только копируем в буфер, потому что
    // буфера на платформе может не быть — тогда остаётся выделить текст и скопировать
    // руками, и окно говорит об этом прямо, а не молчит о неудаче
    shareLink?.let { link ->
        AlertDialog(
            onDismissRequest = { shareLink = null },
            title = { Text("Ссылка на чат") },
            text = {
                Column {
                    Text(
                        text = if (link.copied) {
                            "Ссылка скопирована в буфер обмена. Второй клиент с ключом сервиса " +
                                "откроет по ней этот же чат:"
                        } else {
                            "Скопировать в буфер не удалось — выделите ссылку и скопируйте её вручную. " +
                                "Второй клиент с ключом сервиса откроет по ней этот же чат:"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    // Текст выделяемый: это и есть запасной путь, когда буфера нет
                    SelectionContainer {
                        Text(
                            text = link.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shareLink = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

/**
 * Строка сводки прошлого чата над лентой сервисного чата (§5).
 *
 * Сводку строит сервис при заведении чата и подставляет её модели; в ленте она стоит
 * строкой, а не сообщением: репликой диалога она не была, и человеку важно видеть, что
 * разговор начат не с пустого места. Причина отказа построения показывается отдельно
 * и цветом ошибки — по ней понятно, почему сводки нет, а чат при этом уже заведён.
 *
 * @param summary Текст сводки из ответа сервиса; null — сводки нет.
 * @param error Причина, по которой сводку не построили; null — отказа не было.
 */
@Composable
private fun ServiceSummaryNote(
    summary: String?,
    error: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            summary?.let {
                Text(
                    text = "Сводка прошлого чата: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let {
                Text(
                    text = "Сводку прошлого чата построить не удалось: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Режим сравнения: две сборки модели отвечают на один вопрос одновременно.
 *
 * Строка над окнами называет, что сравнивают, и даёт очистку: ходы сравнения живут до
 * перезапуска, а очистка нужна перед новым вопросом — иначе следующее обращение уносит
 * модели историю прошлых ответов, и сравнивать становится нечего.
 *
 * @param feed Лента сравнения: ходы, описание окон и состояние запроса.
 * @param onClear Очистка ходов сравнения.
 */
@Composable
private fun CompareMode(
    feed: CompareFeed,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)
        ) {
            Text(
                text = "Один вопрос — два ответа: как модель отвечает сейчас и как — оптимизированная сборка",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear, enabled = feed.turns.isNotEmpty() && !feed.loading) {
                Text("Очистить сравнение", style = MaterialTheme.typography.labelLarge)
            }
        }
        // Отказ всего запроса стоит над окнами: причина относится к обоим — ответов нет
        // ни у одной сборки. Отказ одной сборки печатается в её окне ([ComparePanes])
        feed.error?.let { reason ->
            ErrorMessage(reason, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        ComparePanes(feed = feed, modifier = Modifier.fillMaxSize())
    }
}

/** Пустой снимок инвариантов: шторка открывается и до ответа сервера — тогда видно, что каталог видов не пришёл. */
private val EMPTY_INVARIANTS = InvariantSnapshot()

/**
 * Сохранение черновика профиля между пересозданиями экрана: [UserProfile] — обычный
 * объект данных, и `rememberSaveable` его без правила не сохранит (сохраняются
 * примитивы и списки). Шесть полей строк — как раз такой список.
 */
private val USER_PROFILE_SAVER: Saver<UserProfile, Any> = listSaver<UserProfile, String>(
    save = { listOf(it.role, it.stack, it.style, it.format, it.constraints, it.signOff) },
    restore = { UserProfile(it[0], it[1], it[2], it[3], it[4], it[5]) }
)

/**
 * Сохранение черновика настроек сервиса между пересозданиями экрана: [ServerSettings] —
 * обычный объект данных, и `rememberSaveable` его без правила не сохранит. Список строк —
 * как раз такой сохраняемый набор, поэтому режим и три числа переводятся в текст и обратно;
 * неразобранное значение возвращается к умолчанию сервиса, а не роняет восстановление.
 */
private val SERVER_SETTINGS_SAVER: Saver<ServerSettings, Any> = listSaver<ServerSettings, String>(
    save = {
        listOf(
            it.mode.name,
            it.baseUrl,
            it.apiKey,
            it.model,
            it.temperature.toString(),
            it.maxTokens.toString(),
            it.contextWindow.toString()
        )
    },
    restore = {
        ServerSettings(
            mode = if (it[0] == ChatMode.SERVICE.name) ChatMode.SERVICE else ChatMode.CURRENT,
            baseUrl = it[1],
            apiKey = it[2],
            model = it[3],
            temperature = it[4].toDoubleOrNull() ?: 0.7,
            maxTokens = it[5].toIntOrNull() ?: 512,
            contextWindow = it[6].toIntOrNull() ?: 4096
        )
    }
)
