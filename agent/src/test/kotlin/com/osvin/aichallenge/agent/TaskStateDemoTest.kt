package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Демонстрация дня 13: задача как конечный автомат — путь по этапам, отбитые переходы,
 * пауза на любом этапе и продолжение без повторных объяснений.
 *
 * Этап, текущий шаг и ожидаемое действие ведёт агент: состояние уходит в каждый запрос
 * системным сообщением, поэтому модель продолжает работу с того места, где остановилась.
 * Переход предлагает модель, а принимает код по таблице ([TaskRules]) — без неё модель
 * закрывает задачу, не проверив результат. Задачу заводит человек явным действием
 * ([TaskWriter]); до этого её не ведёт никто, и служебных вызовов к модели нет вовсе.
 *
 * Четыре этапа идут на одной сцене — сборке экрана расходов. Транспорт подставленный:
 * ответы заданы сценой, поэтому прогон повторяем и сети не требует. Живые вызовы дня
 * (`-Pdemo.live=1`) стоят только четвёртого этапа: там сравниваются формулировки ответов.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TaskStateDemoTest {

    /**
     * Оговорка о транспорте для этапов без живых вызовов: строка режима у демонстраций общая,
     * а первые три этапа идут на подставленном транспорте в обоих режимах — иначе в живом
     * прогоне их подпись обещала бы вызовы API, которых не было.
     */
    private fun noteCannedTransport() {
        if (demoOnLiveApi) {
            log("транспорт этапа: подставленный ответ — здесь проверяются переходы и пауза, а не формулировки; живой API сегодня — только этап 4")
        }
    }

    /**
     * Этап 1: путь планирование → выполнение → проверка → назад в работу → проверка → готово.
     *
     * Первый ход идёт до старта задачи: ни блока состояния в запросе, ни служебного вызова.
     * Дальше каждый ход печатает, что предложила модель, что решил код и куда встало состояние.
     */
    @Test
    fun stage1_taskPathThroughStages() = runBlocking {
        stage("Этап 1: путь задачи по этапам (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryTaskStateStore()
        val writer = TaskWriter(store)
        val dialog = mutableListOf<ChatMessage>()
        val llm = TaskDemoClient(CANNED_REPLY, ArrayDeque(PATH.map { it.proposal }))

        val beforeStart = turn(store, llm, dialog, ASK_BEFORE_START, PATH_SESSION)
        log("до старта задачи: служебных вызовов ${beforeStart.serviceCalls}, блок состояния в запросе: " +
            if (beforeStart.block.isEmpty()) "нет" else "есть")
        val started = writer.start(PATH_SESSION)
        log("задача взята в работу явным действием: этап ${stageTitle(started.task?.stage)}; повторный старт " +
            "её не сбрасывает: ${writer.start(PATH_SESSION).task == started.task}")
        log("")

        val rows = PATH.map { scene -> turn(store, llm, dialog, scene.message, PATH_SESSION) }
        printPathHeader()
        rows.forEachIndexed { index, run -> printPathRow(PATH[index], run) }
        log("")
        log("«принято»: «да» — этап сменился, «да (без перехода)» — уточнён шаг внутри этапа.")
        log("")
        log("блок состояния, ушедший в запрос на последнем ходу:")
        rows.last().block.lines().forEach(::log)
        val updateTokens = rows.sumOf { it.task.updateTokens }
        val updateCost = rows.mapNotNull { it.task.updateCostUsd }.takeIf { it.size == rows.size }?.sum()
        log("служебные вызовы: ${rows.size} (по одному на ход), $updateTokens ток., цена ${costText(updateCost)}")

        assertEquals(0, beforeStart.serviceCalls, "пока задачи нет, служебных вызовов нет вовсе")
        assertEquals(0, beforeStart.task.updateTokens, "и обновлять нечего: состояние не ведётся")
        assertNull(beforeStart.task.stage, "в отчёте задачи нет: ${beforeStart.task}")
        assertTrue(beforeStart.block.isEmpty(), "блока состояния в запросе нет: ${beforeStart.block}")
        assertEquals(PATH_EXPECTED.map { it.state }, rows.map { it.stateAfter }, "состояние идёт по таблице переходов")
        assertEquals(PATH_EXPECTED.map { it.moved }, rows.map { it.task.moved }, "переходом считается смена этапа")
        assertEquals(rows.map { it.stateAfter?.render() }, rows.map { it.block }, "в запрос уходит состояние этого хода")
    }

    /**
     * Этап 2: запрещённые переходы: планирование → проверка, выполнение → готово,
     * готово → выполнение. Каждый отклонён с причиной, состояние при отказе не меняется.
     */
    @Test
    fun stage2_rejectedTransitions() = runBlocking {
        stage("Этап 2: отбитые переходы (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryTaskStateStore()
        val writer = TaskWriter(store)
        val rejected = REJECTIONS.mapIndexed { index, case ->
            rejectionRun(store, writer, case, "$REJECT_SESSION-${index + 1}")
        }

        printRejectHeader()
        rejected.forEach(::printRejectRow)
        log("")
        log("отказ называет причину, поэтому работа продолжается с того же шага, а не с начала")

        rejected.forEach { run ->
            assertEquals(1, run.task.rejected, "запрещённый переход отклонён один раз: «${run.case.message}»")
            assertEquals(
                run.before,
                run.turn.stateAfter,
                "состояние не изменилось: ни этап, ни шаг, ни ожидаемое действие"
            )
            assertTrue(run.task.rejectReason.orEmpty().isNotBlank(), "отказ приходит причиной, а не молчанием")
        }
    }

    /**
     * Этап 3: пауза на каждом из четырёх этапов.
     *
     * Пауза — решение человека ([TaskWriter]), а не переход: на ней служебного вызова нет,
     * состояние заморожено, а в запросе есть строка про паузу. После её снятия состояние
     * то же, поэтому работа продолжается с того же шага.
     */
    @Test
    fun stage3_pauseOnEveryStage() = runBlocking {
        stage("Этап 3: пауза на каждом этапе (подставленный ответ)")
        noteCannedTransport()

        val store = InMemoryTaskStateStore()
        val writer = TaskWriter(store)
        val rows = TaskStage.entries.map { target -> pauseRun(store, writer, target) }

        printPauseHeader()
        rows.forEach(::printPauseRow)
        log("")
        log("блок состояния, ушедший в запрос на паузе (этап «${rows[1].target.title}»):")
        rows[1].turn.block.lines().forEach(::log)

        rows.forEach { run ->
            assertEquals(0, run.turn.serviceCalls, "на паузе служебного вызова нет вовсе: ${run.target.title}")
            assertEquals(0, run.turn.task.updateTokens, "и токенов обновления не тратится: ${run.target.title}")
            assertEquals(run.before.copy(paused = true), run.onPause, "состояние заморожено на месте")
            assertTrue(run.turn.block.contains(pauseLine(run.before)), "в запросе есть строка про паузу")
            assertEquals(run.before, run.afterResume, "после снятия паузы состояние то же")
        }
    }

    /**
     * Этап 4: продолжение после паузы коротким сообщением.
     *
     * Задачу до середины выполнения доводит подставленный транспорт, а живые вызовы этапа —
     * ответ на паузе (служебного вызова на паузе нет) и ход после её снятия: всего два-три.
     * Проверяемо то, что можно проверить: пауза состояние не сдвинула, а возобновлённый запрос
     * несёт тот же шаг и ожидаемое действие. Видно ли продолжение в самом ответе — печатается
     * строкой: формулировку выбирает модель.
     */
    @Test
    fun stage4_resumeWithoutRepeatingTheStory() = runBlocking {
        stage("Этап 4: продолжение после паузы без повторных объяснений")
        logCannedTransport("до середины выполнения задачу доводит подставленный транспорт")

        val store = InMemoryTaskStateStore()
        val writer = TaskWriter(store)
        val dialog = mutableListOf<ChatMessage>()
        val setup = listOf(PATH[0], PATH[1], PATH[2])
        val setupClient = TaskDemoClient(CANNED_REPLY, ArrayDeque(setup.map { it.proposal }))
        writer.start(RESUME_SESSION)
        setup.forEach { scene -> turn(store, setupClient, dialog, scene.message, RESUME_SESSION) }
        val middle = requireNotNull(store.get(RESUME_SESSION))
        log("задача на середине выполнения: этап ${stageTitle(middle.stage)}, ${digest(middle)}")
        log("")

        assertIs<TaskWrite.Written>(writer.setPaused(RESUME_SESSION, true), "пауза ставится, когда задача есть")
        val pausedTurn = resumeTurn(store, dialog, PAUSE_QUESTION, CANNED_PAUSED_REPLY)
        val onPause = requireNotNull(store.get(RESUME_SESSION))
        val offPause = assertIs<TaskWrite.Written>(
            writer.setPaused(RESUME_SESSION, false),
            "пауза снимается у той же задачи"
        )
        val resumed = resumeTurn(store, dialog, RESUME_QUESTION, CANNED_RESUME_REPLY, ArrayDeque(listOf(RESUME_PROPOSAL)))

        log("на паузе («$PAUSE_QUESTION») в запросе:")
        pausedTurn.block.lines().forEach(::log)
        log("")
        printResumeHeader()
        printResumeRow("пауза", PAUSE_QUESTION, pausedTurn)
        printResumeRow("продолжение", RESUME_QUESTION, resumed)
        log("")
        log("ответ на паузе: ${pausedTurn.result.reply}")
        log("ответ после паузы: ${resumed.result.reply}")
        log("возобновлённый запрос несёт тот же шаг «${middle.step}» и ожидаемое действие «${middle.expectedAction}»")
        log("продолжение в ответе: ${continuesFrom(resumed.result.reply, middle)}")
        log("общих слов в двух ответах: ${overlapText(pausedTurn.result.reply, resumed.result.reply)}")
        val calls = pausedTurn.requestCount + resumed.requestCount
        log("вызовов на этапе: $calls")
        if (demoOnLiveApi) {
            log("оба продолжения пришли от живой модели: их формулировки разошлись — состояние несёт шаг, а не пересказ")
        } else {
            log("ответы подставлены: различие формулировок видно только в живом режиме (-Pdemo.live=1)")
        }

        assertEquals(middle.copy(paused = true), onPause, "пауза не сдвинула состояние: ни этап, ни шаг, ни действие")
        assertEquals(middle, requireNotNull(offPause.snapshot.task), "после снятия паузы состояние то же")
        assertTrue(resumed.block.contains(middle.step), "возобновлённый запрос несёт тот же шаг: ${resumed.block}")
        assertTrue(
            resumed.block.contains(middle.expectedAction),
            "и то же ожидаемое действие: ${resumed.block}"
        )
        assertTrue(calls <= 3, "этап стоит не больше трёх вызовов: $calls")
    }
}

/**
 * Ход сцены: реплика человека и ответ модели на служебный вызов.
 *
 * @param message Она же вопрос запроса и колонка «сообщение» в таблице.
 * @param proposal Строгая форма [TaskExtractor]; `{"task":null}` — переходить некуда.
 */
private class SceneTurn(val message: String, val proposal: String)

/** Ожидаемое состояние после хода и признак перехода: этап сменился или шаг уточнён. */
private class Expected(val state: TaskState, val moved: Boolean)

/**
 * Случай отказа: как задача доходит до этапа и что модель предлагает вопреки таблице.
 *
 * @param path Ходы до исходного этапа; пусто — задача только заведена.
 */
private class RejectionCase(
    val message: String,
    val path: List<SceneTurn>,
    val proposal: String
)

/** Сессии: у каждого диалога своё состояние задачи — задача живёт по сессии. */
private const val PATH_SESSION = "demo-task-path"
private const val REJECT_SESSION = "demo-task-reject"
private const val PAUSE_SESSION = "demo-task-pause"
private const val RESUME_SESSION = "demo-task-resume"

/** Ход до старта задачи: пока её нет, агент не ведёт ничего. */
private const val ASK_BEFORE_START = "Сделаем экран расходов?"

/** Короткие реплики продолжения: объяснять работу заново не нужно. */
private const val PAUSE_QUESTION = "продолжай"
private const val RESUME_QUESTION = "и дальше?"

/** Бюджет ответа сцены: у модели с рассуждениями они тратят тот же бюджет, что и текст. */
private const val TASK_ANSWER_BUDGET = 32_768

/** Подставленные ответы сцены: непустые, иначе агент считает ход неудачным. */
private const val CANNED_REPLY = "Принял: продолжаю по шагу плана, начинать заново не нужно."
private const val CANNED_PAUSED_REPLY = "Стою на паузе: работу не начинаю, жду продолжения."
private const val CANNED_RESUME_REPLY = "Продолжаю с шага 2: посчитать итог за месяц, начинать заново не нужно."

/** Предложение на возобновлённом ходу: пустые шаг и действие — прежние сохранятся. */
private const val RESUME_PROPOSAL = """{"task":{"stage":"execution"}}"""

/**
 * Путь сцены: планирование → выполнение (два шага) → проверка → назад в работу → проверка →
 * готово. Последний ход называет только этап: так модель говорит, что шаг не изменился.
 */
private val PATH = listOf(
    SceneTurn(
        "План: список трат, итог, фильтр",
        """{"task":{"stage":"planning","step":"собрать план","expected_action":"показать план"}}"""
    ),
    SceneTurn(
        "Начинаю по плану",
        """{"task":{"stage":"execution","step":"шаг 1: разметка","expected_action":"свёрстать список"}}"""
    ),
    SceneTurn(
        "Шаг 1 готов",
        """{"task":{"stage":"execution","step":"шаг 2: итог","expected_action":"посчитать итог"}}"""
    ),
    SceneTurn(
        "Шаг 2 готов",
        """{"task":{"stage":"validation","step":"проверить цифры","expected_action":"сверить с данными"}}"""
    ),
    SceneTurn(
        "Итог не сходится",
        """{"task":{"stage":"execution","step":"шаг 2: пересчёт","expected_action":"исправить расчёт"}}"""
    ),
    SceneTurn(
        "Расчёт исправлен",
        """{"task":{"stage":"validation","step":"повторная проверка","expected_action":"подтвердить цифры"}}"""
    ),
    SceneTurn("Теперь всё сходится", """{"task":{"stage":"done"}}""")
)

/**
 * Что должно получиться на пути — по таблице переходов, а не по предложениям сцены:
 * ожидание записано отдельно, поэтому демонстрация проверяет агента, а не повторяет его.
 */
private val PATH_EXPECTED = listOf(
    Expected(TaskState("planning", "собрать план", "показать план"), moved = false),
    Expected(TaskState("execution", "шаг 1: разметка", "свёрстать список"), moved = true),
    Expected(TaskState("execution", "шаг 2: итог", "посчитать итог"), moved = false),
    Expected(TaskState("validation", "проверить цифры", "сверить с данными"), moved = true),
    Expected(TaskState("execution", "шаг 2: пересчёт", "исправить расчёт"), moved = true),
    Expected(TaskState("validation", "повторная проверка", "подтвердить цифры"), moved = true),
    Expected(TaskState("done", "повторная проверка", "подтвердить цифры"), moved = true)
)

/** Запрещённые переходы из задания дня. */
private val REJECTIONS = listOf(
    RejectionCase(
        "Проверять уже нечего",
        emptyList(),
        """{"task":{"stage":"validation","step":"проверить цифры","expected_action":"сверить с данными"}}"""
    ),
    RejectionCase(
        "Задача готова",
        listOf(PATH[1]),
        """{"task":{"stage":"done"}}"""
    ),
    RejectionCase(
        "Продолжим работу",
        listOf(PATH[1], PATH[3], PATH[6]),
        """{"task":{"stage":"execution","step":"шаг 3: фильтр","expected_action":"сделать фильтр"}}"""
    )
)

/** Как довести задачу до этапа: предложения те же, что на пути этапа 1. */
private val ROUTE: Map<TaskStage, List<SceneTurn>> = mapOf(
    TaskStage.PLANNING to listOf(PATH[0]),
    TaskStage.EXECUTION to listOf(PATH[0], PATH[1]),
    TaskStage.VALIDATION to listOf(PATH[0], PATH[1], PATH[3]),
    TaskStage.DONE to listOf(PATH[0], PATH[1], PATH[3], PATH[6])
)

/** Разбор ответа служебного вызова: им и проверяется, что модель предложила. */
private val EXTRACTOR = TaskExtractor()

/**
 * Заголовок блока состояния: берётся из [TaskState.render], чтобы демонстрация не повторяла
 * слова промпта своей копией.
 */
private val TASK_HEADER = TaskState(stage = TaskStage.PLANNING.wire).render().lineSequence().first()

/** Признак служебного запроса состояния: с этих слов начинается инструкция [TaskExtractor]. */
private const val TASK_CALL_MARKER = "Ты ведёшь состояние задачи"

/** Шапка таблицы этапа 1: те же колонки переносим в README. */
private fun printPathHeader(): Unit = log(
    String.format(
        Locale.ROOT,
        PATH_ROW_FORMAT,
        "сообщение",
        "предложено",
        "принято",
        "этап",
        "текущий шаг",
        "ожидаемое действие"
    )
)

/** Формат строки этапа 1: колонки выровнены по левому краю — заголовок стоит над своим значением. */
private const val PATH_ROW_FORMAT = "%-34s %-32s %-18s %-12s %-22s %-22s"

/** Шапка таблицы этапа 2: направление предложения, причина отказа и куда встала задача. */
private fun printRejectHeader(): Unit = log(
    String.format(Locale.ROOT, REJECT_ROW_FORMAT, "предложено", "причина отказа", "этап остался")
)

/** Формат строки этапа 2: причина отказа — самая длинная колонка, её и не обрезаем. */
private const val REJECT_ROW_FORMAT = "%-26s %-88s %-12s"

/** Шапка таблицы этапа 3: состояние до паузы, после неё и число вызовов на паузе. */
private fun printPauseHeader(): Unit = log(
    String.format(Locale.ROOT, PAUSE_ROW_FORMAT, "этап", "до паузы", "после паузы", "вызовов на паузе")
)

/** Формат строки этапа 3: одинаковые клетки «до» и «после» и есть то, что проверяет пауза. */
private const val PAUSE_ROW_FORMAT = "%-12s %-38s %-38s %-16s"

/** Шапка таблицы этапа 4: два хода после паузы и состояние на конец каждого. */
private fun printResumeHeader(): Unit = log(
    String.format(
        Locale.ROOT,
        RESUME_ROW_FORMAT,
        "ход",
        "сообщение",
        "вызовов",
        "этап",
        "текущий шаг",
        "ожидаемое действие"
    )
)

/** Формат строки этапа 4: «вызовов» — все вызовы к модели, их на паузе нет. */
private const val RESUME_ROW_FORMAT = "%-12s %-14s %-8s %-12s %-16s %-22s"

/** Строка таблицы этапа 1. */
private fun printPathRow(scene: SceneTurn, run: TurnRun): Unit = log(
    String.format(
        Locale.ROOT,
        PATH_ROW_FORMAT,
        scene.message,
        offeredText(scene.proposal),
        acceptedCell(run),
        stageTitle(run.task.stage),
        run.task.step.ifEmpty { "—" },
        run.task.expectedAction.ifEmpty { "—" }
    )
)

/** Строка таблицы этапа 2: «этап остался» берётся из состояния после хода, а не из ожидания. */
private fun printRejectRow(run: RejectRun): Unit = log(
    String.format(
        Locale.ROOT,
        REJECT_ROW_FORMAT,
        direction(run.before, run.case.proposal),
        run.task.rejectReason ?: "причина не названа",
        stageTitle(run.turn.stateAfter?.stage)
    )
)

/** Строка таблицы этапа 3. */
private fun printPauseRow(run: PauseRun): Unit = log(
    String.format(
        Locale.ROOT,
        PAUSE_ROW_FORMAT,
        run.target.title,
        digest(run.before),
        digest(run.onPause),
        run.turn.serviceCalls.toString()
    )
)

/** Строка таблицы этапа 4. */
private fun printResumeRow(title: String, message: String, run: TurnRun): Unit = log(
    String.format(
        Locale.ROOT,
        RESUME_ROW_FORMAT,
        title,
        message,
        run.serviceCalls.toString(),
        stageTitle(run.task.stage),
        run.task.step.ifEmpty { "—" },
        run.task.expectedAction.ifEmpty { "—" }
    )
)

/**
 * Оговорка о транспорте для этапа, который идёт на подставленных ответах в любом режиме:
 * без неё подпись живого прогона обещала бы вызовы API, которых не было.
 */
private fun logCannedTransport(note: String) {
    if (demoOnLiveApi) log("транспорт этапа: подставленный ответ — здесь меряются запросы, а не ответы; $note")
}

/** Агент демонстрации: состояние задачи лежит в общем сторе [store], поэтому видно следующему ходу. */
private fun taskAgent(llm: LlmClient, store: TaskStateStore) =
    LlmAgent(llm, logger = AgentLogger { }, taskStateStore = store)

/**
 * Один ход сцены: вопрос человека уходит агенту, а из ответа берётся отчёт о задаче.
 *
 * @param sessionId Сессия диалога: по ней читается и пишется состояние задачи.
 */
private suspend fun turn(
    store: TaskStateStore,
    llm: TaskDemoClient,
    dialog: MutableList<ChatMessage>,
    message: String,
    sessionId: String
): TurnRun {
    val callsBefore = llm.serviceCalls
    val requestsBefore = llm.requests.size
    val result = taskAgent(llm, store).run(
        message,
        options(dialog.toList(), maxTokens = TASK_ANSWER_BUDGET, sessionId = sessionId, strategy = ContextStrategy.FULL)
    )
    val run = TurnRun(
        result = result,
        request = llm.requests.last { it.messages.last().content == message },
        serviceCalls = llm.serviceCalls - callsBefore,
        requestCount = llm.requests.size - requestsBefore,
        stateAfter = store.get(sessionId)
    )
    dialog += ChatMessage("user", message)
    dialog += ChatMessage("assistant", result.reply)
    return run
}

/** Ход после паузы: в живом режиме отвечает настоящая модель, иначе — подставленный ответ. */
private suspend fun resumeTurn(
    store: TaskStateStore,
    dialog: MutableList<ChatMessage>,
    message: String,
    reply: String,
    proposals: ArrayDeque<String> = ArrayDeque()
): TurnRun = turn(
    store,
    TaskDemoClient(reply, proposals, if (demoOnLiveApi) liveClient() else null),
    dialog,
    message,
    RESUME_SESSION
)

/** Ход с запрещённым переходом: задача доводится до исходного этапа, последний ход предлагает запрет. */
private suspend fun rejectionRun(
    store: TaskStateStore,
    writer: TaskWriter,
    case: RejectionCase,
    session: String
): RejectRun {
    val dialog = mutableListOf<ChatMessage>()
    val llm = TaskDemoClient(CANNED_REPLY, ArrayDeque(case.path.map { it.proposal } + case.proposal))
    writer.start(session)
    case.path.forEach { scene -> turn(store, llm, dialog, scene.message, session) }
    val before = requireNotNull(store.get(session)) { "задача не доведена до этапа: ${case.message}" }
    return RejectRun(case, before, turn(store, llm, dialog, case.message, session))
}

/**
 * Прогон этапа 3 по одному этапу: задача доводится до него, ставится пауза, на паузе уходит
 * сообщение, а после снятия паузы состояние читается снова — по нему и видно, что пауза
 * ничего не сдвинула.
 */
private suspend fun pauseRun(store: TaskStateStore, writer: TaskWriter, target: TaskStage): PauseRun {
    val session = "$PAUSE_SESSION-${target.wire}"
    val route = ROUTE.getValue(target)
    val dialog = mutableListOf<ChatMessage>()
    val setup = TaskDemoClient(CANNED_REPLY, ArrayDeque(route.map { it.proposal }))
    writer.start(session)
    route.forEach { scene -> turn(store, setup, dialog, scene.message, session) }
    val before = requireNotNull(store.get(session)) { "задача не доведена до этапа ${target.title}" }

    assertIs<TaskWrite.Written>(writer.setPaused(session, true), "пауза ставится, когда задача есть")
    val pausedTurn = turn(store, TaskDemoClient(CANNED_REPLY), dialog, PAUSE_QUESTION, session)
    val onPause = requireNotNull(store.get(session))
    assertIs<TaskWrite.Written>(writer.setPaused(session, false), "пауза снимается у той же задачи")
    return PauseRun(target, before, pausedTurn, onPause, store.get(session))
}

/** Что показал один ход сцены. */
private class TurnRun(
    val result: AgentResult,
    /** Запрос сцены этого хода: в нём виден блок состояния и вопрос. */
    val request: DeepSeekRequest,
    /** Сколько служебных вызовов сделал этот ход: на паузе их нет вовсе. */
    val serviceCalls: Int,
    /** Сколько всего вызовов сделал этот ход: запрос сцены и служебные. */
    val requestCount: Int,
    /** Состояние после хода: в нём виден принятый переход. */
    val stateAfter: TaskState?
) {

    /** Отчёт о задаче: состояние на конец ответа и цена служебного вызова. */
    val task: TaskReport get() = result.tokens.task

    /** Блок состояния в запросе сцены; пусто — задачи в диалоге не было. */
    val block: String get() = taskBlockOf(request)
}

/** Что показал ход с запрещённым переходом. */
private class RejectRun(val case: RejectionCase, val before: TaskState, val turn: TurnRun) {
    val task: TaskReport get() = turn.task
}

/** Что показал этап 3 по одному этапу. */
private class PauseRun(
    val target: TaskStage,
    val before: TaskState,
    val turn: TurnRun,
    val onPause: TaskState,
    val afterResume: TaskState?
)

/**
 * Транспорт демонстрации: подставленный ответ и запись запросов.
 *
 * Служебный вызов отличается от запроса сцены системным сообщением: инструкцию пишет
 * [TaskExtractor], и начинается она словами [TASK_CALL_MARKER]. По этому признаку транспорт
 * и отвечает: сцене — [reply], служебному вызову — очередное предложение из [proposals],
 * а если предложений не осталось — «переходить некуда». Токены запроса считает тот же
 * счётчик, что и агент, поэтому «факт» в отчёте равен оценке; токены ответа постоянные.
 *
 * Живой режим ([live]) отвечает настоящей моделью: подставленных ответов в нём нет,
 * но вызовы считаются так же — по ним видно, чего стоил этап.
 */
private class TaskDemoClient(
    private val reply: String = CANNED_REPLY,
    private val proposals: ArrayDeque<String> = ArrayDeque(),
    private val live: LlmClient? = null
) : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    /** Сколько служебных вызовов ушло этим транспортом: на паузе их нет ни одного. */
    var serviceCalls = 0
        private set

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        val service = request.messages.first().content.startsWith(TASK_CALL_MARKER)
        if (service) serviceCalls++
        live?.let { return it.complete(request) }
        val answer = if (service) proposals.removeFirstOrNull() ?: NO_PROPOSAL else reply
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

/** Очередного предложения не осталось: переходить некуда — состояние останется прежним. */
private const val NO_PROPOSAL = """{"task":null}"""

/** Блок состояния в запросе: он начинается с заголовка [TASK_HEADER] и он один такой. */
private fun taskBlockOf(request: DeepSeekRequest): String =
    request.messages.firstOrNull { it.content.startsWith(TASK_HEADER) }?.content.orEmpty()

/** Строка паузы в блоке: её строит [TaskState.render], демонстрация её только ищет. */
private fun pauseLine(state: TaskState): String = state.copy(paused = true).render().lineSequence().last()

/** Название этапа: неизвестное значение печатаем как есть — так ошибку видно, а не прячем. */
private fun stageTitle(stage: String?): String = stage?.let { TaskStage.ofWire(it)?.title ?: it } ?: "—"

/** Состояние одной строкой: по шагу и ожидаемому действию видно, что задача не сдвинулась. */
private fun digest(state: TaskState): String =
    listOf(state.step, state.expectedAction).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "шаг не назван" }

/** Предложение, как его читает человек: названия этапов — те же, что в блоке состояния. */
private fun offeredText(proposal: String): String {
    val parsed = EXTRACTOR.parse(proposal) ?: return "предложения нет"
    val stage = TaskStage.ofWire(parsed.stage)?.title ?: "этап «${parsed.stage}»"
    return listOf(stage, parsed.step).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Направление предложения: откуда задача и куда её зовут — так отказ читается без пояснений. */
private fun direction(before: TaskState, proposal: String): String {
    val parsed = EXTRACTOR.parse(proposal)
    val to = parsed?.let { TaskStage.ofWire(it.stage)?.title ?: "этап «${it.stage}»" } ?: "не разобралось"
    return "${stageTitle(before.stage)} → $to"
}

/** Клетка «принято»: состоялся ли переход этого хода и сменился ли этап. */
private fun acceptedCell(run: TurnRun): String = when {
    run.task.stage == null -> "—"
    run.task.rejected > 0 -> "нет"
    run.task.moved -> "да"
    else -> "да (без перехода)"
}

/** Цена в микроцентах: у модели без опубликованного тарифа — прочерк вместо нуля. */
private fun costText(value: Double?): String = value?.let { "\$${money(it)}" } ?: "тариф не опубликован"

/**
 * Видно ли в ответе, что работа продолжается с текущего шага: ищем значимые слова шага
 * и ожидаемого действия. Это подсказка человеку, а не проверка: формулировку выбирает модель.
 */
private fun continuesFrom(reply: String, state: TaskState): String {
    val words = significantWords("${state.step} ${state.expectedAction}")
    if (words.isEmpty()) return "сравнивать не с чем: шаг не назван"
    val text = reply.lowercase(Locale.ROOT)
    val found = words.filter { text.contains(it) }
    return "${found.size} из ${words.size}" +
        if (found.isEmpty()) " — прямо о шаге ответ не говорит" else " (${found.joinToString(", ")})"
}

/** Доля общих слов в двух ответах: 1.0 — те же слова, 0.0 — ничего общего. */
private fun overlapText(first: String, second: String): String {
    val a = significantWords(first).toSet()
    val b = significantWords(second).toSet()
    if (a.isEmpty() || b.isEmpty()) return "сравнивать не с чем: ответы пусты"
    return String.format(Locale.ROOT, "%.0f%%", a.intersect(b).size.toDouble() / a.union(b).size * 100)
}

/** Значимые слова текста: короткие служебные слова сравнение только зашумляют. */
private fun significantWords(text: String): List<String> =
    text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 5 }.distinct()
