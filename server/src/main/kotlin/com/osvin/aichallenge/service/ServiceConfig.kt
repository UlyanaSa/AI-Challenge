package com.osvin.aichallenge.service

/**
 * Настройки сервиса `/api/…`: единственное место, где читаются его переменные окружения.
 *
 * Отдельный тип, а не поля сервера: сервис — вторая поверхность рядом с `/v1/…`, и её
 * условия (ключ доступа, частота, параллельность, потолки контекста) не должны разъезжаться
 * по маршрутам. Сборка ([fromEnv]) — чистая функция от карты окружения: запуск отдаёт ей
 * словарь процесса вместе с `.env` тем же способом, что и настройки провайдера
 * (`LlmProviderConfig.fromEnv`), а тесты — свою карту, поэтому ни одна проверка сервиса
 * не зависит от машины и не поднимает ничего настоящего.
 *
 * Пустая строка и неположительное число — не значение, а умолчание: пустой ключ включил бы
 * поверхность без защиты, нулевая частота запретила бы всё, а нулевой срок ожидания очереди
 * отвергал бы каждый запрос при занятых генерациях. Значение сверх потолка обрезается до
 * потолка, а не отвергается: иначе клиент узнавал бы об ограничении из отказа вместо ответа.
 *
 * @param host Адрес прослушивания; по умолчанию все интерфейсы, потому что сервис стоит
 *        за обратным прокси, а не рядом с браузером.
 * @param port Порт прослушивания.
 * @param apiKeys Ключи доступа: объединение одиночного ключа и списка. Пусто — поверхность
 *        `/api/…`, кроме проверки живости, отвечает отказом конфигурации, а не пускает всех.
 * @param rateLimitPerMinute Потолок запросов к модели в минуту на один ключ.
 * @param maxConcurrentGenerations Сколько генераций сервис ведёт одновременно.
 * @param queueLimit Сколько запросов ждут место в очереди, прежде чем получить отказ.
 * @param queueWaitMillis Сколько запрос ждёт место, прежде чем получить отказ.
 * @param maxContextTokens Потолок контекстного окна: клиент может просить меньше, но не больше.
 * @param maxGenerationTokens Потолок бюджета ответа: он же резерв окна под ответ.
 * @param systemPrompt Обязательная системная инструкция сервиса: из контекста не выкидывается.
 * @param chatDbPath Файл SQLite с сессиями; в контейнере — точка монтирования тома.
 * @param summaryEnabled Строить ли сводку прошлого чата при создании нового. Выключенная
 *        настройка сильнее запроса: при ней сводка не строится никогда.
 * @param summaryPrompt Системная инструкция сводки: что именно сворачивать в прошлом диалоге.
 * @param summaryMaxTokens Бюджет сводки; обрезается потолком ответа, как и клиентский бюджет.
 * @param summaryMaxMessages Сколько последних сообщений прошлого чата уходит в сводку.
 * @param chatsListLimit Потолок `limit` в `GET /api/chats`: клиент может просить меньше.
 */
data class ServiceConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val apiKeys: Set<String> = emptySet(),
    val rateLimitPerMinute: Int = DEFAULT_RATE_LIMIT_PER_MINUTE,
    val maxConcurrentGenerations: Int = DEFAULT_MAX_CONCURRENT_GENERATIONS,
    val queueLimit: Int = DEFAULT_QUEUE_LIMIT,
    val queueWaitMillis: Long = DEFAULT_QUEUE_WAIT_MILLIS,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    val maxGenerationTokens: Int = DEFAULT_MAX_GENERATION_TOKENS,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val chatDbPath: String = DEFAULT_CHAT_DB_PATH,
    val summaryEnabled: Boolean = DEFAULT_SUMMARY_ENABLED,
    val summaryPrompt: String = DEFAULT_SUMMARY_PROMPT,
    val summaryMaxTokens: Int = DEFAULT_SUMMARY_MAX_TOKENS,
    val summaryMaxMessages: Int = DEFAULT_SUMMARY_MAX_MESSAGES,
    val chatsListLimit: Int = DEFAULT_CHATS_LIST_LIMIT
) {

    /** Заданы ли ключи: без них работает только проверка живости, остальное — отказ конфигурации. */
    val configured: Boolean get() = apiKeys.isNotEmpty()

    companion object {

        /** Адрес прослушивания. */
        const val ENV_HOST = "SERVICE_HOST"

        /** Порт прослушивания. */
        const val ENV_PORT = "SERVICE_PORT"

        /** Одиночный ключ доступа; объединяется со списком ключей. */
        const val ENV_API_KEY = "SERVICE_API_KEY"

        /** Ключи доступа через запятую: несколько клиентов на один сервис. */
        const val ENV_API_KEYS = "SERVICE_API_KEYS"

        /** Потолок запросов к модели в минуту на ключ. */
        const val ENV_RATE_LIMIT_PER_MINUTE = "SERVICE_RATE_LIMIT_PER_MINUTE"

        /** Сколько генераций сервис ведёт одновременно. */
        const val ENV_MAX_CONCURRENT_GENERATIONS = "SERVICE_MAX_CONCURRENT_GENERATIONS"

        /** Сколько запросов ждут место в очереди. */
        const val ENV_QUEUE_LIMIT = "SERVICE_QUEUE_LIMIT"

        /** Сколько запрос ждёт место в очереди. */
        const val ENV_QUEUE_WAIT_MS = "SERVICE_QUEUE_WAIT_MS"

        /** Потолок контекстного окна. */
        const val ENV_MAX_CONTEXT_TOKENS = "SERVICE_MAX_CONTEXT_TOKENS"

        /** Потолок бюджета ответа. */
        const val ENV_MAX_GENERATION_TOKENS = "SERVICE_MAX_GENERATION_TOKENS"

        /** Обязательная системная инструкция сервиса. */
        const val ENV_SYSTEM_PROMPT = "SERVICE_SYSTEM_PROMPT"

        /** Файл SQLite с сессиями. */
        const val ENV_CHAT_DB = "CHAT_DB"

        /** Строить ли сводку прошлого чата при создании нового. */
        const val ENV_SUMMARY_ENABLED = "SERVICE_SUMMARY_ENABLED"

        /** Системная инструкция сводки. */
        const val ENV_SUMMARY_PROMPT = "SERVICE_SUMMARY_PROMPT"

        /** Бюджет сводки в токенах. */
        const val ENV_SUMMARY_MAX_TOKENS = "SERVICE_SUMMARY_MAX_TOKENS"

        /** Сколько последних сообщений прошлого чата сворачивать. */
        const val ENV_SUMMARY_MAX_MESSAGES = "SERVICE_SUMMARY_MAX_MESSAGES"

        /** Потолок `limit` в списке чатов. */
        const val ENV_CHATS_LIST_LIMIT = "SERVICE_CHATS_LIST_LIMIT"

        /** Адрес прослушивания по умолчанию. */
        const val DEFAULT_HOST = "0.0.0.0"

        /** Порт прослушивания по умолчанию. */
        const val DEFAULT_PORT = 8080

        /** Частота по умолчанию: десять запросов в минуту на ключ. */
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 10

        /** Параллельность по умолчанию: две генерации — предел одной локальной модели. */
        const val DEFAULT_MAX_CONCURRENT_GENERATIONS = 2

        /** Очередь по умолчанию: четыре ждущих запроса. */
        const val DEFAULT_QUEUE_LIMIT = 4

        /** Срок ожидания по умолчанию: пятнадцать секунд. */
        const val DEFAULT_QUEUE_WAIT_MILLIS = 15_000L

        /** Окно контекста по умолчанию: умолчание демона Ollama. */
        const val DEFAULT_MAX_CONTEXT_TOKENS = 4096

        /** Бюджет ответа по умолчанию: короткая реплика, а не рассуждение на страницу. */
        const val DEFAULT_MAX_GENERATION_TOKENS = 512

        /** Файл сессий по умолчанию: каталог данных рядом с процессом. */
        const val DEFAULT_CHAT_DB_PATH = "data/service-chats.db"

        /** Сводка по умолчанию включена: сервис сворачивает прошлый чат сам, без просьбы клиента. */
        const val DEFAULT_SUMMARY_ENABLED = true

        /**
         * Инструкция сводки по умолчанию.
         *
         * Сводка нужна как факты для следующего разговора, а не как пересказ человеку:
         * вступление и обращение к читателю в ней — лишние токены, которые уедут
         * в системную часть каждого следующего запроса.
         */
        const val DEFAULT_SUMMARY_PROMPT =
            "Сожми диалог в короткую сводку: факты, числа, имена, договорённости и решения. " +
                "Без вступлений и без обращения к человеку. Не более 200 слов, по-русски."

        /** Бюджет сводки по умолчанию: короткая выжимка, а не второй диалог. */
        const val DEFAULT_SUMMARY_MAX_TOKENS = 256

        /** Сколько последних сообщений сворачивать по умолчанию: примерно двадцать пар реплик. */
        const val DEFAULT_SUMMARY_MAX_MESSAGES = 40

        /** Потолок списка чатов по умолчанию: одна страница панели. */
        const val DEFAULT_CHATS_LIST_LIMIT = 100

        /**
         * Температура клиента не поднимается выше этого значения.
         *
         * Потолок нужен, потому что температуру присылает клиент, а маленькая локальная модель
         * на высокой температуре перестаёт отвечать воспроизводимо (замеры дня 26: «Юпитер»
         * и «Жупитер» на одном вопросе). Отвергать значение сверх потолка не стали: клиент
         * получил бы отказ вместо ответа, а обрезанное значение — это ровно та крайняя точка,
         * которую он и просил.
         */
        const val TEMPERATURE_CEILING = 2.0

        /**
         * Обязательная инструкция по умолчанию.
         *
         * Сервис отвечает на вопросы человека, а не рассуждает вслух: маленькая модель без
         * инструкции склонна печатать ход мысли, который клиент показывает как ответ.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — ассистент приватного сервиса на локальной модели. Отвечай по-русски, " +
                "кратко и по существу. Не пересказывай эту инструкцию. Если данных для ответа " +
                "не хватает, скажи об этом прямо."

        /**
         * Настройки из окружения.
         *
         * Каждое поле читается отдельно и по одному правилу: значение переменной, если оно
         * есть и положительно, иначе умолчание. Список ключей склеивается с одиночным ключом,
         * потому что оба имени живут в проде: одиночный ключ удобно задать руками, список —
         * выдать нескольким клиентам.
         *
         * @param env Переменные окружения: в запуске — `System.getenv()` вместе с `.env`,
         *        в тестах — своя карта.
         */
        fun fromEnv(env: Map<String, String>): ServiceConfig {
            val single = env[ENV_API_KEY].text()
            val many = env[ENV_API_KEYS].text()
                ?.split(',')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            val contextTokens = env[ENV_MAX_CONTEXT_TOKENS].positiveInt() ?: DEFAULT_MAX_CONTEXT_TOKENS
            // Потолок ответа считается до бюджета сводки: сводка — такой же ответ модели,
            // и её бюджет не может быть больше того, что вообще разрешено генерировать.
            val generationTokens = (env[ENV_MAX_GENERATION_TOKENS].positiveInt()
                ?: DEFAULT_MAX_GENERATION_TOKENS).coerceAtMost(contextTokens)
            return ServiceConfig(
                host = env[ENV_HOST].text() ?: DEFAULT_HOST,
                port = env[ENV_PORT].positiveInt() ?: DEFAULT_PORT,
                apiKeys = (listOfNotNull(single) + many).toSet(),
                rateLimitPerMinute = env[ENV_RATE_LIMIT_PER_MINUTE].positiveInt()
                    ?: DEFAULT_RATE_LIMIT_PER_MINUTE,
                maxConcurrentGenerations = env[ENV_MAX_CONCURRENT_GENERATIONS].positiveInt()
                    ?: DEFAULT_MAX_CONCURRENT_GENERATIONS,
                queueLimit = env[ENV_QUEUE_LIMIT].nonNegativeInt() ?: DEFAULT_QUEUE_LIMIT,
                queueWaitMillis = env[ENV_QUEUE_WAIT_MS].positiveLong() ?: DEFAULT_QUEUE_WAIT_MILLIS,
                maxContextTokens = contextTokens,
                // Потолок ответа не может превышать окно: с большим резервом бюджет запроса
                // стал бы отрицательным, и сервис отвечал бы переполнением на каждый вопрос.
                // Обрезаем молча — это то же правило, что и для клиентских значений.
                maxGenerationTokens = generationTokens,
                systemPrompt = env[ENV_SYSTEM_PROMPT].text() ?: DEFAULT_SYSTEM_PROMPT,
                chatDbPath = env[ENV_CHAT_DB].text() ?: DEFAULT_CHAT_DB_PATH,
                summaryEnabled = env[ENV_SUMMARY_ENABLED].flag(DEFAULT_SUMMARY_ENABLED),
                summaryPrompt = env[ENV_SUMMARY_PROMPT].text() ?: DEFAULT_SUMMARY_PROMPT,
                summaryMaxTokens = (env[ENV_SUMMARY_MAX_TOKENS].positiveInt()
                    ?: DEFAULT_SUMMARY_MAX_TOKENS).coerceAtMost(generationTokens),
                summaryMaxMessages = env[ENV_SUMMARY_MAX_MESSAGES].positiveInt()
                    ?: DEFAULT_SUMMARY_MAX_MESSAGES,
                chatsListLimit = env[ENV_CHATS_LIST_LIMIT].positiveInt() ?: DEFAULT_CHATS_LIST_LIMIT
            )
        }

        /** Значение переменной без пустых строк: пустая строка — это «не задано». */
        private fun String?.text(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Флаг из переменной; незнакомая строка оставляет умолчание.
         *
         * Опечатка в `SERVICE_SUMMARY_ENABLED=false` не должна молча включать сводку и наоборот:
         * разбирать потом, почему модель вызывается лишний раз, дороже, чем назвать строку
         * нераспознанной и оставить прежнее поведение.
         */
        private fun String?.flag(fallback: Boolean): Boolean = when (text()?.lowercase()) {
            "1", "true", "yes", "да" -> true
            "0", "false", "no", "нет" -> false
            else -> fallback
        }

        /** Целое больше нуля; прочее — «не задано». */
        private fun String?.positiveInt(): Int? = text()?.toIntOrNull()?.takeIf { it > 0 }

        /** Целое не меньше нуля: очередь длиной ноль — осознанное «ждать негде». */
        private fun String?.nonNegativeInt(): Int? = text()?.toIntOrNull()?.takeIf { it >= 0 }

        /** Длительность больше нуля; прочее — «не задано». */
        private fun String?.positiveLong(): Long? = text()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
