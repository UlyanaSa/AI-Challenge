package com.osvin.aichallenge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.InvariantSnapshot
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
 * правятся поверх чата. Шторок памяти агента и подключения к GitHub здесь нет: чат ведёт
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
    // Подписка на состояния из ViewModel
    val messages by viewModel.messages.collectAsStateWithLifecycle()
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

    // Локальное состояние ввода
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
                title = title,
                isOnline = isOnline,
                model = answerModel,
                provider = answerProvider,
                onBack = onBack,
                onTitleClick = { viewModel.checkHealth() },
                onNewChat = onNewChat,
                onInvariants = { invariantsOpen = true },
                onProfile = { profileOpen = true },
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
                isLoading = uiState is ChatUiState.Loading,
                enabled = isOnline == true
            )
        }
    ) { padding ->
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
                // Пустой чат: подсказка вместо пустого экрана
                if (messages.isEmpty() && uiState !is ChatUiState.Loading) {
                    item {
                        Text(
                            text = "Напишите первое сообщение — у этого чата своя история " +
                                "и своя сессия агента, отдельная от остальных чатов.",
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
