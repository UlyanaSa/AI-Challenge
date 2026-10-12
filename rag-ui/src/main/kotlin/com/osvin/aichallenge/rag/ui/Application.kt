package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.indexing.ollama.EmbeddingProviders
import com.osvin.aichallenge.rag.Api
import com.osvin.aichallenge.rag.Controls
import com.osvin.aichallenge.rag.chat.ChatScenarios
import com.osvin.aichallenge.models.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Что страница показывает вместо ответа, если запрос не разобрался. */
@Serializable
data class ErrorDto(val message: String)

/**
 * Страница дня 23: три режима ответа и разбор этапов конвейера на десяти контрольных вопросах,
 * и день 24 поверх неё: grounded-ответ с источниками, цитатами, их проверкой и отказом. День 25
 * добавляет к этому мини-чат: разговор по книге с памятью задачи, источниками и запуском сценариев
 * ([RagSession.chatState] и три мутирующих маршрута) — поверх того же поиска и той же модели.
 *
 * Сервер локальный и слушает только `127.0.0.1`: это окно в прогон на этой машине, а не сервис,
 * которому нужен внешний доступ. Страница отдаётся статикой из ресурсов (`web/`), данные — по
 * маршрутам: что известно до нажатия, состояние прогона, запуск прогона и сборка индекса, а для
 * разговора — его состояние, реплика, очистка и запуск сценария. Больше сервер ничего не умеет,
 * и весь он умещается в этот файл: логика — в `:rag` и в [RagSession], и ни день 24, ни день 25
 * не завели второго набора ни маршрутов, ни настроек — они едут теми же полями состояния.
 *
 * Порт (`RAG_UI_PORT`, по умолчанию 8098) и рабочий каталог (`RAG_UI_DIR`) задаются окружением:
 * страница может понадобиться рядом с прогоном в консоли, а каталог страницы намеренно отдельный —
 * иначе её отчёты переписали бы отчёты прогона.
 *
 * Провайдер векторов выбирается до старта сервера, а не в первом запросе: это или подключение
 * к демону Ollama (проба вектора занимает секунды, пока модель грузится в память), или откат
 * на хеширование с причиной — и то и другое должно быть видно в консоли запуска, а не в браузере
 * после нажатия кнопки.
 */
fun main() {
    val port = System.getenv("RAG_UI_PORT")?.toIntOrNull() ?: RagSession.DEFAULT_PORT
    val workDir = Files.createDirectories(
        Paths.get(System.getenv("RAG_UI_DIR") ?: RagSession.DEFAULT_DIR)
    )
    val embedding = runBlocking { EmbeddingProviders.fromEnv() }
    val apiKey = Api.key()
    val provider = Api.provider()
    val model = Api.modelName(AppConfig.DEFAULT_MODEL)
    println("Векторы: ${embedding.name} (${embedding.note})")
    println(
        when {
            provider == LlmProvider.LOCAL ->
                "Генерация: локальная модель $model (демон Ollama) — ключ облака странице не нужен."

            apiKey == null ->
                "Ключ DEEPSEEK_API_KEY не найден: страница покажет поиск, но ответов модели не будет."

            else -> "Ключ DEEPSEEK_API_KEY найден: страница отвечает моделью $model."
        }
    )
    println("Страница дней 23–25: http://127.0.0.1:$port (рабочий каталог: ${workDir.toAbsolutePath()})")
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(workDir, embedding, apiKey, provider = provider, model = model)
    }.start(wait = true)
}

/** Маршруты страницы; вынесены из `main`, чтобы тот остался описанием запуска. */
fun Application.module(
    workDir: Path,
    embedding: Embedding,
    apiKey: String?,
    /**
     * Провайдер генерации: локальный отвечает демоном Ollama и облачного ключа не требует
     * (`LLM_PROVIDER=local`). Умолчание — облачный, потому что тесты страницы подставляют
     * клиента сами и о провайдере машины не знают.
     */
    provider: LlmProvider = LlmProvider.CLOUD,
    /**
     * Нарезка корпуса: по умолчанию структурная — та же, что у прогона в консоли, иначе числа
     * страницы и отчёта относились бы к разным индексам.
     */
    strategy: ChunkingStrategyType = ChunkingStrategyType.STRUCTURAL,
    model: String = AppConfig.DEFAULT_MODEL,
    /**
     * Имя облачной модели для сравнения — **не** то же, что [model].
     *
     * [model] называет модель, которой отвечает прогон страницы, и в локальном режиме это имя
     * локальное (`Api.modelName` подставляет `qwen3:1.7b`). Облачной руке сравнения нужен
     * настоящий облачный идентификатор: отправить ей локальное имя значит получить от API отказ
     * «такой модели нет» и пустые колонки вместо сравнения.
     */
    cloudModel: String = AppConfig.DEFAULT_MODEL
) {
    val session = RagSession(
        workDir = workDir,
        scope = this,
        embedding = embedding,
        strategy = strategy,
        model = model,
        apiKey = apiKey,
        provider = provider
    )
    // Сравнение моделей живёт рядом с прогоном и делит с ним индекс: и локальная, и облачная рука
    // отвечают по тому же файлу поиска, что и прогон десяти вопросов. Своей пересборки у сравнения
    // поэтому нет — сломать общий индекс оно не может.
    val compare = CompareSession(
        workDir = workDir,
        scope = this,
        embedding = embedding,
        strategy = strategy,
        cloudModel = cloudModel,
        localModel = Api.settingsFor(LlmProvider.LOCAL).ollamaModel,
        apiKey = apiKey
    )

    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            explicitNulls = false
        })
    }
    // Ошибку страница показывает текстом, а не пустым ответом: причину нужно видеть там же,
    // где нажимали кнопку.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto("Ошибка на сервере: ${cause.message ?: cause::class.simpleName}")
            )
        }
    }

    /**
     * Причина, по которой реплику нельзя принять, или `null`.
     *
     * Общей проверкой на три мутирующих маршрута чата, потому что причина у отказа одна и та же,
     * и повторять её в каждом маршруте значило бы завести три места, где она формулируется.
     * Недоступность и занятость — разные ответы: первая не изменится от повтора запроса, вторая
     * пройдёт, когда кончится текущий ход, — и человеку нужно видеть, чего именно ждать.
     */
    fun chatBlocked(): ErrorDto? {
        val state = session.chatState()
        return when {
            state.note != null -> ErrorDto(state.note)
            state.busy -> ErrorDto(RagSession.CHAT_BUSY_MESSAGE)
            else -> null
        }
    }

    routing {
        staticResources("/", "web")

        /** Что страница знает до нажатия: база, индекс, провайдер и десять вопросов с эталонами. */
        get("/api/setup") { call.respond(session.setup()) }

        /** Состояние прогона: страница опрашивает его, пока идёт работа. */
        get("/api/state") { call.respond(session.state()) }

        /**
         * Запуск прогона: `ids` — номера вопросов через запятую (пусто — все десять) и настройки
         * этапов (`topK`, `retrievalTopK`, `finalTopK`, `threshold`, `rewrite`, `rerank`).
         *
         * Настройки разбираются здесь, а проверяются в [RagSession.start]: страница — второй вход
         * в тот же прогон, и неизвестный вариант этапа должен отвергнуть запуск с причиной там же,
         * где и в консоли. Отвергнутый запуск ничего не меняет: состояние остаётся тем же, а страница
         * показывает причину рядом с кнопкой. Принятый — заменяет идущий прогон и стирает его данные:
         * страница про это предупреждает подписью у кнопок.
         */
        post("/api/run") {
            val ids = call.request.queryParameters["ids"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val defaults = RunSettings.DEFAULT
            val parameters = call.request.queryParameters
            val settings = RunSettings(
                baselineTopK = parameters["topK"]?.toIntOrNull() ?: defaults.baselineTopK,
                retrievalTopK = parameters["retrievalTopK"]?.toIntOrNull() ?: defaults.retrievalTopK,
                finalTopK = parameters["finalTopK"]?.toIntOrNull() ?: defaults.finalTopK,
                threshold = parameters["threshold"]?.replace(',', '.')?.toDoubleOrNull() ?: defaults.threshold,
                rewrite = parameters["rewrite"]?.trim()?.lowercase() ?: defaults.rewrite,
                rerank = parameters["rerank"]?.trim()?.lowercase() ?: defaults.rerank
            )
            val selected = if (ids.isEmpty()) Controls.questions.map { it.id } else ids
            when (val outcome = session.start(selected, settings)) {
                is StartOutcome.Accepted -> call.respond(session.state())
                is StartOutcome.Rejected ->
                    call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /**
         * Состояние сравнения моделей: качество, скорость и стабильность каждой.
         *
         * Отдаётся целиком, как состояние прогона: страница опрашивает один ответ и рисует из него
         * обе модели. Собрать сравнение из двух запросов значило бы показать его из разных моментов
         * времени — у локальной модели уже посчитан третий повтор, у облачной ещё первый.
         */
        get("/api/compare") { call.respond(compare.state()) }

        /**
         * Запуск сравнения: `ids` — вопросы, `repeats` — сколько раз прогнать каждую модель.
         *
         * Настройки этапов разбираются так же, как у прогона, и проверяются в [CompareSession.start]:
         * страница — второй вход в тот же конвейер, и неизвестный вариант этапа должен отвергнуть
         * запуск там же, где и в консоли. Индекс не собирается: сравнение идёт по готовому файлу,
         * и причина отказа видна странице текстом, а не пустыми таблицами.
         */
        post("/api/compare") {
            val ids = call.request.queryParameters["ids"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val parameters = call.request.queryParameters
            val defaults = RunSettings.DEFAULT
            val settings = RunSettings(
                baselineTopK = parameters["topK"]?.toIntOrNull() ?: defaults.baselineTopK,
                retrievalTopK = parameters["retrievalTopK"]?.toIntOrNull() ?: defaults.retrievalTopK,
                finalTopK = parameters["finalTopK"]?.toIntOrNull() ?: defaults.finalTopK,
                threshold = parameters["threshold"]?.replace(',', '.')?.toDoubleOrNull() ?: defaults.threshold,
                rewrite = parameters["rewrite"]?.trim()?.lowercase() ?: defaults.rewrite,
                rerank = parameters["rerank"]?.trim()?.lowercase() ?: defaults.rerank
            )
            val selected = if (ids.isEmpty()) Controls.questions.map { it.id } else ids
            val repeats = parameters["repeats"]?.toIntOrNull() ?: CompareSession.DEFAULT_REPEATS
            when (val outcome = compare.start(selected, repeats, settings)) {
                is StartOutcome.Accepted -> call.respond(compare.state())
                is StartOutcome.Rejected ->
                    call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /** Остановка сравнения: посчитанное остаётся, работа прекращается. */
        post("/api/compare/stop") {
            if (compare.stop()) {
                call.respond(compare.state())
            } else {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("Останавливать нечего: сравнение не идёт"))
            }
        }

        /** Пересборка индекса: без модели, поэтому доступна и без ключа. */
        post("/api/index") {
            when (val outcome = session.rebuildIndex()) {
                is StartOutcome.Accepted -> call.respond(session.state())
                is StartOutcome.Rejected ->
                    call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /**
         * Остановка работы: прогона или пересборки индекса.
         *
         * Состояние отдаётся ответом намеренно: страница не ждёт следующего опроса раз в секунду,
         * чтобы показать «остановлен» — иначе между нажатием и подписью на экране была бы заметная
         * пауза, и человек нажал бы второй раз. Ответ — то же состояние, что у `/api/state`: третий
         * формат ответа пришлось бы разбирать на странице вторым кодом.
         */
        post("/api/stop") {
            if (session.stop()) {
                call.respond(session.state())
            } else {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("Останавливать нечего: работа не идёт"))
            }
        }

        /** Состояние мини-чата дня 25: лента ходов, память задачи, сводка и итог сценария. */
        get("/api/chat") { call.respond(session.chatState()) }

        /**
         * Реплика человека: тело `{text}`.
         *
         * Пустая реплика отвергается до всякой работы: ход разговора без вопроса — не ход. Ответ —
         * то же состояние, что у `/api/chat`: страница не ждёт следующего опроса, чтобы показать
         * новый ход, — иначе между нажатием и ответом на экране была бы заметная пауза.
         */
        post("/api/chat/message") {
            val text = runCatching { call.receive<ChatSendDto>() }.getOrNull()?.text?.trim().orEmpty()
            if (text.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto(RagSession.CHAT_EMPTY_MESSAGE))
                return@post
            }
            chatBlocked()?.let { blocked ->
                call.respond(HttpStatusCode.BadRequest, blocked)
                return@post
            }
            call.respond(session.chatSend(text))
        }

        /**
         * Очистка разговора и памяти задачи. Ответ — состояние, как у остальных мутирующих маршрутов:
         * страница рисует пустую ленту сразу по ответу, а не по следующему опросу.
         */
        post("/api/chat/reset") {
            chatBlocked()?.let { blocked ->
                call.respond(HttpStatusCode.BadRequest, blocked)
                return@post
            }
            call.respond(session.chatReset())
        }

        /**
         * Запуск сценария дня 25: тело `{name}`.
         *
         * Неизвестное имя отвергается с перечнем имён из [ChatScenarios.names] — страница печатает
         * его человеку, и второй список сценариев в разметке разошёлся бы с движком. Запуск не ждёт
         * конца сценария: состояние отдаётся сразу, прогресс виден через `/api/chat`.
         */
        post("/api/chat/scenario") {
            val name = runCatching { call.receive<ChatScenarioRequestDto>() }.getOrNull()?.name?.trim().orEmpty()
            chatBlocked()?.let { blocked ->
                call.respond(HttpStatusCode.BadRequest, blocked)
                return@post
            }
            if (ChatScenarios.byName(name) == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("Сценарий «$name» неизвестен: доступны ${ChatScenarios.names.joinToString(", ")}")
                )
                return@post
            }
            call.respond(session.chatScenario(name))
        }
    }
}
