package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
import com.osvin.aichallenge.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Репозиторий чатов: список диалогов, активный чат и обмен сообщениями с сервером.
 *
 * Каждый чат — отдельная сессия агента: идентификатор чата ([Chat.id]) уходит на
 * сервер как `sessionId`, поэтому сводка истории на сервере своя у каждого чата.
 * Идентификатор хранится в БД устройства ([ChatStore]), пока чат не удалён.
 *
 * Диалог может ветвиться ([DialogBranch]): сообщения всех веток лежат в хранилище,
 * но в чате ([messages]) и в запросе к модели участвует только путь активной ветки
 * (см. [DialogBranches]).
 *
 * Стратегия управления контекстом принадлежит чату ([Chat.strategy]): от неё зависит,
 * какой контекст уходит в модель (сжатие истории, окно, слои памяти), и в разных чатах
 * он уместен свой. Поэтому открытие чата поднимает его стратегию в настройки
 * ([settings]), а смена стратегии сохраняется в чате — иначе после перезапуска
 * приложения диалог молча вернулся бы к стратегии по умолчанию.
 *
 * Память агента, наоборот, общая для профиля: и рабочая, и долговременная видны
 * из любого чата, и снимок памяти ([loadMemory]) грузится один и тот же.
 *
 * Профиль пользователя ([loadProfile]) тоже общий и лежит на сервере: клиент его
 * только показывает и правит ([saveProfile]), а в запрос к модели профиль не
 * подставляет — это делает сервер, поэтому в теле запроса его нет.
 *
 * Задача ([loadTask]) — наоборот, своя у каждого чата: этап и шаг описывают работу
 * в этом диалоге, поэтому состояние адресуется сессией чата. Ведёт задачу сервер,
 * а клиент её только показывает и передаёт явные действия человека: взял в работу
 * ([startTask]), поставил на паузу ([setTaskPaused]), забыл ([forgetTask]). Как и
 * память, задачу он не выводит сам — состояние и каталог этапов приходят с сервера,
 * а после каждого ответа модели обновляются из отчёта ([TaskReport]) без перезапроса.
 */
class ChatRepository(
    private val baseUrl: String,
    private val store: ChatStore,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
) {
    // Внутренние потоки данных (StateFlow)
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _activeChat = MutableStateFlow<Chat?>(null)
    val activeChat: StateFlow<Chat?> = _activeChat.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _branches = MutableStateFlow<List<DialogBranch>>(emptyList())
    val branches: StateFlow<List<DialogBranch>> = _branches.asStateFlow()

    private val _activeBranchId = MutableStateFlow<String?>(null)
    val activeBranchId: StateFlow<String?> = _activeBranchId.asStateFlow()

    // Память агента из последнего отчёта; null — отчёта ещё не было
    private val _memory = MutableStateFlow<MemoryReport?>(null)
    val memory: StateFlow<MemoryReport?> = _memory.asStateFlow()

    // Снимок памяти профиля, общий для всех чатов; null — снимок ещё не загружен
    // или сервер недоступен
    private val _layers = MutableStateFlow<MemoryLayers?>(null)
    val layers: StateFlow<MemoryLayers?> = _layers.asStateFlow()

    // Отказ сервера на явную запись в память или на смену стратегии; null — отказа не было
    private val _memoryError = MutableStateFlow<String?>(null)
    val memoryError: StateFlow<String?> = _memoryError.asStateFlow()

    // Профиль пользователя: объявленные предпочтения, общие для всех чатов. Живёт
    // отдельно от памяти — это разные сущности: память агент извлекает из диалога,
    // профиль пользователь объявляет сам. null — профиль ещё не загружен или сервер
    // недоступен, тогда шторка открывается пустой формой.
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    // Отказ сервера на чтение или запись профиля; null — отказа не было. Это своя
    // ошибка, а не [memoryError]: сбой профиля не должен выглядеть как сбой памяти.
    private val _profileError = MutableStateFlow<String?>(null)
    val profileError: StateFlow<String?> = _profileError.asStateFlow()

    // Задача активного чата: на каком этапе работа и что ждут от человека. Живёт
    // отдельно от памяти: память — это то, что агент помнит из диалога, а задача —
    // состояние работы, которое человек заводит сам и которым сам управляет. Снимок
    // несёт и каталог этапов, поэтому подписи полосы берутся из ответа сервера.
    // null — снимка ещё не было: состояние задачи на экране неизвестно.
    private val _task = MutableStateFlow<TaskSnapshot?>(null)
    val task: StateFlow<TaskSnapshot?> = _task.asStateFlow()

    // Отказ сервера на чтение задачи и на действия с ней; null — отказа не было.
    // Это своя ошибка, а не [memoryError] и не [profileError]: сбой задачи не должен
    // выглядеть как сбой памяти, а сбой профиля — как сбой задачи.
    private val _taskError = MutableStateFlow<String?>(null)
    val taskError: StateFlow<String?> = _taskError.asStateFlow()

    // Настройки генерации из шторки. Стратегия в них — стратегия активного чата,
    // поэтому живут они рядом с активным чатом, а не в слое интерфейса.
    private val _settings = MutableStateFlow(GenerationSettings())
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

    /**
     * Проверка доступности сервера.
     */
    suspend fun checkHealth() {
        try {
            val response = client.get("$baseUrl/v1/health")
            _isServerOnline.value = response.status.isSuccess()
        } catch (e: Exception) {
            _isServerOnline.value = false
        }
    }

    /**
     * Загрузка списка сохранённых чатов (например, после перезапуска приложения).
     */
    suspend fun loadChats() {
        _chats.value = store.chats()
    }

    /**
     * Настройки генерации из шторки: модель, бюджет, температура и стратегия.
     * Стратегия из них становится стратегией нового чата ([createChat]); дальше
     * её меняют уже у чата ([updateStrategy]).
     */
    fun updateSettings(settings: GenerationSettings) {
        _settings.value = settings
    }

    /**
     * Новый чат со своей историей и своей сессией агента. Стратегия контекста
     * берётся из текущих настроек шторки и остаётся в самом чате ([Chat.strategy]):
     * дальше её меняют у чата ([updateStrategy]), и она переживает перезапуск
     * приложения. Диалог начинается с основной линии: веток ещё нет, активной
     * ветки тоже.
     */
    suspend fun createChat(): Chat {
        val chat = Chat(id = newHexId(), title = NEW_CHAT_TITLE, strategy = _settings.value.strategy.wire)
        store.create(chat)
        _activeChat.value = chat
        _messages.value = emptyList()
        _branches.value = emptyList()
        _activeBranchId.value = null
        _memory.value = null
        _layers.value = null
        _memoryError.value = null
        // Состояние задачи принадлежит чату, поэтому у нового чата его нет: свой
        // снимок читается ниже, а прежний относился к прошлому диалогу
        _task.value = null
        _taskError.value = null
        _state.value = ChatUiState.Idle
        _chats.value = store.chats()
        // Память общая для профиля: шторке сразу нужен снимок слоёв и каталога типов,
        // а не записи этого чата — своих записей у чата нет
        loadMemory()
        // Профиль тоже общий и нужен шторке сразу, а не после первого нажатия:
        // он подставляется в каждый запрос на сервере, поэтому в чате его видно
        loadProfile()
        // Задача своя у каждого чата: полоса показывает работу этого диалога сразу,
        // а не после первого сообщения — состояние живёт на сервере, не в переписке
        loadTask()
        return chat
    }

    /**
     * Открытие чата: поднимаем его сообщения из БД вместе с ветками.
     * Дальше запросы уходят с идентификатором этого чата, поэтому сводка истории
     * на сервере — та же, что была в прошлый раз. Диалог продолжается в той ветке,
     * в которой пользователь остановился.
     */
    suspend fun openChat(id: String) {
        val chat = store.chat(id) ?: return
        val branches = store.branches(id)
        _activeChat.value = chat
        // Стратегия чата поднимается из хранилища: с ней этот чат собирает контекст
        // так, как выбрал пользователь, а не как настроен соседний диалог
        _settings.value = _settings.value.copy(strategy = strategyOfWire(chat.strategy))
        _branches.value = branches
        _activeBranchId.value = chat.activeBranchId
        _memory.value = null
        _layers.value = null
        _memoryError.value = null
        // Прежнее состояние задачи относилось к соседнему чату: задача у каждого
        // диалога своя, поэтому до ответа сервера о ней лучше молчать, чем показать чужую
        _task.value = null
        _taskError.value = null
        _messages.value = DialogBranches.activePath(store.messages(id), branches, chat.activeBranchId)
        _state.value = ChatUiState.Idle
        // Память общая для профиля, но чат мог остаться без снимка (его чистит
        // удаление чата) — читаем снова, чтобы шторка была наполнена
        loadMemory()
        // Профиль читаем на каждом открытии чата: его могли поправить с другого
        // устройства, а шторка должна открываться с тем, что лежит на сервере
        loadProfile()
        // Задача у каждого чата своя, и состояние её живёт на сервере, а не в истории
        // сообщений: без чтения открытый чат показал бы пустую полосу при живой задаче
        loadTask()
    }

    /**
     * Смена стратегии активного чата: она остаётся в самом чате ([Chat.strategy])
     * и в настройках ([settings]), поэтому переживает перезапуск приложения и не
     * переносится на соседние чаты. Следующий запрос к модели уходит уже с ней
     * ([sendMessage]). Менять нечего, когда чат не выбран: причина уходит в тот же
     * [memoryError], что и у явной записи в память, — иначе нажатие в шторке
     * выглядело бы как сломанное.
     */
    suspend fun updateStrategy(strategy: ContextStrategy) {
        val chat = _activeChat.value ?: return noActiveChat("стратегия не изменена")
        store.setStrategy(chat.id, strategy.wire)
        _activeChat.value = chat.copy(strategy = strategy.wire)
        _settings.value = _settings.value.copy(strategy = strategy)
        _chats.value = store.chats()
    }

    /**
     * Удаление чата вместе с историей, ветками и сессией агента.
     * На сервере чистим только сессию сводки: сводка своя у каждого чата, и после
     * удаления она не нужна. Память чат не адресует — оба слоя живут по профилю
     * и остаются на месте, поэтому удаление диалога не стирает рабочую память
     * задачи, записанную из любого чата. Если сервер недоступен, чат на устройстве
     * всё равно удалён.
     */
    suspend fun deleteChat(id: String) {
        store.delete(id)
        if (_activeChat.value?.id == id) {
            _activeChat.value = null
            _messages.value = emptyList()
            _branches.value = emptyList()
            _activeBranchId.value = null
            _memory.value = null
            // Шторка чистится вместе с чатом: активного диалога больше нет, и снимок
            // профиля в ней не остаётся — следующий открытый чат прочитает его заново
            _layers.value = null
            _memoryError.value = null
            // Полоса задачи чистится вместе с чатом: её состояние адресовалось сессией
            // этого диалога, и после удаления показывать его нечему
            _task.value = null
            _taskError.value = null
            _state.value = ChatUiState.Idle
        }
        _chats.value = store.chats()
        clearServerSession(id)
    }

    /**
     * Ветка от сообщения активного пути: точка ветвления — само это сообщение,
     * поэтому ветка начинается сразу после него и продолжается отдельно от соседей.
     * Созданная ветка сразу становится активной: пользователь продолжает диалог в ней.
     */
    suspend fun createBranchFrom(message: ChatMessage) {
        val chat = _activeChat.value ?: return
        val path = DialogBranches.activePath(
            history = store.messages(chat.id),
            branches = store.branches(chat.id),
            activeBranchId = _activeBranchId.value
        )
        val index = path.indexOfFirst { it == message }
        if (index < 0) return

        val branch = DialogBranch(
            id = newHexId(),
            // Родитель — ветка самого сообщения: у сообщения основной линии его нет
            parentId = message.branchId,
            // Общих с родителем сообщений ровно столько, сколько в пути до этого сообщения
            forkedAfter = index + 1
        )
        store.createBranch(chat.id, branch)
        switchBranch(branch.id)
    }

    /**
     * Переключение активной ветки диалога; null — возврат на основную линию.
     * Дальше и экран, и запросы к модели видят только путь новой ветки,
     * а сообщения соседних веток остаются в хранилище со своими метками.
     */
    suspend fun switchBranch(branchId: String?) {
        val chat = _activeChat.value ?: return
        val branches = store.branches(chat.id)
        val target = branchId?.takeIf { id -> branches.any { it.id == id } }
        store.setActiveBranch(chat.id, target)
        _activeChat.value = chat.copy(activeBranchId = target)
        _branches.value = branches
        _activeBranchId.value = target
        _messages.value = DialogBranches.activePath(store.messages(chat.id), branches, target)
    }

    /**
     * Отправка сообщения нейросети в активный чат.
     *
     * На сервер уезжает вся история чата: агент сам решает, что из неё уходит
     * в модель, — по выбранной стратегии ([GenerationSettings.strategy]) и по
     * пути активной ветки (см. [DialogBranches]). Метки веток при истории
     * сохраняются, иначе агент не собрал бы этот путь, а `branchId`/`branches`
     * нужны только стратегии «ветки диалога».
     *
     * @param message Текст сообщения пользователя.
     * @param settings Настройки генерации из шторки настроек.
     */
    suspend fun sendMessage(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return

        val chat = _activeChat.value
        if (chat == null) {
            _state.value = ChatUiState.Error("Чат не выбран")
            return
        }

        _state.value = ChatUiState.Loading

        // Ветка, в которой продолжается диалог: её метку получат оба новых сообщения
        val activeBranchId = _activeBranchId.value
        val branches = if (settings.strategy == ContextStrategy.BRANCHES) store.branches(chat.id) else null

        // История чата до этого вопроса: все ветки вместе, у каждой своя метка
        val history = store.messages(chat.id)

        try {
            val response = client.post("$baseUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        model = settings.model,
                        maxTokens = settings.maxTokens,
                        stop = settings.stopWords.ifEmpty { null },
                        temperature = settings.temperature,
                        systemPrompt = settings.systemPrompt.ifBlank { null },
                        history = history.takeIf { it.isNotEmpty() },
                        sessionId = chat.id,
                        strategy = settings.strategy.wire,
                        windowMessages = settings.windowMessages,
                        branchId = activeBranchId.takeIf { branches != null },
                        branches = branches
                    )
                )
            }

            // Вопрос пользователя попадает в диалог в любом случае: и при ответе,
            // и при ошибке — иначе при пустом ответе модели он пропадает с экрана.
            val userMessage = ChatMessage(MessageRole.USER, message, branchId = activeBranchId)
            _messages.value += userMessage
            store.append(chat.id, userMessage)

            // Заголовок чата берём из первого вопроса пользователя за всю переписку:
            // в списке чатов видно, о чём каждый диалог. Считаем по всей истории,
            // а не по активному пути: в ветке вопрос уже не первый.
            if (history.none { it.role == MessageRole.USER }) {
                val title = titleFrom(message)
                store.retitle(chat.id, title)
                _activeChat.value = _activeChat.value?.copy(title = title)
            }
            _chats.value = store.chats()

            if (response.status.isSuccess()) {
                val chatResponse = response.body<ChatResponse>()

                // Отчёт агента печатаем в лог платформы (на Android — в logcat):
                // строки те же, что агент пишет на сервере, но видны рядом с приложением.
                // Память агента оттуда же попадает в шторку на экране чата, а состояние
                // задачи — в полосу над полем ввода: и то, и другое без перезапроса.
                chatResponse.tokens?.let { report ->
                    platformLog("agent", report.logEntry(settings.model))
                    _memory.value = report.memory
                    applyTaskReport(report.task)
                }

                val assistantMessage = ChatMessage(MessageRole.ASSISTANT, chatResponse.reply, branchId = activeBranchId)
                _messages.value = _messages.value + assistantMessage
                store.append(chat.id, assistantMessage)
                _chats.value = store.chats()

                _state.value = ChatUiState.Success(chatResponse.reply)
                _isServerOnline.value = true

                // Агент мог дописать память сам: панель показывает то, что теперь
                // лежит в слоях на сервере, а не только то, что ушло в этот ответ
                loadMemory()
            } else {
                // Сервер объясняет отказ в теле ответа (переполнение контекста,
                // пустой ответ модели, сбой провайдера) — в чате показываем его
                // текст, а не только код статуса.
                val reason = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                val error = reason ?: "Ошибка сервера: ${response.status.value}"
                platformLog("agent", "[agent] Ошибка: $error")
                _state.value = ChatUiState.Error(error)
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            platformLog("agent", "[agent] Сервер недоступен: ${e.message ?: "нет соединения"}")
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Снимок памяти профиля: слои и каталог типов. Тот же для всех чатов, поэтому
     * активный чат не нужен — шторка показывает память и без него. Без снимка шторке
     * нечего показывать, поэтому null и до первого запроса, и когда сервер недоступен:
     * чат при этом продолжает работать — память не часть диалога, а отдельный слой.
     */
    suspend fun loadMemory() {
        try {
            val response = client.get("$baseUrl/v1/memory")
            if (response.status.isSuccess()) {
                _layers.value = response.body()
                _memoryError.value = null
            } else {
                _layers.value = null
                // Без снимка в шторке нет каталога типов, и записать некуда: причину
                // надо показать пользователю, иначе недоступная кнопка выглядит сломанной
                _memoryError.value = "Память недоступна: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] Память не загружена: ${response.status.value}")
            }
        } catch (e: Exception) {
            _layers.value = null
            _memoryError.value = e.message ?: "Память недоступна: нет соединения"
            platformLog("agent", "[agent] Память не загружена: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Профиль пользователя с сервера: объявленные предпочтения, которые агент
     * учитывает в каждом ответе. Профиль общий для всех чатов, поэтому активный чат
     * здесь не нужен. Это не память: своего слияния, пределов и отбора типов у профиля
     * нет — что лежит на сервере, то и показывается, поэтому у него своё состояние
     * ([profile]) и своя ошибка ([profileError]), и сбой профиля не трогает диалог.
     * Без профиля шторке нечего показывать: null и до первого запроса, и когда сервер
     * недоступен — тогда пользователь видит пустую форму и причину строкой ошибки.
     */
    suspend fun loadProfile() {
        try {
            val response = client.get("$baseUrl/v1/profile")
            if (response.status.isSuccess()) {
                _profile.value = response.body()
                _profileError.value = null
            } else {
                _profile.value = null
                _profileError.value = "Профиль недоступен: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] Профиль не загружен: ${response.status.value}")
            }
        } catch (e: Exception) {
            _profile.value = null
            _profileError.value = e.message ?: "Профиль недоступен: нет соединения"
            platformLog("agent", "[agent] Профиль не загружен: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Сохранение профиля целиком: тело — сам профиль, а состояние — из ответа сервера,
     * поэтому в шторке видно то, что сохранено, а не то, что набрано: сервер мог
     * дополнить или урезать поля.
     * Отказ сервера и сбой сети не переводят чат в [ChatUiState.Error] и не стирают уже
     * загруженный профиль: диалог продолжается, а причина видна строкой в шторке
     * ([profileError]) — несделанные правки при этом остаются в черновике на экране.
     */
    suspend fun saveProfile(profile: UserProfile) {
        try {
            val response = client.put("$baseUrl/v1/profile") {
                contentType(ContentType.Application.Json)
                setBody(profile)
            }
            if (response.status.isSuccess()) {
                _profile.value = response.body()
                _profileError.value = null
            } else {
                _profileError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _profileError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Состояние задачи активного чата: этап, шаг, ожидаемое действие и каталог этапов.
     * Задача адресуется сессией диалога, поэтому без активного чата читать нечего —
     * тогда состояние пустое, а не чужое.
     *
     * Без снимка полосе нечего показывать, поэтому null и до первого запроса, и когда
     * сервер недоступен: чат при этом продолжает работать — задача не часть диалога,
     * а отдельный слой работы.
     */
    suspend fun loadTask() {
        val chat = _activeChat.value
        if (chat == null) {
            _task.value = null
            return
        }
        try {
            val response = client.get("$baseUrl/v1/task") {
                parameter("sessionId", chat.id)
            }
            if (response.status.isSuccess()) {
                _task.value = response.body()
                _taskError.value = null
            } else {
                _task.value = null
                // Без снимка полоса молчит, а причина должна быть видна: иначе
                // «Взять в работу» выглядела бы сломанной кнопкой
                _taskError.value = "Задача недоступна: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] Задача не загружена: ${response.status.value}")
            }
        } catch (e: Exception) {
            _task.value = null
            _taskError.value = e.message ?: "Задача недоступна: нет соединения"
            platformLog("agent", "[agent] Задача не загружена: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Взятие задачи в работу: сервер заводит её и отвечает первым этапом — планированием.
     * Действие явное, как запись в память: пока человек не нажал, агент задачу не ведёт
     * и состояние работы ему неоткуда взять.
     */
    suspend fun startTask() {
        val chat = _activeChat.value ?: return taskUnavailable("задача не взята")
        applyTask {
            client.post("$baseUrl/v1/task") {
                contentType(ContentType.Application.Json)
                setBody(TaskStartRequest(sessionId = chat.id))
            }
        }
    }

    /**
     * Пауза и продолжение задачи: одно поле на оба действия, потому что разница между
     * ними только в нём — на паузе состояние остаётся на месте, и после продолжения
     * работа идёт с того же шага, без повторных объяснений.
     */
    suspend fun setTaskPaused(paused: Boolean) {
        val chat = _activeChat.value ?: return taskUnavailable(
            if (paused) "задача не поставлена на паузу" else "задача не продолжена"
        )
        applyTask {
            client.put("$baseUrl/v1/task") {
                contentType(ContentType.Application.Json)
                setBody(TaskPauseRequest(sessionId = chat.id, paused = paused))
            }
        }
    }

    /**
     * Забвение задачи: состояние и шаг стираются на сервере, каталог этапов остаётся
     * в ответе — поэтому полоса сразу показывает, что задачи нет, а не пустоту.
     * Операция идемпотентна: сервер отвечает тем же снимком, поэтому забыть задачу
     * можно и повторно.
     */
    suspend fun forgetTask() {
        val chat = _activeChat.value ?: return taskUnavailable("задача не забыта")
        applyTask {
            client.delete("$baseUrl/v1/task") { parameter("sessionId", chat.id) }
        }
    }

    /**
     * Состояние задачи из отчёта ответа: этап, шаг и ожидаемое действие посчитал сервер,
     * пока отвечал, поэтому клиент их только переносит — без отдельного запроса, который
     * показал бы то же самое с задержкой.
     *
     * Каталог этапов в отчёт не входит: он остаётся тем, что пришёл со снимком
     * ([loadTask]). Взять его в отчёте неоткуда, а вторая копия на клиенте разошлась бы
     * с серверной.
     *
     * @param report Отчёт задачи; null — агент о задаче не отчитывался, и состояние
     *        остаётся прежним: молчание отчёта о задаче не значит, что её больше нет.
     */
    private fun applyTaskReport(report: TaskReport?) {
        if (report == null) return
        val stages = _task.value?.stages.orEmpty()
        val stage = report.stage
        // Нет этапа — нет и задачи: так сервер сообщает, что она закрыта или не заводилась
        _task.value = if (stage == null) {
            TaskSnapshot(task = null, stages = stages)
        } else {
            TaskSnapshot(
                task = TaskState(
                    stage = stage,
                    step = report.step,
                    expectedAction = report.expectedAction,
                    paused = report.paused
                ),
                stages = stages
            )
        }
    }

    /** Общий путь действий с задачей: снимок из ответа идёт в состояние, отказ — в текст ошибки. */
    private suspend fun applyTask(request: suspend () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) {
                _task.value = response.body()
                _taskError.value = null
            } else {
                // Сервер объясняет отказ в теле (например, задачи нет) — показываем его
                // текст, а не только код статуса
                _taskError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _taskError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Явная запись в память: тип и текст. Запись адресуется парой «тип + текст»,
     * поэтому ключа в запросе нет, и чат в ней не назван: оба писаемых типа живут
     * по профилю, поэтому записать в память можно и без активного чата, и запись
     * будет видна из любого диалога.
     * Отказ сервера и сбой сети не переводят чат в [ChatUiState.Error]: диалог
     * продолжается, а причина видна строкой в шторке памяти ([memoryError]),
     * и уже показанный снимок остаётся на месте.
     */
    suspend fun remember(layer: String, value: String) {
        applyMemoryWrite {
            client.post("$baseUrl/v1/memory") {
                contentType(ContentType.Application.Json)
                setBody(MemoryWriteRequest(layer = layer, value = value))
            }
        }
    }

    /**
     * Удаление записи из памяти: тип и текст записи. Как и запись, чат здесь не нужен:
     * память общая для профиля. Операция идемпотентна: сервер отвечает тем же снимком,
     * поэтому забыть запись можно и повторно.
     */
    suspend fun forget(layer: String, value: String) {
        applyMemoryWrite {
            client.delete("$baseUrl/v1/memory") {
                parameter("layer", layer)
                parameter("value", value)
            }
        }
    }

    /**
     * Чат не выбран — адресовать действие нечем. Молчание здесь выглядело бы как
     * сломанная кнопка в шторке, поэтому причина уходит в тот же [memoryError].
     * Память чат не адресует, поэтому отказ остаётся только у смены стратегии.
     *
     * @param reason Что именно не сделано.
     */
    private fun noActiveChat(reason: String) {
        _memoryError.value = "$reason: чат не выбран"
    }

    /**
     * Действие с задачей, когда чат не выбран: адресовать его нечем, потому что
     * состояние задачи живёт по сессии диалога, а не по профилю. Молчание выглядело бы
     * как сломанная кнопка в полосе, поэтому причина уходит в [taskError].
     *
     * @param reason Что именно не сделано.
     */
    private fun taskUnavailable(reason: String) {
        _taskError.value = "$reason: чат не выбран"
    }

    /** Общий путь записи и удаления: снимок из ответа идёт в состояние, отказ — в текст ошибки. */
    private suspend fun applyMemoryWrite(request: suspend () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) {
                _layers.value = response.body()
                _memoryError.value = null
            } else {
                _memoryError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _memoryError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Убирает сессию чата на сервере: сама история лежит на устройстве, а на
     * сервере по сессии хранится только сводка, и после удаления чата она не нужна.
     */
    private suspend fun clearServerSession(id: String) {
        runCatching { client.delete("$baseUrl/v1/chats/$id") }
            .onSuccess { response ->
                if (!response.status.isSuccess()) {
                    platformLog("agent", "[agent] Сессия $id на сервере не удалена: ${response.status.value}")
                }
            }
            .onFailure { e ->
                platformLog("agent", "[agent] Сессия $id на сервере не удалена: ${e.message ?: "нет соединения"}")
            }
    }

    /**
     * Стратегия чата по значению из хранилища. Неизвестное значение — память
     * агента: это умолчание приложения, а откат на более старую стратегию снова
     * спрятал бы от модели записи памяти, сделанные в чате.
     */
    private fun strategyOfWire(strategy: String): ContextStrategy =
        ContextStrategy.ofWire(strategy) ?: ContextStrategy.MEMORY

    /**
     * Заголовок чата — начало первого сообщения пользователя.
     */
    private fun titleFrom(message: String): String {
        val line = message.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= TITLE_LIMIT) line else line.take(TITLE_LIMIT - 1).trimEnd() + "…"
    }

    /**
     * Идентификатор чата и ветки диалога: 32 hex-символа. Идентификатор чата
     * уходит на сервер как идентификатор сессии, поэтому у чатов он разный,
     * а у одного чата — один и тот же всегда; идентификатор ветки — её метка
     * на сообщениях и в структуре веток.
     */
    private fun newHexId(): String = buildString {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private companion object {
        /** Заголовок чата до первого сообщения. */
        const val NEW_CHAT_TITLE = "Новый чат"

        /** Сколько символов первого сообщения попадает в заголовок списка. */
        const val TITLE_LIMIT = 40
    }
}
