package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Демонстрация дня 14: инварианты проекта — правила, которые ассистент нарушать не имеет права.
 *
 * Инварианты лежат в своём сторе ([InvariantStore]), а не в переписке: блок правил собирает
 * [InvariantRules.render], и агент подставляет его системным сообщением в каждый запрос —
 * рядом с профилем и состоянием задачи. Поэтому правило действует и в новом чате, где о нём
 * никто не говорил, и его нельзя вытеснить из диалога сжатием истории: в истории лежат только
 * реплики, а правила живут на сервере.
 *
 * Конфликт запроса с правилом ищет служебный вызов [InvariantGuard]: он получает инварианты,
 * последние сообщения и вопрос и отвечает строгим JSON с вердиктом. Вердикт проверяет код:
 * по нему агент добавляет в запрос ещё одно системное сообщение — правило отказа. Отказывается
 * по этой причине уже модель, поэтому отказ объясним: он называет вид нарушения (его назвала
 * проверка), формулировку правила (она взята из стора) и причину (её написал разбор вердикта).
 *
 * Четыре этапа идут на одной сцене — экране настроек в приложении учёта расходов. Этапы 1–3
 * идут на подставленном транспорте: вердикты проверки заданы сценой, поэтому прогон повторяем
 * и сети не требует. Живые вызовы дня (`-Pdemo.live=1`) стоят только четвёртого этапа: там
 * сравниваются формулировки, а вердикт обязан быть от модели — подставленный вердикт показал бы
 * конфликт, которого проверка не находила.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InvariantDemoTest {

    /**
     * Оговорка о транспорте для этапов без живых вызовов: строка режима у демонстраций общая,
     * а первые три этапа идут на подставленных вердиктах в обоих режимах — иначе в живом прогоне
     * их подпись обещала бы вызовы API, которых не было.
     */
    private fun noteCannedTransport() {
        if (demoOnLiveApi) {
            log("транспорт этапа: подставленный ответ — здесь проверяются запросы и вердикты, а не формулировки; живой API сегодня — только этап 4")
        }
    }

    /**
     * Этап 1: инварианты живут вне диалога и уходят в каждый запрос.
     *
     * В таблице — чего стоит блок и чем кончилась проверка на каждом ходу; отдельно печатается
     * сам блок, который видит модель. История диалога при этом растёт только репликами: правила
     * не сообщение диалога, а системное сообщение запроса, поэтому их видит и новый чат.
     */
    @Test
    fun stage1_invariantsOutOfDialogInEveryRequest() = runBlocking {
        stage("Этап 1: инварианты вне диалога и в каждом запросе (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryInvariantStore()
        val writer = InvariantWriter(store)
        assertNull(store.get(DEFAULT_PROFILE), "инварианты ещё не задавали: стор пуст")
        showSnapshot(writer)
        log("")

        val llm = InvariantDemoClient(CANNED_REPLY, ArrayDeque(INVARIANT_SCENE.map { ALLOWED_VERDICT }))
        val dialog = mutableListOf<ChatMessage>()
        val runs = INVARIANT_SCENE.map { message -> invariantTurn(store, llm, dialog, message) }

        printRequestHeader()
        runs.forEach(::printRequestRow)
        log("")
        log("блок инвариантов, ушедший в запрос последнего хода:")
        runs.last().block.lines().forEach(::log)
        log("")
        log("по отчёту последнего хода: ${runs.last().report.tokens} ток. — блок инвариантов и сообщение проверки")
        log("история диалога: ${dialog.size} сообщений за ${INVARIANT_SCENE.size} хода — по два на ход; инварианты в неё не пишутся")

        runs.forEach { run ->
            assertEquals("allowed", run.report.verdict, "запрос в рамках правил: «${run.message}»")
            assertTrue(run.report.violated.isEmpty(), "нарушений нет: ${run.report.violated}")
            assertTrue(
                run.block.startsWith(INVARIANT_HEADER),
                "блок инвариантов уходит в каждый запрос: ${run.block}"
            )
            assertEquals(1, run.invariantMessages, "в запросе он один и он не реплика диалога")
            assertEquals(1, run.guardRequests, "на каждый запрос — одна служебная проверка")
        }
        assertEquals(INVARIANT_SCENE.size * 2, dialog.size, "инварианты в историю диалога не попадают")
        assertTrue(dialog.none { it.content.startsWith(INVARIANT_HEADER) }, "их нет и среди реплик: $dialog")
    }

    /**
     * Этап 2: запрос против инварианта — проверка называет нарушение, а в запрос уходит
     * правило отказа.
     *
     * Сцена просит то, что запрещено: переписать агент на Java и RxJava (стек), отдавать данные
     * во внешний сервис (бизнес-правило) и развести общий код по нативным модулям (архитектура).
     * Сообщение проверки печатается целиком: это и есть то, по чему модель отказывается.
     */
    @Test
    fun stage2_requestAgainstInvariant() = runBlocking {
        stage("Этап 2: запрос против инварианта — вердикт проверки и правило отказа (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryInvariantStore()
        val runs = CONFLICTS.map { case ->
            case to invariantTurn(
                store,
                InvariantDemoClient(CANNED_REPLY, ArrayDeque(listOf(case.guardReply))),
                mutableListOf(),
                case.message
            )
        }

        printConflictHeader()
        runs.forEach { (case, run) -> printConflictRow(case, run) }
        log("")
        log("вид нарушения и причину называет служебная проверка, формулировку правила агент берёт")
        log("из стора, а отказывается уже модель — по сообщению проверки в запросе")

        runs.forEach { (case, run) ->
            assertEquals("violated", run.report.verdict, "конфликт найден: «${case.message}»")
            assertEquals(case.kinds, run.report.violated, "нарушенные виды называет проверка")
            assertTrue(run.block.startsWith(INVARIANT_HEADER), "блок инвариантов уходит и в такой запрос")
            assertTrue(
                run.check.contains(invariantValue(case.kinds.single())),
                "правило отказа называет нарушенный инвариант: ${run.check}"
            )
            assertTrue(
                run.report.reason.orEmpty().isNotBlank(),
                "причина от проверки приходит строкой, а не молчанием"
            )
        }
        assertEquals(
            Invariant.DEFAULT,
            store.invariantsOrDefault(),
            "отказ ничего в сторе не переписывает: правила остаются прежними"
        )
    }

    /**
     * Этап 3: запрос в рамках инвариантов — проверка это подтверждает, а модель отвечает
     * обычным ходом.
     *
     * Второй запрос прямо повторяет инвариант «решение»: правило не мешает работе, когда работа
     * идёт по нему.
     */
    @Test
    fun stage3_requestWithinInvariants() = runBlocking {
        stage("Этап 3: запрос в рамках инвариантов (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryInvariantStore()
        val runs = WITHIN.map { message ->
            invariantTurn(store, InvariantDemoClient(CANNED_REPLY, ArrayDeque(listOf(ALLOWED_VERDICT))), mutableListOf(), message)
        }

        printVerdictHeader()
        runs.forEach(::printVerdictRow)
        log("")
        log("сообщение проверки в запросе: ${runs.last().check}")
        log("ответ модели: ${runs.last().result.reply}")

        runs.forEach { run ->
            assertEquals("allowed", run.report.verdict, "запрос правилам не противоречит: «${run.message}»")
            assertTrue(run.report.violated.isEmpty(), "и нарушенных видов нет: ${run.report.violated}")
            assertEquals(InvariantGuard.ALLOWED_MESSAGE, run.check, "сообщение проверки уходит модели")
            assertTrue(run.block.startsWith(INVARIANT_HEADER), "блок инвариантов на месте: ${run.block}")
        }
    }

    /**
     * Этап 4: отказ на конфликте и ответ в рамках правил — живой прогон.
     *
     * Живёт только за флагом (`-Pdemo.live=1`): здесь сравниваются формулировки, а не механика.
     * Два хода сцены — нарушающий запрос и запрос в рамках правил; каждый ход стоит двух
     * обращений к модели: служебная проверка ([InvariantGuard]) и сам ответ. Отказ и название
     * нарушенного инварианта печатаются строкой: формулировку выбирает модель, и жёстко её
     * требовать значило бы проверять не механику, а стиль.
     */
    @Test
    fun stage4_liveRefusalAndAnswerWithinInvariants() = runBlocking {
        assumeTrue("этап идёт только в живом режиме: ./gradlew :agent:demoLogs -Pdemo.live=1", demoOnLiveApi)
        stage("Этап 4: отказ на конфликте и ответ в рамках правил (живой API)")

        val store = InMemoryInvariantStore()
        val conflict = CONFLICTS.first()
        val llm = InvariantDemoClient(CANNED_REPLY, ArrayDeque(), liveClient())
        val refused = invariantTurn(store, llm, mutableListOf(), conflict.message)
        val within = invariantTurn(store, llm, mutableListOf(), WITHIN.first())

        printVerdictHeader()
        printVerdictRow(refused)
        printVerdictRow(within)
        log("")
        log("правило отказа, ушедшее модели на конфликте: ${refused.check}")
        log("")
        log("ответ на нарушающий запрос: ${refused.result.reply}")
        log("отказ в нём: ${refusalNote(refused.result.reply)}")
        log(
            "названный инвариант «${kindTitle(conflict.kinds.single())}»: " +
                mentions(invariantValue(conflict.kinds.single()), refused.result.reply)
        )
        log("")
        log("ответ на запрос в рамках: ${within.result.reply}")
        log("вызовов к модели на этапе: ${llm.requests.size} (служебных проверок: ${llm.guardCalls})")

        assertEquals("violated", refused.report.verdict, "живая проверка нашла конфликт: «${conflict.message}»")
        assertTrue(
            refused.report.violated.containsAll(conflict.kinds),
            "и назвала нарушенный вид: ${refused.report.violated}"
        )
        assertTrue(refused.block.startsWith(INVARIANT_HEADER), "блок инвариантов уходит и в живой запрос")
        assertEquals("allowed", within.report.verdict, "запрос в рамках правил проверка пропускает")
        assertTrue(within.report.violated.isEmpty(), "нарушенных видов нет: ${within.report.violated}")
        assertEquals(InvariantGuard.ALLOWED_MESSAGE, within.check, "и сообщение проверки уходит модели")
    }
}

/**
 * Случай конфликта: нарушающий запрос, ответ проверки на него и ожидаемые виды нарушений.
 *
 * @param message Реплика человека: она же колонка «запрос» в таблице.
 * @param guardReply Строгий JSON [InvariantGuard] — так отвечает проверка, увидевшая конфликт.
 * @param kinds Виды нарушенных инвариантов: сверяются с отчётом, поэтому записаны отдельно
 *        от ответа проверки — демонстрация проверяет агента, а не повторяет его разбор.
 */
private class ConflictCase(val message: String, val guardReply: String, val kinds: List<String>)

/** Ответ подставленного транспорта: этапы 1–3 проверяют запросы, а не формулировки ответа. */
private const val CANNED_REPLY = "Принял: продолжаю в рамках правил проекта, начинать заново не нужно."

/** Вердикт проверки, когда запрос правилам не противоречит. */
private const val ALLOWED_VERDICT = """{"verdict":"allowed"}"""

/**
 * Бюджет ответа сцены: у модели с рассуждениями они тратят тот же бюджет, что и текст.
 * Служебной проверке бюджет свой ([InvariantGuard]): на маленьком она возвращает пустой ответ.
 */
private const val INVARIANT_ANSWER_BUDGET = 32_768

/**
 * Сцена этапа 1: три хода в рамках правил — в каждом запросе виден блок инвариантов.
 * Сессии у инвариантов нет: они лежат по профилю, а не по чату.
 */
private val INVARIANT_SCENE = listOf(
    "Собери план: экран настроек в приложении учёта расходов.",
    "Опиши, как этот экран ляжет в общий код.",
    "Что дальше по плану?"
)

/** Запросы этапа 3: обычная работа и работа прямо по инварианту «решение». */
private val WITHIN = listOf(
    "Добавь экран настроек в общий код, платформенные адаптеры оставь тонкими.",
    "Храни инварианты на сервере и подставляй их системным сообщением в каждый запрос."
)

/** Запросы этапа 2: каждый нарушает своё правило, и каждое нарушение называет проверка. */
private val CONFLICTS = listOf(
    ConflictCase(
        "Перепиши агент на Java и RxJava — так привычнее.",
        """{"verdict":"violated","violations":[{"kind":"${InvariantKind.STACK.wire}",""" +
            """"reason":"просят код на Java и RxJava, а проект только на Kotlin"}]}""",
        listOf(InvariantKind.STACK.wire)
    ),
    ConflictCase(
        "Отдавай историю диалогов во внешний сервис аналитики.",
        """{"verdict":"violated","violations":[{"kind":"${InvariantKind.BUSINESS.wire}",""" +
            """"reason":"данные пользователя уходят третьим лицам, а храниться они должны на своём сервере"}]}""",
        listOf(InvariantKind.BUSINESS.wire)
    ),
    ConflictCase(
        "Разведи общий код по отдельным нативным модулям под каждую платформу.",
        """{"verdict":"violated","violations":[{"kind":"${InvariantKind.ARCHITECTURE.wire}",""" +
            """"reason":"общий код перестаёт быть общим: правила расходятся по платформам"}]}""",
        listOf(InvariantKind.ARCHITECTURE.wire)
    )
)

/** Вопрос для служебного запроса, по которому берётся признак: ответ этого запроса не разбирается. */
private const val PROBE_QUESTION = "проверка"

/**
 * Начало сообщения проверки: так агент помечает системное сообщение с вердиктом. Берётся
 * у самой проверки ([InvariantGuard.ALLOWED_MESSAGE]) — шаблон демонстрация не переписывает
 * своей копией, а ищет сообщение по началу и печатает его целиком.
 */
private val CHECK_MARKER: String = InvariantGuard.ALLOWED_MESSAGE.substringBefore(':')

/**
 * Заголовок блока инвариантов: берётся из [InvariantRules.render], чтобы демонстрация
 * не повторяла слова промпта своей копией.
 */
private val INVARIANT_HEADER: String = InvariantRules.render(Invariant.DEFAULT).lineSequence().first()

/**
 * Признак служебного запроса проверки: инструкцию пишет [InvariantGuard], и первая её строка —
 * единственное, чем этот запрос отличается от запроса сцены. Берётся у самой проверки,
 * а не переписывается в демонстрации.
 */
private val GUARD_CALL_MARKER: String = InvariantGuard()
    .request(DEMO_MODEL, Invariant.DEFAULT, listOf(ChatMessage("user", PROBE_QUESTION)))
    .messages.first()
    .content
    .lineSequence()
    .first()

/** Шапка таблицы этапа 1: чего стоит блок в запросе и чем кончилась проверка. */
private fun printRequestHeader(): Unit = log(
    String.format(Locale.ROOT, REQUEST_ROW_FORMAT, "запрос", "токенов блока", "вердикт", "нарушено")
)

/** Формат строки этапа 1: «токенов блока» — оценка локального счётчика по самому блоку. */
private const val REQUEST_ROW_FORMAT = "%-62s %14s %-10s %-12s"

/** Строка таблицы этапа 1. */
private fun printRequestRow(run: InvariantTurn): Unit = log(
    String.format(
        Locale.ROOT,
        REQUEST_ROW_FORMAT,
        run.message,
        EstimatingTokenCounter.count(run.block).toString(),
        verdictText(run.report.verdict),
        violationsText(run.report.violated)
    )
)

/**
 * Шапка таблицы этапа 2: вердикт, нарушенные виды, причина проверки и то, что ушло модели.
 * Последняя колонка — сообщение проверки целиком: по нему и видно, чего от модели хотят.
 */
private fun printConflictHeader(): Unit = log(
    String.format(Locale.ROOT, CONFLICT_ROW_FORMAT, "запрос", "вердикт", "нарушенные виды", "причина от проверки", "что ушло модели")
)

/** Формат строки этапа 2: сообщение проверки не обрезаем — оно и есть предмет этапа. */
private const val CONFLICT_ROW_FORMAT = "%-50s %-10s %-17s %-46s %s"

/** Строка таблицы этапа 2. */
private fun printConflictRow(case: ConflictCase, run: InvariantTurn): Unit = log(
    String.format(
        Locale.ROOT,
        CONFLICT_ROW_FORMAT,
        case.message,
        verdictText(run.report.verdict),
        violationsText(run.report.violated),
        run.report.reason ?: "причина не названа",
        run.check
    )
)

/** Шапка таблицы этапов 3 и 4: чем кончилась проверка каждого запроса. */
private fun printVerdictHeader(): Unit = log(
    String.format(Locale.ROOT, VERDICT_ROW_FORMAT, "запрос", "вердикт", "нарушено")
)

/** Формат строки этапов 3 и 4: нарушенных видов у запроса в рамках правил быть не должно. */
private const val VERDICT_ROW_FORMAT = "%-64s %-10s %-12s"

/** Строка таблицы этапов 3 и 4. */
private fun printVerdictRow(run: InvariantTurn): Unit = log(
    String.format(
        Locale.ROOT,
        VERDICT_ROW_FORMAT,
        run.message,
        verdictText(run.report.verdict),
        violationsText(run.report.violated)
    )
)

/**
 * Снимок стора: сколько правил видит ассистент и каких видов. Каталог видов уезжает в снимке
 * ([InvariantKind.info]), поэтому своей копии подписей демонстрация не держит.
 */
private fun showSnapshot(writer: InvariantWriter) {
    val snapshot = writer.snapshot()
    log("инвариантов в снимке: ${snapshot.invariants.size}, видов: ${snapshot.kinds.size}")
    snapshot.invariants.forEach { log("- ${kindTitle(it.kind)}: ${it.value}") }
    log("каталог видов: ${snapshot.kinds.joinToString(", ") { "${it.title} (${it.kind})" }}")
}

/** Вердикт проверки, как он виден в отчёте: проверки не было — так и печатаем. */
private fun verdictText(verdict: String?): String = verdict ?: "проверки не было"

/** Нарушенные виды по-русски; пусто — прочерк: эта колонка и есть список нарушений. */
private fun violationsText(kinds: List<String>): String = kinds.map(::kindTitle).joinToString(", ").ifEmpty { "—" }

/** Название вида: неизвестное значение печатаем как есть — так ошибку видно, а не прячем. */
private fun kindTitle(kind: String?): String = kind?.let { InvariantKind.ofWire(it)?.title ?: it } ?: "—"

/** Формулировка правила проекта этого вида: её и называет правило отказа. */
private fun invariantValue(kind: String): String = requireNotNull(
    Invariant.DEFAULT.firstOrNull { it.kind == kind }
) { "в умолчаниях проекта нет вида $kind" }.value

/**
 * Видно ли в ответе название нарушенного инварианта: ищем значимые слова его формулировки.
 * Это подсказка человеку, а не проверка — формулировку отказа выбирает модель.
 */
private fun mentions(value: String, reply: String): String {
    val words = invariantWords(value)
    if (words.isEmpty()) return "сравнивать не с чем: правило не названо"
    val text = reply.lowercase(Locale.ROOT)
    val found = words.filter { text.contains(it) }
    return "${found.size} из ${words.size}" +
        if (found.isEmpty()) " — правило в ответе не названо" else " (${found.joinToString(", ")})"
}

/** Признаки отказа в ответе: по ним видно, отказался ассистент или выполнил просьбу. */
private val REFUSAL_MARKERS = listOf("отказ", "наруша", "не могу", "не буду", "противореч", "не стану")

/** Отказался ли ассистент: тоже подсказка человеку — формулировку выбирает модель. */
private fun refusalNote(reply: String): String {
    val text = reply.lowercase(Locale.ROOT)
    val found = REFUSAL_MARKERS.filter { text.contains(it) }
    return if (found.isEmpty()) "прямого отказа в ответе не видно" else "отказ: ${found.joinToString(", ")}"
}

/**
 * Значимые слова текста: короткие служебные слова сравнение только зашумляют. Порог здесь
 * ниже, чем у соседних демонстраций, из-за «Java» — четыре буквы, а слово в правиле ключевое.
 */
private fun invariantWords(text: String): List<String> =
    text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.distinct()

/** Агент демонстрации: инварианты лежат в общем сторе [store], поэтому видны каждому ходу. */
private fun invariantAgent(llm: LlmClient, store: InvariantStore) =
    LlmAgent(llm, logger = AgentLogger { }, invariantStore = store)

/**
 * Один ход сцены: вопрос человека уходит агенту, а из ответа берётся отчёт об инвариантах.
 *
 * Диалог наполняется после ответа, поэтому в запрос уходит история прошлых ходов — как в чате.
 */
private suspend fun invariantTurn(
    store: InvariantStore,
    llm: InvariantDemoClient,
    dialog: MutableList<ChatMessage>,
    message: String
): InvariantTurn {
    val guardsBefore = llm.guardCalls
    val result = invariantAgent(llm, store).run(
        message,
        options(dialog.toList(), maxTokens = INVARIANT_ANSWER_BUDGET, strategy = ContextStrategy.FULL)
    )
    val run = InvariantTurn(
        message = message,
        result = result,
        request = llm.requests.last { it.messages.last().content == message },
        guardRequests = llm.guardCalls - guardsBefore
    )
    dialog += ChatMessage("user", message)
    dialog += ChatMessage("assistant", result.reply)
    return run
}

/** Что показал один ход сцены. */
private class InvariantTurn(
    /** Реплика человека: она же колонка «запрос» в таблицах. */
    val message: String,
    val result: AgentResult,
    /** Запрос сцены: в нём виден блок инвариантов и сообщение проверки. */
    val request: DeepSeekRequest,
    /** Сколько служебных проверок сделал этот ход. */
    val guardRequests: Int
) {

    /** Отчёт об инвариантах: что ушло в запрос, чем кончилась проверка и чего она стоила. */
    val report: InvariantReport get() = result.tokens.invariants

    /** Блок инвариантов в запросе: он начинается с заголовка [INVARIANT_HEADER] и он один такой. */
    val block: String get() = request.messages.firstOrNull { it.content.startsWith(INVARIANT_HEADER) }?.content.orEmpty()

    /** Сообщение проверки; пусто — проверки не было и правила отказа модель не получила. */
    val check: String get() = request.messages.firstOrNull { it.content.startsWith(CHECK_MARKER) }?.content.orEmpty()

    /** Сколько сообщений запроса — блоки инвариантов: в истории их нет, там реплики диалога. */
    val invariantMessages: Int get() = request.messages.count { it.content.startsWith(INVARIANT_HEADER) }
}

/**
 * Транспорт демонстрации: подставленный ответ и запись запросов.
 *
 * Служебная проверка отличается от запроса сцены началом инструкции: её пишет [InvariantGuard],
 * и по этому признаку транспорт и отвечает — сцене [reply], проверке очередной вердикт
 * из [verdicts], а если вердиктов не осталось, «нарушений нет». Токены запроса считает тот же
 * счётчик, что и агент, поэтому «факт» в отчёте равен оценке; токены ответа постоянные.
 *
 * Живой режим ([live]) отвечает настоящей моделью: подставленных вердиктов в нём нет —
 * иначе конфликт показывал бы подставленный вердикт, а не живая проверка. Вызовы считаются
 * так же, по ним видно, чего стоил этап.
 */
private class InvariantDemoClient(
    private val reply: String = CANNED_REPLY,
    private val verdicts: ArrayDeque<String> = ArrayDeque(),
    private val live: LlmClient? = null
) : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    /** Сколько служебных проверок ушло этим транспортом. */
    var guardCalls = 0
        private set

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        val guard = request.messages.first().content.startsWith(GUARD_CALL_MARKER)
        if (guard) guardCalls++
        live?.let { return it.complete(request) }
        val answer = if (guard) verdicts.removeFirstOrNull() ?: ALLOWED_VERDICT else reply
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
