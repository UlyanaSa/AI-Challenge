package com.osvin.aichallenge.repository

import com.osvin.aichallenge.data.*
import com.osvin.aichallenge.appAddress
import com.osvin.aichallenge.chatLinkTo
import com.osvin.aichallenge.copyToClipboard
import com.osvin.aichallenge.nowMillis
import com.osvin.aichallenge.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *
 * Инварианты ([loadInvariants]) тоже общие и живут отдельно от диалога: правила
 * проекта лежат на сервере в своём хранилище, а не в истории сообщений, и сервер
 * подставляет их в каждый запрос системным сообщением при любой стратегии. Клиент
 * их только показывает и правит явными действиями человека ([rememberInvariant],
 * [forgetInvariant]), а проверку запроса на конфликт делает сервер. Своей копии
 * умолчаний у клиента нет: пока человек не задал правил, снимок с сервера приходит
 * с ними же.
 *
 * Режим «Сервис» ([ChatMode.SERVICE]) ведёт не тот разговор, что путь `/v1`: чаты и история
 * живут в SQLite приватного сервиса (`/api/…`), и клиент их только читает ([loadServiceChats])
 * и показывает. Поэтому список чатов сервиса — своё состояние, а не список локальных чатов:
 * у них разные хранилища, и один список на двоих показал бы чаты одной базы среди чатов другой.
 * Общий чат у нескольких клиентов — это один чат с одним идентификатором: его открывают из
 * списка или по ссылке ([openServiceChat], [openSharedServiceChat]), а «Обновить»
 * ([refreshService]) перечитывает его, потому что о чужих записях сервис сам не сообщает.
 * Сводку прошлого чата строит сервис при заведении чата ([createServiceChat]) и передаёт
 * её модели сам; клиент показывает её строкой над лентой ([serviceSummary]) и в ленту
 * не кладёт — это контекст для модели, а не реплика диалога.
 *
 * GitHub ([loadGitHub], [connectGitHub]) — чужой сервис, и клиент его не знает:
 * подключение, список инструментов, доступ к API и сами вызовы держит сервер в отдельном
 * MCP-процессе, а сюда приходит только снимок ([GitHubConnection]). Поэтому клиент
 * не хранит ни состояния подключения, ни токена — токена он не видит вовсе: доступ
 * к GitHub сервер ищет сам на своей машине, а в снимке называет лишь логин, права
 * и место, откуда доступ взят. Вызов инструмента ([callGitHubTool]) остаётся
 * в ленте служебной записью ([MessageRole.TOOL]) с командой и результатом, но в модель
 * не уезжает: это след работы для человека, а не реплика диалога.
 *
 * Курсы валют ([CurrencyChat]) — единственное, что репозиторий ведёт сам, без человека:
 * монитор ([runCurrencyMonitor]) раз в минуту кладёт в закреплённый чат строку
 * с текущими курсами и на смене часа — сводку прошедшего часа. Пишет он их ролью
 * [MessageRole.MONITOR], поэтому лента их показывает, а модель не получает: это
 * показание прибора, а не реплика диалога. Оба окна изменения читаются одним маршрутом
 * ([loadCurrencyChange] для суток в шапке и чтение часа для сводки): считает изменение
 * сервер, а клиент только печатает цифры, что пришли.
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
        // Срок ставится на соединение, а не на весь запрос: ответ модели приходит минутами,
        // и общий срок запроса обрывал бы долгие ответы. Срок самого чтения курсов короче
        // и ставится там, где читаются курсы ([loadCurrencySnapshot])
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    },
    /**
     * Срок одного чтения курсов ([CURRENCY_READ_TIMEOUT_MS] по умолчанию).
     *
     * Параметром, а не только константой, — по тому же счёту, что время и пауза монитора
     * ([runCurrencyMonitor]): проверять срок приходится на медленном сервере, а ждать
     * в проверке настоящие двадцать секунд нечем.
     */
    private val currencyReadTimeoutMs: Long = CURRENCY_READ_TIMEOUT_MS,
    /**
     * Хранилище настроек сервиса на устройстве: адрес, ключ, модель и рамки запроса
     * переживают перезапуск приложения ([ServerSettings]).
     *
     * Параметром, а не платформенной функцией внутри, — по тому же счёту, что и хранилище
     * чатов ([store]): у Android его подставляет точка входа вместе с `Context` (там
     * `SharedPreferences`), у браузера — `localStorage`, а проверки кладут настройки
     * в память процесса и на диск машины не пишут.
     */
    private val settingsStorage: SettingsStorage = InMemorySettingsStorage()
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

    // Инварианты профиля: правила, которые ассистент нарушать не имеет права. Живут
    // отдельно и от памяти, и от задачи: правила не извлекаются из диалога и не
    // описывают работу — их объявляет человек, они лежат на сервере в своём хранилище
    // и уходят в каждый запрос системным сообщением. Снимок несёт и каталог видов,
    // поэтому подписи шторки берутся из ответа сервера. null — снимка ещё не было.
    private val _invariants = MutableStateFlow<InvariantSnapshot?>(null)
    val invariants: StateFlow<InvariantSnapshot?> = _invariants.asStateFlow()

    // Отказ сервера на чтение инвариантов и на правку правил; null — отказа не было.
    // Это своя ошибка: сбой правил не должен выглядеть как сбой памяти или задачи —
    // правку инварианта человек делает отдельным действием и ждёт отдельного ответа.
    private val _invariantsError = MutableStateFlow<String?>(null)
    val invariantsError: StateFlow<String?> = _invariantsError.asStateFlow()

    // Подключение к GitHub и его инструменты: клиент сам в GitHub не ходит, туда ходит
    // сервер в отдельном MCP-процессе, а сюда приходит только снимок. Живёт отдельно
    // от памяти, профиля и задачи: это чужой сервис, и его отказ не должен выглядеть
    // как отказ памяти. null — снимка ещё не было: сервер недоступен или чтения не было
    // вовсе, и «не подключено» отсюда не выводится — подключение держит сервер,
    // а не клиент, и второй копии этого флага на устройстве нет намеренно.
    private val _github = MutableStateFlow<GitHubConnection?>(null)
    val github: StateFlow<GitHubConnection?> = _github.asStateFlow()

    // Отказ сервера на подключение, отключение и вызов инструмента; null — отказа не было.
    // Это своя ошибка, а не [memoryError]: сбой GitHub не должен выглядеть как сбой памяти,
    // и причина отказа инструмента не должна теряться среди ошибок диалога.
    private val _githubError = MutableStateFlow<String?>(null)
    val githubError: StateFlow<String?> = _githubError.asStateFlow()

    // Изменение курсов за сутки для шапки закреплённого чата; null — ещё не читали
    // или прочитать не удалось. Это снимок, а не вывод клиента: первое и последнее
    // значение курса за окно считает сервер, и вторая копия этих подсчётов на клиенте
    // разошлась бы с серверной — тем более что делить и вычитать деньги клиенту нечем.
    private val _currencyChange = MutableStateFlow<CurrencyChangeFeed?>(null)
    val currencyChange: StateFlow<CurrencyChangeFeed?> = _currencyChange.asStateFlow()

    // Отказ сервера на чтение изменения курсов; null — отказа не было. Это своя ошибка,
    // а не [memoryError]: сбой курсов не должен выглядеть как сбой памяти. Снимок при
    // отказе не стирается: показать «изменения нет» там, где оно уже прочитано, значило
    // бы соврать — причина остаётся строкой рядом со снимком.
    private val _currencyChangeError = MutableStateFlow<String?>(null)
    val currencyChangeError: StateFlow<String?> = _currencyChangeError.asStateFlow()

    // Настройки генерации из шторки. Стратегия в них — стратегия активного чата,
    // поэтому живут они рядом с активным чатом, а не в слое интерфейса.
    private val _settings = MutableStateFlow(GenerationSettings())
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

    // Провайдер выбирает сервер, а не клиент, поэтому модель последнего ответа
    // приходит в теле ответа и хранится здесь: в шапке чата видно, кто ответил
    // на самом деле, а не что выбрано в настройках. В облачном режиме это та же
    // модель, что в настройках, в локальном — загруженная модель Ollama.
    private val _answerModel = MutableStateFlow<String?>(null)
    val answerModel: StateFlow<String?> = _answerModel.asStateFlow()

    private val _answerProvider = MutableStateFlow<String?>(null)
    val answerProvider: StateFlow<String?> = _answerProvider.asStateFlow()

    // Лента сравнения двух сборок модели: свои ходы, своё состояние запроса. Держится
    // отдельно от ленты чата, потому что на один вопрос сравнение отвечает дважды, и второй
    // ответ в обычной ленте выглядел бы повтором. В хранилище сравнение не пишется вовсе:
    // сравнивают сборки модели, а не ведут диалог (см. [CompareFeed]).
    private val _compare = MutableStateFlow(CompareFeed())
    val compare: StateFlow<CompareFeed> = _compare.asStateFlow()

    // Настройки приватного сервиса `/api/…` (`ChatMode.SERVICE`): адрес, ключ и рамки
    // запроса. Поднимаются из хранилища устройства ([settingsStorage]) — без этого после
    // обновления страницы приложение возвращалось в локальный режим, и чаты сервиса,
    // живые в его SQLite, не подгружались: спрашивать о них стало некому. Здесь же, а не
    // в слое интерфейса: от режима зависит, кому адресован запрос, а это решает
    // репозиторий, а не экран.
    private val _serverSettings = MutableStateFlow(ServerSettings())
    val serverSettings: StateFlow<ServerSettings> = _serverSettings.asStateFlow()

    init {
        // Настройки прошлого запуска ставятся до того, как их начнут читать экран
        // и [loadServiceChats]: режим «Сервис» — это то, по чему решается, спрашивать
        // ли сервис вообще
        restoredServerSettings()?.let { _serverSettings.value = it }
    }

    // Итог проверки подключения к сервису: своё поле, а не общий [isServerOnline] —
    // путь `/v1` и сервис `/api/…` отказывают по разным причинам, и сводить их в один
    // флаг значило бы показать «сервер недоступен» там, где на деле не принят ключ.
    private val _serviceConnection = MutableStateFlow(ConnectionReport())
    val serviceConnection: StateFlow<ConnectionReport> = _serviceConnection.asStateFlow()

    // Лента сервисного чата: держится отдельно от [messages], потому что это другой
    // разговор. Сессии сервиса живут в его SQLite, а история локального чата — в БД
    // устройства; сложи их в один список — и в одном диалоге показались бы реплики
    // другого, а переключение режима стирало бы историю то там, то здесь.
    private val _serviceMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val serviceMessages: StateFlow<List<ChatMessage>> = _serviceMessages.asStateFlow()

    // Идентификатор открытой сессии сервиса; null — сессии ещё нет. На экране живёт
    // в памяти, а на устройстве — записью [SETTINGS_LAST_CHAT]: саму сессию ведёт сервис,
    // и второй её копии у клиента не нужно, но идентификатор нужен — по нему после
    // перезапуска приложения открывается тот же разговор, а история поднимается из сервиса
    // ([loadServiceHistory]). Ставится он только через [setServiceChat], чтобы экран
    // и устройство не разошлись.
    private val _serviceChatId = MutableStateFlow<String?>(null)
    val serviceChatId: StateFlow<String?> = _serviceChatId.asStateFlow()

    // Заголовок открытой сессии сервиса: его называет ответ сервиса, поэтому в шапке
    // стоит он, а не заголовок локального чата, который открыт в другой панели.
    private val _serviceChatTitle = MutableStateFlow<String?>(null)
    val serviceChatTitle: StateFlow<String?> = _serviceChatTitle.asStateFlow()

    // Чаты сервиса для боковой панели (`GET /api/chats`). Источник истины по ним — сервис:
    // своего списка чатов у режима «Сервис» нет, и показывать вместо него локальные
    // значило бы выдавать БД устройства за разговоры, которых на сервисе нет.
    private val _serviceChats = MutableStateFlow(ServiceChatList())
    val serviceChats: StateFlow<ServiceChatList> = _serviceChats.asStateFlow()

    // Признак того, что список чатов сервиса уже читали: до этого панель пуста, а не «чатов нет».
    private val _serviceChatsLoaded = MutableStateFlow(false)
    val serviceChatsLoaded: StateFlow<Boolean> = _serviceChatsLoaded.asStateFlow()

    // Отказ на чтение списка сервисных чатов; null — отказа не было. Это своя ошибка,
    // а не [state]: список читается и когда никакой чат не открыт, и сбой списка не должен
    // выглядеть сбоем разговора.
    private val _serviceChatsError = MutableStateFlow<String?>(null)
    val serviceChatsError: StateFlow<String?> = _serviceChatsError.asStateFlow()

    // Сводка прошлого чата у открытого сервисного чата и причина, по которой её не построили.
    // Хранятся отдельно от ленты намеренно: сводка — контекст, подставленный сервисом
    // в запрос к модели, а не реплика диалога, и в ленту она не попадает (§5).
    private val _serviceSummary = MutableStateFlow<String?>(null)
    val serviceSummary: StateFlow<String?> = _serviceSummary.asStateFlow()

    private val _serviceSummaryError = MutableStateFlow<String?>(null)
    val serviceSummaryError: StateFlow<String?> = _serviceSummaryError.asStateFlow()

    // Вызовы сервиса вынесены в [ServiceClient]: ключ уходит только туда, и только
    // там живёт разбор его ошибок. Клиент берётся тот же, что у пути `/v1`, — движок
    // общий, адрес задаёт вызов.
    private val serviceClient = ServiceClient(client)

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
     * Новый чат со своей историей и своей сессией агента. Стратегия контекста
     * берётся из настроек ([GenerationSettings]) и остаётся в самом чате
     * ([Chat.strategy]): дальше её меняют у чата ([updateStrategy]), и она
     * переживает перезапуск приложения. Диалог начинается с основной линии:
     * веток ещё нет, активной ветки тоже.
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
        // Правила общие для профиля и от чата не зависят, поэтому снимок остаётся
        // тем же; читаем его ниже, чтобы шторка открывалась с тем, что лежит на сервере
        _invariantsError.value = null
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
        // Инварианты общие для профиля и читаются там же, где профиль: шторка должна
        // открываться с действующими правилами этого проекта, а не с пустым списком
        loadInvariants()
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
        // Чужого снимка инвариантов не бывает: правила живут по профилю, а не по чату,
        // поэтому прежний остаётся на месте и ниже только подтверждается сервером
        _invariantsError.value = null
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
        // Инварианты читаем на каждом открытии чата: их могли поправить с другого
        // устройства, а шторка должна открываться с тем, что лежит на сервере
        loadInvariants()
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
     *
     * Закреплённый чат не удаляется: это лента, которую приложение ведёт само
     * ([CurrencyChat]), и кнопки удаления у неё на экране нет. Сторож нужен и здесь:
     * удали чат — монитор на следующем ударе завёл бы новый пустой, и вся лента
     * потерялась бы вместе с чатом, а сессия на сервере осталась бы висеть.
     */
    suspend fun deleteChat(id: String) {
        if (store.chat(id)?.pinned == true) return
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
            // Шторка инвариантов чистится вместе с чатом, как и память: активного
            // диалога больше нет, и следующий открытый чат прочитает правила заново
            _invariants.value = null
            _invariantsError.value = null
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
     *
     * Записи о вызовах инструментов ([MessageRole.TOOL]) в историю не включаются: это след
     * работы агента для человека, а не реплика диалога. Модель не должна видеть команды,
     * которых не было в переписке с ней, — иначе вызов выглядел бы уже сделанным, и она
     * повторила бы его или сослалась на данные, которых в её ответе не было.
     *
     * Строки монитора ([MessageRole.MONITOR]) не включаются по той же причине: курсы
     * валют называло приложение, а не человек, и в истории модели они читались бы как
     * реплика пользователя. В закреплённом чате-ленте вопросов не бывает вовсе — его
     * поле ввода скрыто, и запрос туда не уходит ([Chat.pinned]).
     */
    suspend fun sendMessage(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return

        val chat = _activeChat.value
        if (chat == null) {
            _state.value = ChatUiState.Error("Чат не выбран")
            return
        }
        // Лента монитора — не диалог: писать в неё нечем (поле ввода скрыто), а модель
        // здесь отвечать не на что. Сторож нужен и в репозитории, а не только на экране:
        // иначе прямой вызов отправил бы на сервер вопрос, которого человек не задавал.
        if (chat.pinned) return

        _state.value = ChatUiState.Loading

        // Ветка, в которой продолжается диалог: её метку получат оба новых сообщения
        val activeBranchId = _activeBranchId.value
        val branches = if (settings.strategy == ContextStrategy.BRANCHES) store.branches(chat.id) else null

        // История чата до этого вопроса: все ветки вместе, у каждой своя метка.
        // Записи о вызовах инструментов отсеиваются: это след работы для человека
        // ([MessageRole.TOOL]), а не реплика диалога, и модель не должна видеть команды,
        // которых не было в переписке с ней. Строки монитора — там же ([MessageRole.MONITOR]):
        // их писало приложение, и в истории модели они выглядели бы словами пользователя.
        val history = store.messages(chat.id)
            .filter { it.role != MessageRole.TOOL && it.role != MessageRole.MONITOR }

        try {
            val response = client.post("$baseUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        // Модель не называется: её выбирает конфигурация сервера
                        // (`LLM_PROVIDER`), и в локальном режиме присланное имя всё равно
                        // было бы подменено загруженной моделью Ollama. Отчёт и шапка
                        // берут имя из ответа — оно и есть настоящее
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
                    // Имя модели берём из ответа: клиент модель не выбирает, и только ответ
                    // знает, кто отвечал — в локальном режиме это загруженная модель Ollama
                    platformLog("agent", report.logEntry(chatResponse.model ?: MODEL_NOT_NAMED))
                    _memory.value = report.memory
                    applyTaskReport(report.task)
                }

                // Вызовы инструментов из этого ответа остаются при сообщении: под пузырём
                // видно, что агент делал и чем это кончилось, — иначе данные в ответе
                // выглядели бы взятыми из ниоткуда. Пустой отчёт — вызовов не было.
                val assistantMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = chatResponse.reply,
                    branchId = activeBranchId,
                    tools = chatResponse.tokens?.tools?.calls.orEmpty()
                )
                _messages.value = _messages.value + assistantMessage
                store.append(chat.id, assistantMessage)
                _chats.value = store.chats()

                _state.value = ChatUiState.Success(chatResponse.reply)
                _isServerOnline.value = true
                _answerModel.value = chatResponse.model
                _answerProvider.value = chatResponse.provider

                // Агент мог дописать память сам: панель показывает то, что теперь
                // лежит в слоях на сервере, а не только то, что ушло в этот ответ
                loadMemory()
                // Кнопка GitHub показывает правду: сервер инструментов мог умереть между
                // вопросами, а снимок на клиенте обновляется только чтением — без него
                // подключение выглядело бы живым, хотя вызовы уже не работают
                loadGitHub()
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
     * Сохранение настроек сервиса: адрес, ключ, модель и рамки запроса.
     *
     * Смена адреса или ключа сбрасывает сессию сервиса ([_serviceChatId]) и её ленту:
     * сессия принадлежит тому сервису, на который её завели, и, уведя запрос на другой
     * адрес или под другой ключ, её пришлось бы разыскивать с чужими правами. Смена
     * только режима сессию не трогает — переключение туда и обратно не должно терять
     * разговор. Состояние соединения тоже сбрасывается: с новым адресом и ключом
     * прежний итог проверки к нему не относится.
     *
     * Список чатов сервиса отпускается вместе с сессией и по той же причине: он принадлежит
     * тому сервису, который его отдал, и показывать чужие чаты под новым адресом значило бы
     * выдавать один сервис за другой.
     */
    fun updateServerSettings(settings: ServerSettings) {
        val previous = _serverSettings.value
        _serverSettings.value = settings
        persistServerSettings(settings)
        if (settings.baseUrl != previous.baseUrl || settings.apiKey != previous.apiKey) {
            forgetServiceChat()
            forgetServiceChats()
            _serviceConnection.value = ConnectionReport()
        }
    }

    /**
     * Настройки сервиса, записанные прошлым запуском; null — их не сохраняли.
     *
     * Читаются только целиком: адрес без ключа или ключ без адреса — это не настройка,
     * а её обломок, и восстановить половину значило бы отправить запрос не туда, куда
     * человек настраивал. Незнакомый режим — тоже отказ поднимать настройки: подставить
     * вместо него текущий значило бы увести запрос на другой путь молча.
     */
    private fun restoredServerSettings(): ServerSettings? {
        val mode = settingsStorage.read(SETTINGS_MODE)?.let { stored ->
            ChatMode.entries.firstOrNull { it.name == stored }
        } ?: return null
        val baseUrl = settingsStorage.read(SETTINGS_BASE_URL) ?: return null
        val defaults = ServerSettings()
        return ServerSettings(
            mode = mode,
            baseUrl = baseUrl,
            apiKey = settingsStorage.read(SETTINGS_API_KEY).orEmpty(),
            model = settingsStorage.read(SETTINGS_MODEL) ?: defaults.model,
            temperature = settingsStorage.read(SETTINGS_TEMPERATURE)?.toDoubleOrNull()
                ?: defaults.temperature,
            maxTokens = settingsStorage.read(SETTINGS_MAX_TOKENS)?.toIntOrNull()
                ?: defaults.maxTokens,
            contextWindow = settingsStorage.read(SETTINGS_CONTEXT_WINDOW)?.toIntOrNull()
                ?: defaults.contextWindow
        )
    }

    /** Кладёт настройки сервиса на устройство: их же читает следующий запуск. */
    private fun persistServerSettings(settings: ServerSettings) {
        settingsStorage.write(SETTINGS_MODE, settings.mode.name)
        settingsStorage.write(SETTINGS_BASE_URL, settings.baseUrl)
        settingsStorage.write(SETTINGS_API_KEY, settings.apiKey)
        settingsStorage.write(SETTINGS_MODEL, settings.model)
        settingsStorage.write(SETTINGS_TEMPERATURE, settings.temperature.toString())
        settingsStorage.write(SETTINGS_MAX_TOKENS, settings.maxTokens.toString())
        settingsStorage.write(SETTINGS_CONTEXT_WINDOW, settings.contextWindow.toString())
    }

    /**
     * Идентификатор сервисного чата, открытого прошлым запуском: по нему приложение
     * возвращается в тот же разговор. null — открывать нечего: чат не открывали или удалили.
     */
    fun rememberedServiceChatId(): String? =
        settingsStorage.read(SETTINGS_LAST_CHAT)?.takeIf { it.isNotBlank() }

    /**
     * Сделать сессию сервиса текущей (или отпустить её — `null`) и запомнить это на устройстве.
     *
     * Присвоение и память в одном месте намеренно: идентификатор, оставшийся на устройстве
     * от прошлого разговора, но забытый на экране (или наоборот), вернул бы человека
     * в чужой чат — а разойтись им теперь есть от чего: экран живёт до перезапуска,
     * запись на устройстве — дольше.
     */
    private fun setServiceChat(chatId: String?) {
        _serviceChatId.value = chatId
        settingsStorage.write(SETTINGS_LAST_CHAT, chatId)
    }

    /** Отпустить открытую сессию сервиса, её ленту, заголовок и сводку: она не этого сервиса. */
    private fun forgetServiceChat() {
        setServiceChat(null)
        _serviceChatTitle.value = null
        _serviceMessages.value = emptyList()
        _serviceSummary.value = null
        _serviceSummaryError.value = null
    }

    /** Забыть список чатов сервиса: в локальном режиме и при смене адреса или ключа он не наш. */
    private fun forgetServiceChats() {
        _serviceChats.value = ServiceChatList()
        _serviceChatsError.value = null
        _serviceChatsLoaded.value = false
    }

    /**
     * Проверка подключения к сервису: `GET /api/health` без ключа, затем `GET /api/models` с ним.
     *
     * Сначала живость, потом доступ: `/api/health` отвечает без ключа и говорит, поднят
     * ли сервис и загружена ли модель, — и запрашивать список моделей у лежащего сервиса
     * было бы незачем. Состояние выбирается по тому, что именно отказало: не принят
     * ключ — [ServerConnectionState.AUTH_ERROR], служба молчит и 503 — [ServerConnectionState.UNAVAILABLE],
     * ответила без нужной модели — [ServerConnectionState.MODEL_MISSING]. Список моделей
     * возвращается в отчёте, чтобы шторка показывала, из чего выбирать.
     *
     * Настройки приходят параметром, а не читаются из сохранённых: проверяют то, что человек
     * видит в шторке сейчас. Иначе набранный, но не сохранённый ключ уходил бы в проверку
     * пустым, и на верный ключ шторка отвечала бы «Ошибка авторизации» — то есть врала бы
     * о том, что на экране.
     */
    suspend fun checkServiceConnection(settings: ServerSettings): ConnectionReport {
        _serviceConnection.value = ConnectionReport(ServerConnectionState.CHECKING, "Проверка подключения…")
        val report = try {
            val health = serviceClient.health(settings)
            try {
                val models = serviceClient.models(settings).map { it.name }
                if (models.none { it == settings.model }) {
                    ConnectionReport(
                        state = ServerConnectionState.MODEL_MISSING,
                        message = "Модель недоступна: ${settings.model} нет в списке моделей сервиса",
                        models = models
                    )
                } else {
                    ConnectionReport(
                        state = ServerConnectionState.CONNECTED,
                        message = "Подключён: сервис отвечает, модель ${settings.model} доступна",
                        models = models
                    )
                }
            } catch (e: ServiceApiException) {
                if (e.code == CODE_UNAUTHORIZED) {
                    ConnectionReport(ServerConnectionState.AUTH_ERROR, "Ошибка авторизации: ${e.message}")
                } else {
                    ConnectionReport(ServerConnectionState.UNAVAILABLE, "Недоступен: ${e.message}")
                }
            } catch (e: Exception) {
                ConnectionReport(ServerConnectionState.UNAVAILABLE, "Недоступен: ${e.message ?: "нет соединения"}")
            }
        } catch (e: ServiceApiException) {
            ConnectionReport(ServerConnectionState.UNAVAILABLE, "Недоступен: ${e.message}")
        } catch (e: Exception) {
            ConnectionReport(ServerConnectionState.UNAVAILABLE, "Недоступен: ${e.message ?: "нет соединения"}")
        }
        _serviceConnection.value = report
        return report
    }

    /**
     * Заведение сессии сервиса: `POST /api/chats`.
     *
     * Лента очищается: новая сессия — новый разговор, и показывать в ней ходы прошлой
     * значило бы показывать историю, которой у этой сессии нет. Заголовок и сводку
     * называет ответ сервиса ([applyServiceChat]): прошлый чат сервис выбирает сам,
     * поэтому и то, что из него свернули, знает только он. null — сервис отказал;
     * причина уже выставлена в [state] тем же механизмом, что и отказ на ход.
     */
    suspend fun createServiceChat(): String? {
        _state.value = ChatUiState.Loading
        return try {
            val created = serviceClient.createChat(_serverSettings.value, title = null)
            setServiceChat(created.chatId)
            _serviceMessages.value = emptyList()
            applyServiceChat(created)
            _state.value = ChatUiState.Idle
            created.chatId
        } catch (e: ServiceApiException) {
            applyServiceError(e)
            null
        } catch (e: Exception) {
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
            null
        }
    }

    /**
     * Заголовок заведённой сессии и то, что сервис сказал про сводку её прошлого чата (§1.2).
     *
     * Одним местом на оба пути заведения — по кнопке и на повторной попытке хода: сводка
     * приходит тем же ответом, а пустые строки не сохраняются, чтобы «Сводка прошлого чата: »
     * без текста не появлялась в ленте (§5).
     */
    private fun applyServiceChat(created: ServiceChatCreated) {
        _serviceChatTitle.value = created.title.takeIf { it.isNotBlank() }
        _serviceSummary.value = created.summary?.takeIf { it.isNotBlank() }
        _serviceSummaryError.value = created.summaryError?.takeIf { it.isNotBlank() }
    }

    /**
     * Открытие чата сервиса по идентификатору: `GET /api/chats/{id}` и ход в него же (§5).
     *
     * Так открывают и чат из списка, и чат из ссылки: идентификатор у сервисного чата один
     * на всех клиентов с ключом, поэтому открыть его — это и есть показать общий разговор.
     * Прошлый разговор отпускается до чтения: держать чужую ленту, сводку и заголовок под
     * новым идентификатором значило бы показать один чат под именем другого.
     */
    suspend fun openServiceChat(chatId: String) {
        forgetServiceChat()
        setServiceChat(chatId)
        loadServiceHistory()
    }

    /**
     * Чат из ссылки (`?chat=<id>`): режим переводится в «Сервис» и чат открывается (§5).
     *
     * Ссылкой делятся сервисным чатом, поэтому и открывать её нужно там, где он живёт:
     * оставшись в локальном режиме, клиент показал бы пустой локальный чат с тем же
     * идентификатором, и разговор не нашёлся бы. Ключ берётся тот, что лежит в хранилище
     * устройства ([ServerSettings]): после перезапуска он там же, где его оставил человек.
     */
    suspend fun openSharedServiceChat(chatId: String) {
        // Через общее сохранение, а не присвоением: режим — часть настроек, и переключённый
        // по ссылке он должен остаться на устройстве. Иначе после обновления страницы ссылка
        // открывалась бы в локальном режиме — тот же отказ, что и с потерянными настройками
        if (_serverSettings.value.mode != ChatMode.SERVICE) {
            updateServerSettings(_serverSettings.value.copy(mode = ChatMode.SERVICE))
        }
        openServiceChat(chatId)
    }

    /**
     * Список чатов сервиса для боковой панели: `GET /api/chats` (§5).
     *
     * В локальном режиме запрос не делается вовсе: панель показывает локальные чаты,
     * и спрашивать сервис, которого в этом режиме не касаются, значило бы носить ключ
     * туда, где он не нужен. Список принадлежит тому сервису, который его отдал, поэтому
     * при смене адреса или ключа он отпускается ([updateServerSettings]).
     *
     * Отказ чтения не стирает прочитанное: показать «чатов нет» там, где список уже был,
     * значило бы соврать — причина остаётся строкой рядом со снимком ([serviceChatsError]).
     */
    suspend fun loadServiceChats() {
        if (_serverSettings.value.mode != ChatMode.SERVICE) {
            forgetServiceChats()
            return
        }
        try {
            _serviceChats.value = serviceClient.chats(_serverSettings.value)
            _serviceChatsError.value = null
        } catch (e: ServiceApiException) {
            _serviceChatsError.value = serviceErrorText(e)
        } catch (e: Exception) {
            _serviceChatsError.value = e.message ?: "Сетевая ошибка"
        }
        // Прочитанным список считается и после отказа: иначе панель мигала бы пустотой
        // и после ответа сервиса — а причина отказа уже стоит рядом строкой
        _serviceChatsLoaded.value = true
    }

    /**
     * «Обновить» у ленты сервиса: перечитать список чатов и историю открытого чата (§5).
     *
     * Общий чат у нескольких клиентов — это один чат на сервисе, и о чужих записях сервис
     * сам не сообщает: без перечитывания лента осталась бы снимком момента открытия.
     */
    suspend fun refreshService() {
        loadServiceChats()
        loadServiceHistory()
    }

    /**
     * Есть ли чем делиться ссылкой: адрес страницы знает только браузер ([appAddress]).
     *
     * Где адреса нет — приложение Android, iOS, десктоп, — остаётся голый идентификатор:
     * его не скопировать (буфера на этих платформах у клиента нет, `copyToClipboard` там
     * false) и не открыть как ссылку, поэтому кнопка «Поделиться» в шапке не показывается
     * (`ChatTopBar.canShare`). Признак живёт здесь, рядом с самим адресом: платформенная
     * разница — в одном месте, а не в вёрстке.
     */
    val canShareServiceChat: Boolean = appAddress() != null

    /**
     * Ссылка на открытый чат сервиса для «Поделиться» (§5): `<адрес приложения>/?chat=<id>`.
     *
     * Копирование в буфер пробуем здесь же ([copyToClipboard]) и исход возвращаем наружу
     * ([ServiceShareLink]): там, где буфера нет или запись отклонена, ссылку показывают
     * текстом для выделения, а не обещают «скопировано». null — делиться нечем: либо
     * открытого чата нет, либо платформа не знает адреса страницы, и ссылки не собрать
     * ([canShareServiceChat]).
     */
    suspend fun shareServiceChat(): ServiceShareLink? {
        val chatId = _serviceChatId.value?.takeIf { it.isNotBlank() } ?: return null
        val address = appAddress() ?: return null
        val text = chatLinkTo(address, chatId)
        return ServiceShareLink(text = text, copied = copyToClipboard(text))
    }

    /**
     * Ход в сервисном чате: `POST /api/chat` и запись ответа в ленту.
     *
     * Сессия заводится сама, если её ещё нет: человек выбирает режим и пишет, а не
     * отдельно заводит сессию. Если сервис ответил `chat_not_found` — сессию на сервере
     * удалили, и её поднимают заново с **одной** повторной попыткой; больше одной
     * намеренно, иначе мёртвая сессия крутила бы запросы в цикле.
     *
     * Ошибку показываем существующим механизмом чата ([ChatUiState.Error]), а не своим
     * состоянием: и отказ сервиса, и ошибка сети должны стоять в ленте там, где читает
     * человек, — рядом с местом, куда он писал.
     */
    suspend fun sendServiceMessage(text: String) {
        if (text.isBlank()) return
        val settings = _serverSettings.value
        try {
            val chatId = _serviceChatId.value ?: createServiceChat() ?: return
            // Ожидание ставится после того, как сессия есть: заведение сессии тоже
            // ход к сервису, но показывать его отдельным ожиданием нечего — человек
            // ждёт ответа на своё сообщение, а не открытия сессии
            _state.value = ChatUiState.Loading
            val response = try {
                serviceClient.send(settings, chatId, text)
            } catch (e: ServiceApiException) {
                if (e.code != CODE_CHAT_NOT_FOUND) throw e
                // Сессии на сервере больше нет: заводим новую и повторяем ровно один раз.
                // Сводку и заголовок берём из того же ответа: это уже другая сессия,
                // и то, что в шапке и строке сводки, должно быть про неё
                val fresh = serviceClient.createChat(settings, title = null)
                setServiceChat(fresh.chatId)
                applyServiceChat(fresh)
                serviceClient.send(settings, fresh.chatId, text)
            }
            _serviceMessages.value = _serviceMessages.value +
                ChatMessage(MessageRole.USER, text) +
                ChatMessage(MessageRole.ASSISTANT, response.reply)
            // Имя ответившей модели берём из ответа и в режиме сервиса: сервис мог ответить
            // своей моделью, а не той, что просили, — и в шапке должно стоять то, кто ответил
            _answerModel.value = response.model?.takeIf { it.isNotBlank() }
            _state.value = ChatUiState.Success(response.reply)
        } catch (e: ServiceApiException) {
            applyServiceError(e)
        } catch (e: Exception) {
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Чтение истории открытой сессии сервиса: `GET /api/chats/{id}`.
     *
     * Лента заменяется историей сервиса целиком, а не дополняется: сервер — источник
     * правды о ней, и добавить прочитанное к тому, что уже на экране, значило бы удвоить
     * каждый ход после повторного чтения. Оттуда же приходят заголовок и сводка прошлого
     * чата (§1.3) — сводку в ленту не кладём: это контекст для модели, а не реплика (§5).
     * Причина отказа построения сводки остаётся прежней: история её не несёт, и обнулять
     * сказанное при создании чата значило бы стереть причину, о которой человек уже знает.
     */
    suspend fun loadServiceHistory() {
        val chatId = _serviceChatId.value ?: return
        try {
            val history = serviceClient.history(_serverSettings.value, chatId)
            _serviceMessages.value = history.messages.map { stored ->
                ChatMessage(roleFrom(stored.role), stored.content)
            }
            _serviceChatTitle.value = history.title.takeIf { it.isNotBlank() }
            _serviceSummary.value = history.summary?.takeIf { it.isNotBlank() }
        } catch (e: ServiceApiException) {
            applyServiceError(e)
        } catch (e: Exception) {
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Удаление сессии сервиса: `DELETE /api/chats/{id}`.
     *
     * Сессия и лента отпускаются только после успеха: если сервис отказал, ссылка
     * на сессию остаётся — иначе разговор был бы потерян на клиенте, но жив на сервере.
     */
    suspend fun deleteServiceChat() {
        val chatId = _serviceChatId.value ?: return
        try {
            serviceClient.deleteChat(_serverSettings.value, chatId)
            forgetServiceChat()
        } catch (e: ServiceApiException) {
            applyServiceError(e)
        } catch (e: Exception) {
            _state.value = ChatUiState.Error(e.message ?: "Сетевая ошибка")
        }
    }

    /**
     * Отказ сервиса — в состояние ошибки чата.
     *
     * Текст берётся у сервиса (`message`) и дополняется `Retry-After`, если он есть:
     * «повторите через N с» человеку нужнее кода 429. `unauthorized` отдельно переводит
     * состояние соединения в [ServerConnectionState.AUTH_ERROR]: ключ мог протухнуть
     * посреди работы, и это должно быть видно в шторке, а не только строкой в ленте.
     * В лог уходит код, а не текст: текст может нести данные пользователя.
     */
    private fun applyServiceError(e: ServiceApiException) {
        _state.value = ChatUiState.Error(serviceErrorText(e))
    }

    /**
     * Отказ сервиса словами для человека: сообщение сервиса и срок повторения, если он назван.
     *
     * Отдельно от [applyServiceError], потому что отказ видят не только в ленте чата:
     * список чатов сервиса показывает причину рядом с собой ([serviceChatsError]), и вторая
     * копия «повторите через N с» разошлась бы с первой. Побочный перевод состояния
     * соединения — тот же, что и у отказа на ход: `unauthorized` виден в шторке сервиса,
     * каким бы запросом ключ ни отвергли.
     */
    private fun serviceErrorText(e: ServiceApiException): String {
        val retry = e.retryAfterSeconds?.let { ". Повторите через $it с" }.orEmpty()
        if (e.code == CODE_UNAUTHORIZED) {
            _serviceConnection.value = ConnectionReport(
                state = ServerConnectionState.AUTH_ERROR,
                message = "Ошибка авторизации: ${e.message}"
            )
        }
        platformLog("service", "отказ ${e.code}")
        return e.message + retry
    }

    /**
     * Описание панелей сравнения: чем одна сборка модели отличается от другой.
     *
     * Читается при включении сравнения, до первого вопроса: окна подписываются заголовком,
     * моделью, окном контекста и бюджетом, и по этим подписям видно, что именно сравнивают.
     * Настройки приходят с сервера, а не задаются в интерфейсе: сравнивают серверные
     * сборки, и клиент, назвавший их сам, показывал бы свои умолчания вместо серверных.
     *
     * Отказ чтения не выключает сравнение: вопрос всё равно можно задать, и ответы придут
     * с заголовками окон ([PaneAnswer.title]) — подписи будут короче, но верные.
     */
    suspend fun loadCompare() {
        try {
            val response = client.get("$baseUrl/v1/chat/dual")
            if (response.status.isSuccess()) {
                val info = response.body<DualChatInfo>()
                _compare.value = _compare.value.copy(panes = info.panes, error = null)
            } else {
                _compare.value = _compare.value.copy(
                    error = "Описание сборок недоступно: сервер ответил ${response.status.value}"
                )
                platformLog("agent", "[agent] Сравнение: описание сборок не загружено: ${response.status.value}")
            }
        } catch (e: Exception) {
            _compare.value = _compare.value.copy(
                error = e.message ?: "Описание сборок недоступно: нет соединения"
            )
            platformLog("agent", "[agent] Сравнение: описание сборок не загружено: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Вопрос обеим сборкам сразу: один запрос, два ответа, оба окна пишут одновременно.
     *
     * История у окон своя и собирается из ходов: каждая сборка продолжает свой разговор,
     * а не читает ответы соседки. Вопрос в ленте хранится один раз ([CompareTurn]):
     * спрашивали у двух сборок не два вопроса, а один.
     *
     * Ответ одной панели может не прийти, и это не отменяет сравнение: причина отказа
     * остаётся в её окне ([PaneAnswer.error]). Общий отказ (сервер недоступен, набор
     * настроек отвергнут) остаётся строкой над окнами, а вопрос в ленту не попадает.
     */
    suspend fun sendCompare(message: String, settings: GenerationSettings = GenerationSettings()) {
        if (message.isBlank()) return

        val asker = _activeChat.value
        if (asker == null) {
            _compare.value = _compare.value.copy(error = "Чат не выбран")
            return
        }
        // Лента монитора — не диалог: в неё пишет приложение, и вопроса человека там нет
        if (asker.pinned) return

        val feed = _compare.value
        // Вопрос печатается в обоих окнах сразу, ещё до ответа: иначе панели выглядят
        // пустыми всё время, пока модель думает, и непонятно, ушёл вопрос или нет
        _compare.value = feed.copy(loading = true, error = null, pendingQuestion = message)

        try {
            val response = client.post("$baseUrl/v1/chat/dual") {
                contentType(ContentType.Application.Json)
                setBody(
                    DualChatRequest(
                        message = message,
                        baselineHistory = history(feed, Pane.BASELINE),
                        optimizedHistory = history(feed, Pane.OPTIMIZED),
                        systemPrompt = settings.systemPrompt.ifBlank { null },
                        maxTokens = settings.maxTokens,
                        stop = settings.stopWords.ifEmpty { null },
                        temperature = settings.temperature
                    )
                )
            }

            if (response.status.isSuccess()) {
                val answers = response.body<DualChatResponse>().answers
                val turn = CompareTurn(
                    question = message,
                    // Панель, которой в ответе нет, называется отказом: пустое окно
                    // с вечным ожиданием читалось бы как «модель ещё думает»
                    baseline = answers.firstOrNull { it.pane == Pane.BASELINE } ?: missing(Pane.BASELINE, feed),
                    optimized = answers.firstOrNull { it.pane == Pane.OPTIMIZED } ?: missing(Pane.OPTIMIZED, feed)
                )
                _compare.value = _compare.value.copy(
                    turns = _compare.value.turns + turn,
                    panes = panesFrom(turn, _compare.value),
                    loading = false,
                    pendingQuestion = null,
                    error = null
                )
                _isServerOnline.value = true
                turn.optimized?.tokens?.let { report ->
                    platformLog("agent", report.logEntry(turn.optimized.model ?: MODEL_NOT_NAMED))
                }
            } else {
                val reason = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                val error = reason ?: "Ошибка сервера: ${response.status.value}"
                platformLog("agent", "[agent] Сравнение: отказ сервера: $error")
                _compare.value = _compare.value.copy(loading = false, pendingQuestion = null, error = error)
            }
        } catch (e: Exception) {
            _isServerOnline.value = false
            platformLog("agent", "[agent] Сравнение: сервер недоступен: ${e.message ?: "нет соединения"}")
            _compare.value = _compare.value.copy(
                loading = false,
                pendingQuestion = null,
                error = e.message ?: "Сервер недоступен: нет соединения"
            )
        }
    }

    /**
     * Очистка ленты сравнения: ходы убираются, описание окон остаётся.
     *
     * Описание перечитывать не нужно — сборки те же, — а вот контекст разговора у окон
     * начинается заново: очистка для того и нужна, чтобы следующий вопрос задавался
     * по чистому листу, без истории предыдущих ответов в запросе.
     */
    fun clearCompare() {
        _compare.value = CompareFeed(panes = _compare.value.panes)
    }

    /** История одного окна: его вопросы и его ответы, старые — первыми. */
    private fun history(feed: CompareFeed, pane: String): List<PaneMessage>? = feed.turns
        .flatMap { turn ->
            buildList {
                add(PaneMessage(role = MessageRole.USER.wire, content = turn.question))
                // Отказ в историю не идёт: слова модели в ответе нет, и пустая реплика
                // ассистента сбивала бы следующее обращение
                turn.answer(pane)
                    ?.takeIf { !it.failed && it.reply.isNotBlank() }
                    ?.let { add(PaneMessage(role = MessageRole.ASSISTANT.wire, content = it.reply)) }
            }
        }
        .takeIf { it.isNotEmpty() }

    /** Ответ-отказ панели, которой не было в ответе сервера: окно называет причину, а не молчит. */
    private fun missing(pane: String, feed: CompareFeed): PaneAnswer = PaneAnswer(
        pane = pane,
        title = feed.pane(pane)?.title.orEmpty(),
        error = "Сборка не ответила: сервер вернул ответы без её окна"
    )

    /**
     * Заголовки и настройки окон из ответа: они нужны и тогда, когда описание панелей
     * не прочиталось. Прочитанное описание не переписывается настройками ответа: у него
     * те же поля, но полнее — с температурой, рассуждением и шаблоном промпта.
     */
    private fun panesFrom(turn: CompareTurn, feed: CompareFeed): List<PaneInfo> =
        listOfNotNull(turn.baseline, turn.optimized)
            .filter { answer -> feed.panes.none { it.pane == answer.pane } }
            .map { answer ->
                PaneInfo(
                    pane = answer.pane,
                    title = answer.title,
                    model = answer.model.orEmpty(),
                    provider = answer.provider.orEmpty()
                )
            }
            .let { fromAnswers -> feed.panes + fromAnswers }

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
        // Переходы в отчёт не входят по той же причине, что каталог этапов: это таблица снимка,
        // и берётся она из последнего ответа сервера, а не собирается на клиенте.
        val transitions = _task.value?.transitions.orEmpty()
        val stage = report.stage
        // Нет этапа — нет и задачи: так сервер сообщает, что она закрыта или не заводилась
        _task.value = if (stage == null) {
            TaskSnapshot(task = null, stages = stages, transitions = transitions)
        } else {
            TaskSnapshot(
                task = TaskState(
                    stage = stage,
                    step = report.step,
                    expectedAction = report.expectedAction,
                    paused = report.paused
                ),
                stages = stages,
                transitions = transitions
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
     * Инварианты профиля: правила, которые ассистент нарушать не имеет права, и каталог
     * видов. Общие для всех чатов, поэтому активный чат здесь не нужен — как память
     * и профиль. Это отдельное хранилище, а не часть диалога: правила не лежат в истории
     * сообщений, их держит сервер и подставляет в каждый запрос системным сообщением при
     * любой стратегии. Без снимка шторке нечего показывать, поэтому null и до первого
     * запроса, и когда сервер недоступен: чат при этом продолжает работать.
     */
    suspend fun loadInvariants() {
        try {
            val response = client.get("$baseUrl/v1/invariants")
            if (response.status.isSuccess()) {
                _invariants.value = response.body()
                _invariantsError.value = null
            } else {
                _invariants.value = null
                // Без снимка в шторке нет каталога видов, и добавить правило некуда:
                // причину надо показать пользователю, иначе кнопка выглядит сломанной
                _invariantsError.value = "Инварианты недоступны: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] Инварианты не загружены: ${response.status.value}")
            }
        } catch (e: Exception) {
            _invariants.value = null
            _invariantsError.value = e.message ?: "Инварианты недоступны: нет соединения"
            platformLog("agent", "[agent] Инварианты не загружены: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Явное добавление инварианта: вид и формулировку человек выбирает сам — правило
     * объявляет он, а не выводит модель. Чат здесь не нужен: правила живут по профилю,
     * поэтому добавленное из любого диалога видно из остальных.
     * Отказ сервера и сбой сети не переводят чат в [ChatUiState.Error]: диалог
     * продолжается, а причина видна строкой в шторке инвариантов ([invariantsError]).
     */
    suspend fun rememberInvariant(kind: String, value: String) {
        applyInvariantWrite {
            client.post("$baseUrl/v1/invariants") {
                contentType(ContentType.Application.Json)
                setBody(InvariantWriteRequest(kind = kind, value = value))
            }
        }
    }

    /**
     * Удаление инварианта: вид и формулировка правила. Как и добавление, чат здесь
     * не нужен — правила общие для профиля. Операция идемпотентна: сервер отвечает
     * тем же снимком, поэтому убрать правило можно и повторно. Убрали все правила —
     * в снимке приходит пустой список, и проверки запроса на конфликт больше нет:
     * это не «снимок не пришёл», а явное «правил нет».
     */
    suspend fun forgetInvariant(kind: String, value: String) {
        applyInvariantWrite {
            client.delete("$baseUrl/v1/invariants") {
                parameter("kind", kind)
                parameter("value", value)
            }
        }
    }

    /** Общий путь добавления и удаления правила: снимок из ответа идёт в состояние, отказ — в текст ошибки. */
    private suspend fun applyInvariantWrite(request: suspend () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) {
                _invariants.value = response.body()
                _invariantsError.value = null
            } else {
                // Сервер объясняет отказ в теле (например, правило не понравилось
                // проверкам) — показываем его текст, а не только код статуса
                _invariantsError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _invariantsError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Снимок работы с GitHub: подключено ли, кто сервер инструментов и что он умеет.
     * Читается без активного чата: подключение общее, а не принадлежит диалогу — иначе
     * кнопка в одном чате показывала бы не то же, что в соседнем.
     *
     * Снимок не сбрасывается при отказе: подключение держит сервер, и «сеть отвалилась»
     * — это не «отключено». Стереть снимок значило бы показать неправду и предложить
     * подключиться там, где подключение, возможно, живо; причина отказа при этом видна
     * строкой ([githubError]).
     */
    suspend fun loadGitHub() {
        try {
            val response = client.get("$baseUrl/v1/github")
            if (response.status.isSuccess()) {
                _github.value = response.body()
                _githubError.value = null
            } else {
                _githubError.value = "GitHub недоступен: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] GitHub не загружен: ${response.status.value}")
            }
        } catch (e: Exception) {
            _githubError.value = e.message ?: "GitHub недоступен: нет соединения"
            platformLog("agent", "[agent] GitHub не загружен: ${e.message ?: "нет соединения"}")
        }
    }

    /**
     * Подключение к серверу инструментов GitHub.
     *
     * Тела у запроса нет: токена клиент не знает и не передаёт — доступ к GitHub сервер
     * ищет сам на своей машине, а сюда возвращает только результат поиска: логин, права
     * и место, откуда доступ взят. Того же требовал бы и второй путь «токен из поля
     * ввода»: секрет пришлось бы набирать заново на каждом устройстве и хранить
     * в состоянии экрана, а доступ с машины и так уже есть у сервера.
     *
     * Отсутствие доступа — не отказ подключения: сессия поднимается, и снимок приходит
     * с `authorized = false` и подсказкой ([GitHubConnection.hint]) о том, что сделать.
     * Строкой ошибки ([githubError]) остаётся только случай, когда процесс MCP не поднялся.
     */
    suspend fun connectGitHub() {
        applyGitHubWrite {
            client.post("$baseUrl/v1/github/connect")
        }
    }

    /**
     * Отключение от сервера инструментов: сервер закрывает сессию и отвечает снимком
     * с `connected = false`. Снимок берётся из ответа, а не собирается на клиенте:
     * список инструментов и имя сервера знает только он.
     */
    suspend fun disconnectGitHub() {
        applyGitHubWrite {
            client.post("$baseUrl/v1/github/disconnect")
        }
    }

    /**
     * Вызов инструмента GitHub с аргументами человека.
     *
     * Вызов остаётся в ленте чата служебной записью ([MessageRole.TOOL]): команда и её
     * результат — то, что человек должен видеть, иначе данные в ответе ассистента
     * выглядят взятыми из ниоткуда. Запись хранится в том же хранилище, что и сообщения,
     * поэтому результат вызова виден и после перезапуска приложения. В историю, которая
     * уезжает на сервер, такие записи не входят ([sendMessage]): это след работы
     * для человека, а не реплика диалога.
     *
     * Отказ (не подключено, инструмента нет) и сбой сети не переводят чат
     * в [ChatUiState.Error]: диалог продолжается, а причина видна строкой ([githubError]).
     * Чат не выбран — единственный случай, когда записи некуда лечь, и тогда причина
     * та же, что у отправки сообщения.
     *
     * @param name Имя инструмента из снимка ([GitHubTool.name]).
     * @param arguments Значения аргументов по именам — те, что человек выбрал из объявленных;
     *        пустая карта — вызов без аргументов (у инструмента их нет либо все его
     *        аргументы без объявленных значений).
     */
    suspend fun callGitHubTool(name: String, arguments: Map<String, String>) {
        val chat = _activeChat.value
        if (chat == null) {
            _state.value = ChatUiState.Error("Чат не выбран")
            return
        }
        // Аргументы собираются из выбранных вариантов, а не разбираются из набранного текста:
        // строка JSON, написанная руками, могла не быть объектом, и отказ прилетал бы
        // за опечатку, а не за вызов. Типизировать аргументы классом на клиенте всё равно
        // нечем — набор полей у каждого инструмента свой ([GitHubToolArgument]), — поэтому
        // здесь свободный JSON-объект из готовых значений.
        val payload = JsonObject(arguments.mapValues { JsonPrimitive(it.value) })

        try {
            val response = client.post("$baseUrl/v1/github/call") {
                contentType(ContentType.Application.Json)
                setBody(GitHubCallRequest(name = name, arguments = payload))
            }
            if (response.status.isSuccess()) {
                val call = response.body<GitHubCallResponse>()
                val message = ChatMessage(
                    role = MessageRole.TOOL,
                    // Текста у служебной записи нет: команду и результат несёт [ChatMessage.tools],
                    // а пустая строка оставляет поле обязательным — сама запись о вызове
                    // это не реплика диалога
                    content = "",
                    // Метка ветки та же, что у следующего ответа ассистента: вызов виден
                    // в той же ветке, в которой шёл диалог, и не всплывает в соседней
                    branchId = _activeBranchId.value,
                    tools = listOf(
                        ToolCallRecord(
                            name = name,
                            // В ленте видно ровно то, что ушло серверу
                            arguments = payload.toString(),
                            result = call.result,
                            failed = call.failed
                        )
                    )
                )
                _messages.value = _messages.value + message
                store.append(chat.id, message)
                _chats.value = store.chats()
                _githubError.value = null
            } else {
                // Сервер объясняет отказ в теле (нет подключения, нет такого инструмента) —
                // показываем его текст, а не только код статуса
                _githubError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _githubError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Общий путь подключения и отключения: снимок из ответа идёт в состояние, отказ —
     * в текст ошибки, а прежний снимок остаётся на месте. Стереть его при отказе значило
     * бы показать «не подключено» там, где подключение, возможно, живо: состояние
     * подключения держит сервер, и знает о нём только он.
     */
    private suspend fun applyGitHubWrite(request: suspend () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) {
                _github.value = response.body()
                _githubError.value = null
            } else {
                _githubError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Ошибка сервера: ${response.status.value}"
            }
        } catch (e: Exception) {
            _githubError.value = e.message ?: "Сетевая ошибка"
        }
    }

    /**
     * Закреплённый чат курсов валют: находит его в хранилище, а если такого чата ещё
     * нет — заводит.
     *
     * Идентификатор у чата постоянный ([CurrencyChat.ID]), поэтому поиск по нему
     * и есть проверка «чат уже есть»: второй такой чат не появится, сколько бы раз
     * метод ни вызвали (монитор зовёт его на каждом ударе). Заголовок берётся из
     * постоянной, а не из первого сообщения: сообщений у ленты ещё нет, а в списке
     * чатов она должна называться сразу. Чат закреплён ([Chat.pinned]) — список
     * показывает его первым, а удалить его нельзя; стратегия — та же, что у нового
     * чата по умолчанию: модель в ленте не участвует, но настройка чата не должна
     * оказаться пустой, если шторку памяти в нём откроют.
     */
    suspend fun ensureCurrencyChat(): Chat {
        store.chat(CurrencyChat.ID)?.let { return it }
        val chat = Chat(
            id = CurrencyChat.ID,
            title = CurrencyChat.TITLE,
            pinned = true,
            strategy = ContextStrategy.MEMORY.wire
        )
        store.create(chat)
        _chats.value = store.chats()
        return chat
    }

    /**
     * Текущие курсы: `GET /v1/currency`. Отдаёт снимок как есть — в том числе пустой:
     * сервер вправе ответить без курсов, объяснившись словами в `note`, и это не сбой
     * запроса, а состояние данных.
     *
     * Отказ и сбой сети исключением не бросают, а возвращают null и остаются в логе:
     * читает этот метод монитор, и его удар должен пропуститься тихо. Строка ошибки
     * в ленте курсов была бы сообщением, которого человек не просил: лента показывает
     * курсы, а не разбор сбоев, и повторись сбой — она бы заполнилась ошибками.
     *
     * У чтения есть срок ([CURRENCY_READ_TIMEOUT_MS]), короче минуты: без него зависший
     * сервер останавливал бы ленту насовсем — удар ждёт ответа и не возвращается, а значит
     * не наступает и следующий. Замерено на стенде: пока сервер поднимал свой процесс
     * инструментов, ответа не было вовсе. Сроком плата за отказ — пропущенная минута,
     * а не остановка ленты.
     */
    suspend fun loadCurrencySnapshot(): CurrencySnapshot? {
        return try {
            val response = withTimeout(currencyReadTimeoutMs) { client.get("$baseUrl/v1/currency") }
            if (response.status.isSuccess()) {
                response.body()
            } else {
                platformLog("agent", "[agent] Курсы не прочитаны: сервер ответил ${response.status.value}")
                null
            }
        } catch (expired: TimeoutCancellationException) {
            // Молчание сервера не объявляется потерей связи: застрять мог инструмент службы
            // курсов, а сам сервер приложения при этом отвечает — состояние связи ведёт
            // проверка связи ([checkHealth]), и подменять её вывод отсюда нечем
            platformLog("agent", "[agent] Курсы не прочитаны: сервер не ответил за ${currencyReadTimeoutMs / 1000} с")
            null
        } catch (e: Exception) {
            _isServerOnline.value = false
            platformLog("agent", "[agent] Курсы не прочитаны: ${e.message ?: "нет соединения"}")
            null
        }
    }

    /**
     * Изменение курсов за [hours] часов для шапки закреплённого чата:
     * `GET /v1/currency/change?hours=N`.
     *
     * Ответ кладётся в состояние только целиком ([currencyChange]): частичного снимка
     * не бывает — изменение по валютам считает сервер одним ответом, и дорисовать
     * недостающую валюту на клиенте нечем. Прежний снимок при отказе остаётся
     * на месте, а причина ложится строкой ([currencyChangeError]).
     */
    suspend fun loadCurrencyChange(hours: Int = CURRENCY_DAY_HOURS) {
        readCurrencyChange(hours)?.let { _currencyChange.value = it }
    }

    /**
     * Дописывает строку монитора в ленту чата и держит её длину в пределе
     * ([CURRENCY_FEED_LIMIT]).
     *
     * Роль у сообщения служебная ([MessageRole.MONITOR]): строку напечатало приложение,
     * поэтому лента рисует её карточкой, а в историю для модели она не попадает
     * ([sendMessage]). Ветки у такой строки нет: лента курсов линейна, ветвить её нечем.
     *
     * Чат, который открыт на экране, обновляется сразу: лента цены в том, что видно
     * без переоткрытия чата, — а строка приходит раз в минуту, и ждать её появления
     * до следующего открытия было бы странно. Про закрытый чат ничего не делается:
     * его лента читается из хранилища при открытии.
     */
    suspend fun appendMonitorMessage(chatId: String, text: String) {
        val message = ChatMessage(role = MessageRole.MONITOR, content = text)
        store.append(chatId, message)
        store.trimMessages(chatId, CURRENCY_FEED_LIMIT)
        _chats.value = store.chats()
        if (_activeChat.value?.id == chatId) {
            _messages.value = DialogBranches.activePath(
                history = store.messages(chatId),
                branches = store.branches(chatId),
                activeBranchId = _activeBranchId.value
            )
        }
    }

    /**
     * Монитор курсов: удар сразу при запуске, дальше — раз в [CURRENCY_TICK_MS] по минутной
     * сетке, начало которой — запуск цикла.
     *
     * Цикл бесконечный и рассчитан на то, что живёт в области видимости приложения
     * ([ChatViewModel.startCurrencyMonitor]): приложение открыто — лента наполняется,
     * закрыто — цикл умирает вместе со своей областью. Это и есть требуемая частота
     * без будильников и фоновых служб: пока приложение живо, минута тикает.
     *
     * Пауза считается до следующей точки сетки ([pauseToNextTick]), а не выжидается
     * минутой после удара: удар сам занимает время (чтение курсов по сети), и «минута
     * после работы» отодвигала бы каждый следующий удар на это время. Замер на стенде
     * дал 120–480 мс на удар: сетка уезжала вправо на полминуты за час и проходила через
     * моменты сбора службы — строка с уже показанной минутой шла второй раз, а следующая
     * минута пропускалась. Сетка держит удар на своём месте, и метка службы в строке идёт
     * по минуте на строку.
     *
     * Отброшены две другие правки: не печатать строку, если метка службы не сменилась
     * (пропуск минуты вместо повтора — лента стала бы реже обещанного), и печатать время
     * удара вместо метки службы (был бы виден момент печати, а не момент, на который
     * посчитаны курсы).
     *
     * Обе зависимости цикла — момент времени и пауза — берутся параметрами: в тесте
     * так задаётся своё время и свой шаг, а приложение зовёт метод без аргументов
     * и получает системные часы и настоящую минутную паузу. Это тот же приём, что
     * и подставной транспорт в конструкторе ([client]): с ним цикл проверяется
     * без ожидания настоящей минуты.
     *
     * @param now Момент удара в миллисекундах UTC.
     * @param wait Пауза до следующего удара в миллисекундах.
     */
    suspend fun runCurrencyMonitor(
        now: () -> Long = { nowMillis() },
        wait: suspend (Long) -> Unit = { delay(it) }
    ) {
        // Начало сетки — запуск цикла: первый удар идёт сразу, остальные — по его точкам
        val grid = now()
        var hour: Long? = null
        while (true) {
            hour = currencyMonitorTick(now(), hour)
            wait(pauseToNextTick(grid, now()))
        }
    }

    /**
     * Пауза до следующей точки минутной сетки, начавшейся в [grid].
     *
     * Точки сетки — [grid] и дальше через [CURRENCY_TICK_MS]. Удар, занявший больше минуты,
     * пропускает свои точки, а не сдвигает сетку: пауза ведёт к первой точке, которая ещё
     * не прошла, поэтому долгое чтение не утаскивает за собой расписание и лента не теряет
     * минуты молча.
     */
    private fun pauseToNextTick(grid: Long, moment: Long): Long {
        val passed = moment - grid
        val points = if (passed < 0) 1 else passed / CURRENCY_TICK_MS + 1
        return grid + points * CURRENCY_TICK_MS - moment
    }

    /**
     * Один удар монитора за момент [moment].
     *
     * Удар читает курсы и, если они пришли, дописывает минутную строку в закреплённый
     * чат. Сводка добавляется только тогда, когда сменился час: на первом ударе час
     * лишь запоминается. Так перезапуск приложения не плодит сводки — он ведь не видел
     * прошедшего часа, и «сводка» за него была бы пересказом того, чего монитор не
     * наблюдал. Неудачное чтение сводки удар не повторяет: час уже сменился, и вторая
     * попытка через минуту назвала бы тем же именем следующий час. Причина уходит в лог.
     *
     * @param lastHour Номер часа предыдущего удара (UTC, от начала эпохи); null — ударов
     *        ещё не было.
     * @return Номер текущего часа — его запоминает цикл ([runCurrencyMonitor]).
     */
    private suspend fun currencyMonitorTick(moment: Long, lastHour: Long?): Long {
        val chat = ensureCurrencyChat()
        val hour = moment / HOUR_MS

        val snapshot = loadCurrencySnapshot()
        if (snapshot != null) {
            if (snapshot.rates.isNullOrEmpty()) {
                // Курсов нет, но сервер сказал почему — в ленту это не печатается:
                // там расписание, а не разбор сбоев; строку видно в логе
                platformLog("agent", "[agent] Курсы без значений: ${snapshot.note ?: "причина не названа"}")
            } else {
                appendMonitorMessage(chat.id, minuteLine(snapshot, moment))
            }
        }

        if (lastHour != null && lastHour != hour) {
            val feed = readCurrencyChange(CURRENCY_HOUR_HOURS)
            if (feed != null && feed.currencies.isNotEmpty()) {
                appendMonitorMessage(chat.id, hourSummary(hour, feed))
            }
        }
        return hour
    }

    /**
     * Текст минутной строки: `12:05 · EUR 95,8709 · USD 84,3414 · GEL 32,1693`.
     *
     * Время берётся из метки сервера, а не из удара монитора: по ней видно, когда курсы
     * посчитал сервер, — удар же мог запоздать. Метка не разобралась или не пришла —
     * печатается время удара, и строка остаётся со временем, а не без него. Числа идут
     * как их послал сервер, меняется только разделитель ([decimalComma]): округлить
     * их значило бы показать курс, которого сервер не называл.
     */
    private fun minuteLine(snapshot: CurrencySnapshot, moment: Long): String {
        val time = snapshot.updatedAt?.let(::utcTimeFromMark) ?: utcTime(moment)
        val rates = snapshot.rates.orEmpty()
            .entries
            .joinToString(" · ") { (currency, rate) -> "$currency ${rate.decimalComma()}" }
        return "$time · $rates"
    }

    /**
     * Текст сводки часа: первой строкой интервал, дальше по строке на валюту
     * ([CurrencyChangeItem.line]).
     *
     * Интервал — прошедший час до текущего ([hour] — номер часа удара): сервер считает
     * изменение за окно, закончившееся на границе часа, поэтому прошедший час и есть
     * то окно, данные которого пришли. Если приложение не тикало дольше часа, сводка
     * всё равно описывает последний полный час — тот, что покрыт данными, — а не весь
     * пропущенный промежуток, о котором наблюдений нет.
     */
    private fun hourSummary(hour: Long, feed: CurrencyChangeFeed): String = buildString {
        append("Сводка за час ")
        append(utcTime((hour - 1) * HOUR_MS))
        append('–')
        append(utcTime(hour * HOUR_MS))
        append(':')
        feed.currencies.forEach { item ->
            append('\n')
            append(item.line())
        }
    }

    /**
     * Чтение изменения курсов за [hours] часов без записи в состояние шапки.
     *
     * Отдельно от [loadCurrencyChange] потому, что этим чтением пользуются оба окна:
     * шапка спрашивает сутки и показывает снимок на экране, а сводке часа нужен час,
     * и подменять им суточный снимок шапки нельзя — на экране оказалось бы изменение
     * за час под заголовком «за сутки». Причина отказа — одна на оба окна
     * ([currencyChangeError]): маршрут один, и сбой у него общий.
     */
    private suspend fun readCurrencyChange(hours: Int): CurrencyChangeFeed? {
        return try {
            // Срок тот же, что у чтения курсов: этим чтением закрывается час в сводке,
            // и оно тоже идёт из удара монитора — зависни оно, удар не вернётся
            val response = withTimeout(currencyReadTimeoutMs) {
                client.get("$baseUrl/v1/currency/change") {
                    parameter("hours", hours)
                }
            }
            if (response.status.isSuccess()) {
                _currencyChangeError.value = null
                response.body()
            } else {
                _currencyChangeError.value = runCatching { response.body<ErrorResponse>().error }.getOrNull()
                    ?: "Изменение курсов недоступно: сервер ответил ${response.status.value}"
                platformLog("agent", "[agent] Изменение курсов не прочитано: ${response.status.value}")
                null
            }
        } catch (expired: TimeoutCancellationException) {
            val reason = "сервер не ответил за ${currencyReadTimeoutMs / 1000} с"
            _currencyChangeError.value = "Изменение курсов недоступно: $reason"
            platformLog("agent", "[agent] Изменение курсов не прочитано: $reason")
            null
        } catch (e: Exception) {
            _isServerOnline.value = false
            _currencyChangeError.value = e.message ?: "Изменение курсов недоступно: нет соединения"
            platformLog("agent", "[agent] Изменение курсов не прочитано: ${e.message ?: "нет соединения"}")
            null
        }
    }

    /**
     * Время «ЧЧ:мм» по UTC из момента в миллисекундах.
     *
     * Часы и минуты считаются делением от начала эпохи, а не календарём: в ленте нужно
     * только время суток по UTC, а часового пояса у клиента для этого хватать не должно —
     * курсы обновляет сервер по своему расписанию, и в ленте видно его время, одно
     * на всех устройствах. Остаток берётся с двойным знаком деления: так время остаётся
     * верным и для моментов до начала эпохи (нулевые и отрицательные метки), где обычный
     * остаток дал бы отрицательные часы.
     */
    private fun utcTime(epochMs: Long): String {
        val minutes = epochMs / 60_000
        val hour = ((minutes / 60) % 24 + 24) % 24
        val minute = (minutes % 60 + 60) % 60
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Время «ЧЧ:мм» из метки сервера вида `2026-09-28T15:04:00Z`; null — метка
     * не похожа на ISO-8601 с временем, и брать из неё нечего.
     *
     * Разбора даты здесь нет намеренно: нужны только часы и минуты, а они в этой метке
     * уже стоят подряд после `T`. Разбирать метку календарём значило бы тянуть
     * в общий код библиотеку дат ради подстроки из пяти символов.
     */
    private fun utcTimeFromMark(mark: String): String? {
        val time = mark.substringAfter('T', "")
        return if (time.length >= 5 && time[2] == ':') time.take(5) else null
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
     * Роль сообщения из истории сервиса. Неизвестное значение — ответ ассистента:
     * сервис пишет в историю только реплики диалога (`user`/`assistant`), системы
     * среди них не бывает, и показать незнакомое как реплику человека значило бы
     * приписать человеку слова, которых он не писал.
     */
    private fun roleFrom(role: String): MessageRole =
        MessageRole.entries.firstOrNull { it.wire == role } ?: MessageRole.ASSISTANT

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

        /**
         * Код отказа сервиса «ключ не принят»: переводит состояние соединения
         * в ошибку авторизации, а не в «недоступен».
         */
        const val CODE_UNAUTHORIZED = "unauthorized"

        /**
         * Код отказа сервиса «такой сессии нет»: на ход в удалённую сессию сервис
         * отвечает им, и это единственный отказ, который клиент повторяет — заведя
         * сессию заново.
         */
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"

        /**
         * Имя модели в записи лога, когда ответ его не назвал.
         *
         * Так бывает только у старого сервера: современный ответ несёт и модель,
         * и провайдера. Прочерк вместо имени честнее подстановки выбранной в настройках
         * модели — её клиент больше не выбирает, и называть её значило бы выдумать ответ.
         */
        const val MODEL_NOT_NAMED = "модель не названа"

        /** Сколько символов первого сообщения попадает в заголовок списка. */
        const val TITLE_LIMIT = 40

        /** Частота удара монитора курсов: раз в минуту. */
        const val CURRENCY_TICK_MS = 60_000L

        /**
         * Срок чтения курсов: двадцать секунд.
         *
         * Меньше минуты — чтобы зависшее чтение стоило одного удара, а не сетки: удар вернётся
         * по сроку и пропустит свою минуту, следующая придёт по расписанию. Больше подъёма
         * процесса инструментов сервера (рукопожатие MCP) — иначе первый удар терялся бы
         * на каждом холодном запуске.
         */
        const val CURRENCY_READ_TIMEOUT_MS = 20_000L

        /** Срок соединения с сервером приложения: сеть в помещении медленной не бывает. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Сколько часов просит шапка закреплённого чата: сутки. */
        const val CURRENCY_DAY_HOURS = 24

        /** Сколько часов просит сводка часа: прошедший час. */
        const val CURRENCY_HOUR_HOURS = 1

        /**
         * Сколько последних сообщений хранит лента закреплённого чата: строки приходят
         * раз в минуту, и без предела лента росла бы без конца. Предел задан контрактом
         * (240) и держит примерно четыре последних часа минутных строк вместе со сводками
         * этих часов. За сутки в ленте помещается не всё, и это выбор в пользу размера
         * хранилища: суточную картину показывает шапка чата ([CurrencyChangeFeed]),
         * а не вся лента — ради неё одной хранить сутки строк незачем.
         */
        const val CURRENCY_FEED_LIMIT = 240

        /** Миллисекунд в часе: по нему считается номер часа UTC. */
        const val HOUR_MS = 3_600_000L

        /**
         * Имена настроек сервиса в хранилище устройства ([settingsStorage]).
         *
         * Имя — одно на поле и в одном месте: настройку пишет репозиторий и читает он же
         * при следующем запуске ([restoredServerSettings]), и разошедшееся имя обернулось бы
         * настройкой, которая «не сохраняется» — а именно этот отказ и починили.
         * Префикс `service.` отделяет их от записей других настроек, которые могут
         * появиться в том же хранилище.
         */
        const val SETTINGS_MODE = "service.mode"

        /** Адрес сервиса: без него настройки не поднимаются (см. [restoredServerSettings]). */
        const val SETTINGS_BASE_URL = "service.baseUrl"

        /** Ключ доступа: лежит на устройстве, поэтому вход в сервис переживает перезапуск. */
        const val SETTINGS_API_KEY = "service.apiKey"

        /** Имя модели, которое просят у сервиса. */
        const val SETTINGS_MODEL = "service.model"

        /** Температура генерации; хранится текстом, как и остальные числа настроек. */
        const val SETTINGS_TEMPERATURE = "service.temperature"

        /** Бюджет ответа в токенах. */
        const val SETTINGS_MAX_TOKENS = "service.maxTokens"

        /** Потолок контекстного окна. */
        const val SETTINGS_CONTEXT_WINDOW = "service.contextWindow"

        /** Чат сервиса, открытый прошлым запуском: в него приложение возвращается после обновления. */
        const val SETTINGS_LAST_CHAT = "service.lastChatId"
    }
}
