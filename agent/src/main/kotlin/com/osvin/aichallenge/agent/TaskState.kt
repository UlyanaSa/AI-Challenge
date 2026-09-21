package com.osvin.aichallenge.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Заголовок блока состояния: по нему состояние задачи видно и в промпте, и в логе. */
private const val TASK_HEADER = "Задача в работе (веди её по шагам, не начинай заново):"

/** Подписи полей блока: те же слова, что в задании дня, — иначе блок не прочитать. */
private const val STAGE_LABEL = "этап"
private const val STEP_LABEL = "текущий шаг"
private const val EXPECTED_LABEL = "ожидаемое действие"
private const val PAUSED_LABEL = "на паузе"

/**
 * Строка паузы: состояние на паузе заморожено, поэтому модели нужно знать ровно это —
 * работа стоит, продолжать надо с того же шага, а сделанное пересказывать не надо.
 */
private const val PAUSED_HINT = "задача остановлена — продолжай с этого же шага, выполненное не пересказывай"

/**
 * Начало сообщения об отклонённом переходе. Ассистент узнаёт по нему, что этап остался
 * прежним, и по этой же строке отказ видно в демонстрации ([TASK_REJECTION_HEADER]).
 */
const val TASK_REJECTION_HEADER = "Переход отклонён (этап остался прежним):"

/**
 * Этап задачи: на каком круге работы она стоит — план, работа, проверка результата, закрытие.
 *
 * Состояние задачи — конечный автомат ([TaskState]), а не свободный текст: этап называет
 * модель, но сам переход разрешает код по таблице ([TaskRules.allowed]). В этом и смысл
 * автомата: без таблицы модель закрывает задачу, не проверив результат, — в новом запросе
 * она видит только переписку и решает, что работа сделана. Таблица оставляет решение
 * за кодом, а отказ объясняет причиной, чтобы работа продолжилась, а не встала.
 *
 * @param wire Значение этапа в служебном вызове, в состоянии на проводе и в отчёте агента.
 * @param title Название этапа для блока состояния, интерфейса и логов.
 * @param hint Что на этом этапе происходит — то, что стоит в скобках в каталоге этапов.
 */
enum class TaskStage(val wire: String, val title: String, val hint: String) {
    PLANNING("planning", "планирование", "что нужно сделать и в каком порядке"),
    EXECUTION("execution", "выполнение", "делаем шаг за шагом по плану"),
    VALIDATION("validation", "проверка", "сверяем результат с задачей"),
    DONE("done", "готово", "задача закрыта, следующая начинается заново");

    companion object {

        /** Этап по значению провода; null — этап неизвестен, и переход по нему не принимается. */
        fun ofWire(value: String?): TaskStage? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }

        /**
         * Каталог этапов: уезжает клиенту вместе со снимком задачи ([TaskSnapshot]) и уходит
         * в инструкцию служебного вызова. Подписи и пояснения живут только здесь, поэтому
         * своей копии таблицы этапов у клиента нет — разойтись с сервером они не могут.
         */
        val info: List<TaskStageInfo> = entries.map { TaskStageInfo(it.wire, it.title, it.hint) }

        /**
         * Состояние новой задачи: она только заведена, поэтому этап — планирование, а шага
         * и ожидаемого действия ещё нет: их назовёт модель первым служебным вызовом.
         */
        fun start(): TaskState = TaskState(stage = PLANNING.wire)
    }
}

/**
 * Этап задачи в каталоге: как он называется и что на нём происходит.
 *
 * Каталог собирается из [TaskStage] и уезжает клиенту вместе со снимком: полоса задачи
 * в интерфейсе рисуется по нему, как шторка памяти по каталогу типов ([MemoryType]).
 *
 * @param stage Этап: значение [TaskStage.wire], оно же уходит обратно при переходе.
 * @param title Название этапа для интерфейса и логов.
 * @param hint Пояснение к названию из задания дня.
 */
@Serializable
data class TaskStageInfo(
    @SerialName("stage") val stage: String,
    @SerialName("title") val title: String,
    @SerialName("hint") val hint: String
)

/**
 * Состояние задачи: где работа стоит и что делается следующим шагом.
 *
 * В запрос состояние уходит блоком ([render]) при каждом обращении, пока задача открыта:
 * так модель видит, на чём остановилась, и не начинает заново — ни в следующем сообщении,
 * ни после паузы. Ради этого в нём три поля, а не одно:
 *
 * - [stage] — этап: он переживает отдельный шаг и называет, в каком круге работы дело;
 * - [step] — текущий шаг: то, что делается прямо сейчас, одной фразой;
 * - [expectedAction] — ожидаемое действие: что модель должна сделать в этом ответе. Без
 *   него этап и шаг ещё можно пересказать, а вот продолжить работу — нет.
 *
 * [paused] — не этап, а отдельный флаг: на паузе задача не движется, но остаётся ровно
 * там, где её оставили, поэтому после снятия паузы работа идёт с того же шага. Ставит
 * и снимает его человек ([TaskWriter]), а не модель: пауза — решение о работе, а не шаг.
 *
 * @param stage Этап задачи: значение [TaskStage.wire].
 * @param step Текущий шаг: что делается сейчас.
 * @param expectedAction Ожидаемое действие: что модель делает в этом ответе.
 * @param paused Задача на паузе: состояние заморожено, служебного вызова нет.
 */
@Serializable
data class TaskState(
    @SerialName("stage") val stage: String,
    @SerialName("step") val step: String = "",
    @SerialName("expected_action") val expectedAction: String = "",
    @SerialName("paused") val paused: Boolean = false
) {

    /** Этап, если он известен; null — состояние испорчено, и переход по нему не принять. */
    fun stageOf(): TaskStage? = TaskStage.ofWire(stage)

    /**
     * Блок состояния для системного сообщения: заголовок и заполненные поля.
     *
     * Пустые поля не печатаются: строка «текущий шаг: » без значения читается моделью как
     * требование, о котором забыли. Этап печатается названием — по нему модель понимает,
     * в каком круге работы дело, а значение провода ей видно из инструкции служебного вызова.
     */
    fun render(): String {
        val lines = listOfNotNull(
            (stageOf()?.title ?: stage.trim()).takeIf { it.isNotEmpty() }?.let { "- $STAGE_LABEL: $it" },
            field(STEP_LABEL, step),
            field(EXPECTED_LABEL, expectedAction),
            if (paused) "- $PAUSED_LABEL: $PAUSED_HINT" else null
        )
        return (listOf(TASK_HEADER) + lines).joinToString("\n")
    }

    /** Строка блока; поле без текста в блок не попадает. */
    private fun field(label: String, value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }?.let { "- $label: $it" }

    /**
     * Сообщение ассистенту об отклонённом переходе: этап остался прежним, работа идёт дальше
     * с того же шага.
     *
     * Без него модель увидела бы только неизменившийся блок состояния и, не поняв отказа,
     * попробовала бы перепрыгнуть этап ещё раз — а причина отказа объясняет, чего не хватает
     * работе и когда переход станет возможен. Поэтому причина приходит из таблицы
     * ([TaskRules]), а не сочиняется здесь: сообщение и отказ говорят одно и то же.
     *
     * @param reason Причина отказа из [TaskRules.accept].
     */
    fun renderRejection(reason: String): String = buildString {
        append("$TASK_REJECTION_HEADER $reason. ")
        append("Продолжай работу на этапе «${stageOf()?.title ?: stage}»")
        step.trim().takeIf { it.isNotEmpty() }?.let { append(", текущий шаг: $it") }
        expectedAction.trim().takeIf { it.isNotEmpty() }?.let { append(", ожидаемое действие: $it") }
        append(". Этапы не перескакивают: переход станет возможен, когда работа дойдёт до него.")
    }
}

/**
 * Разрешённый переход задачи: из какого этапа куда можно и почему именно так.
 *
 * Переходы уезжают клиенту вместе со снимком ([TaskSnapshot]), чтобы «куда дальше можно»
 * было видно и человеку, а не только коду: ассистент ограничен таблицей ([TaskRules]),
 * поэтому и человек должен видеть тот же список, а не догадываться о нём по отказам.
 *
 * @param from Этап, из которого переходят: значение [TaskStage.wire].
 * @param to Этап, в который переходят: значение [TaskStage.wire].
 * @param why Почему такой переход разрешён — то, что стоит за ним по работе.
 */
@Serializable
data class TaskTransition(
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
    @SerialName("why") val why: String
)

/**
 * Предложение перехода от модели: на какой этап задача переходит и что делается дальше.
 *
 * Пустые [step] и [expectedAction] — не «стереть», а «не сказано»: модель может не повторять
 * шаг в каждом ответе, поэтому прежнее значение остаётся ([TaskRules.accept]). Этап пустым
 * быть не может: он и есть предложение, и без него переход не проверить.
 *
 * @param stage Этап, на который задача переходит: значение [TaskStage.wire].
 * @param step Текущий шаг после перехода; пусто — шаг не изменился.
 * @param expectedAction Ожидаемое действие после перехода; пусто — действие не изменилось.
 */
data class TaskProposal(
    val stage: String,
    val step: String = "",
    val expectedAction: String = ""
)

/** Чем кончилась проверка перехода: состоянием задачи или отказом с причиной. */
sealed interface TaskDecision {

    /**
     * Переход разрешён таблицей, состояние задачи обновлено.
     *
     * @param state Состояние задачи после перехода.
     * @param moved Сменился ли этап этим переходом: уточнение шага внутри этапа — не переход.
     */
    data class Accepted(val state: TaskState, val moved: Boolean) : TaskDecision

    /**
     * Переход запрещён: этап не разобрался или его нет в таблице разрешённых.
     * Причина объясняет правило, поэтому по ней видно, что делать вместо.
     */
    data class Rejected(val reason: String) : TaskDecision
}

/**
 * Таблица переходов задачи и проверка предложенного перехода.
 *
 * Таблица — единственное место, где сказано, куда из какого этапа можно, и она же причина
 * отказов. Переходы описывают работу, а не формальность: из планирования нельзя сразу
 * в проверку — проверять нечего; из выполнения нельзя в готово — результат не проверен;
 * из готово нельзя назад в работу — задача закрыта, и продолжать её значило бы потерять
 * границу между сделанным и новым. Отказ всегда приходит причиной, а не молчанием:
 * по причине модель продолжает работу, а человек в логе видит, чего от неё хотели.
 */
object TaskRules {

    /**
     * Куда можно перейти из каждого этапа. Свой этап в таблице есть всегда: модель может
     * остаться на месте и уточнить шаг — это не переход, а уточнение, и запрещать его незачем.
     */
    val allowed: Map<TaskStage, Set<TaskStage>> = mapOf(
        TaskStage.PLANNING to setOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.DONE),
        TaskStage.EXECUTION to setOf(TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.PLANNING),
        TaskStage.VALIDATION to setOf(
            TaskStage.VALIDATION,
            TaskStage.EXECUTION,
            TaskStage.DONE,
            TaskStage.PLANNING
        ),
        TaskStage.DONE to setOf(TaskStage.DONE, TaskStage.PLANNING)
    )

    /**
     * Почему разрешён каждый из [allowed] переходов. Здесь — смысл перехода, а не правило:
     * правила запретов лежат в [refusals], и вместе они объясняют «можно так» и «нельзя иначе».
     *
     * Таблица объявлена до [transitions]: переходы собираются из неё, и причина берётся здесь,
     * а не наоборот.
     */
    private val transitionWhys: Map<Pair<TaskStage, TaskStage>, String> = mapOf(
        (TaskStage.PLANNING to TaskStage.PLANNING) to
            "план уточняется: остаться в планировании — это уточнение, а не переход",
        (TaskStage.PLANNING to TaskStage.EXECUTION) to
            "план утверждён: реализацию начинают после плана, а не до него",
        (TaskStage.PLANNING to TaskStage.DONE) to
            "план оказался ненужным: задачу закрывают, не начиная работы",
        (TaskStage.EXECUTION to TaskStage.EXECUTION) to
            "следующий шаг внутри этапа: этап тот же, меняется только шаг",
        (TaskStage.EXECUTION to TaskStage.VALIDATION) to
            "работа сделана: результат сверяют с задачей до того, как объявить готовым",
        (TaskStage.EXECUTION to TaskStage.PLANNING) to
            "шаг показал, что план неверен: план пересобирают целиком, а не правят на ходу",
        (TaskStage.VALIDATION to TaskStage.VALIDATION) to
            "проверка идёт: результат сверяют по частям, оставаясь в этом же этапе",
        (TaskStage.VALIDATION to TaskStage.EXECUTION) to
            "проверка не прошла: работа возвращается на доработку",
        (TaskStage.VALIDATION to TaskStage.DONE) to
            "результат сошёлся с задачей: лишь после проверки задачу можно закрыть",
        (TaskStage.VALIDATION to TaskStage.PLANNING) to
            "проверка показала, что задача была понята неверно: работу начинают с плана заново",
        (TaskStage.DONE to TaskStage.DONE) to
            "задача закрыта и остаётся закрытой: новых переходов этим ответом нет",
        (TaskStage.DONE to TaskStage.PLANNING) to
            "закрытая задача не продолжается: следующая начинается с планирования"
    )

    /**
     * Разрешённые переходы словами: из этапа — куда и почему. Собираются из [allowed],
     * поэтому список переходов и сама таблица разойтись не могут.
     *
     * Причина берётся из [transitionWhys]; у каждого разрешённого перехода она есть —
     * иначе переход шёл бы без объяснения, и человек видел бы «можно», не понимая «почему».
     */
    val transitions: List<TaskTransition> = allowed.entries.flatMap { (from, to) ->
        to.map { target -> TaskTransition(from.wire, target.wire, transitionWhy(from, target)) }
    }

    /** Причина перехода; общая ветка остаётся на случай нового этапа — она называет своё. */
    private fun transitionWhy(from: TaskStage, to: TaskStage): String = transitionWhys[from to to]
        ?: "из ${from.title} задача идёт в ${to.title}: это следующий круг работы"

    /**
     * Проверяет предложение модели и возвращает состояние задачи после него.
     *
     * @param previous Состояние задачи до предложения; null — задачи ещё нет, и тогда
     *        законно только её начало, планирование.
     */
    fun accept(previous: TaskState?, proposal: TaskProposal): TaskDecision {
        val proposed = TaskStage.ofWire(proposal.stage)
            ?: return TaskDecision.Rejected(unknownStage(proposal.stage))
        if (previous == null) {
            return if (proposed == TaskStage.PLANNING) {
                TaskDecision.Accepted(
                    state = TaskState(
                        stage = proposed.wire,
                        step = proposal.step.trim(),
                        expectedAction = proposal.expectedAction.trim()
                    ),
                    // Задачи не было — этап не менялся: состояние появилось, а не перешло.
                    moved = false
                )
            } else {
                TaskDecision.Rejected(
                    "задача только заведена: начать её можно с планирования, а не с «${proposed.title}»"
                )
            }
        }

        val from = previous.stageOf()
            ?: return TaskDecision.Rejected("прежнее состояние задачи испорчено: этап «${previous.stage}» неизвестен")
        if (proposed !in allowed.getValue(from)) return TaskDecision.Rejected(refusal(from, proposed))

        return TaskDecision.Accepted(
            state = TaskState(
                stage = proposed.wire,
                // Пустое поле в предложении — «не сказано», а не «стереть»: модель может
                // не повторять шаг и действие в каждом ответе, а без них состояние теряет
                // то, ради чего оно нужно, — чем продолжить работу.
                step = proposal.step.trim().ifEmpty { previous.step },
                expectedAction = proposal.expectedAction.trim().ifEmpty { previous.expectedAction },
                // Пауза — решение человека, а не переход: модель её не ставит и не снимает.
                paused = previous.paused
            ),
            moved = proposed != from
        )
    }

    /** Причина отказа: этап назван значением, которого в задании дня нет. */
    private fun unknownStage(value: String): String = buildString {
        append("этап «${value.trim()}» неизвестен: у задачи этапы ")
        append(TaskStage.entries.joinToString(", ") { "${it.title} (${it.wire})" })
    }

    /**
     * Причина отказа для запрещённого перехода: почему нельзя и что делать вместо.
     * Запрещённых переходов всего четыре ([allowed]), и у каждого своя причина; общая
     * ветка остаётся на случай нового этапа — она называет те переходы, которые есть.
     */
    private fun refusal(from: TaskStage, to: TaskStage): String = refusals[from to to]
        ?: "из ${from.title} нельзя в ${to.title}: из этого этапа задача идёт только в " +
        allowed.getValue(from).joinToString(", ") { it.title }

    /** Причины запретов: их читает человек в логе, по ним и видно, чего не хватило работе. */
    private val refusals: Map<Pair<TaskStage, TaskStage>, String> = mapOf(
        (TaskStage.PLANNING to TaskStage.VALIDATION) to
            "из планирования нельзя в проверку: проверять пока нечего — сначала выполните план",
        (TaskStage.EXECUTION to TaskStage.DONE) to
            "из выполнения нельзя в готово: результат не проверен — перейдите в проверку",
        (TaskStage.DONE to TaskStage.EXECUTION) to
            "из готово нельзя назад в выполнение: задача закрыта — начните новую с планирования",
        (TaskStage.DONE to TaskStage.VALIDATION) to
            "из готово нельзя в проверку: задача закрыта — начните новую с планирования"
    )
}

/**
 * Состояние задачи в отчёте ответа: где работа стоит и чего стоил служебный вызов.
 *
 * Поля описывают состояние на конец ответа — то, что легло в стор. На паузе и при отказе
 * это прежнее состояние: переход не приняли, и задача осталась на месте. Блок в запросе
 * при этом мог быть другим ([tokens] считает именно его): состояние для запроса собрано
 * до ответа модели, а переход по нему принят уже после.
 *
 * @param stage Этап задачи; null — задачи в этом диалоге нет.
 * @param step Текущий шаг.
 * @param expectedAction Ожидаемое действие.
 * @param paused Задача на паузе: служебного вызова не было вовсе.
 * @param moved Этап сменился этим ответом.
 * @param rejected Сколько переходов отклонено: этап неизвестен или запрещён таблицей.
 * @param rejectReason Причина последнего отказа.
 * @param tokens Токены блока состояния в запросе.
 * @param updateTokens Токены служебного вызова обновления состояния (запрос и ответ).
 * @param updateCostUsd Цена этого вызова в USD; null — тариф не опубликован.
 */
@Serializable
data class TaskReport(
    @SerialName("stage") val stage: String? = null,
    @SerialName("step") val step: String = "",
    @SerialName("expected_action") val expectedAction: String = "",
    @SerialName("paused") val paused: Boolean = false,
    @SerialName("moved") val moved: Boolean = false,
    @SerialName("rejected") val rejected: Int = 0,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("tokens") val tokens: Int = 0,
    @SerialName("update_tokens") val updateTokens: Int = 0,
    @SerialName("update_cost_usd") val updateCostUsd: Double? = null
)

/**
 * Снимок задачи: состояние диалога и каталог этапов.
 *
 * Состояния нет — это ответ «задачи в диалоге нет», а не ошибка: шторка задачи рисуется
 * всегда, а каталог берётся из снимка, потому что своей копии этапов у клиента нет.
 *
 * @param task Состояние задачи; null — задача не заведена. Умолчание тут уместно: без задачи
 *        поле на провод не уходит, и клиент видит снимок без состояния — ровно то, что есть.
 * @param stages Каталог этапов: подписи и пояснения для интерфейса. Умолчания у поля нет
 *        намеренно: каталог равен [TaskStage.info], а значение, равное объявленному
 *        умолчанию, kotlinx.serialization на провод не пишет — клиент получил бы снимок
 *        без подписей, а своей таблицы этапов у него нет. Пишет каталог всегда [TaskWriter].
 * @param transitions Разрешённые переходы: из какого этапа куда можно и почему. Умолчания нет
 *        по той же причине, что у [stages]: это таблица [TaskRules.transitions], и клиент
 *        показывает «куда дальше можно» по ней, а своей копии таблицы у него нет.
 */
@Serializable
data class TaskSnapshot(
    @SerialName("task") val task: TaskState? = null,
    @SerialName("stages") val stages: List<TaskStageInfo>,
    @SerialName("transitions") val transitions: List<TaskTransition>
)
