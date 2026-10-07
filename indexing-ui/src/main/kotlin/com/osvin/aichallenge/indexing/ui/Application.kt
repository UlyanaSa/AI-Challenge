package com.osvin.aichallenge.indexing.ui

import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.indexing.ollama.EmbeddingProviders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * Страница дня 21: интерфейс к конвейеру индексации.
 *
 * Сервер локальный и слушает только `127.0.0.1`: это окно в прогон на этой машине, а не сервис,
 * которому нужен внешний доступ. Страница отдаётся статикой из ресурсов (`web/`), данные — по
 * пяти маршрутам: корпус с настройками по умолчанию, запуск индексации с настройками, её
 * состояние и поиск. Больше сервер ничего не умеет, и весь он умещается в этот файл: логика —
 * в конвейере (`:indexing`) и в [IndexingSession].
 *
 * Порт задаётся переменной окружения `INDEXING_UI_PORT` (по умолчанию 8099), каталог индексов —
 * `INDEXING_UI_INDEX_DIR`: и то и другое нужно только для запуска рядом с чем-то ещё на той же
 * машине.
 *
 * Чем считать векторы, решается здесь же и один раз на запуск: `INDEXING_EMBEDDING` (по умолчанию
 * `auto`) выбирает модель через локальный демон Ollama или хешированные признаки, а
 * `INDEXING_OLLAMA_URL` и `INDEXING_OLLAMA_MODEL` называют демон и модель. Выбор печатается в
 * консоль и уходит на страницу: без него числа близости не читаются.
 */
fun main() {
    val port = System.getenv("INDEXING_UI_PORT")?.toIntOrNull() ?: IndexingSession.DEFAULT_PORT
    val workDir = IndexingSession.workDirectory(
        Paths.get(System.getenv("INDEXING_UI_INDEX_DIR") ?: "build/index-ui")
    )
    // Провайдер выбирается до старта сервера, а не в первом запросе: выбор — это или подключение
    // к демону (проба вектора занимает секунды, пока модель грузится в память), или откат на
    // хеширование с причиной. То и другое должно быть видно в консоли запуска, а не в браузере
    // после нажатия кнопки.
    val embedding = runBlocking { EmbeddingProviders.fromEnv() }
    println("Эмбеддинг: ${embedding.name}, размерность ${embedding.provider.dimension} — ${embedding.note}")
    println("Интерфейс дня 21: http://127.0.0.1:$port (рабочий каталог: ${workDir.toAbsolutePath()})")
    embeddedServer(Netty, port = port, host = "127.0.0.1") { module(workDir, embedding) }.start(wait = true)
}

/** Маршруты страницы; вынесены из `main`, чтобы тот остался описанием запуска. */
fun Application.module(workDir: Path, embedding: Embedding) {
    val session = IndexingSession(workDir, this, embedding = embedding)

    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            explicitNulls = false
        })
    }
    // Ошибку конвейера страница показывает текстом, а не пустым ответом: причину нужно видеть
    // там же, где нажимали кнопку.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto("Ошибка на сервере: ${cause.message ?: cause::class.simpleName}")
            )
        }
    }

    routing {
        staticResources("/", "web")

        /** Корпус прогона: файлы с их размерами в символах и токенах, настройки и эталонный вопрос. */
        get("/api/setup") { call.respond(session.setup()) }

        /** Состояние индексации: страница опрашивает его, пока идёт работа. */
        get("/api/status") { call.respond(session.status()) }

        /**
         * Запуск индексации с настройками из запроса: `chunk`, `overlap`, `maxChunk`.
         *
         * Настройки не разбираются здесь на правила: их проверяют конструкторы чанкеров, и сюда
         * возвращается их же сообщение. Отвергнутый запуск ничего не меняет — индекс остаётся тем,
         * что был собран, а страница показывает ошибку рядом с полями.
         */
        post("/api/index") {
            val settings = call.chunkSettings()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto(BAD_SETTINGS))
            when (val outcome = session.start(settings)) {
                is StartOutcome.Accepted -> call.respond(outcome.status)
                is StartOutcome.Rejected -> call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /**
         * Документ по ссылке: `url` — адрес PDF, остальные параметры — те же настройки нарезки.
         *
         * Ответ несёт весь корпус после добавления, а не одну строку: по нему страница перерисовывает
         * список файлов. Скачанный документ тут же уходит в индексацию с этими же настройками —
         * «загрузил» и «проиндексировал» для человека одно действие, и разделять их значило бы
         * оставлять документ в корпусе, но не в поиске.
         */
        post("/api/documents") {
            val url = call.request.queryParameters["url"]?.trim().orEmpty()
            if (url.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("Не задана ссылка: параметр url пуст"))
                return@post
            }
            when (val outcome = session.addDocumentByUrl(url)) {
                is AddOutcome.Added -> call.respond(
                    AddedDocumentDto(outcome.file, outcome.documents, outcome.status)
                )
                is AddOutcome.Rejected -> call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /**
         * Локальный PDF: файл приходит телом запроса (`multipart/form-data`, поле `file`), настройки —
         * теми же параметрами, что и у индексации.
         *
         * Отдельный маршрут от ссылки, а не общий с `url`: у файла из формы нет адреса, и выдавать
         * его за ссылку значило бы притворяться, что документ откуда-то скачан. Дальше путь общий —
         * тот же разбор по страницам и тот же перезапуск индексации.
         */
        post("/api/documents/upload") {
            if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("Ожидается форма с файлом: multipart/form-data с полем file")
                )
                return@post
            }
            // Имя файла можно передать параметром: не-ASCII имя в заголовке части не переживает
            // разбор заголовков (браузер шлёт байты UTF-8 там, где разрешён только ASCII), поэтому
            // страница называет файл ещё и здесь — в параметре запроса кодировка сохраняется.
            val providedName = call.request.queryParameters["name"]?.trim()?.takeIf { it.isNotEmpty() }

            var name: String? = null
            var bytes: ByteArray? = null
            try {
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem && bytes == null) {
                        name = part.originalFileName?.takeIf { it.isNotBlank() }
                        bytes = part.provider().readRemaining().readByteArray()
                    }
                    part.dispose()
                }
            } catch (cause: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("Форма не разбирается: ${cause.message ?: cause::class.simpleName}")
                )
                return@post
            }

            val file = bytes
            if (file == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("В запросе нет файла: прикрепите PDF"))
                return@post
            }
            val fileName = providedName ?: name ?: DEFAULT_UPLOAD_NAME
            when (val outcome = session.addDocumentFromFile(fileName, file)) {
                is AddOutcome.Added -> call.respond(
                    AddedDocumentDto(outcome.file, outcome.documents, outcome.status)
                )
                is AddOutcome.Rejected -> call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
            }
        }

        /** Поиск по обоим индексам: `question` и `topK` (по умолчанию [IndexingSession.DEFAULT_TOP_K]). */
        get("/api/search") {
            val topK = call.request.queryParameters["topK"]?.toIntOrNull() ?: IndexingSession.DEFAULT_TOP_K
            if (topK < 1 || topK > IndexingSession.MAX_TOP_K) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("topK должен быть от 1 до ${IndexingSession.MAX_TOP_K}, получено $topK")
                )
                return@get
            }
            val result = session.search(topK)
            if (result == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorDto("Индекс ещё не построен: загрузите PDF и запустите индексацию")
                )
            } else {
                call.respond(result)
            }
        }
    }
}

/** Настройки нарезки из параметров запроса; `null` — параметр передан, но не целым числом. */
private fun ApplicationCall.chunkSettings(): ChunkSettingsDto? {
    val defaults = ChunkSettingsDto()
    return ChunkSettingsDto(
        chunkSize = intParam("chunk", defaults.chunkSize) ?: return null,
        overlap = intParam("overlap", defaults.overlap) ?: return null,
        maxChunkSize = intParam("maxChunk", defaults.maxChunkSize) ?: return null
    )
}

/** Имя документа, если в форме его не назвали. */
private const val DEFAULT_UPLOAD_NAME = "document.pdf"

/** Что отвечает сервер, если настройки нарезки пришли не числами. */
private const val BAD_SETTINGS = "Параметры chunk, overlap и maxChunk должны быть целыми числами"

/** Целое из параметра запроса; `null` — параметр передан, но числом не является. */
private fun ApplicationCall.intParam(name: String, fallback: Int): Int? {
    val raw = request.queryParameters[name] ?: return fallback
    return raw.toIntOrNull()
}
