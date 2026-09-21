package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.ContextStrategy
import com.osvin.aichallenge.agent.ContextOverflowException
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.EmptyReplyException
import com.osvin.aichallenge.agent.InMemoryMemoryStore
import com.osvin.aichallenge.agent.InMemorySummaryStore
import com.osvin.aichallenge.agent.InMemoryTaskStateStore
import com.osvin.aichallenge.agent.InvariantForget
import com.osvin.aichallenge.agent.InvariantWrite
import com.osvin.aichallenge.agent.InvariantWriter
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.MemoryForget
import com.osvin.aichallenge.agent.MemoryWrite
import com.osvin.aichallenge.agent.MemoryWriter
import com.osvin.aichallenge.agent.ProfileStore
import com.osvin.aichallenge.agent.TaskWrite
import com.osvin.aichallenge.agent.TaskWriter
import com.osvin.aichallenge.agent.UserProfile
import com.osvin.aichallenge.invariants.JsonFileInvariantStore
import com.osvin.aichallenge.memory.JsonFileMemoryStore
import com.osvin.aichallenge.models.*
import com.osvin.aichallenge.profile.JsonFileProfileStore
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * HTTP-клиент для взаимодействия с внешним API DeepSeek.
 * Сконфигурирован с поддержкой JSON и игнорированием неизвестных полей.
 * Таймауты подняты: сильные модели (deepseek-v4-pro) на длинных ответах
 * отвечают дольше дефолтных 15 секунд движка CIO (день 5: сравнение моделей).
 */
val client = HttpClient(CIO) {
    install(ClientContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 300_000
    }
}

/**
 * Сводки историй диалогов: отдельно от сообщений, по сессии.
 *
 * Хранилище общее на все запросы сервера: агент создаётся на каждый запрос,
 * а сводка должна пережить запрос и заменить свёрнутые сообщения в следующем.
 */
val summaryStore = InMemorySummaryStore()

/**
 * Состояние задачи по сессии: этап, текущий шаг и ожидаемое действие.
 *
 * Стоит рядом со сводками, а не с памятью, потому что живёт так же — по диалогу.
 * Задача описывает работу в конкретном чате, и её шаг — продолжение именно этой
 * истории: чат удалили, задача ушла вместе с ним, а в соседнем диалоге своя. Память
 * же обоих слоёв принадлежит профилю и видна из любого чата.
 *
 * Лежит в памяти процесса, а не в файле, по той же причине, по которой привязана
 * к сессии: это курсор текущей работы, и перезапуск сервера означает, что продолжать
 * нечего — сообщения диалога, относительно которых шаг что-то значит, сервер не хранит.
 * Состояние из файла показывало бы шаг к истории, которой на сервере уже нет.
 *
 * Общее на все запросы, как сводки: агент создаётся на каждый запрос, а задача должна
 * пережить запрос, чтобы следующий продолжил с того же шага, а не начал заново.
 */
val taskStateStore = InMemoryTaskStateStore()

/**
 * Память агента на сервере: два хранилища, по одному на слой с состоянием.
 *
 * Оба слоя живут по профилю, а не по чату: и рабочая память задачи, и долговременная
 * общие для всех диалогов, поэтому запись видна из любого чата. Разница между ними —
 * срок жизни: рабочая лежит в памяти процесса и обнуляется перезапуском сервера, для
 * этого и создана на каждом запуске; долговременная пишется в файл и перезапуск
 * переживает, иначе профиль пользователя, решения и знания собирались бы заново
 * из одного диалога, в котором их нет.
 *
 * Как и сводки, хранилища общие на все запросы: агент создаётся на каждый запрос,
 * а память должна пережить запрос и наполниться в следующем.
 */
val workingMemory = InMemoryMemoryStore()

val longTermMemory = JsonFileMemoryStore(JsonFileMemoryStore.defaultFile())

/**
 * Профиль пользователя на сервере: объявленные предпочтения, а не извлечённые из диалога.
 *
 * Рядом с памятью, но не память: профиль задаёт сам пользователь и правит его шторкой
 * профиля, поэтому здесь нет ни извлечения моделью, ни слияния с прежними записями,
 * ни пределов объёма. Общее у них только место — идентификатор профиля
 * ([DEFAULT_PROFILE]), по которому профиль лежит рядом с памятью того же профиля.
 *
 * Лежит в файле, как долговременная память: персонализация должна пережить перезапуск
 * сервера, иначе ассистент терял бы настройки пользователя при каждом запуске.
 */
val profileStore = JsonFileProfileStore(JsonFileProfileStore.defaultFile())

/**
 * Инварианты проекта на сервере: правила, которым обязан соответствовать ассистент.
 *
 * Отдельное хранилище, а не поле профиля или памяти: инварианты не описывают
 * пользователя и не извлекаются из диалога — это условие задачи, которое проверяется
 * до ответа, поэтому у них нет ни извлечения моделью, ни слияния с прежними записями.
 * Общий у трёх хранилищ только адрес — идентификатор профиля ([DEFAULT_PROFILE]),
 * по которому правила лежат рядом с профилем и памятью того же профиля.
 *
 * Лежит в файле, как профиль и долговременная память: правила задаёт человек, поэтому
 * они должны пережить перезапуск сервера — иначе ограничения снимались бы вместе с
 * процессом, и ассистент молча выполнял бы просьбы, от которых его уберегали.
 *
 * Отличие от прочих сторов: здесь значимо и само наличие записи. «Правил не задавали»
 * (умолчания проекта) и «правил нет» (человек убрал все) — разные состояния, поэтому
 * убрать все правила можно, и проверки конфликта после этого не будет.
 */
val invariantStore = JsonFileInvariantStore(JsonFileInvariantStore.defaultFile())

/**
 * Профиль, который уходит в запрос к модели и показывается клиенту: пусто в сторе —
 * профиль KMP-разработчика ([UserProfile.DEFAULT]).
 *
 * Одна функция на оба места — маршрут профиля и маршрут чата: иначе пользователь видел
 * бы в шторке один профиль, а в запрос уходил другой. Умолчание к тому же делает
 * приложение персонализированным с первого запуска, а не после ручного заполнения.
 */
fun ProfileStore.profileOrDefault(profileId: String = DEFAULT_PROFILE): UserProfile =
    get(profileId) ?: UserProfile.DEFAULT

/**
 * Точка входа в приложение.
 * Запускает встроенный сервер Netty.
 */
fun main() {
    embeddedServer(
        Netty,
        port = AppConfig.DEFAULT_PORT,
        host = AppConfig.DEFAULT_HOST,
        module = Application::module
    ).start(wait = true)
}

/**
 * Ключ DeepSeek: сначала переменная окружения, затем `server/.env`.
 *
 * `.env` читает сам сервер, а не только Gradle-задача `runDev`: сервер запускают
 * и из IDE, и через `:server:run`, и из собранного jar — без этого ключа маршрут
 * падал до того, как агент успевал напечатать свои логи.
 */
private fun deepSeekApiKey(): String? =
    System.getenv("DEEPSEEK_API_KEY") ?: envFile()["DEEPSEEK_API_KEY"]

/** Содержимое `.env` рядом с проектом или в текущем каталоге; пусто, если файла нет. */
private fun envFile(): Map<String, String> {
    val file = listOf(File("server/.env"), File(".env")).firstOrNull { it.isFile } ?: return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

/**
 * Причина отказа, когда запрос к задаче не назвал диалог.
 *
 * Состояние задачи живёт по сессии, а не по профилю, поэтому безымянный запрос
 * адресовать нечего: ответ пустым снимком значил бы «задачи нет ни у кого»,
 * а запись в безымянную сессию потерялась бы при удалении любого чата.
 */
private const val TASK_SESSION_REQUIRED =
    "не названа сессия: состояние задачи живёт по диалогу, поэтому без sessionId у запроса нет адреса"

/** Отказ «сессия не названа» — ошибка клиента, а не пустой снимок. */
private suspend fun ApplicationCall.respondTaskSessionRequired() =
    respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(success = false, error = TASK_SESSION_REQUIRED)
    )

/**
 * Основной модуль сервера Ktor.
 * Настраивает плагины и маршрутизацию.
 */
fun Application.module() {
    // Поддержка JSON для входящих и исходящих данных
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    // Глобальная обработка исключений
    install(StatusPages) {
        // Переполнение контекста — ошибка запроса, а не сбой сервера
        exception<ContextOverflowException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(success = false, error = cause.message ?: "Контекст переполнен")
            )
        }

        // Пустой ответ модели: бюджета ответа не хватило на текст (thinking-модель
        // спустила его на рассуждения) — клиенту нужна причина, а не пустая строка
        exception<EmptyReplyException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(success = false, error = cause.message ?: "Модель не вернула текст ответа")
            )
        }

        // Ошибка запроса у провайдера: переполнение контекста, неверный max_tokens —
        // это вина запроса, а не сбой сервера. Сбой API провайдера — 502.
        exception<LlmApiException> { call, cause ->
            val status = if (cause.status in 400..499) {
                HttpStatusCode.BadRequest
            } else {
                HttpStatusCode.BadGateway
            }
            call.respond(
                status,
                ErrorResponse(success = false, error = cause.message ?: "Ошибка LLM API")
            )
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Internal Server Error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(success = false, error = cause.message ?: "Неизвестная ошибка сервера")
            )
        }
    }

    // Настройка CORS для возможности запросов с разных доменов (полезно для Web/JS)
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    // Ограничение частоты запросов для защиты API
    install(RateLimit) {
        register(RateLimitName("chat")) {
            rateLimiter(limit = 20, refillPeriod = 60.seconds)
        }
    }

    routing {
        /**
         * Проверка состояния сервера и API ключа.
         */
        get("/v1/health") {
            val apiKey = deepSeekApiKey()

            if (apiKey.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HealthResponse(
                        status = "error",
                        deepseek = "unknown",
                        message = "Ключ DEEPSEEK_API_KEY не задан в переменных окружения"
                    )
                )
                return@get
            }

            try {
                // Простая проверка связи с API через список моделей
                val response = client.get("https://api.deepseek.com/v1/models") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }

                if (response.status.isSuccess()) {
                    call.respond(HealthResponse(status = "ok", deepseek = "online"))
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthResponse(
                            status = "error",
                            deepseek = "auth_failed",
                            code = response.status.value
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthResponse(status = "error", deepseek = "offline", message = e.message)
                )
            }
        }

        /**
         * Основной endpoint для чата.
         * Транспортный адаптер: принимает HTTP-запрос, передаёт набор параметров
         * агенту ([LlmAgent]) и возвращает ответ клиенту. Вся работа с моделью
         * инкапсулирована в агенте.
         *
         * Профиль читается из стора на каждый запрос — централизованно, здесь: клиент
         * его не присылает и не может забыть, и ни одна стратегия контекста не отключает
         * персонализацию. Сам [com.osvin.aichallenge.models.ChatRequest] профиля не знает:
         * это объявленные предпочтения сервера, а не поле запроса.
         *
         * Инварианты читаются тем же способом и по той же причине: правила проекта —
         * объявленное состояние сервера, поэтому клиент их не присылает, и клиентская
         * версия правил не может разойтись с той, по которой ассистент отвечает.
         */
        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val apiKey = deepSeekApiKey()
                ?: error("API ключ не настроен")

            val agent = LlmAgent(
                DeepSeekClient(apiKey, client),
                summaryStore = summaryStore,
                taskStateStore = taskStateStore,
                workingMemory = workingMemory,
                longTermMemory = longTermMemory,
                invariantStore = invariantStore
            )
            val result = agent.run(
                userMessage = request.message,
                options = request.toAgentOptions(profile = profileStore.profileOrDefault())
            )

            call.respond(
                ChatResponse(
                    success = true,
                    reply = result.reply,
                    usage = result.usage,
                    tokens = result.tokens
                )
            )
        }

        /**
         * Удаление сессии чата.
         * Клиент удалил чат — сводка его истории и состояние его задачи серверу больше
         * не нужны: без этого они копились бы на каждый удалённый диалог. Сессионное
         * стирается целиком, потому что продолжать в удалённом чате нечего: ни сводки,
         * ни этапа с шагом у него не остаётся. Память не трогается: оба слоя принадлежат
         * профилю, а не чату, поэтому удаление чата не стирает рабочую память задачи —
         * её убирают только забыванием в шторке, вытеснением и перезапуск сервера.
         * Инварианты не трогаются тем более: они принадлежат проекту, а не диалогу —
         * один и тот же человек работает по одним правилам во всех чатах, поэтому
         * удалить правило можно только явным действием в шторке инвариантов.
         */
        delete("/v1/chats/{sessionId}") {
            val sessionId = call.parameters["sessionId"].orEmpty()
            summaryStore.clear(sessionId)
            taskStateStore.clear(sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Профиль пользователя: объявленные предпочтения, которые уезжают в каждый
         * запрос к модели.
         *
         * Пусто в сторе — отдаём профиль KMP-разработчика ([UserProfile.DEFAULT]):
         * приложение должно быть персонализировано с первого запуска, а не после того,
         * как пользователь вручную заполнит шторку профиля.
         */
        get("/v1/profile") {
            call.respond(profileStore.profileOrDefault())
        }

        /**
         * Правка профиля: тело — профиль целиком, потому что правится он шторкой,
         * где заполнены все поля.
         *
         * Возвращается сохранённый профиль, а не отправленный: клиент видит то, что
         * легло в стор, и шторка после сохранения показывает то же, чем пользуется
         * модель. Профиль не проходит ни слияние с прежним, ни проверки пределов —
         * это не память, а объявленные предпочтения: что прислали, то и лежит.
         */
        put("/v1/profile") {
            val profile = call.receive<UserProfile>()
            profileStore.put(DEFAULT_PROFILE, profile)
            call.respond(profile)
        }

        /**
         * Инварианты проекта: правила, которым обязан соответствовать ассистент.
         *
         * Снимок полный — сами правила и каталог видов: подписи и пояснения в интерфейсе
         * берутся из него, и своей копии таблицы видов на клиенте нет. Пусто в сторе —
         * отдаём умолчания проекта ([com.osvin.aichallenge.agent.Invariant.DEFAULT]):
         * ассистент ограничен с первого запуска, а не после того, как человек заполнит
         * правила. Пустой список в сторе — не то же самое: человек убрал все правила,
         * поэтому снимок приходит пустым, и проверки конфликта в запросах больше нет.
         */
        get("/v1/invariants") {
            call.respond(InvariantWriter(invariantStore).snapshot())
        }

        /**
         * Добавление правила: явное действие человека, как запись в память.
         *
         * Отказ (вид неизвестен, пустая формулировка, исчерпан предел) — ошибка запроса:
         * клиент покажет причину, а правила останутся прежними. Повтор с тем же текстом
         * не ошибка: формулировка заменяется, как в памяти дня 11. Возвращается снимок,
         * а не отправленное правило: клиент видит то, что легло в стор и уедет в запрос.
         */
        post("/v1/invariants") {
            val request = call.receive<InvariantWriteRequest>()
            val writer = InvariantWriter(invariantStore)
            when (val write = writer.remember(request.kind, request.value)) {
                is InvariantWrite.Rejected -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, error = write.reason)
                )

                is InvariantWrite.Written -> call.respond(write.snapshot)
            }
        }

        /**
         * Забывание правила: вид и формулировку человек называет сам.
         *
         * Повторное удаление не ошибка: снимок вернётся как был — правило адресуется
         * текстом, и забывать уже нечего. Убрали все правила — в сторе лежит пустой
         * список, поэтому снимок приходит пустым, и агент больше не проверяет конфликты:
         * в этом и отличие от «правил не задавали», когда работают умолчания проекта.
         */
        delete("/v1/invariants") {
            val params = call.request.queryParameters
            val writer = InvariantWriter(invariantStore)
            when (val forget = writer.forget(params["kind"], params["value"])) {
                is InvariantForget.Rejected -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, error = forget.reason)
                )

                is InvariantForget.Forgotten -> call.respond(forget.snapshot)
            }
        }

        /**
         * Память агента целиком: оба хранимых типа и каталог типов памяти.
         *
         * Снимок полный, а не только то, что ушло в последний запрос: по нему работает
         * шторка памяти, поэтому она не зависит от выбранной стратегии. Каталог типов
         * уезжает вместе со снимком — подписи и пояснения в интерфейсе берутся из него,
         * и своей копии таблицы типов на клиенте нет. Сессии в запросе нет: оба слоя
         * живут по профилю, поэтому снимок один на все чаты.
         */
        get("/v1/memory") {
            call.respond(MemoryWriter(workingMemory, longTermMemory).layers())
        }

        /**
         * Явная запись в память: сохранить фразу, которую пользователь назвал сам.
         *
         * Отказ (тип неизвестен, тип краткосрочный, пустой текст) — ошибка запроса:
         * клиент покажет её текст, а память останется прежней. Сессии в теле нет:
         * и рабочую, и долговременную память запись адресует профилю.
         */
        post("/v1/memory") {
            val request = call.receive<MemoryWriteRequest>()
            val writer = MemoryWriter(workingMemory, longTermMemory)
            val write = writer.remember(request.layer, request.value)
            when (write) {
                is MemoryWrite.Rejected -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, error = write.reason)
                )

                is MemoryWrite.Written -> call.respond(writer.layers())
            }
        }

        /**
         * Забывает запись в названном типе памяти — тоже явный выбор пользователя.
         *
         * Повторное удаление не ошибка: снимок вернётся как был. Неизвестный тип или
         * краткосрочная память — ошибка запроса: интерфейс рисует выбор по каталогу,
         * значит пришло чужое.
         */
        delete("/v1/memory") {
            val params = call.request.queryParameters
            val writer = MemoryWriter(workingMemory, longTermMemory)
            val forget = writer.forget(params["layer"].orEmpty(), params["value"].orEmpty())
            when (forget) {
                is MemoryForget.Rejected -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, error = forget.reason)
                )

                is MemoryForget.Forgotten -> call.respond(forget.layers)
            }
        }

        /**
         * Состояние задачи этого диалога: этап, текущий шаг и ожидаемое действие.
         *
         * Снимок читается и до старта: пустое состояние — это ответ «задачи нет»,
         * а не ошибка, поэтому шторка задачи рисуется всегда, а не после того, как
         * пользователь её завёл. Каталог этапов уезжает вместе со снимком — подписи
         * в интерфейсе берутся из него, и своей копии таблицы этапов на клиенте нет.
         * Сессия в параметрах обязательна: состояние живёт по диалогу.
         */
        get("/v1/task") {
            val sessionId = call.request.queryParameters["sessionId"].orEmpty()
            if (sessionId.isBlank()) {
                call.respondTaskSessionRequired()
                return@get
            }
            call.respond(TaskWriter(taskStateStore).snapshot(sessionId))
        }

        /**
         * Взятие задачи в работу: явное действие человека, как запись в память.
         *
         * Ведёт задачу дальше агент сам, а этот вызов только заводит её — и заводит
         * в этапе «планирование». Повторное нажатие не ошибка и не сбрасывает уже
         * пройденный путь: кнопка может нажаться дважды. Ответ — снимок, а не пустая
         * строка: клиент сразу видит, что задача в работе и с какого шага.
         */
        post("/v1/task") {
            val request = call.receive<TaskStartRequest>()
            if (request.sessionId.isBlank()) {
                call.respondTaskSessionRequired()
                return@post
            }
            call.respond(TaskWriter(taskStateStore).start(request.sessionId))
        }

        /**
         * Пауза и продолжение задачи: состояние замораживается, шаг остаётся на месте.
         *
         * Отказ (задача не заведена) — ошибка запроса: ставить на паузу нечего, и клиент
         * покажет причину вместо того, чтобы решить, будто задача есть. Снятие паузы
         * продолжает с того же шага: служебного вызова к модели на паузе нет вовсе,
         * поэтому терять нечего.
         */
        put("/v1/task") {
            val request = call.receive<TaskPauseRequest>()
            if (request.sessionId.isBlank()) {
                call.respondTaskSessionRequired()
                return@put
            }
            val write = TaskWriter(taskStateStore).setPaused(request.sessionId, request.paused)
            when (write) {
                is TaskWrite.Rejected -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, error = write.reason)
                )

                is TaskWrite.Written -> call.respond(write.snapshot)
            }
        }

        /**
         * Забывание задачи: пользователь закрыл работу в этом диалоге.
         *
         * Повторное удаление не ошибка: снимок вернётся как был — пустым, потому что
         * забывать уже нечего. Тем же способом задача чистится при удалении чата.
         */
        delete("/v1/task") {
            val sessionId = call.request.queryParameters["sessionId"].orEmpty()
            if (sessionId.isBlank()) {
                call.respondTaskSessionRequired()
                return@delete
            }
            call.respond(TaskWriter(taskStateStore).forget(sessionId))
        }
    }
}
