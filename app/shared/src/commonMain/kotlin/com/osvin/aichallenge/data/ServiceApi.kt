package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Тела запросов и ответов приватного сервиса `/api/…` (день 30).
 *
 * День 31 добавил список чатов (`GET /api/chats`), сводку прошлого чата при создании
 * (`POST /api/chats`) и поля сводки в историю: чаты и история живут на сервисе, а клиент
 * их только читает и показывает.
 *
 * Это отдельные модели, а не переиспользование [ChatRequest]/[ChatResponse]: у сервиса
 * свой контракт — сессия адресуется его собственным `chatId`, ответ несёт `requestId`,
 * расход токенов и число усечённых сообщений, а ошибка приходит тройкой `code/message/requestId`.
 * Свести их с формами агента значило бы подгонять одну поверхность под другую, хотя
 * `/v1/…` обязан остаться прежним дословно.
 *
 * Значения по умолчанию у полей — не украшение: клиент разбирает ответы с `ignoreUnknownKeys`,
 * и незаполненное необязательное поле не должно ронять разбор ответа сервиса.
 */

/** Ответ сервиса на ошибку: `{"code","message","requestId"}`. Единый формат всех отказов `/api/…`. */
@Serializable
data class ServiceErrorBody(
    val code: String = "",
    val message: String = "",
    val requestId: String = ""
)

/**
 * Запрос одного хода чата.
 *
 * @param chatId Идентификатор сессии сервиса: обязателен, иначе сервис ответит `invalid_request`.
 * @param message Текущее сообщение пользователя: непустое.
 * @param model Имя модели; null — сервис берёт свою модель из окружения.
 * @param temperature Температура; null — умолчание сервиса (потолком ограничивается на сервере).
 * @param maxTokens Бюджет ответа; null — умолчание сервиса.
 * @param contextWindow Потолок окна; null — умолчание сервиса.
 */
@Serializable
data class ServiceChatRequest(
    val chatId: String,
    val message: String,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null
)

/**
 * Успешный ответ на ход чата.
 *
 * @param requestId Короткий идентификатор запроса: по нему отказ сервиса связывается с логом.
 * @param chatId Сессия, к которой записан ход; может отличаться от запрошенной при `chat_not_found`.
 * @param reply Текст ответа модели.
 * @param model Имя модели, которой ответил сервис; null — сервис его не назвал.
 * @param elapsedMs Длительность генерации.
 * @param promptTokens Токенов во входе; null — движок не прислал `usage` (не ноль).
 * @param completionTokens Токенов в ответе; null — движок не прислал `usage`.
 * @param totalTokens Всего токенов; null — движок не прислал `usage`.
 * @param trimmedMessages Сколько старых сообщений усечено ради бюджета контекста.
 * @param status Слово состояния: `ok` у успешного ответа.
 */
@Serializable
data class ServiceChatResponse(
    val requestId: String = "",
    val chatId: String = "",
    val reply: String = "",
    val model: String? = null,
    val elapsedMs: Long = 0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val trimmedMessages: Int = 0,
    val status: String = ""
)

/**
 * Живость сервиса, `GET /api/health` — доступен без ключа.
 *
 * @param status `ok` — сервис и демон в порядке; `error` — при 503.
 * @param provider Кто отвечает: у дня 30 — `local`.
 * @param model Модель, настроенная на сервисе.
 * @param ollama Состояние демона: `ok` или `unreachable`.
 * @param modelLoaded Загружена ли модель; null — сервис об этом не сказал.
 * @param uptimeMs Время работы процесса.
 * @param message Причина отказа словами; null — отказа не было.
 */
@Serializable
data class ServiceHealth(
    val status: String = "",
    val provider: String? = null,
    val model: String? = null,
    val ollama: String? = null,
    val modelLoaded: Boolean? = null,
    val uptimeMs: Long? = null,
    val message: String? = null
)

/** Список моделей сервиса, `GET /api/models`. */
@Serializable
data class ServiceModels(val models: List<ServiceModel> = emptyList())

/**
 * Модель в списке сервиса.
 *
 * @param name Имя модели: по нему выбирают модель и сверяют её с настройкой.
 * @param sizeBytes Размер файла модели; null — демон его не назвал.
 * @param quantization Квантование; null — демон его не назвал.
 * @param parameterSize Число параметров словами; null — демон его не назвал.
 */
@Serializable
data class ServiceModel(
    val name: String,
    val sizeBytes: Long? = null,
    val quantization: String? = null,
    val parameterSize: String? = null
)

/**
 * Создание сессии: тело `POST /api/chats`.
 *
 * @param title Заголовок; null — умолчание хранилища сервиса.
 * @param previousChatId Чей диалог сворачивать в сводку; null — сервис выберет прошлый
 *        чат сам (самую свежую другую сессию с сообщениями). Поле не отправляется, когда
 *        чат заводят из клиента: выбор прошлого чата — дело сервиса.
 * @param summarize Строить ли сводку; null — умолчание сервиса (`true`). `false` выключает
 *        сводку наверняка, поэтому поле шлётся только когда сводку гасят.
 */
@Serializable
data class ServiceCreateChatRequest(
    val title: String? = null,
    val previousChatId: String? = null,
    val summarize: Boolean? = null
)

/**
 * Ответ `POST /api/chats` (201): заведённая сессия и сводка её прошлого чата.
 *
 * @param previousChatId Чей диалог сворачивали; null — прошлого чата не было или сводку не строили.
 * @param summary Текст сводки; null — сводки нет.
 * @param summaryMessages Сколько сообщений прошлого чата в неё вошло; 0 — сводки нет.
 * @param summaryError Словами, почему сводку не построили; null — отказу не было. Чат при
 *        отказе всё равно заведён: показывать причину нужно, а не запирать разговор.
 */
@Serializable
data class ServiceChatCreated(
    val chatId: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    val previousChatId: String? = null,
    val summary: String? = null,
    val summaryMessages: Int = 0,
    val summaryError: String? = null
)

/**
 * История сессии, `GET /api/chats/{id}`.
 *
 * @param previousChatId Чей диалог сворачивали в сводку при создании; null — сводки нет.
 * @param summary Сводка прошлого чата; null — её нет. В сообщениях истории её нет:
 *        это контекст, подставленный сервисом в запрос к модели, а не реплика диалога.
 * @param summaryMessages Сколько сообщений прошлого чата вошло в сводку.
 */
@Serializable
data class ServiceChatHistory(
    val chatId: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messages: List<ServiceStoredMessage> = emptyList(),
    val previousChatId: String? = null,
    val summary: String? = null,
    val summaryMessages: Int = 0
)

/**
 * Чат в списке сервиса, `GET /api/chats`.
 *
 * @param chatId Идентификатор чата: им же адресуется история и ход в этот чат.
 * @param title Заголовок чата.
 * @param createdAt Когда чат завели.
 * @param updatedAt Когда в нём писали последний раз: по нему список отсортирован.
 * @param messages Сколько в чате сообщений.
 * @param summary Сводка прошлого чата; null — сводки нет.
 */
@Serializable
data class ServiceChatItem(
    val chatId: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messages: Int = 0,
    val summary: String? = null
)

/**
 * Список чатов сервиса, `GET /api/chats`.
 *
 * @param chats Чаты, свежие сверху.
 * @param count Сколько чатов в ответе (не всего в базе): клиент показывает «сколько отдано».
 */
@Serializable
data class ServiceChatList(
    val chats: List<ServiceChatItem> = emptyList(),
    val count: Int = 0
)

/**
 * Ссылка «поделиться чатом» для ленты сервиса: текст для показа и уложилась ли она в буфер.
 *
 * Отдельным объектом, а не парой значений: ссылку надо и скопировать, и показать — буфера
 * обмена на платформе может не быть, и тогда единственный способ передать её — выделить
 * текст руками. Признак [copied] говорит, получилось ли первое, чтобы в окне не обещать
 * скопированное, которого нет.
 *
 * @param text Ссылка вида `<адрес приложения>/?chat=<id>`, а где адреса нет — сам идентификатор.
 * @param copied Удалось ли положить текст в буфер обмена платформы.
 */
data class ServiceShareLink(val text: String, val copied: Boolean)

/**
 * Сообщение в истории сервиса.
 *
 * @param role Роль на проводе (`user`, `assistant`, `system`): своё перечисление не заводим —
 *        роль называет сервис, и клиент лишь переводит её в [MessageRole].
 * @param content Текст сообщения.
 * @param createdAt Время записи в миллисекундах.
 */
@Serializable
data class ServiceStoredMessage(
    val role: String = "",
    val content: String = "",
    val createdAt: Long = 0
)

/** Ответ `DELETE /api/chats/{id}`: сколько сообщений унесла сессия. */
@Serializable
data class ServiceDeletedChat(
    val chatId: String = "",
    val deleted: Boolean = false,
    val messages: Int = 0
)
