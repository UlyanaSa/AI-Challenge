package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.rag.Check

/**
 * Итог одного шага сценария: что ожидалось и что вышло.
 *
 * Это **измерение**, а не приговор дня ([ChatScenarioRun.accepted]): [failures] перечисляет, где
 * поведение разошлось с ожиданием (вид ответа, отсутствие отказа, не названная цель), а [facts] —
 * какие сведения ответа названы. Поэтому шаг может быть `passed` и при незасчитанном факте: сценарий
 * и здесь меряет разговор по частям, а не решает за него.
 *
 * Причины в [failures] называются словами, а не флагом: «ожидался ответ по базе, получен отказ» —
 * это то, что нужно прочитать в отчёте, чтобы понять, чинить память, поиск или промпт.
 */
data class ChatScenarioCheck(
    /** Номер шага в сценарии, с единицы. */
    val index: Int,
    val question: String,
    /** Вид ответа совпал с ожиданием; `null` — вид не проверялся. */
    val kindOk: Boolean?,
    /** Отказ был там, где ожидался; `null` — отказ не ожидался. */
    val refusalOk: Boolean?,
    /** Ответ назвал цель разговора; `null` — не проверялось. */
    val goalOk: Boolean?,
    /** По факту на ожидаемое сведение ответа. */
    val facts: List<Boolean>,
    val failures: List<String>
) {

    /** Обязательная часть пройдена: поведение дня, а не содержание ответа. */
    val passed: Boolean get() = failures.isEmpty()

    /** Сколько ожидаемых сведений ответ назвал. */
    val factsMatched: Int get() = facts.count { it }

    val factsTotal: Int get() = facts.size
}

/**
 * Прогон сценария: ходы, проверки и приговор.
 *
 * Приговор делится на **обязательное** и **измеряемое**, и это решение дня, а не удобство отчёта.
 *
 * Обязательное — то, что задание требует от чата: у каждого ответа назван источник
 * ([ChatSummary.sourcesEverywhere]), у каждого отказа названа причина ([ChatSummary.refusalsNamed]),
 * цель разговора зафиксирована и больше не менялась ([ChatSummary.goalKept]), память задачи удержала
 * ограничения и термины ([memoryKept]), сбоев обращения к модели нет ([ChatSummary.errors]).
 * Эти пять и решают, принят день или нет.
 *
 * Измеряемое — поведение на каждом шаге ([checks]) и сведения ответов ([factsMatched]). Проверки
 * по шагам стоят здесь именно измеряемым, и так решил живой прогон. Ожидание вида ответа («вопрос
 * о книге — по базе», «просьба о правиле — по памяти задачи») зависит и от поиска, и от модели:
 * в одном прогоне сценария вопрос, отвечённый по фрагментам в двух других, получил отказ «в найденных
 * фрагментах этого нет» — честный отказ по правилу дня 24, а не потерянный источник; в другом прогоне
 * просьба «отвечай только по тексту базы» получила ответ по базе вместо подтверждения — с источниками,
 * как задание и требует. Объявлять из-за этого день несданным значило бы валить его за то, чего он
 * не обещает: обещано не терять цель, всегда называть источник, не выдумывать и помнить установленное,
 * и на таких шагах всё это соблюдено. Поэтому разошедшийся шаг виден и в таблице, и в счётчике,
 * но приговор решают обязательные пять — а счётчик отличает «модель ответила иначе» от «система
 * потеряла память», что читателю отчёта и нужно.
 */
data class ChatScenarioRun(
    val scenario: ChatScenario,
    val turns: List<ChatTurn>,
    val summary: ChatSummary,
    val checks: List<ChatScenarioCheck>,
    /** Держатся ли памятью задачи ограничения и термины, названные в сценарии. */
    val memoryKept: Boolean
) {

    val factsMatched: Int get() = checks.sumOf { it.factsMatched }
    val factsTotal: Int get() = checks.sumOf { it.factsTotal }

    /** Шаги, где поведение не совпало с ожиданием. */
    val failedSteps: List<ChatScenarioCheck> get() = checks.filter { !it.passed }

    /** Шаги, где ожидание сошлось; остальные — мера качества, а не приговор ([accepted]). */
    val passedSteps: Int get() = checks.size - failedSteps.size

    /** День принят: источники у каждого ответа, причина у каждого отказа, цель и память держатся. */
    val accepted: Boolean
        get() = summary.sourcesEverywhere && summary.refusalsNamed && summary.goalKept &&
            memoryKept && summary.errors == 0

    /** Строки приговора для отчёта и страницы: сначала обязательное, потом измеряемое. */
    val verdict: List<String>
        get() = listOf(
            "источники у каждого ответа: ${answer(summary.sourcesEverywhere)} " +
                "(ответов ${summary.answered}, без источника ${summary.answersWithoutSource})",
            "у каждого отказа названа причина: ${answer(summary.refusalsNamed)} " +
                "(отказов ${summary.refusals}, без причины ${summary.refusalsWithoutReason})",
            "цель разговора держится: ${answer(summary.goalKept)} " +
                "(«${summary.goal ?: "не зафиксирована"}»" +
                (summary.goalFixedAt?.let { ", зафиксирована на ходу $it" } ?: "") + ")",
            "память задачи удержала ограничения и термины: ${answer(memoryKept)} " +
                "(ограничений ${summary.constraints}, терминов ${summary.terms})",
            "сбоев обращения к модели нет: ${answer(summary.errors == 0)} " +
                "(сбоев ${summary.errors})",
            "поведение по шагам: $passedSteps из ${checks.size} совпало с ожиданием" +
                (if (failedSteps.isEmpty()) "" else
                    " (отличия на шагах ${failedSteps.joinToString(", ") { it.index.toString() }}; " +
                        "мера качества, а не приговор)"),
            "сведения ответов: $factsMatched из $factsTotal ожидаемых названо " +
                "(мера качества, а не приговор)",
            "итог: ${if (accepted) "принято" else "не принято: не сошлась обязательная часть"}"
        )

    private fun answer(ok: Boolean): String = if (ok) "да" else "нет"
}

/**
 * Прогон сценария по сессии чата: реплика — ход — проверка.
 *
 * Работает поверх [ChatSession], а не внутри неё, и это разделение существенно: сессия не знает
 * ни о сценариях, ни об ожиданиях. Иначе в движке появилось бы знание о проверке, и «чат отвечает
 * так, потому что так проверяют» перестало бы быть отличимо от «чат отвечает так, потому что умеет».
 * Здесь же сессия спрашивается ровно так, как её спросит человек со страницы.
 *
 * Проверка фактов идёт тем же сверщиком, что в дне 22 ([Check.contains]): признак — основа слова,
 * и ответ «тростью» засчитывается так же, как «трость». Иначе сценарий мерил бы слог модели.
 */
object ChatScenarioRunner {

    /**
     * Прогоняет сценарий целиком и возвращает ходы с проверками.
     *
     * Ходы берутся из сессии ([ChatSession.turns]) после прогона, а не собираются по ходу: сессия
     * нумерует ходы и ведёт память, и второй список тех же ходов разошёлся бы с первым.
     */
    suspend fun run(session: ChatSession, scenario: ChatScenario): ChatScenarioRun {
        val before = session.turns().size
        scenario.steps.forEach { step -> session.ask(step.text) }
        val turns = session.turns().drop(before)
        val checks = scenario.steps.mapIndexed { position, step ->
            check(position + 1, step, turns.getOrNull(position), scenario)
        }
        val summary = ChatReport.summarize(session.turns())
        return ChatScenarioRun(
            scenario = scenario,
            turns = turns,
            summary = summary,
            checks = checks,
            memoryKept = summary.constraints >= scenario.expectedConstraints &&
                summary.terms >= scenario.expectedTerms
        )
    }

    /** Проверка одного шага: обязательная часть — нарушениями, содержательная — числами. */
    fun check(
        index: Int,
        step: ChatScenarioStep,
        turn: ChatTurn?,
        scenario: ChatScenario
    ): ChatScenarioCheck {
        if (turn == null) {
            return ChatScenarioCheck(
                index = index,
                question = step.text,
                kindOk = null,
                refusalOk = null,
                goalOk = null,
                facts = emptyList(),
                failures = listOf("хода нет: сценарий оборвался")
            )
        }

        val failures = ArrayList<String>()
        val kind = turn.kind
        val kindOk = step.kind?.let { expected ->
            (kind == expected).also { matched ->
                if (!matched) {
                    failures += "вид ответа: ожидался «${expected.title}», получен " +
                        (kind?.title ?: turn.refusal?.let { "отказ" } ?: turn.error?.let { "сбой" } ?: "ничего")
                }
            }
        }
        val refusalOk = if (!step.refusal) {
            null
        } else {
            val refused = turn.refusal != null && !turn.refusalReason.isNullOrBlank()
            refused.also { matched ->
                if (!matched) {
                    failures += if (turn.refusal == null) {
                        "ожидался отказ, получен ответ" + (kind?.let { " (${it.title})" } ?: "")
                    } else {
                        "отказ без причины: причина обязана быть названа"
                    }
                }
            }
        }
        val goalOk = if (!step.goal) null else {
            val matched = if (step.goalFromMemory) goalInMemory(turn, scenario) else goalRestated(turn, scenario)
            if (!matched) {
                failures += if (step.goalFromMemory) {
                    "цель разговора не записалась в память задачи этим ходом"
                } else {
                    "в ответе не названа цель разговора " +
                        "(ожидались слова: ${scenario.goalKeywords.joinToString(", ")})"
                }
            }
            matched
        }

        // Ответ сверяется в нормализованном виде, как это делает день 22 ([Check.evaluate],
        // `Check.normalize`): признак факта — основа слова в нижнем регистре, и «Петра» в начале
        // фразы обязано засчитываться так же, как «петра» в середине. Живой прогон показал, что
        // без нормализации проверка пропускала ровно те факты, что названы с большой буквы,
        // и «ответ назван не полностью» было ошибкой сверки, а не ответа.
        val answer = Check.normalize(turn.answer.orEmpty())
        return ChatScenarioCheck(
            index = index,
            question = step.text,
            kindOk = kindOk,
            refusalOk = refusalOk,
            goalOk = goalOk,
            facts = step.facts.map { fact -> Check.contains(answer, fact) },
            failures = failures
        )
    }

    /**
     * Названа ли в ответе цель разговора.
     *
     * Проверяются слова сценария ([ChatScenario.goalKeywords]), а не формулировка памяти: память
     * пишет модель, и сверять её с ней же — значит проверять, что модель согласна сама с собой.
     * Достаточно [MIN_GOAL_WORDS] слов, а не всех: цель человек называет и своими словами, и порядок
     * слов в ответе свободный; требование всех слов мерило бы формулировку.
     */
    /**
     * Записала ли память задачи цель разговора после этого хода.
     *
     * Сверяются слова сценария, а не память с памятью: цель в память пишет модель своими словами,
     * и «цель записана» проверяется тем, что в записи есть предмет разговора, названный человеком
     * ([ChatScenario.goalKeywords]). Формулировку модель может сократить — поэтому слов нужно
     * [MIN_GOAL_WORDS], как и при сверке ответа ([goalRestated]).
     */
    fun goalInMemory(turn: ChatTurn, scenario: ChatScenario): Boolean {
        val goal = turn.memory.goal ?: return false
        val memory = Check.normalize(goal)
        val matched = scenario.goalKeywords.count { keyword -> Check.matches(memory, keyword) }
        return matched >= minOf(MIN_GOAL_WORDS, scenario.goalKeywords.size)
    }

    fun goalRestated(turn: ChatTurn, scenario: ChatScenario): Boolean {
        val raw = turn.answer ?: return false
        val answer = Check.normalize(raw)
        val matched = scenario.goalKeywords.count { keyword -> Check.matches(answer, keyword) }
        return matched >= minOf(MIN_GOAL_WORDS, scenario.goalKeywords.size)
    }

    /**
     * Отчёт о прогоне сценария: приговор, проверки по шагам и разговор целиком.
     *
     * Печатается и то, что сошлось, и то, что нет: отчёт, в котором видно только провалы, читается
     * как список придирок, а отчёт, где видно только успехи, ничего не доказывает. Содержание ответов
     * печатается целиком — по нему читатель и судит, держался ли разговор предмета.
     */
    fun markdown(
        sessionId: String,
        run: ChatScenarioRun,
        settings: ChatSettings,
        model: String
    ): String = buildString {
        val scenario = run.scenario
        appendLine("# Сценарий ${scenario.name}: ${scenario.title}")
        appendLine()
        appendLine("Разговор: $sessionId.")
        appendLine("Цель разговора: ${scenario.goal}.")
        appendLine("Реплик: ${scenario.length}. Модель: $model. Настройки поиска: ${settings.description}.")
        appendLine()
        appendLine("## Приговор")
        appendLine()
        run.verdict.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Проверки по шагам")
        appendLine()
        appendLine("| # | реплика | вид ответа | цель | отказ | сведения |")
        appendLine("|---|---|---|---|---|---|")
        run.checks.forEach { check ->
            appendLine(
                "| ${check.index} | ${check.question.replace(Regex("\\s+"), " ").take(60)} | " +
                    "«${check.kindOk?.let { if (it) "да" else "нет" } ?: "—"}» | " +
                    "${check.goalOk?.let { if (it) "да" else "нет" } ?: "—"} | " +
                    "${check.refusalOk?.let { if (it) "да" else "нет" } ?: "—"} | " +
                    "${check.factsMatched}/${check.factsTotal} |"
            )
        }
        appendLine()
        run.checks.filter { !it.passed }.forEach { check ->
            appendLine("Шаг ${check.index} — нарушения:")
            check.failures.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Разговор")
        appendLine()
        run.turns.forEach { turn ->
            appendLine("### ${turn.index}. ${turn.question}")
            appendLine()
            appendLine("Реплика человека: ${turn.at}")
            appendLine()
            appendLine(turn.answer ?: turn.error?.let { "Ответ не получен: $it" }
                ?: "Отказ: ${turn.refusalReason}")
            appendLine()
            appendLine("Источники: ${turn.sourcesLine}")
            appendLine()
            appendLine("Память задачи (${turn.memoryNote}):")
            appendLine()
            appendLine(turn.memory.render().ifEmpty { "— пуста" })
            appendLine()
        }
    }

    /** Сколько слов цели достаточно, чтобы считать её названной. */
    private const val MIN_GOAL_WORDS = 2
}
