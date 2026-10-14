package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentLogger
import com.osvin.aichallenge.agent.AgentOptions
import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.agent.ContextStrategy
import com.osvin.aichallenge.agent.ContextOverflowException
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.EmptyReplyException
import com.osvin.aichallenge.agent.InMemoryInvariantStore
import com.osvin.aichallenge.agent.InMemoryMemoryStore
import com.osvin.aichallenge.agent.InMemorySummaryStore
import com.osvin.aichallenge.agent.InMemoryTaskStateStore
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantForget
import com.osvin.aichallenge.agent.InvariantWrite
import com.osvin.aichallenge.agent.InvariantWriter
import com.osvin.aichallenge.agent.LlmAgent
import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmClientFactory
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.agent.LlmProviderConfig
import com.osvin.aichallenge.agent.LlmTimeoutException
import com.osvin.aichallenge.agent.LlmUnavailableException
import com.osvin.aichallenge.agent.MemoryForget
import com.osvin.aichallenge.agent.MemoryWrite
import com.osvin.aichallenge.agent.MemoryWriter
import com.osvin.aichallenge.agent.OllamaProbe
import com.osvin.aichallenge.agent.ProfileStore
import com.osvin.aichallenge.agent.TaskWrite
import com.osvin.aichallenge.agent.TaskWriter
import com.osvin.aichallenge.agent.UserProfile
import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import com.osvin.aichallenge.currency.mcp.MAX_CHANGE_HOURS
import com.osvin.aichallenge.currency.mcp.MIN_CHANGE_HOURS
import com.osvin.aichallenge.invariants.JsonFileInvariantStore
import com.osvin.aichallenge.memory.JsonFileMemoryStore
import com.osvin.aichallenge.models.*
import com.osvin.aichallenge.profile.JsonFileProfileStore
import com.osvin.aichallenge.service.ChatSessionStore
import com.osvin.aichallenge.service.ServiceAuth
import com.osvin.aichallenge.service.ServiceConfig
import com.osvin.aichallenge.service.ServiceDeps
import com.osvin.aichallenge.service.ServiceLimits
import com.osvin.aichallenge.service.ServiceMetrics
import com.osvin.aichallenge.service.SqliteChatStore
import com.osvin.aichallenge.service.serviceApi
import com.osvin.aichallenge.tuning.Tuning
import com.osvin.aichallenge.tuning.Tunings
import com.osvin.aichallenge.models.config.AppConfig
import com.osvin.aichallenge.orchestration.McpOrchestrator
import com.osvin.aichallenge.orchestration.McpToolRegistry
import com.osvin.aichallenge.orchestration.ToolServers
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource
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
            // У ответа с вызовом инструмента `content` приходит null: без этого разбор
            // падал бы на пустом тексте, то есть ровно там, где начинается вызов инструмента.
            coerceInputValues = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 300_000
    }
}

/**
 * Настройки провайдера генерации: чей транспорт вызывает агент.
 *
 * Читаются один раз при первом обращении к файлу: переменные окружения важнее строк
 * `.env`, потому что ими провайдера переключают при запуске (`LLM_PROVIDER=local
 * ./gradlew :server:run`), а `.env` — это сохранённая настройка машины, которую разово
 * переопределяют. Ни маршрут чата, ни агент имён переменных не знают: они работают
 * с [LlmProviderConfig], поэтому адрес демона и имя модели не разошлись по коду.
 */
val llmProviderConfig: LlmProviderConfig = LlmProviderConfig.fromEnv(envFile() + System.getenv())

/**
 * Сборка транспорта по настройкам: единственное место, где выбирается провайдер.
 *
 * Общая на все запросы, как хранилища: маршрут чата просит у неё клиента на каждый
 * запрос, а не решает сам, куда идти. Так переключение провайдера не может обойти
 * одну ветку маршрута: и чат, и проверка здоровья смотрят на [LlmClientFactory.provider].
 */
val llmClientFactory = LlmClientFactory(llmProviderConfig, client, AgentLogger.Console)

/**
 * Настройки приватного AI-сервиса (день 30): адрес, ключи, ограничения и потолки.
 *
 * Читаются тем же словарём окружения, что и настройки провайдера, и по той же причине:
 * переменные запуска важнее `.env`, а имена переменных знает только [ServiceConfig] —
 * ни маршрут сервиса, ни клиент их не видят. Значения приходят запуском, а не кодом,
 * потому что один и тот же сервер работает и на хосте, и в контейнере, где порт,
 * адрес Ollama и ключ доступа задаёт окружение.
 */
val serviceConfig: ServiceConfig = ServiceConfig.fromEnv(envFile() + System.getenv())

/**
 * Хранилище сессий сервиса: история диалогов переживает перезапуск процесса.
 *
 * Открывается один раз на процесс, а не на поднятый сервер: тесты поднимают в одной JVM
 * несколько серверов, и закрытие хранилища на остановке одного ломало бы остальные.
 * Незавершённых записей при этом не остаётся — каждая запись идёт отдельной транзакцией,
 * а журнал WAL восстанавливается при следующем открытии.
 */
val serviceStore: ChatSessionStore = SqliteChatStore(Path.of(serviceConfig.chatDbPath))

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
 * Отличие от прочих сторов: здесь значимо и само наличие записи. «Правил не задавали» и
 * «правил нет» (человек убрал все) — разные состояния, поэтому убрать все правила можно,
 * и проверки конфликта после этого не будет.
 *
 * Набор проекта ([Invariant.DEFAULT]) в хранилище кладёт сервер при первом запуске
 * ([seedProjectInvariants]): агент умолчаний себе не подставляет, а проект правила имеет
 * с первого запуска.
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
 * Кладёт в хранилище набор правил проекта, если правил ещё не задавали: один раз,
 * при первом запуске сервера.
 *
 * Правила проекта действуют с первого запуска, а не после того, как человек их заполнит:
 * иначе ассистент отвечал бы без ограничений ровно там, где они нужнее всего, — на первом
 * же запросе. Подставляет их сервер, а не агент: правило — условие, наложенное на работу,
 * и назначать его себе агент не может ([InvariantStore]). Заданные правила при этом не
 * трогаются: если запись уже есть — в том числе пустой список, которым человек убрал все
 * правила, — она остаётся как есть. Поэтому «правил нет» переживает и перезапуск.
 */
private fun seedProjectInvariants() {
    if (invariantStore.get(DEFAULT_PROFILE) == null) {
        invariantStore.put(DEFAULT_PROFILE, Invariant.DEFAULT)
    }
}

/**
 * Точка входа в приложение.
 * Запускает встроенный сервер Netty.
 */
fun main() {
    seedProjectInvariants()
    embeddedServer(
        Netty,
        // Адрес и порт приходят запуском, а не кодом: тот же сервер работает на хосте и
        // в контейнере, где порт публикации задаёт окружение. Умолчания прежние (8080, 0.0.0.0),
        // поэтому запуск без переменных ничего не меняет.
        port = serviceConfig.port,
        host = serviceConfig.host,
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
 * Здоровье локального провайдера: жив ли демон Ollama и загружена ли модель.
 *
 * Проверку ведёт фабрика ([LlmClientFactory.probeOllama]) — тем адресом и тем клиентом,
 * с которыми пойдут запросы чата, поэтому страница не может узнать про одну модель,
 * а работать с другой. Здесь только перевод итога в ответ сервера: «демон не ответил»
 * и «демон ответил, модели нет» — разные причины, и человеку они называют разные действия.
 *
 * @param factory Сборка транспорта: из неё берутся и проверка, и имя модели для ответа.
 */
private suspend fun ApplicationCall.respondOllamaHealth(factory: LlmClientFactory) {
    val model = factory.config.ollamaModel
    when (val probe = factory.probeOllama()) {
        is OllamaProbe.Failed -> respond(
            HttpStatusCode.ServiceUnavailable,
            HealthResponse(
                status = "error",
                deepseek = "local",
                provider = LlmProvider.LOCAL.wire,
                model = model,
                message = probe.reason
            )
        )

        is OllamaProbe.Loaded -> {
            val loaded = probe.models.any { it == model || it.startsWith("$model:") }
            if (loaded) {
                respond(
                    HealthResponse(
                        status = "ok",
                        deepseek = "local",
                        provider = LlmProvider.LOCAL.wire,
                        model = model,
                        message = "Локальная модель $model доступна"
                    )
                )
            } else {
                respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthResponse(
                        status = "error",
                        deepseek = "local",
                        provider = LlmProvider.LOCAL.wire,
                        model = model,
                        message = "Модель $model не загружена в Ollama: выполните `ollama pull $model`"
                    )
                )
            }
        }
    }
}

/**
 * Основной модуль сервера Ktor.
 * Настраивает плагины и маршрутизацию.
 *
 * @param githubTools Инструменты GitHub — общее на все запросы состояние, которое переживает
 *        запрос (агент создаётся на каждый запрос, а сессия инструментов одна). Приходит
 *        параметром со значением по умолчанию, а не берётся из глобальной переменной: так
 *        маршруты проверяются на настоящем MCP-процессе с подставным GitHub, а глобального
 *        изменяемого состояния в сервере не остаётся. Запуск без аргумента создаёт обычные
 *        инструменты, поэтому [main] этим значением не занимается.
 * @param currencyTools Инструменты сервиса курсов — такое же общее состояние, и устроено оно
 *        так же, с одним отличием: сессию открывает первый запрос к модели, а не человек
 *        кнопкой, потому что у сервиса курсов нет ни доступа, который надо настроить, ни
 *        состояния, которое надо показать (см. [CurrencyTools]).
 * @param pipelineTools Инструменты пайплайна — три шага цепочки «курсы → сводка → файл».
 *        Поднимаются тем же ленивым путём, что и курсы: сервер пайплайна не спрашивает
 *        ни ключей, ни разрешений, а цепочку собирает модель по описаниям инструментов
 *        (см. [PipelineTools]).
 * @param reportTools Инструменты сервера отчётов — сборка markdown и запись файла. Четвёртый
 *        сервер в том же общем списке: инструменты всех серверов уезжают модели одним набором,
 *        а маршрут вызова ищет сервер по имени (см. [McpOrchestrator]).
 * @param llmClients Сборка транспорта к модели: провайдер выбирается настройками запуска,
 *        а не запросом клиента. Приходит параметром со значением по умолчанию, как инструменты:
 *        тесты подставляют фабрику с подставным HTTP-движком и проверяют локальный режим,
 *        не поднимая ни Ollama, ни облачный API.
 */
fun Application.module(
    githubTools: GitHubTools = GitHubTools(),
    currencyTools: CurrencyTools = CurrencyTools(),
    pipelineTools: PipelineTools = PipelineTools(),
    reportTools: ReportTools = ReportTools(),
    llmClients: LlmClientFactory = llmClientFactory
) {
    // Единый реестр инструментов всех серверов и оркестратор, который по имени вызова находит
    // сервер: модель видит один список инструментов, а куда отправить вызов — знает реестр.
    val toolRegistry = McpToolRegistry()
    val orchestrator = McpOrchestrator(toolRegistry, onLog = { println("[orchestrator] $it") })

    /**
     * Инструменты всех серверов на этот запрос.
     *
     * Список собирается заново на каждый запрос, а не один раз при запуске: сессия сервера
     * поднимается лениво, и сервер, который в прошлый раз не ответил, в следующий раз может
     * ответить — а сервер, чей процесс умер, наоборот, объявит инструменты, которых уже нет,
     * и вызов по ним вернётся отказом с кодом, а не тишиной.
     */
    suspend fun toolServers(): List<Pair<String, List<AgentTool>>> = listOf(
        ToolServers.GITHUB to githubTools.tools(),
        ToolServers.CURRENCY to currencyTools.tools(),
        ToolServers.PIPELINE to pipelineTools.tools(),
        ToolServers.REPORT to reportTools.tools()
    )

    // Состояние приватного AI-сервиса (день 30) собирается здесь, а не внутри маршрутов:
    // место в очереди генераций и счётчики метрик общие на все запросы, поэтому и жить они
    // должны на уровне приложения — маршрут, создавший их сам, считал бы только свои запросы.
    // Хранилище сессий приходит извне ([serviceStore]) по той же причине: история диалогов
    // переживает и запрос, и перезапуск сервера.
    val serviceLimits = ServiceLimits(
        rateLimitPerMinute = serviceConfig.rateLimitPerMinute,
        maxConcurrent = serviceConfig.maxConcurrentGenerations,
        queueLimit = serviceConfig.queueLimit,
        queueWaitMillis = serviceConfig.queueWaitMillis
    )
    val serviceMetrics = ServiceMetrics(
        config = serviceConfig,
        activeGenerations = serviceLimits::activeGenerations,
        queuedGenerations = serviceLimits::queuedGenerations,
        sessions = serviceStore::counts
    )
    val serviceDeps = ServiceDeps(
        config = serviceConfig,
        store = serviceStore,
        metrics = serviceMetrics,
        limits = serviceLimits,
        // Проверка ключа — общая на запросы поверхность: набор ключей и счётчики отказов
        // не должны собираться заново на каждый запрос.
        auth = ServiceAuth(serviceConfig.apiKeys),
        // Транспорт тот же, что у чата страницы: провайдера выбирает запуск, а не маршрут,
        // поэтому сервис не может отвечать моделью, которой сервер не пользуется.
        llm = llmClients
    )

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

        // Локальная модель не отвечает: демон Ollama не запущен или слушает по другому
        // адресу. Это не ошибка запроса и не 500 сервера: сервер работает, недоступен
        // провайдер, поэтому 503 с причиной — клиент покажет её человеку.
        exception<LlmUnavailableException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(success = false, error = cause.message ?: "Локальная модель недоступна")
            )
        }

        // Провайдер не успел ответить за отведённый срок: 504, а не 500 — запрос был
        // принят и до провайдера дошёл, но ответа не дождались.
        exception<LlmTimeoutException> { call, cause ->
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse(success = false, error = cause.message ?: "Превышен срок ожидания ответа модели")
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
        // Ключ сервиса уезжает своим заголовком: без этой строки браузерная страница
        // (у неё другой источник, чем у сервера, — порт разработки) не пройдёт предварительный
        // запрос, и «ошибка авторизации» окажется ошибкой CORS.
        allowHeader(ServiceAuth.HEADER)
    }

    // Ограничение частоты запросов, одновременных генераций и доступ по ключу живут
    // в поверхности сервиса (`/api/*`, [serviceApi]): там же, где их считают метрики,
    // и до обращения к модели. Общий плагин частоты здесь не регистрируется: он отвечал
    // бы на отказ своим телом, а задание требует единого формата ошибки службы.

    // Остановка сервера гасит и сервер инструментов: он поднят нашим процессом, поэтому
    // его завершение — наша забота, иначе после остановки приложения в системе остался бы
    // висеть чужой процесс, которому больше некому отвечать.
    monitor.subscribe(ApplicationStopped) {
        runBlocking {
            githubTools.close()
            currencyTools.close()
            pipelineTools.close()
            reportTools.close()
        }
    }

    routing {
        // Приватный AI-сервис живёт под своим префиксом (`/api`), страница и агент — под
        // прежним (`/v1`): два способа обращения к одной модели не мешают друг другу, потому
        // что у сервиса свои сессии, свой ключ и свои ограничения, а прежние маршруты
        // не знают ни о ключе, ни о лимитах. Клиент переключается между ними настройкой,
        // а не другой сборкой сервера.
        serviceApi(serviceDeps)

        /**
         * Проверка состояния сервера и провайдера модели.
         *
         * В локальном режиме проверяется демон Ollama, а не облачный API: иначе страница
         * объявляла бы сервер офлайн ровно тогда, когда он работает, — ключа облака в этом
         * режиме нет, и проверка по ключу всегда отвечала бы отказом. Локальная проверка
         * смотрит список моделей демона: он отвечает и тогда, когда модель ещё не загружена,
         * поэтому наличие самой модели проверяется по имени в списке — «демон жив, модели нет»
         * это отдельное состояние, и человеку его надо назвать.
         */
        get("/v1/health") {
            if (llmClients.provider == LlmProvider.LOCAL) {
                call.respondOllamaHealth(llmClients)
                return@get
            }

            val apiKey = deepSeekApiKey()

            if (apiKey.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HealthResponse(
                        status = "error",
                        deepseek = "unknown",
                        provider = LlmProvider.CLOUD.wire,
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
                    call.respond(
                        HealthResponse(status = "ok", deepseek = "online", provider = LlmProvider.CLOUD.wire)
                    )
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthResponse(
                            status = "error",
                            deepseek = "auth_failed",
                            provider = LlmProvider.CLOUD.wire,
                            code = response.status.value
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthResponse(
                        status = "error",
                        deepseek = "offline",
                        provider = LlmProvider.CLOUD.wire,
                        message = e.message
                    )
                )
            }
        }

        /**
         * Состояние подключения к GitHub: подключено ли, есть ли доступ, кто ответил и какие
         * инструменты доступны.
         *
         * Всегда 200: до подключения и после отключения — нормальные состояния, и
         * `connected=false` с пустым списком говорит о них лучше, чем код ошибки. 200 же,
         * когда сервер инструментов вообще не поднимался: снимок просто скажет «не
         * подключено», а причина отказа придёт на саму попытку подключения.
         *
         * Снимок пересказывает отчёт инструмента `github_access`, а не проверяет доступ сам:
         * сервер приложения токена не знает и знать не должен — иначе секрет оказался бы
         * в двух местах и мог бы попасть в лог или в ответ.
         */
        get("/v1/github") {
            call.respond(githubTools.connection())
        }

        /**
         * Подключение к MCP-серверу GitHub: поднимается процесс инструментов, а о доступе
         * сервер узнаёт у него самого.
         *
         * Тела запрос больше не принимает — как и `disconnect`: доступ к GitHub берётся
         * готовым с машины, и человеку нечего называть. Искать его (`GITHUB_TOKEN`, файл,
         * связка ключей macOS, `gh`, `git credential`) будет сервер инструментов по своим
         * источникам, а сюда состояние приходит отчётом его же инструмента `github_access`.
         *
         * 200 с состоянием соединения — и когда подключено было уже (повторное нажатие не
         * ошибка), и когда соединение установлено этим запросом. Отсутствие доступа — тоже
         * 200, с `authorized=false` и `hint`: подключение состоялось, инструменты поднялись
         * и перечислены в снимке, недоступен именно GitHub, и человеку нужна подсказка, что
         * сделать, а не отказ шлюза. 502 остаётся только за тем, что процесс инструментов
         * не поднялся: в снимок тогда нечего положить — ни инструментов, ни ответа о доступе,
         * — и это отказ чужого процесса, а не ошибка запроса, поэтому причину отдаём телом
         * [ErrorResponse] как есть.
         */
        post("/v1/github/connect") {
            when (val result = githubTools.connect()) {
                is GitHubConnect.Connected -> call.respond(result.connection)

                is GitHubConnect.Rejected -> call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(success = false, error = result.reason)
                )
            }
        }

        /**
         * Отключение от GitHub: процесс сервера инструментов гасится.
         *
         * 200 и тогда, когда соединения не было: отключать уже нечего, и ошибка здесь
         * заставила бы интерфейс считать, что соединение осталось, тогда как его нет.
         * Ответ — тот же снимок, что у `GET /v1/github`: клиенту не нужен отдельный запрос
         * после нажатия кнопки.
         */
        post("/v1/github/disconnect") {
            call.respond(githubTools.disconnect())
        }

        /**
         * Прямой вызов инструмента из чата: имя и аргументы человек собрал сам.
         *
         * 200 даже при отказе инструмента: вызов дошёл, и инструмент ответил — причину несёт
         * `failed` с текстом в `result`. Это ответ, а не ошибка транспорта, и показывать его
         * надо как результат вызова. Ошибкой запроса осталось только то, чего инструмент не
         * совершал: 409, если соединения нет (вызывать не у кого), и 404, если имени нет
         * в списке объявленных подключённым сервером.
         */
        post("/v1/github/call") {
            val request = call.receive<GitHubCallRequest>()
            when (val result = githubTools.call(request.name, request.arguments)) {
                is GitHubCall.Answered -> call.respond(result.response)

                is GitHubCall.UnknownTool -> call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(success = false, error = result.reason)
                )

                is GitHubCall.Rejected -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(success = false, error = result.reason)
                )
            }
        }

        /**
         * Текущие курсы для закреплённого чата: то, что чат печатает раз в минуту.
         *
         * Тело ответа — ровно ответ инструмента `get_currency_rates`, без пересказа полями
         * сервера: числа уже посчитаны и описаны на стороне сервиса, и вторая модель тех же
         * данных разошлась бы с первой молча, а видно это стало бы только по кривой ленте
         * в приложении. Проверять здесь нечего: у инструмента нет аргументов, а «курсов ещё
         * нет» — это его ответ с пометкой, а не ошибка запроса.
         *
         * 502 — когда сессия сервиса не поднялась: тогда данных нет вовсе, и причина отказа
         * службы уходит телом [ErrorResponse]. Пустой лентой такой отказ показывать нельзя:
         * «курсы не менялись» и «служба не отвечает» — разные вещи.
         */
        get("/v1/currency") {
            when (val result = currencyTools.call(CurrencyMcpServer.RATES_TOOL)) {
                is CurrencyCall.Answered -> call.respondText(result.json, ContentType.Application.Json)

                is CurrencyCall.Unavailable -> call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(success = false, error = result.reason)
                )
            }
        }

        /**
         * Изменение курсов за окно часов: то, что закреплённый чат показывает при открытии.
         *
         * Окно проверяется здесь, а не только инструментом: неверный запрос должен получить
         * 400, а не 502, — иначе опечатка в клиенте выглядела бы как отказ службы. Границы
         * берутся те же, которыми объявлен аргумент инструмента: два числа в двух местах
         * разошлись бы, и отказ на границе окна читался бы как ошибка сервиса.
         *
         * Тело ответа — ответ инструмента `get_currency_change`: в нём и изменение по каждой
         * валюте, и честное покрытие окна (`hoursCovered`), по которому чат говорит, что
         * сохранено меньше суток.
         */
        get("/v1/currency/change") {
            val asked = call.request.queryParameters["hours"]?.trim()
            val hours = asked?.toIntOrNull()
            if (hours == null || hours !in MIN_CHANGE_HOURS..MAX_CHANGE_HOURS) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        success = false,
                        error = "hours: ожидается целое число часов от $MIN_CHANGE_HOURS " +
                            "до $MAX_CHANGE_HOURS, а не «${asked ?: "не задан"}»"
                    )
                )
                return@get
            }

            val arguments = buildJsonObject { put("hours", JsonPrimitive(hours.toString())) }
            when (val result = currencyTools.call(CurrencyMcpServer.CHANGE_TOOL, arguments)) {
                is CurrencyCall.Answered -> call.respondText(result.json, ContentType.Application.Json)

                is CurrencyCall.Unavailable -> call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(success = false, error = result.reason)
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
         *
         * Провайдер выбирает не запрос, а фабрика ([LlmClientFactory]): в локальном режиме
         * запросы идут в Ollama, и та же фабрика называет модель отчёта — иначе лог и отчёт
         * говорили бы об облачной модели, которой в этом режиме не было. Инструменты MCP
         * локальному провайдеру не предлагаются: задание дня допускает базовое взаимодействие
         * с локальной моделью, а маленькая модель, которой предложили десяток инструментов,
         * тратит бюджет на выбор вызова вместо ответа, — и серверы инструментов ради такого
         * ответа не поднимаются вовсе.
         *
         * Локальный режим ведёт диалог, а не работу над проектом, поэтому профиль разработчика
         * и правила проекта в запрос к нему не уходят, а системным промтом становится первое
         * сообщение диалога — без отдельной инструкции (настройки контекста у чата нет).
         * Это измеренное решение: qwen3:1.7b принимает правила проекта за задание и на вопрос
         * о самой большой планете отвечает «Марс» с таблицей инвариантов, а на арифметическую
         * задачу — куском кода на Kotlin. Проверка правил, кроме того, — отдельный вызов модели
         * на каждый запрос, то есть лишняя минута ожидания и лишние токены ради ответа, который
         * забудется через два сообщения. В облачном режиме и профиль, и инварианты остаются
         * как были: там их держит модель, которая на них не сбивается.
         */
        post("/v1/chat/completions") {
            val request = call.receive<ChatRequest>()
            val local = llmClients.provider == LlmProvider.LOCAL
            val agent = LlmAgent(
                llmClients.create(deepSeekApiKey()),
                summaryStore = summaryStore,
                taskStateStore = taskStateStore,
                workingMemory = workingMemory,
                longTermMemory = longTermMemory,
                // Пустое хранилище вместо файлового — не «отключение инвариантов», а выбор
                // набора правил: у локального диалога правил проекта нет, поэтому и проверять
                // нечего. Хранилище файловое остаётся тем же у облачного режима, и правила
                // из него никуда не деваются.
                invariantStore = if (local) InMemoryInvariantStore() else invariantStore
            )
            val options = request.toAgentOptions(profile = profileStore.profileOrDefault())
                .let { base ->
                    if (local) {
                        base.copy(
                            model = llmClients.modelOverride ?: base.model,
                            tools = emptyList(),
                            // Своей системной инструкции сервер не подставляет: инструкции —
                            // это настройка контекста, а её у чата нет. Системным промтом
                            // становится первое сообщение диалога — правило агента
                            // ([com.osvin.aichallenge.agent.LlmAgent]): оно остаётся в силе
                            // и здесь, и клиентская инструкция (`systemPrompt` запроса),
                            // если человек её написал, по-прежнему важнее.
                            // Профиль — не часть диалога, а правила ответа: локальному диалогу
                            // достаётся пустой, потому что перечисленные в профиле предпочтения
                            // описывают работу над этим проектом.
                            profile = null
                        )
                    } else {
                        // Инструменты — из всех серверов сразу, одним набором: имена в нём несут
                        // признак сервера (`currency.get_currency_rates`, `github.get_repositories`),
                        // и по имени вызова оркестратор находит, кому его передать. Недоступный
                        // сервер просто ничего не добавляет, и ответ без его инструментов не отменяется.
                        base.copy(tools = orchestratedTools(toolRegistry, orchestrator, toolServers()))
                    }
                }
            val result = agent.run(userMessage = request.message, options = options)

            call.respond(
                ChatResponse(
                    success = true,
                    reply = result.reply,
                    usage = result.usage,
                    tokens = result.tokens,
                    // Модель называет сервер, а не клиент: в локальном режиме клиент прислал
                    // имя облачной модели, которой не пользовался, и показывать её в интерфейсе
                    // значило бы врать о том, кто ответил.
                    model = options.model ?: AppConfig.DEFAULT_MODEL,
                    provider = llmClients.provider.wire
                )
            )
        }

        /**
         * Две сборки локальной модели на одном вопросе (день 29).
         *
         * Сравниваются не провайдеры, как на странице дня 28, а две сборки **одной** локальной
         * модели: та, что отвечает сейчас, и та, что настроена под кейс, — окно контекста,
         * инструкция и бюджет ответа. Условия держит профиль
         * ([com.osvin.aichallenge.tuning.Tunings]), и он же условие прогона бенчмарка: заведи
         * панель свои настройки, её числа относились бы к другой сборке, чем числа отчёта.
         *
         * Ответы идут одним запросом и одновременно: окна и задуманы как «пишут в одно время».
         * Последовательное ожидание показывало бы в одном окне готовый ответ, пока второе ещё
         * считает, и разница во времени выглядела бы разницей моделей.
         *
         * Отказ одной сборки не отменяет ответ другой: причина остаётся в её окне
         * ([PaneAnswer.error]). Ловится `Throwable`, а не только `Exception`: отсутствующий класс
         * и переполнение при загрузке модели приходят `Error`, и промолчать о них значило бы
         * отдать пустое окно вместо причины — то же правило, что у прогона дня 28.
         *
         * Режим сравнения работает только с локальной моделью: оптимизируют её, а в облачном
         * режиме обе панели отвечали бы одной и той же удалённой моделью с одними настройками,
         * и сравнивать было бы нечего.
         */
        get("/v1/chat/dual") {
            call.respond(dualInfo(llmClients.config))
        }

        post("/v1/chat/dual") {
            if (llmClients.provider != LlmProvider.LOCAL) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(success = false, error = DUAL_LOCAL_ONLY))
                return@post
            }
            val request = call.receive<DualChatRequest>()
            // Обе панели собираются профилями чата, а не ступенями прогона: температура 0.7
            // у обеих сборок и выключенное рассуждение у «как сейчас» — настройки сравнения,
            // и живут они в одном месте ([Tunings.chatBaselineFrom], [Tunings.chatOptimizedFrom]),
            // а не строкой маршрута. Настройки запуска (модель, адрес демона, окно) при этом
            // приходят с сервера: окна обязаны отвечать той моделью, что запущена, — иначе
            // сравнение говорило бы о модели, которой сервер не пользуется.
            val baselineProfile = Tunings.chatBaselineFrom(llmClients.config)
            // Бюджет левой панели называет клиент: он тот же, что у его обычного чата, и сравнение
            // идёт с той настройкой, которой человек пользуется
            val baseline = baselineProfile.copy(
                maxTokens = request.maxTokens ?: baselineProfile.maxTokens
            )
            val optimized = Tunings.chatOptimizedFrom(llmClients.config)

            val answers = coroutineScope {
                val current = async {
                    paneAnswer(
                        pane = Pane.BASELINE,
                        title = BASELINE_TITLE,
                        tuning = baseline,
                        history = request.baselineHistory,
                        request = request
                    )
                }
                val tuned = async {
                    paneAnswer(
                        pane = Pane.OPTIMIZED,
                        title = OPTIMIZED_TITLE,
                        tuning = optimized,
                        history = request.optimizedHistory,
                        request = request
                    )
                }
                listOf(current.await(), tuned.await())
            }

            call.respond(DualChatResponse(answers = answers))
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
         * берутся из него, и своей копии таблицы видов на клиенте нет. Отдаётся то, что
         * лежит в сторе, без подстановок: набор правил проекта сервер положил туда при
         * первом запуске ([seedProjectInvariants]), поэтому пустой снимок означает ровно
         * одно — человек убрал все правила, и проверки конфликта в запросах больше нет.
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

/**
 * Инструменты всех серверов на один запрос человека: реестр пополняется, вызовы маршрутизируются.
 *
 * Реестр пополняется перед каждым запросом, а не один раз при запуске: сессии серверов поднимаются
 * лениво, и сервер, поднявшийся позже, иначе не попал бы в список до перезапуска приложения.
 * Повторная запись того же инструмента пропускается ([McpToolRegistry.register]), поэтому копии
 * в реестре не накапливаются.
 *
 * Заодно начинается новый номер запроса: строки протокола оркестратора нумеруются внутри него,
 * и по ним видно весь workflow одной просьбы — включая то, какой сервер выбран для каждого вызова
 * (`requestId=3 iteration=4 tool=github.getRecentCommits server=github duration=340ms success=true`).
 *
 * Недоступный сервер даёт пустой список инструментов и строку в логе, а не отказ ответа: ответ
 * человека не должен отменяться из-за того, что один из серверов инструментов не поднялся.
 */
internal suspend fun orchestratedTools(
    registry: McpToolRegistry,
    orchestrator: McpOrchestrator,
    servers: List<Pair<String, List<AgentTool>>>
): List<AgentTool> {
    val requestId = orchestrator.newRequest()
    var declaredTools = 0
    servers.forEach { (serverId, declared) ->
        val registration = registry.register(serverId, declared)
        declaredTools += declared.size
        val skipped = if (registration.skipped > 0) ", повторных пропущено ${registration.skipped}" else ""
        if (declared.isEmpty()) {
            println("[orchestrator] сервер $serverId: инструментов нет — сервер не ответил")
        } else {
            println("[orchestrator] сервер $serverId: инструментов ${registration.added}$skipped")
        }
    }
    println("[orchestrator] запрос $requestId: инструментов у модели $declaredTools")
    return orchestrator.tools()
}

/** Заголовок окна «как сейчас»: имя сборки, а не имя профиля прогона. */
private const val BASELINE_TITLE = "неоптимизированная"

/** Заголовок оптимизированного окна. */
private const val OPTIMIZED_TITLE = "оптимизированная"

/** Причина отказа сравнения в облачном режиме: панели сравнивают сборки локальной модели. */
private const val DUAL_LOCAL_ONLY =
    "Сравнение сборок отвечает локальной моделью: запустите сервер с LLM_PROVIDER=local"

/**
 * Описание сборок сравнения: чем панели отличаются, без первого вопроса.
 *
 * Настройки панелей — настоящие, те же, с которыми панели отвечают ([paneInfo] берёт их
 * из того же профиля, что и запрос), иначе подпись окна обещала бы одно, а отвечала модель
 * с другими настройками.
 */
private fun dualInfo(base: LlmProviderConfig): DualChatInfo = DualChatInfo(
    panes = listOf(
        paneInfo(
            pane = Pane.BASELINE,
            title = BASELINE_TITLE,
            tuning = Tunings.chatBaselineFrom(base)
        ),
        paneInfo(
            pane = Pane.OPTIMIZED,
            title = OPTIMIZED_TITLE,
            tuning = Tunings.chatOptimizedFrom(base)
        )
    )
)

/**
 * Панель из профиля: имя модели, провайдер и настройки.
 *
 * Модель и провайдер берутся из настроек транспорта, а не из профиля: модель сравнения задаёт
 * запуск сервера (`OLLAMA_MODEL`), и панель обязана назвать ту, которой ответит.
 */
private fun paneInfo(pane: String, title: String, tuning: Tuning): PaneInfo = PaneInfo(
    pane = pane,
    title = title,
    model = tuning.config.modelOverride ?: AppConfig.DEFAULT_MODEL,
    provider = tuning.config.provider.wire,
    temperature = tuning.config.ollamaTemperature,
    think = tuning.config.ollamaThink,
    numCtx = tuning.config.ollamaNumCtx,
    maxTokens = tuning.maxTokens,
    promptTemplate = tuning.systemPrompt != null
)

/**
 * Ответ одной сборки на вопрос сравнения.
 *
 * Хранилища — свои и в памяти: сравнение задаёт один вопрос дважды, и обе сборки, дописывающие
 * память профиля, затирали бы её друг другом, — а измеряют здесь ответы модели, а не память.
 * История приходит от клиента и у каждой панели своя: вторая сборка не должна читать ответы
 * первой, иначе сравнение превратилось бы в подражание соседке.
 *
 * @param pane Метка панели ([Pane]).
 * @param title Заголовок окна.
 * @param tuning Профиль сборки: настройки транспорта, инструкция и бюджет.
 * @param history История этого окна: прошлые вопросы и его же ответы.
 * @param request Запрос сравнения: вопрос, стоп-слова и своя инструкция человека.
 *
 * Время ответа ([PaneAnswer.elapsedMs]) меряется здесь, а не на клиенте: сборки отличаются
 * окном, бюджетом и инструкцией, и их цена — время — обязана быть измерена у того, кто
 * её и потратил. Часы клиента прибавили бы к ней сеть и разошлись бы с числами отчёта
 * прогона, который считает то же время у того же вызова модели.
 */
private suspend fun paneAnswer(
    pane: String,
    title: String,
    tuning: Tuning,
    history: List<ChatMessage>?,
    request: DualChatRequest
): PaneAnswer {
    val model = tuning.config.modelOverride ?: AppConfig.DEFAULT_MODEL
    val agent = LlmAgent(
        llm = LlmClientFactory(tuning.config, client).create(null),
        summaryStore = InMemorySummaryStore(),
        taskStateStore = InMemoryTaskStateStore(),
        workingMemory = InMemoryMemoryStore(),
        longTermMemory = InMemoryMemoryStore(),
        invariantStore = InMemoryInvariantStore()
    )
    val started = TimeSource.Monotonic.markNow()
    return try {
        val result = agent.run(
            userMessage = request.message,
            options = AgentOptions(
                model = model,
                maxTokens = tuning.maxTokens,
                stop = request.stop,
                // Температуру задаёт транспорт профиля: в запросе её несёт клиент, но локальный
                // транспорт берёт своё значение — оно и есть настройка сборки
                temperature = tuning.config.ollamaTemperature,
                // Инструкция человека важнее шаблона сборки: она — часть вопроса, а шаблон —
                // часть сборки, и подменять сказанное человеком сборка не вправе
                systemPrompt = request.systemPrompt ?: tuning.systemPrompt,
                history = history.orEmpty(),
                strategy = ContextStrategy.FULL,
                // Инструменты локальному режиму не предлагаются — по той же причине, что и в чате:
                // маленькая модель тратит бюджет на выбор вызова вместо ответа
                tools = emptyList()
            )
        )
        PaneAnswer(
            pane = pane,
            title = title,
            reply = result.reply,
            model = model,
            provider = tuning.config.provider.wire,
            elapsedMs = started.elapsedNow().inWholeMilliseconds,
            usage = result.usage,
            tokens = result.tokens
        )
    } catch (cause: Throwable) {
        // Отмену не подавляем: она значит, что запрос или сервер закрылись, и «ответа нет»
        // в окне было бы ложью о причине
        if (cause is CancellationException) throw cause
        PaneAnswer(
            pane = pane,
            title = title,
            model = model,
            provider = tuning.config.provider.wire,
            error = cause.message ?: cause.toString()
        )
    }
}
