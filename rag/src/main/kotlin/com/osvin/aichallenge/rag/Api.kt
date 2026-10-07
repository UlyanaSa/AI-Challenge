package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.DeepSeekClient
import com.osvin.aichallenge.agent.LlmClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Ключ API и клиент к модели: одно место на прогон в консоли и на страницу дня 22.
 *
 * Общий объект, а не две копии, потому что копии разошлись бы именно там, где это дороже всего:
 * в таймаутах и в порядке поиска ключа. Обращение к модели с рассуждением идёт десятками секунд,
 * и таймаут в минуту обрывал бы его на середине — в консоли это выглядело бы как отказ модели,
 * а на странице как зависшая кнопка.
 *
 * Ключ ищется в трёх местах по порядку: свойство запуска, переменная окружения, файл `server/.env`.
 * Порядок повторяет остальные прогоны проекта: ключ у сервера и у прогона один, и лежит он
 * в `server/.env` — файле, который не попадает в репозиторий. Значение ключа не печатается
 * ни при каком исходе: наружу отдаётся только то, нашёлся он или нет.
 */
object Api {

    /** Ключ из свойств запуска, окружения или `server/.env`; `null` — ключа нет нигде. */
    fun key(): String? =
        System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
            ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
            ?: envFileKey()

    /**
     * Клиент модели: тот же набор настроек, что у сервера приложения.
     *
     * Чтение неизвестных полей включено не для удобства: ответы модели приходят с полями, которых
     * в DTO может не быть, и строгий разбор превратил бы лишнее поле в отказ вместо ответа.
     */
    fun model(apiKey: String): LlmClient = DeepSeekClient(apiKey = apiKey, http = httpClient())

    private fun httpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    private fun envFileKey(): String? {
        val env = Path.of("server", ".env")
        if (!Files.isRegularFile(env)) return null
        return Files.readAllLines(env)
            .map { it.trim() }
            .firstOrNull { it.startsWith(DEEPSEEK_KEY) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
    }

    private const val DEEPSEEK_KEY = "DEEPSEEK_API_KEY="
    private const val REQUEST_TIMEOUT_MILLIS = 300_000L
    private const val CONNECT_TIMEOUT_MILLIS = 20_000L
}
