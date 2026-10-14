package com.osvin.aichallenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osvin.aichallenge.data.Chat
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.ChatUiState
import com.osvin.aichallenge.data.CompareFeed
import com.osvin.aichallenge.data.ContextStrategy
import com.osvin.aichallenge.data.GenerationSettings
import com.osvin.aichallenge.data.InvariantSnapshot
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
