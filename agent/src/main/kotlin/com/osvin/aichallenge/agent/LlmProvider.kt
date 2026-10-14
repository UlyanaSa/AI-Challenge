package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.OllamaTags
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess

/**
 * Провайдер генерации: чей транспорт вызывает агент.
 *
 * Значение приходит из конфигурации сервера, а не из запроса клиента: выбор провайдера —
 * это решение о том, куда уходят данные пользователя, и оно принимается при запуске,
 * а не по каждому сообщению. Клиент может выбрать модель облачного провайдера, но не
 * подменить локальный режим на облачный — иначе требование задания «при LOCAL генерация
 * идёт исключительно локально» держалось бы на честном слове клиента.
 *
 * @param wire Значение в конфигурации: `local` — Ollama, `cloud` — облачный API.
 */
enum class LlmProvider(val wire: String) {

    /** Локальная модель через демон Ollama: генерация не покидает эту машину. */
    LOCAL("local"),

    /** Облачный провайдер (DeepSeek): тот же, что был до этого дня. */
    CLOUD("cloud");

    companion object {

        /**
         * Провайдер по значению конфигурации; неизвестное или пустое значение — [CLOUD],
         * то есть поведение, которое было до этого дня: незнакомая строка не должна молча
         * включать локальный режим, которого никто не просил.
         */
        fun fromWire(value: String?): LlmProvider =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: CLOUD
    }
}

/**
 * Настройки подключения к модели: чем и куда ходить за ответом.
 *
 * Отдельный тип, а не константы в бизнес-логике: адрес демона, имя модели и срок
 * задаются запуском ([fromEnv]), а маршрут чата получает уже готовый набор и не знает,
 * откуда взялись значения. Тесты подставляют набор напрямую, поэтому ни один из них
 * не зависит от окружения машины.
 *
 * @param provider Кто отвечает за генерацию.
 * @param ollamaUrl Адрес демона Ollama: с учётом сети той машины, где он действительно
 *        работает (`localhost` указывает на backend, а не на Ollama, если сервер запущен
 *        в контейнере или на другом устройстве).
 * @param ollamaModel Имя локальной модели.
 * @param ollamaTimeoutMillis Срок одного запроса к Ollama.
 * @param ollamaThink Рассуждать ли локальной модели перед ответом.
 * @param ollamaTemperature Температура генерации локальной модели.
 * @param ollamaNumCtx Окно контекста локальной модели в токенах.
 */
data class LlmProviderConfig(
    val provider: LlmProvider = LlmProvider.CLOUD,
    val ollamaUrl: String = LocalLlmClient.DEFAULT_URL,
    val ollamaModel: String = LocalLlmClient.DEFAULT_MODEL,
    val ollamaTimeoutMillis: Long = LocalLlmClient.DEFAULT_TIMEOUT_MILLIS,
    val ollamaThink: Boolean = LocalLlmClient.DEFAULT_THINK,
    val ollamaTemperature: Double = LocalLlmClient.DEFAULT_TEMPERATURE,
    val ollamaNumCtx: Int = LocalLlmClient.DEFAULT_NUM_CTX
) {

    /**
     * Модель, которую задаёт сам транспорт; null — модель выбирает клиент.
     *
     * В локальном режиме модель одна — та, что загружена в Ollama: присланное клиентом
     * имя облачной модели демону ничего не говорит, поэтому запрос собирается с именем
     * из конфигурации. В облачном режиме выбор модели остаётся за клиентом, как раньше.
     */
    val modelOverride: String? get() = if (provider == LlmProvider.LOCAL) ollamaModel else null

    companion object {

        /** Провайдер: `local` или `cloud`; не задан — облачный, как до этого дня. */
        const val ENV_PROVIDER = "LLM_PROVIDER"

        /** Адрес демона Ollama. */
        const val ENV_OLLAMA_URL = "OLLAMA_URL"

        /** Имя локальной модели. */
        const val ENV_OLLAMA_MODEL = "OLLAMA_MODEL"

        /** Срок одного запроса к Ollama в миллисекундах. */
        const val ENV_OLLAMA_TIMEOUT_MS = "OLLAMA_TIMEOUT_MS"

        /** Рассуждение локальной модели: `false`/`0` выключает, остальное оставляет включённым. */
        const val ENV_OLLAMA_THINK = "OLLAMA_THINK"

        /** Температура генерации локальной модели. */
        const val ENV_OLLAMA_TEMPERATURE = "OLLAMA_TEMPERATURE"

        /** Окно контекста локальной модели в токенах. */
        const val ENV_OLLAMA_NUM_CTX = "OLLAMA_NUM_CTX"

        /**
         * Настройки из окружения: сначала значение переменной, иначе умолчание.
         *
         * Пустые строки и неположительный срок отбрасываются, а не принимаются как есть:
         * пустой адрес дал бы отказ на каждом запросе, а срок `0` — мгновенный таймаут,
         * и оба выглядели бы поломкой транспорта, а не опечаткой в конфигурации. Строка
         * провайдера читается без учёта регистра ([LlmProvider.fromWire]).
         *
         * @param env Переменные окружения: в проде — `System.getenv()` вместе с `.env`,
         *        в тестах — своя карта, поэтому проверка не зависит от машины.
         */
        fun fromEnv(env: Map<String, String>): LlmProviderConfig = LlmProviderConfig(
            provider = LlmProvider.fromWire(env[ENV_PROVIDER]),
            ollamaUrl = env[ENV_OLLAMA_URL]?.trim()?.takeIf { it.isNotEmpty() }
                ?: LocalLlmClient.DEFAULT_URL,
            ollamaModel = env[ENV_OLLAMA_MODEL]?.trim()?.takeIf { it.isNotEmpty() }
                ?: LocalLlmClient.DEFAULT_MODEL,
            ollamaTimeoutMillis = env[ENV_OLLAMA_TIMEOUT_MS]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                ?: LocalLlmClient.DEFAULT_TIMEOUT_MILLIS,
            // Незнакомая строка флага оставляет умолчание: опечатка в `OLLAMA_THINK`
            // не должна молча менять режим ответа — включённое рассуждение заметно
            // по времени, а выключенное по ответу, и разбирать это потом дороже.
            ollamaThink = env[ENV_OLLAMA_THINK]?.trim()?.lowercase()?.let { thinkFlag(it) }
                ?: LocalLlmClient.DEFAULT_THINK,
            ollamaTemperature = env[ENV_OLLAMA_TEMPERATURE]?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
                ?: LocalLlmClient.DEFAULT_TEMPERATURE,
            // Малое окно не отбрасывается как опечатка: урезанное окно — осознанный выбор
            // для коротких служебных вызовов (проверка правил, извлечение памяти), и запрещать
            // его значило бы отнять у конфигурации рабочую настройку.
            ollamaNumCtx = env[ENV_OLLAMA_NUM_CTX]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: LocalLlmClient.DEFAULT_NUM_CTX
        )

        /** Флаг рассуждения по строке конфигурации; null — значение не распознано. */
        private fun thinkFlag(value: String): Boolean? = when (value) {
            "1", "true", "yes", "да" -> true
            "0", "false", "no", "нет" -> false
            else -> null
        }
    }
}

/**
 * Сборка транспорта по настройкам провайдера: единственное место, где выбирается
 * реализация [LlmClient].
 *
 * Маршрут чата не создаёт клиентов сам: он просит фабрику ([create]), поэтому добавление
 * третьего провайдера не трогает маршруты, а локальный режим не может «случайно» собраться
 * из облачных кусков. Ключ нужен только облачному провайдеру: в локальном режиме его
 * отсутствие — не ошибка, и это то, ради чего провайдер выбирается явно.
 *
 * @param config Настройки провайдера.
 * @param http HTTP-клиент: сервер отдаёт общий, тесты — подставной движок.
 * @param logger Лог транспорта: строки о запросе к Ollama.
 */
class LlmClientFactory(
    val config: LlmProviderConfig,
    private val http: HttpClient,
    private val logger: AgentLogger = AgentLogger.Console
) {

    /** Провайдер, которым работает фабрика: по нему маршрут решает, что доступно модели. */
    val provider: LlmProvider get() = config.provider

    /**
     * Модель, которую транспорт задаёт сам; null — модель выбирает клиент.
     * Маршрут подставляет её в настройки агента, чтобы отчёт и лог называли ту же
     * модель, которой ответил транспорт.
     */
    val modelOverride: String? get() = config.modelOverride

    /**
     * Транспорт к модели.
     *
     * @param apiKey Ключ облачного API; в локальном режиме не нужен и не проверяется.
     * @throws IllegalStateException если выбран облачный провайдер, а ключа нет:
     *         пустой ключ не должен превращаться в запрос, который API отвергнет.
     */
    fun create(apiKey: String?): LlmClient = when (config.provider) {
        LlmProvider.LOCAL -> LocalLlmClient(
            model = config.ollamaModel,
            http = http,
            baseUrl = config.ollamaUrl,
            timeoutMillis = config.ollamaTimeoutMillis,
            logger = logger,
            think = config.ollamaThink,
            temperature = config.ollamaTemperature,
            numCtx = config.ollamaNumCtx
        )

        LlmProvider.CLOUD -> DeepSeekClient(
            apiKey = apiKey?.takeIf { it.isNotBlank() } ?: error(CLOUD_KEY_REQUIRED),
            http = http
        )
    }

    /**
     * Список моделей демона Ollama: [OllamaProbe.Loaded] — демон ответил,
     * [OllamaProbe.Failed] — не ответил или ответил отказом.
     *
     * Проверка идёт по адресу из настроек, тем же HTTP-клиентом, что и запросы чата:
     * страница узнаёт о демоне ровно то, с чем будет работать, и подставной движок
     * в тестах закрывает и запрос чата, и проверку здоровья. Короткий срок здесь
     * намеренно короче рабочего: человек ждёт ответа страницы, а не модели.
     */
    suspend fun probeOllama(): OllamaProbe = try {
        val response = http.get("${config.ollamaUrl}${LocalLlmClient.TAGS_PATH}") {
            timeout { requestTimeoutMillis = OLLAMA_PROBE_TIMEOUT_MILLIS }
        }
        if (response.status.isSuccess()) {
            OllamaProbe.Loaded(response.body<OllamaTags>().models.map { it.name })
        } else {
            OllamaProbe.Failed(
                "Демон Ollama ответил ${response.status}: проверьте адрес ${config.ollamaUrl}"
            )
        }
    } catch (e: Exception) {
        OllamaProbe.Failed("Ollama не отвечает по адресу ${config.ollamaUrl}: ${e.message}")
    }

    /**
     * Та же фабрика с другими настройками генерации: провайдер, HTTP-клиент и журнал те же.
     *
     * Нужна сервису (день 30): клиент присылает модель, температуру и окно контекста в запросе,
     * а транспорт собирается по настройкам запуска. Пересобирать фабрику в маршруте руками
     * значило бы повторить выбор провайдера в обход единственного места, где он выбирается,
     * — поэтому меняются только значения, а маршрут `when (provider)` не пишет.
     *
     * @param model Модель запроса; `null` или пустое — модель запуска.
     * @param temperature Температура запроса; `null` — температура запуска.
     * @param numCtx Окно контекста запроса; `null` — окно запуска.
     */
    fun withOverrides(
        model: String? = null,
        temperature: Double? = null,
        numCtx: Int? = null
    ): LlmClientFactory = LlmClientFactory(
        config = config.copy(
            ollamaModel = model?.trim()?.takeIf { it.isNotEmpty() } ?: config.ollamaModel,
            ollamaTemperature = temperature ?: config.ollamaTemperature,
            ollamaNumCtx = numCtx ?: config.ollamaNumCtx
        ),
        http = http,
        logger = logger
    )

    companion object {

        /** Причина отказа: облачный провайдер без ключа не работает. */
        const val CLOUD_KEY_REQUIRED =
            "Ключ DEEPSEEK_API_KEY не задан: облачный провайдер без ключа не работает, " +
                "выберите LLM_PROVIDER=local для локальной модели"

        /** Срок проверки демона Ollama в миллисекундах. */
        internal const val OLLAMA_PROBE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Итог проверки демона Ollama.
 *
 * Два состояния, а не «список или пусто»: «демон ответил, модели нет» и «демон не
 * ответил» требуют разных действий человека — `ollama pull` и `ollama serve`, —
 * и проверка здоровья называет их разными строками, а не одной общей ошибкой.
 */
sealed interface OllamaProbe {

    /** Демон ответил: [models] — имена загруженных моделей. */
    data class Loaded(val models: List<String>) : OllamaProbe

    /** Демон не ответил или ответил отказом: [reason] — причина словами. */
    data class Failed(val reason: String) : OllamaProbe
}
