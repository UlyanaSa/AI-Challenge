package com.osvin.aichallenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.DialogBranch
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.InvariantSnapshot
import com.osvin.aichallenge.data.MemoryLayers
import com.osvin.aichallenge.data.MemoryReport
import com.osvin.aichallenge.data.TaskSnapshot
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
    val branches: StateFlow<List<DialogBranch>> = repository.branches
    val activeBranchId: StateFlow<String?> = repository.activeBranchId
    val memory: StateFlow<MemoryReport?> = repository.memory
    val layers: StateFlow<MemoryLayers?> = repository.layers
    val memoryError: StateFlow<String?> = repository.memoryError

    // Профиль пользователя из шторки профиля. Это не память, поэтому и состояния
    // свои: профиль объявляет сам пользователь, и он подставляется в каждый запрос
    // к модели на сервере, а не собирается клиентом
    val profile: StateFlow<UserProfile?> = repository.profile
    val profileError: StateFlow<String?> = repository.profileError

    // Задача активного чата: этап, шаг и ожидаемое действие. Состояние и каталог этапов
    // приходят с сервера, поэтому и потоки свои: задача не часть профиля и не память —
    // она принадлежит диалогу, и её состояние не выводится на клиенте
    val task: StateFlow<TaskSnapshot?> = repository.task
    val taskError: StateFlow<String?> = repository.taskError

    // Инварианты профиля: правила проекта, которые ассистент нарушать не имеет права.
    // Это ни память, ни задача: память агент извлекает из диалога, а задача описывает
    // работу в чате — правила же человек объявляет на весь проект. Живут они на сервере
    // отдельно от диалога и уходят в каждый запрос системным сообщением, поэтому клиент
    // их только показывает и правит явными действиями человека
    val invariants: StateFlow<InvariantSnapshot?> = repository.invariants
    val invariantsError: StateFlow<String?> = repository.invariantsError

    val uiState: StateFlow<ChatUiState> = repository.state
    val isOnline: StateFlow<Boolean?> = repository.isServerOnline

    // Текущие настройки генерации из шторки настроек. Стратегия в них — стратегия
    // активного чата, поэтому настройки живут в репозитории рядом с активным чатом
    val settings: StateFlow<GenerationSettings> = repository.settings

    // Признак того, что список сохранённых чатов уже загружен из хранилища
    private val _chatsLoaded = MutableStateFlow(false)
    val chatsLoaded: StateFlow<Boolean> = _chatsLoaded.asStateFlow()

    init {
        // Восстанавливаем чаты, затем проверяем связь
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
        viewModelScope.launch {
            repository.openChat(id)
        }
    }

    /**
     * Создание нового чата с настроенным агентом и переход в него.
     * Стратегия из настроек становится стратегией чата ([Chat.strategy]).
     */
    fun createChat(settings: GenerationSettings) {
        repository.updateSettings(settings)
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
     * Ветка от сообщения активного пути: продолжение диалога отдельно от соседей.
     */
    fun createBranchFrom(message: ChatMessage) {
        viewModelScope.launch {
            repository.createBranchFrom(message)
        }
    }

    /**
     * Переключение активной ветки диалога; null — основная линия.
     */
    fun switchBranch(branchId: String?) {
        viewModelScope.launch {
            repository.switchBranch(branchId)
        }
    }

    /**
     * Смена стратегии контекста активного чата: она сохраняется в самом чате
     * и уходит с каждым следующим запросом к модели.
     */
    fun updateStrategy(strategy: ContextStrategy) {
        viewModelScope.launch {
            repository.updateStrategy(strategy)
        }
    }

    /**
     * Отправка сообщения с текущими настройками генерации.
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            repository.sendMessage(text, settings.value)
        }
    }

    /**
     * Перечитывание снимка памяти активного чата.
     */
    fun loadMemory() {
        viewModelScope.launch {
            repository.loadMemory()
        }
    }

    /**
     * Явная запись в память активного чата выбранного типа.
     */
    fun remember(layer: String, value: String) {
        viewModelScope.launch {
            repository.remember(layer, value)
        }
    }

    /**
     * Удаление записи памяти активного чата: тип и текст записи.
     */
    fun forget(layer: String, value: String) {
        viewModelScope.launch {
            repository.forget(layer, value)
        }
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
     * Перечитывание состояния задачи активного чата. Чат читает его сам при создании
     * и открытии, а это повторное чтение — например, после сбоя сети.
     */
    fun loadTask() {
        viewModelScope.launch {
            repository.loadTask()
        }
    }

    /**
     * Взятие задачи активного чата в работу: дальше её ведёт сервер, а полоса
     * показывает то, что он вернул.
     */
    fun startTask() {
        viewModelScope.launch {
            repository.startTask()
        }
    }

    /**
     * Пауза и продолжение задачи активного чата: оба действия — одно поле,
     * состояние после них берётся из ответа сервера.
     */
    fun setTaskPaused(paused: Boolean) {
        viewModelScope.launch {
            repository.setTaskPaused(paused)
        }
    }

    /**
     * Забвение задачи активного чата: работа стирается на сервере, полоса
     * показывает, что задачи нет.
     */
    fun forgetTask() {
        viewModelScope.launch {
            repository.forgetTask()
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
