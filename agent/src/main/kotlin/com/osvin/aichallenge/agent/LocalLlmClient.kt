package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import kotlin.time.TimeSource

/**
 * HTTP-транспорт агента к локальному демону Ollama.
 *
 * Вторая реализация [LlmClient] рядом с [DeepSeekClient]: агент и весь остальной код
 * видят только контракт, а куда идёт запрос — в облако или на эту машину — решает
 * тот, кто собрал клиента. Транспорт не умеет сам переключаться на облако: адрес,
 * модель и срок заданы его конструктором, и другого адреса он не знает. Поэтому
 * ошибка локального провайдера остаётся ошибкой, а не молчаливым уходом в облако,
 * — это и есть требование задания о запрете автоматического переключения.
 *
 * Запрос уходит на `POST /api/chat` (см. [OllamaChatRequest]), ответ разбирается
 * в модель приложения ([OllamaChatResponse.toDeepSeek]). Модель транспорта —
 * параметр конструктора, а не поле запроса: [DeepSeekRequest.model] называет
 * облачную модель, которой у Ollama нет, и подставлять её в запрос значило бы
 * просить демон о модели, которой он не знает.
 *
 * Срок запроса задаётся конструктором и ставится на каждый вызов отдельно
 * ([timeout]): сильные модели отвечают минутами, а сервер отдаёт общий клиент
 * с общим сроком, и без своего срока локальный провайдер ждал бы столько же,
 * сколько облачный.
 *
 * @param model Имя модели Ollama: локальная модель, к которой подключён транспорт.
 * @param http Клиент тем же способом, что у облачного транспорта: сервер отдаёт общий,
 *        тесты — подставной движок.
 * @param baseUrl Адрес демона Ollama без хвоста: `/api/chat` дописывается запросом.
 * @param timeoutMillis Срок одного запроса в миллисекундах.
 * @param logger Лог транспорта: модель, длительность, расход токенов и тип ошибки.
 *        Текст сообщений в лог не попадает — задание требует не логировать его по умолчанию.
 * @param think Рассуждать ли модели перед ответом. По умолчанию да, и это измеренный
 *        выбор, а не вкус: на «задаче о ста дверях» qwen3:1.7b без рассуждения отвечает
 *        `50`, а с ним — `10`. Цена — рассуждение тратит бюджет ответа и время, поэтому
 *        значение выносится в конфигурацию (`OLLAMA_THINK`), а не зашито.
 * @param temperature Температура генерации транспорта. По умолчанию `0`, то есть
 *        детерминированный ответ, и это тоже измеренный выбор: при `0.7` (умолчание
 *        клиента) маленькая модель называет самую большую планету «Жупитер» — рассуждение
 *        идёт по-английски, и написание слова плывёт от запуска к запуску. При `0` тот же
 *        вопрос даёт «Юпитер» в каждом прогоне. Температура клиента при этом не теряется:
 *        её видно в отчёте о токенах, а значение транспорта задаётся конфигурацией
 *        (`OLLAMA_TEMPERATURE`).
 */
class LocalLlmClient(
    private val model: String = DEFAULT_MODEL,
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_URL,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val logger: AgentLogger = AgentLogger.Console,
    private val think: Boolean = DEFAULT_THINK,
    private val temperature: Double = DEFAULT_TEMPERATURE
) : LlmClient {

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        val started = TimeSource.Monotonic.markNow()
        val response = try {
            http.post("$baseUrl$CHAT_PATH") {
                contentType(ContentType.Application.Json)
                setBody(request.toOllama(model, think = think, temperatureOverride = temperature))
                timeout { requestTimeoutMillis = timeoutMillis }
            }
        } catch (timeout: HttpRequestTimeoutException) {
            throw logFailure("превышен срок запроса ($timeoutMillis мс)", started, LlmTimeoutException(
                "Локальная модель $model не ответила за $timeoutMillis мс. " +
                    "Увеличьте срок запроса или повторите попытку.",
                timeout
            ))
        } catch (timeout: SocketTimeoutException) {
            throw logFailure("превышен срок чтения ответа", started, LlmTimeoutException(
                "Локальная модель $model не ответила за $timeoutMillis мс. " +
                    "Увеличьте срок запроса или повторите попытку.",
                timeout
            ))
        } catch (unavailable: ConnectTimeoutException) {
            throw logFailure("демон не принял соединение", started, LlmUnavailableException(
                unavailableMessage(), unavailable
            ))
        } catch (unavailable: IOException) {
            throw logFailure("демон недоступен ($unavailable)", started, LlmUnavailableException(
                unavailableMessage(), unavailable
            ))
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            throw logFailure(
                "HTTP ${response.status.value}: ${ollamaError(text)}",
                started,
                LlmApiException(response.status.value, "Ollama API error: ${response.status} - ${ollamaError(text)}")
            )
        }

        val body = response.body<OllamaChatResponse>()
        val parsed = try {
            body.toDeepSeek()
        } catch (apiError: LlmApiException) {
            throw logFailure("ответ не разобран: ${apiError.message}", started, apiError)
        }
        logger.log(
            "Ollama $model: ${started.elapsedNow().inWholeMilliseconds} мс, " +
                "запрос ${parsed.usage?.promptTokens ?: 0} ток., ответ ${parsed.usage?.completionTokens ?: 0} ток., успешно"
        )
        return parsed
    }

    /** Строка отказа доступности: одна формулировка на все причины недоступности демона. */
    private fun unavailableMessage(): String =
        "Локальная модель $model недоступна: демон Ollama не отвечает по адресу $baseUrl. " +
            "Запустите Ollama (`ollama serve`) и проверьте, что модель загружена (`ollama pull $model`)."

    /** Печатает причину отказа и возвращает то же исключение, чтобы бросить его одной строкой. */
    private fun logFailure(reason: String, started: TimeSource.Monotonic.ValueTimeMark, error: Exception): Exception {
        logger.log("Ollama $model: ${started.elapsedNow().inWholeMilliseconds} мс, ошибка: $reason")
        return error
    }

    companion object {

        /** Адрес демона Ollama по умолчанию: тот же, с которого читает векторы модуль индексации. */
        const val DEFAULT_URL = "http://127.0.0.1:11434"

        /** Модель задания: локальная модель дня 26. */
        const val DEFAULT_MODEL = "qwen3:1.7b"

        /**
         * Срок запроса по умолчанию: две минуты.
         *
         * Локальная модель на этой машине отвечает секундами, но первая загрузка модели
         * в память занимает десятки секунд, и короткий срок обрывал бы первый же запрос.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L

        /**
         * Рассуждение по умолчанию: включено.
         *
         * Ollama у thinking-модели считает `think` включённым, но полагаться на умолчание
         * нельзя: оно зависит от модели, а разница видна на ответе — с рассуждением
         * qwen3:1.7b решает задачу о ста дверях (`10`), без него называет `50`.
         */
        const val DEFAULT_THINK = true

        /**
         * Температура по умолчанию для локальной модели: `0`.
         *
         * Умолчание клиента (`0.7`) маленькой модели не подходит: ответ становится
         * нестабильным по написанию и по счёту, и один и тот же вопрос даёт то «Юпитер»,
         * то «Жупитер». При `0` ответ воспроизводится от запуска к запуску.
         */
        const val DEFAULT_TEMPERATURE = 0.0

        /** Путь чата в API Ollama: дописывается к адресу демона. */
        private const val CHAT_PATH = "/api/chat"

        /** Путь списка моделей демона: по нему проверяется здоровье локального режима. */
        internal const val TAGS_PATH = "/api/tags"
    }
}
