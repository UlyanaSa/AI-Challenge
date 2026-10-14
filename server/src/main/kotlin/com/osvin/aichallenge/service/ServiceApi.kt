package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.LlmClientFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Всё, что нужно маршрутам сервиса, одним набором.
 *
 * Набор приходит параметром, а не берётся из глобальных переменных: маршруты `/api/…`
 * проверяются на подставном хранилище и подставном движке, не поднимая ни базы, ни
 * демона Ollama. Сборка набора — дело запуска ([com.osvin.aichallenge.Application.module]):
 * сервис не решает сам, где лежит файл базы и откуда взят ключ.
 *
 * @param config Настройки сервиса: ключи, лимиты, потолки, инструкция.
 * @param store Хранилище сессий и истории.
 * @param metrics Метрики: счётчики, длительности, снимок ресурсов.
 * @param limits Ограничения частоты и параллельности.
 * @param auth Проверка ключа доступа и идентификатор запроса.
 * @param llm Сборка транспорта к модели: та же, что у `/v1/…`, поэтому провайдер выбирается
 *        запуском, а не запросом.
 */
class ServiceDeps(
    val config: ServiceConfig,
    val store: ChatSessionStore,
    val metrics: ServiceMetrics,
    val limits: ServiceLimits,
    val auth: ServiceAuth,
    val llm: LlmClientFactory
)

/**
 * Запрос ответа модели.
 *
 * @param chatId Сессия, в которую пишется вопрос и ответ: без неё запрос адресовать некуда.
 * @param message Текущий вопрос человека.
 * @param model Модель запроса; не задана — модель запуска. Клиент может выбрать другую
 *        загруженную модель демона, но не потолки окна и бюджета.
 * @param temperature Температура; не задана — температура запуска. Выше потолка сервиса
 *        значение обрезается, а не отвергается.
 * @param maxTokens Бюджет ответа; не задан — потолок сервиса.
 * @param contextWindow Окно контекста; не задано — потолок сервиса.
 */
@Serializable
data class ChatRequest(
    val chatId: String? = null,
    val message: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null
)

/**
 * Запрос создания сессии.
 *
 * @param title Имя сессии; не задано — имя по умолчанию, потому что чат нужен клиенту
 *        до того, как человек придумал ему имя.
 * @param previousChatId Чат, диалог которого надо свёрнуть в сводку; не задан — сервис
 *        берёт самый свежий другой чат с сообщениями сам.
 * @param summarize Строить ли сводку; не задано — строить, потому что продолжение разговора
 *        и есть обычный случай. `false` отменяет сводку даже при включённой настройке.
 */
@Serializable
data class CreateChatRequest(
    val title: String? = null,
    val previousChatId: String? = null,
    val summarize: Boolean? = null
)

/**
 * Ответ на вопрос.
 *
 * Поля токенов объявлены без умолчаний намеренно: `null` в них — это «движок не назвал
 * расход», и ключ обязан быть в ответе, чтобы клиент отличил отсутствие расхода от нуля
 * (в `usage` его не было, а не «ноль токенов»).
 *
 * @param requestId Идентификатор запроса: тот же, что в логе сервиса.
 * @param chatId Сессия, куда записаны вопрос и ответ.
 * @param reply Текст ответа модели.
 * @param model Модель, которой ответил движок.
 * @param elapsedMs Время вызова модели.
 * @param promptTokens Токены запроса из `usage`; `null` — движок расхода не назвал.
 * @param completionTokens Токены ответа из `usage`; `null` — движок расхода не назвал.
 * @param totalTokens Сумма токенов из `usage`; `null` — движок расхода не назвал.
 * @param trimmedMessages Сколько старых сообщений истории не ушло в запрос.
 * @param status Признак успешного ответа: клиент различает ответ и отказ по телу, а не только
 *        по статусу, и это тот же признак, что у `/v1/…`.
 */
@Serializable
data class ChatReply(
    val requestId: String,
    val chatId: String,
    val reply: String,
    val model: String,
    val elapsedMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val trimmedMessages: Int,
    val status: String
)

/**
 * Модель в списке демона.
 *
 * Имя — единственное, что даёт [com.osvin.aichallenge.agent.OllamaProbe]: проверка здоровья
 * уже разбирает список демона, и второй разбор того же ответа другими полями завёл бы вторую
 * правду о моделях. Размер, квантизация и число параметров остаются пустыми, пока движок
 * не назовёт их в том разборе, — выдумывать их нельзя, а второй HTTP-запрос к демону
 * запрещён заданием.
 *
 * @param name Имя модели с тегом.
 * @param sizeBytes Размер файла модели в байтах; `null` — неизвестен.
 * @param quantization Квантизация; `null` — неизвестна.
 * @param parameterSize Число параметров; `null` — неизвестно.
 */
@Serializable
data class ModelInfo(
    val name: String,
    val sizeBytes: Long? = null,
    val quantization: String? = null,
    val parameterSize: String? = null
)

/** Ответ списка моделей: обёртка к списку, а не массив в корне — по ней клиент отличает ответ от отказа. */
@Serializable
data class ModelsResponse(val models: List<ModelInfo>)

/**
 * Созданная сессия.
 *
 * Поля сводки объявлены без умолчаний намеренно: `null` в них — это «сводки нет», и ключ
 * обязан быть в ответе явно, чтобы клиент отличил её отсутствие от поля, которого он
 * не знает. Клиент показывает и текст сводки, и причину, по которой её нет.
 *
 * @param chatId Идентификатор сессии для следующих запросов.
 * @param title Имя сессии: подставленное умолчание, если человек его не задал.
 * @param createdAt Момент создания в миллисекундах эпохи.
 * @param previousChatId Чат, из которого взята сводка; `null` — сводку не строили
 *        (`summarize=false` или прошлого чата нет вовсе).
 * @param summary Текст сводки прошлого диалога; `null` — сводки нет.
 * @param summaryMessages Сколько сообщений прошлого чата сжато; `0` — сводки нет.
 * @param summaryError Причина, по которой сводка не построена; `null` — построена
 *        или не запрашивалась (отсутствие прошлого чата — не отказ).
 */
@Serializable
data class CreatedChat(
    val chatId: String,
    val title: String,
    val createdAt: Long,
    val previousChatId: String?,
    val summary: String?,
    val summaryMessages: Int,
    val summaryError: String?
)

/**
 * Строка списка чатов.
 *
 * Отдельный тип от [ChatDetail], хотя поля пересекаются: список и история читаются разными
 * экранами и грузятся по отдельности, и общий тип заставлял бы список отвечать за поле
 * сообщений, которого у него нет.
 *
 * @param chatId Идентификатор сессии: по нему клиент открывает чат и адресует ход.
 * @param title Имя сессии.
 * @param createdAt Момент создания.
 * @param updatedAt Момент последнего сообщения: по нему список упорядочен.
 * @param messages Сколько сообщений в чате.
 * @param summary Сводка прошлого диалога; `null` — сводки нет (в ответе явно, чтобы клиент
 *        отличил её отсутствие от незнакомого поля).
 */
@Serializable
data class ChatSummary(
    val chatId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: Int,
    val summary: String?
)

/**
 * Ответ списка чатов.
 *
 * @param chats Чаты, свежие первыми.
 * @param count Сколько чатов отдано в этом ответе (не всего в базе): клиент показывает,
 *        сколько он получил, а не сколько существует.
 */
@Serializable
data class ChatsResponse(val chats: List<ChatSummary>, val count: Int)

/**
 * Сообщение сессии в ответе.
 *
 * Отдельный тип от [StoredMessage], хотя поля совпадают: хранилище — не поверхность,
 * и его форма вправе меняться, не ломая клиента. Совпадение здесь случайно, а не контракт.
 *
 * @param role Роль автора: `user` или `assistant`.
 * @param content Текст сообщения.
 * @param createdAt Момент записи в миллисекундах эпохи.
 */
@Serializable
data class StoredMessageDto(val role: String, val content: String, val createdAt: Long)

/**
 * Сессия с историей.
 *
 * Три поля сводки — те же, что у созданного чата, и объявлены так же, без умолчаний: клиент,
 * открывший историю, обязан увидеть и сводку, с которой разговор начат, и то, что её нет, —
 * а не догадываться об этом по отсутствию ключа.
 *
 * @param chatId Идентификатор сессии.
 * @param title Имя сессии.
 * @param createdAt Момент создания.
 * @param updatedAt Момент последнего сообщения.
 * @param messages История по возрастанию времени.
 * @param previousChatId Чат, из которого взята сводка; `null` — сводки нет.
 * @param summary Сводка прошлого диалога; `null` — сводки нет.
 * @param summaryMessages Сколько сообщений прошлого чата сжато; `0` — сводки нет.
 */
@Serializable
data class ChatDetail(
    val chatId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessageDto>,
    val previousChatId: String?,
    val summary: String?,
    val summaryMessages: Int
)

/**
 * Итог удаления сессии.
 *
 * @param chatId Идентификатор удалённой сессии.
 * @param deleted Признак удаления: клиент видит, что убрано, а не «запрос прошёл».
 * @param messages Сколько сообщений удалено вместе с сессией.
 */
@Serializable
data class DeletedChat(val chatId: String, val deleted: Boolean, val messages: Int)

/**
 * Проверка живости сервиса.
 *
 * Один тип на три состояния, потому что клиенту важно различие не между типами, а между
 * строками `ollama`: «демон не ответил» требует `ollama serve`, «модель не загружена» —
 * `ollama pull`. Поля, не относящиеся к состоянию, остаются пустыми и в ответ не пишутся:
 * умолчание `null` сериализатор пропускает, поэтому форма ответа у состояний разная,
 * как и требует контракт.
 *
 * @param status `ok` или `error`.
 * @param provider Провайдер генерации: `local` у этого сервиса.
 * @param model Модель, которой отвечает сервис.
 * @param ollama `ok` — демон ответил; `unreachable` — не ответил.
 * @param modelLoaded Загружена ли модель; есть только у ответившего демона.
 * @param uptimeMs Сколько сервис работает.
 * @param message Причина отказа словами.
 */
@Serializable
data class ServiceHealth(
    val status: String,
    val provider: String? = null,
    val model: String? = null,
    val ollama: String? = null,
    val modelLoaded: Boolean? = null,
    val uptimeMs: Long? = null,
    val message: String? = null
)

/**
 * Единое тело ошибки сервиса.
 *
 * Формат отличается от `/v1/…` ([com.osvin.aichallenge.ErrorResponse]) намеренно: клиент
 * сервиса разбирает отказ по коду, а не по тексту, и код обязан быть машиночитаемым.
 * Идентификатор запроса здесь затем, что человек ищет в логе строку по нему, а не по тексту,
 * который бывает и русским, и длинным.
 *
 * @param code Машиночитаемый код отказа.
 * @param message Причина словами; текст ключа и переписки в неё не попадает никогда.
 * @param requestId Идентификатор запроса: тот же, что в логе сервиса.
 */
@Serializable
data class ApiError(val code: String, val message: String, val requestId: String) {

    companion object {

        /** Тело запроса не разобрано или не хватает обязательного поля. */
        const val INVALID_REQUEST = "invalid_request"

        /** Ключ доступа не предъявлен или не подходит. */
        const val UNAUTHORIZED = "unauthorized"

        /** Сессия не найдена. */
        const val CHAT_NOT_FOUND = "chat_not_found"

        /** Обязательная часть запроса не влезает в окно контекста. */
        const val CONTEXT_OVERFLOW = "context_overflow"

        /** Исчерпан лимит частоты: в ответе есть `Retry-After`. */
        const val RATE_LIMITED = "rate_limited"

        /** Демон Ollama не ответил. */
        const val OLLAMA_UNAVAILABLE = "ollama_unavailable"

        /** Демон ответил, но модели у него нет. */
        const val MODEL_UNAVAILABLE = "model_unavailable"

        /**
         * Модель вернула пустой текст: бюджет ответа ушёл в рассуждения.
         *
         * Отказ, а не ответ: `status: ok` с пустой строкой клиент покажет как ответ модели,
         * то есть как утверждение, которого не было, — тот же случай, из-за которого
         * в агенте есть [com.osvin.aichallenge.agent.EmptyReplyException], а у `/v1/…` отказ.
         */
        const val EMPTY_REPLY = "empty_reply"

        /** Очередь генераций заполнена или время ожидания вышло: в ответе есть `Retry-After`. */
        const val OVERLOADED = "overloaded"

        /** Модель не успела ответить за отведённый срок. */
        const val TIMEOUT = "timeout"

        /** Сервис не настроен: не задан ключ доступа или выбран не локальный провайдер. */
        const val SERVICE_NOT_CONFIGURED = "service_not_configured"

        /** Неожиданный сбой сервиса: клиент не должен получить ответ другого формата. */
        const val INTERNAL_ERROR = "internal_error"
    }
}

/**
 * Снимок работы сервиса.
 *
 * Форма ответа — из контракта, поэтому тип вложенный: клиент-панель показывает срез
 * целиком, и «плоские» имена полей (`requestsTotal`, `latencyP95`) пришлось бы собирать
 * обратно в группы на клиенте, где они и читаются вместе.
 *
 * @param uptimeMs Сколько сервис работает.
 * @param requests Счётчики запросов к модели.
 * @param latency Длительности генераций.
 * @param generations Занятость генераций.
 * @param rateLimit Отказы по частоте.
 * @param tokens Расход токенов по `usage` ответов.
 * @param sessions Размеры хранилища сессий.
 * @param summaries Сводки прошлых чатов: построенные и непостроенные.
 * @param resources Память и процессор.
 */
@Serializable
data class MetricsSnapshot(
    val uptimeMs: Long,
    val requests: RequestsMetrics,
    val latency: LatencyMetrics,
    val generations: GenerationsMetrics,
    val rateLimit: RateLimitMetrics,
    val tokens: TokenMetrics,
    val sessions: SessionMetrics,
    val summaries: SummaryMetrics,
    val resources: ResourceMetrics
)

/**
 * Счётчики запросов к модели.
 *
 * @param total Сколько запросов принято.
 * @param success Сколько из них ответило успехом.
 * @param failed Сколько ответило отказом.
 */
@Serializable
data class RequestsMetrics(val total: Long, val success: Long, val failed: Long)

/**
 * Длительности генераций.
 *
 * @param averageMs Средняя длительность по всем замерам.
 * @param p95Ms Девяносто пятый процентиль по последним замерам; замеров меньше окна — по всем.
 * @param samples Сколько замеров сделано.
 */
@Serializable
data class LatencyMetrics(val averageMs: Long, val p95Ms: Long, val samples: Long)

/**
 * Занятость генераций.
 *
 * @param active Сколько генераций идёт сейчас.
 * @param queued Сколько запросов ждёт место.
 * @param maxConcurrent Потолок одновременных генераций.
 * @param queueLimit Потолок очереди ожидания.
 */
@Serializable
data class GenerationsMetrics(
    val active: Int,
    val queued: Int,
    val maxConcurrent: Int,
    val queueLimit: Int
)

/**
 * Отказы по частоте.
 *
 * @param rejections Сколько запросов отвергнуто лимитом.
 * @param limitPerMinute Потолок запросов в минуту на ключ.
 */
@Serializable
data class RateLimitMetrics(val rejections: Long, val limitPerMinute: Int)

/**
 * Расход токенов по ответам движка.
 *
 * @param prompt Токены запросов.
 * @param completion Токены ответов.
 * @param total Сумма токенов.
 */
@Serializable
data class TokenMetrics(val prompt: Long, val completion: Long, val total: Long)

/**
 * Размеры хранилища сессий.
 *
 * @param count Сколько сессий заведено.
 * @param messages Сколько сообщений лежит во всех сессиях.
 */
@Serializable
data class SessionMetrics(val count: Int, val messages: Int)

/**
 * Сводки прошлых чатов.
 *
 * Отдельная группа, а не поля в [SessionMetrics]: сводка — это вызов модели, и её отказы
 * не должны выглядеть как работа хранилища. Числа расходятся: чат создаётся в любом случае,
 * а сводка может не построиться.
 *
 * @param built Сколько сводок построено.
 * @param failed Сколько раз сводку построить не удалось: прошлый чат оказался пустым
 *        или модель не ответила.
 */
@Serializable
data class SummaryMetrics(val built: Long, val failed: Long)

/**
 * Память и процессор.
 *
 * Память кучи всегда доступна, а память машины и загрузка процессора — только у реализации
 * `com.sun.management.OperatingSystemMXBean`: на другой JVM их нет, и вместо выдуманного нуля
 * поле остаётся пустым. Ноль здесь был бы враньём — панель показала бы «память не занята»
 * там, где её просто не спросили.
 *
 * @param heapUsedBytes Занятая куча.
 * @param heapMaxBytes Потолок кучи.
 * @param ramUsedBytes Занятая память машины; `null` — неизвестна.
 * @param ramTotalBytes Память машины; `null` — неизвестна.
 * @param systemCpuLoad Загрузка процессора машины; `null` — неизвестна.
 * @param processCpuLoad Загрузка процессора процессом; `null` — неизвестна.
 * @param processors Сколько процессоров видит JVM.
 */
@Serializable
data class ResourceMetrics(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val systemCpuLoad: Double? = null,
    val processCpuLoad: Double? = null,
    val processors: Int
)

/**
 * Разбор тел сервиса: неизвестные поля не ломают запрос, умолчания не пишутся в ответ.
 *
 * Умолчания в ответе не пишутся затем, что форма ответа у одного типа зависит от состояния
 * (см. [ServiceHealth]): пустое поле — это «не относится», а не «пустая строка». Явный
 * `null` при этом остаётся у полей без умолчания ([ChatReply]), где он значит «движок не
 * назвал значение».
 */
internal val serviceJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Отвечает телом сервиса: сериализация своя, а не через общий `ContentNegotiation`.
 *
 * Плагин сервера настраивается один раз на всё приложение и обслуживает `/v1/…`; форма
 * ответа `/api/…` — часть контракта, и она не должна зависеть от того, что завтра решат
 * поменять в настройках общего разбора. Поэтому здесь свой сериализатор и свой текст ответа.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.respondService(
    status: HttpStatusCode,
    body: T
) {
    respondText(serviceJson.encodeToString(body), ContentType.Application.Json, status)
}

/**
 * Отвечает отказом сервиса, при необходимости с `Retry-After`.
 *
 * Заголовок ставится до тела, потому что клиент читает его из заголовков ответа: секунды
 * до освобождения окна и до места в очереди — числа, по которым клиент решает, ждать ему
 * или показывать человеку причину.
 */
internal suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    error: ApiError,
    retryAfterSeconds: Long? = null
) {
    if (retryAfterSeconds != null) {
        response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    }
    respondService(status, error)
}
