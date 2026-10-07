package com.osvin.aichallenge.indexing.ollama

import com.osvin.aichallenge.indexing.embedding.EmbeddingProvider
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Провайдер не ответил или ответил ошибкой.
 *
 * Отдельный тип, а не `IllegalStateException`, потому что вызывающему есть что с этим делать:
 * это не сбой в данных, а состояние окружения — демон не запущен, модель не скачана, порт занят
 * кем-то другим. Сообщение поэтому несёт адрес, модель и подсказку, что проверить, и не тонет
 * в общем «что-то пошло не так».
 */
class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Embeddings из локальной модели через Ollama (`POST /api/embed`).
 *
 * Почему так, а не хеширование признаков: хешированный вектор — это мешок слов, он не знает ни
 * синонимов, ни морфологии, и вопрос, сформулированный не словами книги, до ответа не доходит.
 * Модель за тем же интерфейсом `EmbeddingProvider` отвечает на смысл, а не на совпадение строк,
 * и конвейер об этом не знает: он получает вектор той длины, которую объявил провайдер.
 *
 * Размерность берётся у самой модели ([connect] считает пробный вектор), а не задаётся константой:
 * у `bge-m3` она 1024, у `nomic-embed-text` — 768, и число, вписанное в код, разошлось бы с моделью
 * в первый же день. Из этого же следует, что индекс, посчитанный одной моделью, нельзя искать
 * другой: `Indexer` сверяет длину вектора с [dimension], а `JsonVectorStore` перезаписывается
 * при построении индекса.
 *
 * Таймаут один на всё: первая в жизни проба загружает модель в память (у 1.2 ГБ модели это
 * секунды, а не миллисекунды), и отдельный «быстрый» таймаут на подключение здесь только мешал бы.
 * [available] — отдельный, короткий: она про демон, а не про модель.
 */
class OllamaEmbeddingProvider private constructor(
    /** Адрес демона без завершающего слэша, например `http://127.0.0.1:11434`. */
    private val baseUrl: String,
    /** Имя модели так, как её знает Ollama: `bge-m3`, `nomic-embed-text`, … */
    val model: String,
    override val dimension: Int,
    private val timeout: Duration,
    private val client: HttpClient
) : EmbeddingProvider {

    override suspend fun embed(text: String): List<Float> {
        val vector = embedOnce(client, baseUrl, model, text, timeout)
        if (vector.size != dimension) {
            // Модель просят не менять под собой: если длина поехала, индекс и запросы разошлись бы.
            throw OllamaException(
                "Модель $model вернула вектор длины ${vector.size} вместо $dimension: " +
                    "индекс построен на другой модели, постройте его заново"
            )
        }
        return vector
    }

    /** Имя для показа: по нему читают, какой моделью посчитаны близости на странице. */
    override fun toString(): String = "Ollama $model ($dimension измерений)"

    companion object {

        /** Адрес демона по умолчанию: столько, сколько занимает Ollama после установки. */
        const val DEFAULT_URL: String = "http://127.0.0.1:11434"

        /** Модель по умолчанию: многоязычная (корпус дня — русский текст с английскими терминами). */
        const val DEFAULT_MODEL: String = "bge-m3"

        /** Таймаут запроса к демону: с запасом на первую загрузку модели в память. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(120)

        /** Таймаут проверки демона: она про то, слушает ли порт, а не про то, считает ли модель. */
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** Текст пробы: размерность от текста не зависит, важно лишь получить один вектор. */
        private const val PROBE_TEXT = "проба размерности"

        /**
         * Провайдер для модели [model] на демоне [baseUrl].
         *
         * Размерность выясняется пробным вектором: если модель не скачана или демон не отвечает,
         * исключение приходит отсюда, при подключении, — а не при первом чанке, когда половина
         * индекса уже построена.
         */
        suspend fun connect(
            baseUrl: String = DEFAULT_URL,
            model: String = DEFAULT_MODEL,
            timeout: Duration = DEFAULT_TIMEOUT
        ): OllamaEmbeddingProvider {
            val url = normalize(baseUrl)
            val client = newClient(timeout)
            val probe = embedOnce(client, url, model, PROBE_TEXT, timeout)
            if (probe.isEmpty()) {
                throw OllamaException("Модель $model на $url вернула пустой вектор")
            }
            return OllamaEmbeddingProvider(url, model, probe.size, timeout, client)
        }

        /**
         * Отвечает ли демон на [baseUrl]: нужен там, где провайдер выбирается автоматически.
         *
         * Проверяется именно демон (`GET /api/version`), а не модель: страница при выборе провайдера
         * решает, поднимать ли Ollama, и ошибка «модель не скачана» должна остаться ошибкой
         * с подсказкой `ollama pull`, а не молчаливым переходом на хеширование.
         */
        suspend fun available(baseUrl: String = DEFAULT_URL): Boolean {
            val url = runCatching { normalize(baseUrl) }.getOrNull() ?: return false
            val client = newClient(PROBE_TIMEOUT)
            return try {
                val request = HttpRequest.newBuilder(URI.create("$url/api/version"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build()
                val response = withContext(Dispatchers.IO) {
                    client.send(request, HttpResponse.BodyHandlers.discarding())
                }
                response.statusCode() in 200..299
            } catch (e: Exception) {
                false
            }
        }

        private fun normalize(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Адрес Ollama пуст" }
            require(trimmed.startsWith("http")) { "Адрес Ollama должен начинаться с http, получено $trimmed" }
            return trimmed
        }

        private fun newClient(timeout: Duration): HttpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            // Демон Ollama говорит по HTTP/1.1; явная версия избавляет от попыток h2c-апгрейда.
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        /** Один запрос к `/api/embed`: тело `{model, input}`, ответ `{embeddings: [[…]]}`. */
        private suspend fun embedOnce(
            client: HttpClient,
            baseUrl: String,
            model: String,
            text: String,
            timeout: Duration
        ): List<Float> {
            val body = buildJsonObject {
                put("model", model)
                put("input", text)
            }.toString()
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/embed"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()

            val response = try {
                withContext(Dispatchers.IO) {
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                }
            } catch (e: IOException) {
                throw OllamaException(
                    "Не удалось связаться с Ollama по адресу $baseUrl: ${e.message}. " +
                        "Проверьте, что демон запущен (`ollama serve`)",
                    e
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OllamaException("Запрос к Ollama по адресу $baseUrl прерван", e)
            }

            val payload = response.body()
            if (response.statusCode() !in 200..299) {
                throw OllamaException(
                    "Ollama ответила ${response.statusCode()} на $baseUrl/api/embed" +
                        (errorOf(payload)?.let { ": $it" } ?: "") +
                        if (model.isNotEmpty()) " (модель $model)" else ""
                )
            }
            return vectorOf(payload, model, baseUrl)
        }

        /** Текст ошибки из ответа демона: Ollama кладёт её в поле `error`. */
        private fun errorOf(payload: String): String? = runCatching {
            Json.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()

        private fun vectorOf(payload: String, model: String, baseUrl: String): List<Float> {
            val element = runCatching { Json.parseToJsonElement(payload) }.getOrElse { e ->
                throw OllamaException("Ollama ответила неразборчиво на $baseUrl/api/embed: $payload", e)
            }
            val vector = element.jsonObject["embeddings"]?.jsonArray?.firstOrNull()?.jsonArray
                ?: throw OllamaException(
                    "В ответе Ollama на $baseUrl/api/embed нет вектора для модели $model: $payload"
                )
            return vector.map { it.jsonPrimitive.float }
        }
    }
}
