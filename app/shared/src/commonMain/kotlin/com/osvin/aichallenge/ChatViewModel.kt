package com.osvin.aichallenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatMode
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.CompareFeed
import com.osvin.aichallenge.data.ConnectionReport
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.InvariantSnapshot
import com.osvin.aichallenge.data.ServerSettings
import com.osvin.aichallenge.data.ServiceChatList
import com.osvin.aichallenge.data.ServiceShareLink
import com.osvin.aichallenge.data.UserProfile
import com.osvin.aichallenge.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием чатов.
 * Поддерживает жизненный цикл и хранит данные при изменениях конфигурации.
 */
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    // Список чатов и активный чат: у каждого чата своя история и своя сессия агента
    val chats: StateFlow<List<Chat>> = repository.chats
    val activeChat: StateFlow<Chat?> = repository.activeChat

    // Экспонируем потоки данных из репозитория для UI
    val messages: StateFlow<List<ChatMessage>> = repository.messages

    // Состояния веток диалога здесь нет: переключателя вариантов и кнопки «ветка
    // от этого сообщения» в интерфейсе больше нет, поэтому и подписываться нечему.
    // Само ветвление в репозитории осталось ([ChatRepository.createBranchFrom]):
    // им пользуются механизмы истории и его проверяют тесты стратегий контекста.

    // Профиль пользователя из шторки профиля. Это не память, поэтому и состояния
    // свои: профиль объявляет сам пользователь, и он подставляется в каждый запрос
    // к модели на сервере, а не собирается клиентом
    val profile: StateFlow<UserProfile?> = repository.profile
    val profileError: StateFlow<String?> = repository.profileError

    // Состояния задачи здесь нет: полосы «Взять в работу» в интерфейсе больше нет,
    // поэтому подписываться нечему. Работа с задачей в репозитории осталась
    // ([ChatRepository.loadTask]): её проверяют тесты, а состояние живёт на сервере.

    // Инварианты профиля: правила проекта, которые ассистент нарушать не имеет права.
    // Это ни память, ни задача: память агент извлекает из диалога, а задача описывает
    // работу в чате — правила же человек объявляет на весь проект. Живут они на сервере
    // отдельно от диалога и уходят в каждый запрос системным сообщением, поэтому клиент
    // их только показывает и правит явными действиями человека
    val invariants: StateFlow<InvariantSnapshot?> = repository.invariants
    val invariantsError: StateFlow<String?> = repository.invariantsError

    // Снимков памяти агента и подключения к GitHub здесь нет: их шторки убраны
    // из интерфейса — чат ведёт обычную историю диалога ([ContextStrategy.FULL]),
    // а инструменты MCP локальному провайдеру не предлагаются вовсе.

    // Изменение курсов за сутки и причина последнего отказа в модели представления
    // не выставляются: закреплённый чат курсов скрыт из списка, поэтому и его шапке
    // нечего показывать. Само чтение осталось у репозитория ([ChatRepository.loadCurrencyChange]) —
    // лента включается обратно одной строкой, если её вернут в интерфейс.

    val uiState: StateFlow<ChatUiState> = repository.state
    val isOnline: StateFlow<Boolean?> = repository.isServerOnline

    // Кто ответил на последний вопрос: имя модели и провайдер с сервера. В шапке чата
    // видно правду о локальном режиме — отвечает загруженная модель Ollama, а не та,
    // что выбрана в настройках, потому что выбор провайдера клиенту не принадлежит
    val answerModel: StateFlow<String?> = repository.answerModel
    val answerProvider: StateFlow<String?> = repository.answerProvider

    // Текущие настройки генерации из шторки настроек. Стратегия в них — стратегия
    // активного чата, поэтому настройки живут в репозитории рядом с активным чатом
    val settings: StateFlow<GenerationSettings> = repository.settings

    // Настройки приватного сервиса и итог проверки его подключения. Держатся в модели
    // представления, а не в экране: от режима ([ServerSettings.mode]) зависит, какому
    // пути адресован вопрос, и решает это репозиторий — экран их только показывает
    val serverSettings: StateFlow<ServerSettings> = repository.serverSettings
    val serviceConnection: StateFlow<ConnectionReport> = repository.serviceConnection

    // Лента сервисного чата: отдельная от ленты локального чата, потому что это разные
    // разговоры (см. [ChatRepository.serviceMessages]). Экран показывает её в режиме сервиса
    val serviceMessages: StateFlow<List<ChatMessage>> = repository.serviceMessages

    // Идентификатор и заголовок открытого сервисного чата: по идентификатору собирается
    // ссылка «Поделиться», а заголовок стоит в шапке — их называет сервис, а не настройки
    val serviceChatId: StateFlow<String?> = repository.serviceChatId
    val serviceChatTitle: StateFlow<String?> = repository.serviceChatTitle

    // Чаты сервиса для боковой панели: в режиме «Сервис» она показывает их, а не локальные.
    // Список читается из `/api/chats` — источник истины по сервисным чатам сервис, поэтому
    // и список, и признак прочитанного, и причина отказа держатся здесь, а не в экране
    val serviceChats: StateFlow<ServiceChatList> = repository.serviceChats
    val serviceChatsLoaded: StateFlow<Boolean> = repository.serviceChatsLoaded
    val serviceChatsError: StateFlow<String?> = repository.serviceChatsError

    // Сводка прошлого чата у открытого сервисного чата и причина, по которой её не построили.
    // Экран показывает их строкой над лентой; в ленту сводка не попадает намеренно:
    // это контекст, подставленный сервисом в запрос к модели, а не реплика диалога
    val serviceSummary: StateFlow<String?> = repository.serviceSummary
    val serviceSummaryError: StateFlow<String?> = repository.serviceSummaryError

    // Лента сравнения ещё и переключается: два окна отвечают на один вопрос, и их видно
    // вместо обычной ленты активного чата
    val compare: StateFlow<CompareFeed> = repository.compare
    private val _compareMode = MutableStateFlow(false)
    val compareMode: StateFlow<Boolean> = _compareMode.asStateFlow()

    // Признак того, что список сохранённых чатов уже загружен из хранилища
    private val _chatsLoaded = MutableStateFlow(false)
    val chatsLoaded: StateFlow<Boolean> = _chatsLoaded.asStateFlow()

    init {
        // Восстанавливаем чаты, затем проверяем связь. Закреплённого чата курсов
        // в списке больше нет, поэтому ни он не заводится, ни монитор не стартует:
        // скрытая лента наполнялась бы впустую и дергала сервер каждую минуту
        viewModelScope.launch {
            repository.loadChats()
            _chatsLoaded.value = true
        }
        checkHealth()
    }

    /**
     * Запуск проверки доступности сервера.
     */
    fun checkHealth() {
        viewModelScope.launch {
            repository.checkHealth()
        }
    }

    /**
     * Открытие чата из списка: продолжаем его историю и его сессию агента.
     */
    fun openChat(id: String) {
        // Лента сравнения принадлежит разговору, а не приложению: ходы собраны из истории
        // этого чата, и в другом чате они были бы ответами на вопросы, которых там не было
        repository.clearCompare()
        viewModelScope.launch {
            repository.openChat(id)
        }
    }

    /**
     * Создание нового чата и переход в него.
     *
     * Настраивать нечего: настройки агента лежат значениями по умолчанию
     * ([GenerationSettings]) — провайдера и модель выбирает конфигурация сервера,
     * а не человек в шторке. Стратегия из настроек становится стратегией чата
     * ([Chat.strategy]).
     */
    fun createChat() {
        viewModelScope.launch {
            repository.createChat()
        }
    }

    /**
     * Удаление чата: история на устройстве и сессия агента на сервере.
     */
    fun deleteChat(id: String) {
        viewModelScope.launch {
            repository.deleteChat(id)
        }
    }

    /**
     * Отправка сообщения с текущими настройками генерации.
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            // Режим меняет не то, что видно, а то, кому задаётся вопрос: приватный
            // сервис `/api/…` ведёт свою сессию и свою историю, а путь `/v1` — свою.
            // Развилка здесь, а не в экране: кнопка отправки должна уносить вопрос туда,
            // куда показывает выбранный режим, и второй копии этого правила не нужно.
            if (serverSettings.value.mode == ChatMode.SERVICE) {
                repository.sendServiceMessage(text)
                return@launch
            }
            // Режим сравнения меняет не то, что видно, а то, кому задаётся вопрос:
            // одна сборка модели отвечает или обе сразу. Развилки в экране нет намеренно —
            // иначе кнопка отправки и подсказка о состоянии разошлись бы с тем, что уходит.
            if (_compareMode.value) {
                repository.sendCompare(text, settings.value)
            } else {
                repository.sendMessage(text, settings.value)
            }
        }
    }

    /**
     * Сохранение настроек сервиса из шторки «Сервер»: адрес, ключ, модель и рамки запроса.
     *
     * Сохраняет репозиторий: смена адреса или ключа сбрасывает сессию сервиса, а это —
     * его состояние, не экрана. Синхронно: хранить нечего (настройки живут в памяти),
     * поэтому и корутины не нужно.
     */
    fun saveServerSettings(settings: ServerSettings) {
        repository.updateServerSettings(settings)
    }

    /**
     * Проверка подключения к сервису: живость без ключа, затем список моделей с ключом.
     * Итог кладётся в [serviceConnection] самим репозиторием, поэтому здесь достаточно запуска.
     *
     * Проверяются переданные настройки, а не сохранённые: экран отдаёт черновик шторки,
     * и набранный, но не сохранённый ключ проверяется тем же нажатием, что и всё остальное.
     */
    fun checkServiceConnection(settings: ServerSettings) {
        viewModelScope.launch {
            repository.checkServiceConnection(settings)
        }
    }

    /**
     * Чтение списка чатов сервиса: `GET /api/chats`. В локальном режиме запрос не делается.
     *
     * Запуск без режима проверки: список читается при старте в режиме «Сервис» и при
     * переключении на него, а решение «стоит ли спрашивать сервис» принимает репозиторий —
     * второй копии этого правила в экране быть не должно.
     */
    fun loadServiceChats() {
        viewModelScope.launch {
            repository.loadServiceChats()
        }
    }

    /**
     * Открытие чата сервиса по идентификатору: история поднимается из сервиса, ход
     * дальше адресуется этому же чату. Так открывают чат из списка — и общий разговор
     * он один на всех клиентов с ключом.
     */
    fun openServiceChat(id: String) {
        viewModelScope.launch {
            repository.openServiceChat(id)
        }
    }

    /**
     * Создание чата сервиса и переход в него.
     *
     * Прошлый чат для сводки выбирает сервис: он один знает, какой разговор был прошлым,
     * и клиент, назвавший его сам, показал бы сводку не того диалога.
     */
    fun createServiceChat() {
        viewModelScope.launch {
            repository.createServiceChat()
        }
    }

    /**
     * Открытие чата, которым поделились ссылкой (`?chat=<id>`): режим переводится
     * в «Сервис», чат открывается. Ключ после перезапуска вводят заново — он живёт
     * только в памяти, и без него сервис ответит отказом.
     */
    fun openSharedServiceChat(id: String) {
        viewModelScope.launch {
            repository.openSharedServiceChat(id)
        }
    }

    /** «Обновить» у ленты сервиса: перечитать список чатов и историю открытого чата. */
    fun refreshService() {
        viewModelScope.launch {
            repository.refreshService()
        }
    }

    /**
     * Ссылка на открытый чат сервиса и уложилась ли она в буфер обмена; null — чата нет.
     *
     * Собирается и копируется здесь, а не в экране: адрес приложения и буфер — платформенные,
     * и экран от них только получает готовый текст и признак копирования.
     */
    fun shareServiceChat(): ServiceShareLink? = repository.shareServiceChat()

    /**
     * Включение и выключение сравнения сборок модели.
     *
     * Режим живёт в модели представления, а не в хранилище чата: это способ посмотреть
     * на ответы, а не свойство диалога, — включённое сравнение не должно переживать
     * перезапуск приложения и менять обычную ленту чата.
     *
     * При включении читается описание панелей: до первого вопроса видно, какие сборки
     * отвечают и чем они отличаются. Отказ чтения сравнение не выключает — вопрос можно
     * задать, и окна подпишутся заголовками из ответов.
     */
    fun toggleCompare() {
        val enabled = !_compareMode.value
        _compareMode.value = enabled
        if (enabled) {
            viewModelScope.launch { repository.loadCompare() }
        }
    }

    /** Очистка ленты сравнения: следующий вопрос задаётся окнам заново, без истории. */
    fun clearCompare() {
        repository.clearCompare()
    }

    /**
     * Перечитывание профиля пользователя: он общий для всех чатов, поэтому читается
     * без активного чата. Шторка открывается уже с загруженным профилем — его читает
     * сам чат при создании и открытии, — а это повторное чтение.
     */
    fun loadProfile() {
        viewModelScope.launch {
            repository.loadProfile()
        }
    }

    /**
     * Сохранение профиля целиком: шторка отдаёт свой черновик, а состояние берётся
     * из ответа сервера — в шторке видно то, что сохранено.
     */
    fun saveProfile(profile: UserProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
        }
    }

    /**
     * Перечитывание инвариантов профиля: они общие для всех чатов, поэтому читаются
     * без активного чата. Чат читает их сам при создании и открытии, а это повторное
     * чтение — например, после сбоя сети.
     */
    fun loadInvariants() {
        viewModelScope.launch {
            repository.loadInvariants()
        }
    }

    /**
     * Добавление инварианта: вид из каталога и формулировку правила выбирает человек.
     * Состояние шторки берётся из ответа сервера, поэтому видно то, что сохранено.
     */
    fun rememberInvariant(kind: String, value: String) {
        viewModelScope.launch {
            repository.rememberInvariant(kind, value)
        }
    }

    /**
     * Удаление правила: вид и формулировка инварианта. Убрали все правила — снимок
     * приходит с пустым списком, и проверки запроса на конфликт больше нет.
     */
    fun forgetInvariant(kind: String, value: String) {
        viewModelScope.launch {
            repository.forgetInvariant(kind, value)
        }
    }
}
