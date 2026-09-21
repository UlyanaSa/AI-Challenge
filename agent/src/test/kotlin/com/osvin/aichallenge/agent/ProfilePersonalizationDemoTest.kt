package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Персонализация поверх памяти: профиль пользователя уходит в каждый запрос к модели.
 *
 * Профиль — не память, а объявленные настройки: его не извлекает модель, он не проходит
 * слияние, пределы и отклонение типов памяти и не меняется от диалога к диалогу. Поэтому
 * проверка дня наблюдаемая и простая: один и тот же вопрос на всех пяти стратегиях уходит
 * в модель с одним и тем же блоком профиля, а профиль владельца заканчивает ответ строкой
 * «Ты молодец» — её видно в ответе живого прогона.
 *
 * Этапы делят одну сцену. Первый сравнивает стратегии, второй — двух разных пользователей
 * на одном вопросе, третий сводит в таблицу то, что ассистент учитывает сам. Первый и третий
 * этапы идут на подставленном транспорте: профиль видно по тексту запроса, а живые вызовы
 * дня — только второго этапа, их ровно два, чтобы `-Pdemo.live=1` не тратил бюджет на лишнее.
 * Этапы связаны по имени ([MethodSorters.NAME_ASCENDING]): этап 3 берёт запросы этапа 1,
 * но при запуске в одиночку считает их сам.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProfilePersonalizationDemoTest {

    /**
     * Этап 1: один вопрос — пять стратегий.
     *
     * Стратегия решает, что уходит из истории и какая память идёт в запрос, но профиль
     * она не трогает. Проверяем две вещи: блок профиля есть в каждом запросе и в отчёте
     * видно, сколько токенов он занимает.
     */
    @Test
    fun stage1_profileInEveryRequest() = runBlocking {
        stage("Этап 1: профиль в каждом запросе (один вопрос на пяти стратегиях, подставленный ответ)")
        logCannedTransport()

        val runs = strategyRuns()
        log("")
        log("вопрос: «$QUESTION»")
        log("история к вопросу: ${HISTORY.size} сообщ., окно стратегий окна: $WINDOW")
        log("профиль: $DEFAULT_TITLE")
        log("концовка профиля: ${UserProfile.DEFAULT.signOff.ifBlank { "нет" }}")
        log("")
        printProfileHeaderRow()
        runs.forEach { run ->
            log(
                String.format(
                    Locale.ROOT,
                    PROFILE_ROW_FORMAT,
                    run.strategy.title,
                    if (run.hasProfile) "да" else "нет",
                    run.profileTokens.toString(),
                    run.request.messages.size.toString()
                )
            )
        }
        log("")
        log("блок профиля, который ушёл в запрос (одно и то же сообщение при любой стратегии):")
        runs.first().profileBlock.lines().forEach(::log)
        log("")
        log("строка лога агента о профиле:")
        runs.flatMap { it.logs }
            .flatMap { it.lines() }
            .distinct()
            .filter { it.contains("профиль", ignoreCase = true) }
            .forEach(::log)

        assertTrue(
            runs.all { it.hasProfile },
            "профиль уходит в запрос при любой стратегии: ${runs.map { it.strategy.title to it.hasProfile }}"
        )
        assertTrue(
            runs.all { it.profileTokens > 0 },
            "профиль стоит токенов в каждом запросе: ${runs.map { it.strategy.title to it.profileTokens }}"
        )
    }

    /**
     * Этап 2: тот же вопрос двум разным пользователям одного агента.
     *
     * Профили объявлены заранее и не зависят от диалога: у владельца приложения — Kotlin
     * Multiplatform и строка «Ты молодец» в конце ответа, у продуктового менеджера — краткий
     * ответ списком без кода и жаргона. Проверяем, что блоки в запросах разные и что
     * персональная настройка владельца уходит только в его запрос. В живом режиме те же два
     * профиля отвечают по-настоящему: ответы должны различаться, а есть ли в ответе его
     * собственная концовка — в живом режиме печатаем отдельной строкой и тестом не требуем:
     * концовку может не написать модель, и это её ограничение, а не провал персонализации.
     */
    @Test
    fun stage2_twoProfilesTwoRequests() = runBlocking {
        stage("Этап 2: два профиля — два запроса (один и тот же вопрос)")

        // Стратегия «вся история» и пустые слои памяти: этап про профиль, а не про память,
        // поэтому служебных вызовов нет — в живом режиме это ровно два запроса к модели.
        val calls = listOf(DEFAULT_TITLE to UserProfile.DEFAULT, PM_TITLE to PM_PROFILE).map { (title, profile) ->
            val llm = pairClient()
            val result = profileAgent(llm, mutableListOf(), InMemoryMemoryStore(), InMemoryMemoryStore()).run(
                QUESTION,
                options(HISTORY, maxTokens = PROFILE_ANSWER_BUDGET, strategy = ContextStrategy.FULL)
                    .copy(profile = profile)
            )
            ProfileCall(title, profile, llm.requests.last(), result.reply, result.tokens.profileTokens)
        }

        log("")
        printPairHeaderRow()
        calls.forEach { call ->
            log(
                String.format(
                    Locale.ROOT,
                    PAIR_ROW_FORMAT,
                    call.title,
                    *PROFILE_FIELDS.map { label -> fieldMark(call.profileBlock, label) }.toTypedArray(),
                    call.profileTokens.toString()
                )
            )
        }
        log("")
        log("в ячейке «—» поля в блоке нет: пустые поля не печатаются — поэтому у второго профиля нет")
        log("ни стека, ни концовки; что именно в блоках, видно ниже, в самих сообщениях запроса")
        calls.forEach { call ->
            log("")
            log("блок профиля «${call.title}» (токенов профиля ${call.profileTokens}):")
            call.profileBlock.lines().forEach(::log)
        }
        log("")
        log("вопрос обоим: «$QUESTION»")
        calls.forEach { log("ответ «${it.title}»: ${it.reply}") }
        if (demoOnLiveApi) {
            calls.forEach { log(confirmLine(it)) }
        } else {
            log("")
            log("ответы подставлены и от профиля не зависят: и различие ответов, и собственную концовку")
            log("каждого профиля видно только в живом режиме (-Pdemo.live=1)")
        }

        assertNotEquals(
            calls.first().profileBlock,
            calls.last().profileBlock,
            "блоки двух профилей различаются"
        )
        assertTrue(
            !calls.last().profileBlock.contains(SIGN_OFF),
            "у второго профиля нет концовки владельца: ${calls.last().profileBlock}"
        )

        if (demoOnLiveApi) {
            val normalized = calls.map { it.reply.trim().lowercase(Locale.ROOT) }
            assertNotEquals(
                normalized.first(),
                normalized.last(),
                "два профиля отвечают на один вопрос по-разному: ${calls.map { it.reply }}"
            )
        }
    }

    /**
     * Этап 3: что ассистент учитывает сам — по тем же запросам, что на этапе 1.
     *
     * Числа не выдуманы: каждая клетка посчитана по тексту запроса — блок профиля по его
     * заголовку, слои памяти по заголовкам их блоков, история по совпадению текстов её
     * сообщений. Видно и главное: профиль приходит из настроек, поэтому он в каждом запросе,
     * а память и история зависят от стратегии.
     */
    @Test
    fun stage3_whatTheAssistantAccountsFor() = runBlocking {
        stage("Этап 3: что ассистент учитывает автоматически (запросы этапа 1)")
        logCannedTransport()

        val runs = strategyRuns()
        val rows = listOf(
            "профиль пользователя" to runs.map { yesNo(it.hasProfile) },
            "рабочая память" to runs.map { yesNo(it.hasWorking) },
            "долговременная память" to runs.map { yesNo(it.hasLongTerm) },
            "история диалога" to runs.map { yesNo(it.historyMessages > 0, it.historyMessages) }
        )
        // Вывод строится из тех же клеток, что и таблица: так он не может разойтись с числами.
        val withProfile = runs.count { it.hasProfile }
        val withWorking = runs.filter { it.hasWorking }.map { it.strategy.title }.joinToString(", ") { "«$it»" }
        val withLongTerm = runs.filter { it.hasLongTerm }.map { it.strategy.title }.joinToString(", ") { "«$it»" }
        val histories = runs.joinToString(", ") { "${it.strategy.title} — ${it.historyMessages}" }

        log("")
        log("вопрос: «$QUESTION», история ${HISTORY.size} сообщ., окно $WINDOW")
        log("")
        log(
            String.format(
                Locale.ROOT,
                MIND_ROW_FORMAT,
                "что уходит в запрос",
                *ContextStrategy.entries.map { it.title }.toTypedArray()
            )
        )
        rows.forEach { (title, cells) ->
            log(String.format(Locale.ROOT, MIND_ROW_FORMAT, title, *cells.toTypedArray()))
        }
        log("")
        log("клетки посчитаны по тексту запросов этапа 1: профиль — по заголовку «$PROFILE_HEADER», память —")
        log("по заголовкам её блоков, история — по совпадению текстов сообщений; профиль и слой считаются целыми блоками,")
        log("а «да (N)» у истории — сколько именно её сообщений дошло до модели")
        log("")
        log("Вывод: без действий пользователя в запрос всегда уходит только профиль — его блок есть в $withProfile")
        log("запросах из ${runs.size}, потому что профиль объявлен в настройках, а не выведен из диалога.")
        log("Память и история так не умеют: рабочую память берут $withWorking, долговременную — $withLongTerm,")
        log("а история уходит всем стратегиям, но у стратегий окна она короче ($histories).")
    }
}

/**
 * Сцена обеих сторон: история к вопросу и профили рядом с ним.
 *
 * История короткая и одна на все стратегии: по ней видно, что стратегия меняет её длину
 * в запросе, но не трогает блок профиля. У стратегий окна окно взято [WINDOW], поэтому
 * часть истории отбрасывается — так в таблице этапа 3 видны разные числа в одной строке.
 */
private val HISTORY = listOf(
    ChatMessage("user", "Работаем над приложением учёта личных расходов."),
    ChatMessage("assistant", "Принял: учёт личных расходов."),
    ChatMessage("user", "Хранилище — Room, проект на Kotlin Multiplatform."),
    ChatMessage("assistant", "Записал: Room и Kotlin Multiplatform."),
    ChatMessage("user", "Графики рисуем на Compose, сторонних библиотек не тянем."),
    ChatMessage("assistant", "Понял: Compose, только свои средства.")
)

/**
 * Вопрос этапов 1 и 2: он один и тот же для всех стратегий и обоих профилей,
 * потому что сравниваются не ответы на разные вопросы, а условия одного запроса.
 */
private const val QUESTION = "Спланируй новую задачу: с чего начать и что не забыть?"

/**
 * Размер окна: меньше истории, поэтому у стратегий окна в запросе видно отброшенные
 * старшие сообщения — иначе строка «история диалога» была бы одинаковой у всех стратегий.
 */
private const val WINDOW = 4

/**
 * Бюджет ответа демонстрации: у модели с рассуждениями они тратят тот же бюджет,
 * что и текст, поэтому он взят с запасом — маленький бюджет давал пустой ответ.
 */
private const val PROFILE_ANSWER_BUDGET = 32_768

/** Сессия — состояние стратегии сжатия; профилю и памяти она не нужна: те лежат по профилю. */
private const val SESSION = "demo-profile"

/** Название профиля владельца в таблицах: профиль по умолчанию описывает его. */
private const val DEFAULT_TITLE = "по умолчанию (KMP)"

/** Название второго профиля: другой человек с другими объявленными предпочтениями. */
private const val PM_TITLE = "продуктовый менеджер"

/**
 * Первая строка блока профиля: по ней блок ищется в тексте запроса.
 * Второй раз это же значение встречается только в [UserProfile.render] — проверка
 * идёт по тексту сообщений, а не по внутренностям агента.
 */
private const val PROFILE_HEADER = "Профиль пользователя (учитывай в каждом ответе):"

/** Персональная настройка профиля владельца: её видно и в запросе, и в конце живого ответа. */
private const val SIGN_OFF = "Ты молодец"

/** Часть сцены, которая живёт между этапами: слои памяти и посчитанные запросы этапа 1. */
private object ProfileScene {

    /**
     * Рабочая память профиля: записи уже есть — иначе блока памяти не было бы в запросе
     * и строка таблицы зависела бы от того, успела ли модель наполнить слой.
     */
    val working = InMemoryMemoryStore().apply {
        put(DEFAULT_PROFILE, listOf(MemoryRecord(MemoryLayer.WORKING.wire, "цель — собрать ТЗ на учёт расходов")))
    }

    /** Долговременная память того же профиля: решение по прошлой задаче — обычная её запись. */
    val longTerm = InMemoryMemoryStore().apply {
        put(DEFAULT_PROFILE, listOf(MemoryRecord(MemoryLayer.LONG_TERM.wire, "решение — хранилище Room")))
    }

    /** Запросы этапа 1: этап 3 берёт их же, чтобы таблица описывала один и тот же прогон. */
    var runs: List<StrategyRun> = emptyList()
}

/**
 * Второй профиль: объявленные предпочтения другого человека.
 *
 * Заполнены только те поля, которые он назвал: стека нет, концовки нет, зато есть запрет
 * на код и жаргон — по этому профилю видно, что пустые поля в блок не печатаются.
 */
private val PM_PROFILE = UserProfile(
    role = "продуктовый менеджер",
    stack = "",
    style = "кратко, без вступлений",
    format = "списком, без кода",
    constraints = "без жаргона и без деталей реализации",
    signOff = ""
)

/** Что показал один прогон этапа 1: стратегия, её запрос к модели, токены профиля и лог агента. */
private class StrategyRun(
    val strategy: ContextStrategy,
    val request: DeepSeekRequest,
    val profileTokens: Int,
    val logs: List<String>
) {

    /** Блок профиля в запросе: по нему видно, что именно ушло в модель. */
    val profileBlock: String get() = profileBlockOf(request)

    /** Есть ли профиль в запросе. */
    val hasProfile: Boolean get() = profileBlock.isNotEmpty()

    /**
     * Сколько сообщений [HISTORY] дошло до модели как история: сравнение по тексту, а не
     * по числам агента. Системное сообщение не считается — его роль играет первое сообщение
     * истории, и без этой оговорки одно и то же сообщение посчиталось бы дважды.
     */
    val historyMessages: Int
        get() = HISTORY.count { history ->
            request.messages.any { it.role != "system" && it.content == history.content }
        }

    /** Есть ли в запросе блок рабочей памяти. */
    val hasWorking: Boolean
        get() = request.messages.any { it.content.startsWith(MemoryExtractor.title(MemoryLayer.WORKING)) }

    /** Есть ли в запросе блок долговременной памяти. */
    val hasLongTerm: Boolean
        get() = request.messages.any { it.content.startsWith(MemoryExtractor.title(MemoryLayer.LONG_TERM)) }
}

/** Один запрос этапа 2: профиль, его блок в запросе и ответ модели. */
private class ProfileCall(
    val title: String,
    val profile: UserProfile,
    val request: DeepSeekRequest,
    val reply: String,
    val profileTokens: Int
) {

    /** Блок профиля, который ушёл в модель этим запросом. */
    val profileBlock: String get() = profileBlockOf(request)
}

/**
 * Оговорка о транспорте для этапов без живых вызовов.
 *
 * Строка режима у демонстраций общая, а первый и третий этапы идут на подставленном
 * транспорте в обоих режимах: иначе в живом прогоне их подпись обещала бы вызовы API,
 * которых не было.
 */
private fun logCannedTransport() {
    if (demoOnLiveApi) {
        log("транспорт этапа: подставленный ответ — здесь меряются запросы, а не ответы; живой API сегодня — только этап 2")
    }
}

/**
 * Прогон этапа 1: один вопрос на всех стратегиях.
 *
 * Транспорт здесь всегда подставленный, даже при `-Pdemo.live=1`: профиль видно по тексту
 * запроса, а живой режим дня — это два запроса этапа 2, и повторять сцену на API значило бы
 * платить за то, что и так проверяется офлайн. Прогон кэшируется: этап 3 разбирает те же
 * запросы, а не строит их заново.
 */
private suspend fun strategyRuns(): List<StrategyRun> = ProfileScene.runs.ifEmpty {
    val runs = ContextStrategy.entries.map { strategy ->
        val llm = CannedProfileClient()
        val logs = mutableListOf<String>()
        val result = profileAgent(llm, logs).run(
            QUESTION,
            options(
                HISTORY,
                maxTokens = PROFILE_ANSWER_BUDGET,
                // Сессия у каждой стратегии своя: сводка — состояние стратегии, и в чужой
                // прогон она попадать не должна; профилю и слоям памяти сессия не нужна.
                sessionId = "$SESSION-${strategy.name.lowercase(Locale.ROOT)}",
                strategy = strategy,
                windowMessages = WINDOW
            ).copy(profile = UserProfile.DEFAULT)
        )
        StrategyRun(strategy, llm.requests.last(), result.tokens.profileTokens, logs)
    }
    ProfileScene.runs = runs
    runs
}

/**
 * Агент демонстрации: слои памяти профиля и лог в собираемые строки.
 *
 * Слои памяти передаются явно, чтобы этап 2 мог взять свои пустые: ему нужен профиль,
 * а не следы прошлых задач.
 */
private fun profileAgent(
    llm: LlmClient,
    logs: MutableList<String>,
    working: MemoryStore = ProfileScene.working,
    longTerm: MemoryStore = ProfileScene.longTerm
) = LlmAgent(llm, logger = AgentLogger { logs += it }, workingMemory = working, longTermMemory = longTerm)

/** Транспорт этапа 2: живой — по флагу, иначе подставленный; запросы пишутся в обоих случаях. */
private fun pairClient(): ProfileRecordingClient =
    ProfileRecordingClient(if (demoOnLiveApi) liveClient() else CannedProfileClient())

/** Запись запросов поверх транспорта: по тексту запроса и проверяется блок профиля. */
private class ProfileRecordingClient(private val delegate: LlmClient) : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return delegate.complete(request)
    }
}

/**
 * Подставленный транспорт: служебные запросы стратегии получают служебные ответы,
 * запрос сцены — подставной ответ. Токены считает тот же счётчик, что и агент,
 * поэтому «факт» в отчёте совпадает с оценкой.
 */
private class CannedProfileClient : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        // Служебный запрос обновления памяти отличается от запроса сцены прежними записями
        // слоёв — по ним он и распознаётся. Сводку отвечать нечем: история сцены короче
        // порога сжатия, поэтому служебного запроса сводки в прогоне не бывает.
        val call = request.messages.last().content
        val answer = if (call.contains(MEMORY_BLOCK_MARKER)) EMPTY_MEMORY else CANNED_REPLY
        val promptTokens = EstimatingTokenCounter.countPrompt(request.messages)
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", answer), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = promptTokens,
                completionTokens = CANNED_REPLY_TOKENS,
                totalTokens = promptTokens + CANNED_REPLY_TOKENS,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(CANNED_REASONING_TOKENS)
            )
        )
    }
}

/** Метка служебного запроса обновления памяти: прежние записи слоёв. */
private const val MEMORY_BLOCK_MARKER = "Прежняя память —"

/** Пустое обновление памяти: сцена демонстрации память не трогает — этап про профиль. */
private const val EMPTY_MEMORY = """{"memory":[]}"""

/** Подставленный ответ сцены: непустой и одинаковый — различие профилей проверяет живой режим. */
private const val CANNED_REPLY =
    "План: 1) уточнить цель задачи; 2) разбить на шаги; 3) проверить результат на устройстве."

/** Шапка таблицы этапа 1: те же колонки переносим в README. */
private fun printProfileHeaderRow(): Unit = log(
    String.format(
        Locale.ROOT,
        PROFILE_ROW_FORMAT,
        "стратегия",
        "профиль в запросе",
        "токенов профиля",
        "сообщений в запросе"
    )
)

/**
 * Формат строки этапа 1: стратегия, есть ли профиль, его токены и размер запроса.
 * Все колонки выровнены по левому краю: значения короткие, а заголовки длинные —
 * так заголовок стоит прямо над своим числом и таблица читается без правки в README.
 */
private const val PROFILE_ROW_FORMAT = "%-20s %-18s %-17s %-18s"

/** Шапка таблицы этапа 2: строка на профиль, колонки — его поля и цена профиля. */
private fun printPairHeaderRow(): Unit = log(
    String.format(
        Locale.ROOT,
        PAIR_ROW_FORMAT,
        "профиль",
        "кто",
        "стек",
        "стиль",
        "формат",
        "ограничения",
        "концовка",
        "токенов профиля"
    )
)

/**
 * Формат строки этапа 2: профиль, какие его поля есть в блоке, и токены профиля.
 * Поля отмечаются наличием, а не значением: значения длинные, и обрезать их в таблице
 * значило бы показывать не то, что ушло в модель, — сами блоки печатаются ниже целиком.
 */
private const val PAIR_ROW_FORMAT = "%-22s %-6s %-6s %-6s %-6s %-12s %-10s %-15s"

/** Формат таблицы этапа 3: строка — что уходит в запрос, колонка — стратегия. */
private const val MIND_ROW_FORMAT = "%-22s %-16s %-16s %-16s %-16s %-16s"

/**
 * Подписи полей блока профиля: по ним демонстрация читает запрос и говорит, какие поля
 * в него ушли. Формат блока задаёт [UserProfile.render], поэтому подписи повторены ровно
 * как в нём — иначе читать запрос было бы нечем.
 */
private val PROFILE_FIELDS = listOf("кто", "стек", "стиль", "формат", "ограничения", "в конце каждого ответа")

/** Есть ли поле в блоке профиля этого запроса: строка ищется по подписи поля. */
private fun fieldMark(block: String, label: String): String =
    if (block.lineSequence().any { it.startsWith("- $label: ") }) "есть" else "—"

/** Клетка «да»/«нет»; с числом — сколько именно сообщений дошло до модели. */
private fun yesNo(present: Boolean, count: Int? = null): String = when {
    !present -> "нет"
    count != null -> "да ($count)"
    else -> "да"
}

/** Блок профиля в запросе: ищем по тексту сообщения — так его видит и человек, и проверка. */
private fun profileBlockOf(request: DeepSeekRequest): String =
    request.messages.firstOrNull { it.content.contains(PROFILE_HEADER) }?.content.orEmpty()

/**
 * Строка о собственной концовке профиля: сама настройка объявлена словами, поэтому ищем
 * в ответе её личный маркер [SIGN_OFF]. Отсутствие концовки в выводе видно, но проверкой
 * не падает: концовку может не написать модель, и это не про подстановку профиля.
 */
private fun confirmLine(call: ProfileCall): String {
    if (!call.profile.signOff.contains(SIGN_OFF)) {
        return "у профиля «${call.title}» концовки нет — подставлять нечего"
    }
    return "концовка «$SIGN_OFF» в ответе: ${if (call.reply.contains(SIGN_OFF)) "есть" else "нет (модель её не написала)"}"
}
