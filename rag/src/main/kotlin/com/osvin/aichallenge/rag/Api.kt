package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.agent.LlmClientFactory
import com.osvin.aichallenge.agent.LlmProvider
import com.osvin.aichallenge.agent.LlmProviderConfig
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
 *
 * Провайдера — облачного или локального — выбирает конфигурация запуска ([settings]), тем же
 * набором переменных и той же фабрикой ([LlmClientFactory]), что и веб-чат дня 27: второго места
 * выбора в проекте нет, поэтому прогон RAG и страница не могут оказаться на разных моделях.
 * Ключ облака нужен только облачному провайдеру: локальному он не нужен, и его отсутствие — не
 * ошибка ([provider], [modelName]).
 */
object Api {

    /** Ключ из свойств запуска, окружения или `server/.env`; `null` — ключа нет нигде. */
    fun key(): String? =
        System.getProperty("demo.api.key")?.takeIf { it.isNotBlank() }
            ?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
            ?: envFile()[DEEPSEEK_KEY]?.takeIf { it.isNotBlank() }

    /**
     * Настройки провайдера генерации: окружение и `server/.env`, где переменная окружения важнее.
     *
     * `.env` читается наравне с окружением, потому что файл у прогона и у сервера один:
     * `LLM_PROVIDER=local` кладут туда или в окружение запуска — и оба согласны, чем отвечать.
     */
    fun settings(): LlmProviderConfig = LlmProviderConfig.fromEnv(envFile() + System.getenv())

    /** Провайдер генерации прогона и страницы. */
    fun provider(): LlmProvider = settings().provider

    /**
     * Имя модели для промптов и отчёта.
     *
     * В локальном режиме имя задаёт транспорт: демону уходит модель из настроек провайдера,
     * и отчёт, назвавший запрошенную облачную модель, говорил бы не о том, кто отвечал.
     *
     * @param requested Имя, запрошенное запуском (`--model`).
     */
    fun modelName(requested: String): String = settings().modelOverride ?: requested

    /**
     * Клиент модели: тот же набор настроек, что у сервера приложения.
     *
     * Чтение неизвестных полей включено не для удобства: ответы модели приходят с полями, которых
     * в DTO может не быть, и строгий разбор превратил бы лишнее поле в отказ вместо ответа.
     *
     * @param apiKey Ключ облака; локальному провайдеру не нужен и не проверяется.
     */
    fun model(apiKey: String): LlmClient =
        LlmClientFactory(settings(), httpClient()).create(apiKey)

    /**
     * Клиент выбранного провайдера: нужен сравнению моделей, где отвечают **обе** сразу.
     *
     * Провайдер здесь параметр, а не то, что решает окружение: страница сравнения отвечает
     * и локальной моделью, и облачной на одних вопросах, и второй набор настроек для этого заводить
     * не нужно — меняется одно поле конфигурации ([settingsFor]).
     */
    fun modelFor(provider: LlmProvider, apiKey: String): LlmClient =
        LlmClientFactory(settingsFor(provider), httpClient()).create(apiKey)

    /**
     * Настройки с явно выбранным провайдером: остальное берётся из окружения как есть.
     *
     * Так сравнение получает обе модели с одним адресом демона, одной локальной моделью и одним
     * сроком ожидания: если бы у локальной руки сравнения были свои настройки, её числа относились
     * бы к другой модели, чем числа прогона.
     */
    fun settingsFor(provider: LlmProvider): LlmProviderConfig = settings().copy(provider = provider)

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

    /**
     * `server/.env` как карта `КЛЮЧ → значение`; пустая карта, если файла нет.
     *
     * Строки без `=` и закомментированные пропускаются: файл правит человек, и лишняя строка
     * в нём не должна ломать чтение остальных.
     */
    private fun envFile(): Map<String, String> {
        val env = Path.of("server", ".env")
        if (!Files.isRegularFile(env)) return emptyMap()
        return Files.readAllLines(env)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"')
            }
    }

    /** Имя ключа облачной модели в `server/.env`. */
    private const val DEEPSEEK_KEY = "DEEPSEEK_API_KEY"

    private const val REQUEST_TIMEOUT_MILLIS = 300_000L
    private const val CONNECT_TIMEOUT_MILLIS = 20_000L
}
