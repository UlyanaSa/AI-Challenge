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
import com.osvin.aichallenge.data.DialogBranches
import com.osvin.aichallenge.data.MemoryLayers
import com.osvin.aichallenge.data.UserProfile
import com.osvin.aichallenge.ui.components.*

/**
 * Экран одного чата: история активного чата и обмен сообщениями с агентом.
 * Соединяет ViewModel с пользовательским интерфейсом.
 *
 * Показывается только путь активной ветки: сообщения соседних веток лежат
 * в хранилище, но в чате не мешаются (см. [com.osvin.aichallenge.data.DialogBranches]).
 *
 * Память агента открывается шторкой из верхней панели: в ленте сообщений её нет,
 * чтобы хранилище не мешало диалогу. Профиль пользователя — второй шторкой там же:
 * он общий для всех чатов и подставляется в каждый запрос к модели.
 *
 * @param title Заголовок активного чата для верхней панели.
 * @param onBack Возврат к списку чатов.
 * @param onNewChat Переход к экрану создания нового чата с настройкой агента.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    title: String?,
    onBack: () -> Unit,
    onNewChat: () -> Unit
) {
    // Подписка на состояния из ViewModel
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val branches by viewModel.branches.collectAsStateWithLifecycle()
    val activeBranchId by viewModel.activeBranchId.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val memoryError by viewModel.memoryError.collectAsStateWithLifecycle()
    // Профиль пользователя: что лежит на сервере и чем закончилось чтение или запись
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val profileError by viewModel.profileError.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    // Стратегия контекста активного чата: она же показывается и меняется в шторке памяти
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Локальное состояние ввода
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Форма явной записи в память и признак открытой шторки: живут на экране,
    // поэтому перерисовка списка сообщений не теряет набранный текст
    var memoryLayer by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryValue by rememberSaveable { mutableStateOf("") }
    var memoryOpen by rememberSaveable { mutableStateOf(false) }
    val memorySheetState = rememberModalBottomSheetState()

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

    // Тип записи выбирается только из каталога сервера: по умолчанию — первый
    // писаемый, обычно рабочая память. Пока каталога нет, типа тоже нет.
    LaunchedEffect(layers?.types) {
        val writable = layers?.types.orEmpty().filter { it.writable }
        if (writable.none { it.layer == memoryLayer }) {
            memoryLayer = writable.firstOrNull()?.layer
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
                onBack = onBack,
                onTitleClick = { viewModel.checkHealth() },
                onNewChat = onNewChat,
                onMemory = { memoryOpen = true },
                onProfile = { profileOpen = true }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
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

                // Список сообщений активного пути: на каждом видно варианты продолжения
                itemsIndexed(messages) { index, message ->
                    ChatBubble(
                        message = message,
                        choice = DialogBranches.choiceAfter(messages, index, branches, activeBranchId),
                        onSelectOption = { viewModel.switchBranch(it) },
                        onBranchFrom = { viewModel.createBranchFrom(message) }
                    )
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

    // Шторка памяти: открывается из верхней панели и не занимает место в ленте
    // сообщений. Снимок читается при открытии чата, поэтому шторка показывает
    // то же, что и чат; без снимка видно, что каталог не пришёл.
    if (memoryOpen) {
        ModalBottomSheet(
            onDismissRequest = { memoryOpen = false },
            sheetState = memorySheetState
        ) {
            MemorySheet(
                layers = layers ?: EMPTY_MEMORY_LAYERS,
                report = memory,
                error = memoryError,
                selectedLayer = memoryLayer,
                valueText = memoryValue,
                // Смена стратегии сразу уходит во ViewModel: она же уезжает с запросом
                strategy = settings.strategy,
                onLayerSelected = { memoryLayer = it },
                onValueChange = { memoryValue = it },
                onRemember = {
                    memoryLayer?.let { viewModel.remember(it, memoryValue) }
                    // Текст очищается сразу, тип остаётся выбранным:
                    // так подряд вводят несколько записей одного типа
                    memoryValue = ""
                },
                onForget = { layer, value -> viewModel.forget(layer, value) },
                onStrategySelected = { viewModel.updateStrategy(it) }
            )
        }
    }

    // Шторка профиля: та же форма, что у памяти, — профиль правится поверх чата,
    // и правки видны строкой состояния до самого сохранения
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

/** Пустой снимок: шторка открывается и до ответа сервера — тогда видно, что каталог типов не пришёл. */
private val EMPTY_MEMORY_LAYERS = MemoryLayers()

/**
 * Сохранение черновика профиля между пересозданиями экрана: [UserProfile] — обычный
 * объект данных, и `rememberSaveable` его без правила не сохранит (сохраняются
 * примитивы и списки). Шесть полей строк — как раз такой список.
 */
private val USER_PROFILE_SAVER: Saver<UserProfile, Any> = listSaver<UserProfile, String>(
    save = { listOf(it.role, it.stack, it.style, it.format, it.constraints, it.signOff) },
    restore = { UserProfile(it[0], it[1], it[2], it[3], it[4], it[5]) }
)
