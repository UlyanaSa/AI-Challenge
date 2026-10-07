package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.indexing.ollama.EmbeddingProviders
import com.osvin.aichallenge.rag.Api
import com.osvin.aichallenge.rag.Controls
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
 * Страница дня 22: два агента на десяти контрольных вопросах.
 *
 * Сервер локальный и слушает только `127.0.0.1`: это окно в прогон на этой машине, а не сервис,
 * которому нужен внешний доступ. Страница отдаётся статикой из ресурсов (`web/`), данные — по
 * четырём маршрутам: что известно до нажатия, состояние прогона, запуск прогона и сборка индекса.
 * Больше сервер ничего не умеет, и весь он умещается в этот файл: логика — в `:rag` и в [RagSession].
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
    println("Векторы: ${embedding.name} (${embedding.note})")
    println(
        if (apiKey == null) {
            "Ключ DEEPSEEK_API_KEY не найден: страница покажет поиск, но ответов модели не будет."
        } else {
            "Ключ DEEPSEEK_API_KEY найден: страница отвечает живой моделью."
        }
    )
    println("Страница дня 22: http://127.0.0.1:$port (рабочий каталог: ${workDir.toAbsolutePath()})")
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(workDir, embedding, apiKey)
    }.start(wait = true)
}

/** Маршруты страницы; вынесены из `main`, чтобы тот остался описанием запуска. */
fun Application.module(
    workDir: Path,
    embedding: Embedding,
    apiKey: String?,
    /**
     * Нарезка корпуса: по умолчанию структурная — та же, что у прогона в консоли, иначе числа
     * страницы и отчёта относились бы к разным индексам.
     */
    strategy: ChunkingStrategyType = ChunkingStrategyType.STRUCTURAL,
    model: String = AppConfig.DEFAULT_MODEL
) {
    val session = RagSession(
        workDir = workDir,
        scope = this,
        embedding = embedding,
        strategy = strategy,
        model = model,
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

    routing {
        staticResources("/", "web")

        /** Что страница знает до нажатия: база, индекс, провайдер и десять вопросов с эталонами. */
        get("/api/setup") { call.respond(session.setup()) }

        /** Состояние прогона: страница опрашивает его, пока идёт работа. */
        get("/api/state") { call.respond(session.state()) }

        /**
         * Запуск прогона: `ids` — номера вопросов через запятую (пусто — все десять), `topK`.
         *
         * Отвергнутый запуск ничего не меняет: состояние остаётся тем же, а страница показывает
         * причину рядом с кнопкой.
         */
        post("/api/run") {
            val ids = call.request.queryParameters["ids"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val topK = call.request.queryParameters["topK"]?.toIntOrNull() ?: RagSession.DEFAULT_TOP_K
            val selected = if (ids.isEmpty()) Controls.questions.map { it.id } else ids
            when (val outcome = session.start(selected, topK)) {
                is StartOutcome.Accepted -> call.respond(session.state())
                is StartOutcome.Rejected ->
                    call.respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
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
    }
}
