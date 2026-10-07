package com.osvin.aichallenge.rag.chat

/**
 * Итог разговора числами: то, по чему проверяется задание дня, — не «чат работает», а «сколько
 * реплик получили ответ, у скольких назван источник, где была цель».
 *
 * Сводка считается по ходам, а не ведётся по ходу разговора, и это важно: те же числа должна
 * получить и страница, и отчёт, и проверка сценария ([ChatScenarios]), а один счётчик, обновляемый
 * в трёх местах, рано или поздно начнёт врать в одном из них. Ходы — единственный источник правды:
 * всё остальное — счёт по ним.
 *
 * `answersWithoutSource` — та самая мера «источники всегда», и она считает **ответы** без источника,
 * а не ходы: у отказа источника нет по построению (задание дня 24 это прямо запрещает), и записать
 * отказ в недостачу источников значило бы требовать невозможного — источник, которого нет. Отказ
 * обязан назвать причину, и её наличие проверяется отдельно ([refusals] против [answersWithoutSource]).
 */
data class ChatSummary(
    val turns: Int,
    /** Сколько реплик получило ответ. */
    val answered: Int,
    /** Из них по найденным фрагментам — с источниками и цитатами. */
    val fromBase: Int,
    /** Из них по памяти задачи — о самом разговоре. */
    val fromMemory: Int,
    val refusals: Int,
    /** Отказы без названной причины: должно быть ноль. */
    val refusalsWithoutReason: Int,
    /** Сбои обращения к модели: не отказ, а недошедший запрос. */
    val errors: Int,
    /** Ответы без источника: должно быть ноль. */
    val answersWithoutSource: Int,
    /** Сколько всего источников названо (с повторами одного чанка в разных ходах). */
    val sourcesTotal: Int,
    /** Подтверждённых утверждений во всём разговоре. */
    val claims: Int,
    /** Цитат, отброшенных проверкой в ответах по базе. */
    val quotesDropped: Int,
    /** Цель разговора в конце. */
    val goal: String?,
    /** На каком ходу цель появилась. */
    val goalFixedAt: Int?,
    /** Переформулировки цели: пусто — цель держалась. */
    val goalChanges: List<String>,
    val clarifications: Int,
    val constraints: Int,
    val terms: Int,
    val memoryUpdated: Int,
    val memoryUnchanged: Int,
    val memoryFailed: Int
) {

    /** У каждого ответа назван источник; у отказа и сбоя — своя строка. */
    val sourcesEverywhere: Boolean get() = answersWithoutSource == 0

    /** Цель зафиксирована и с тех пор не менялась — то, что задание называет «не теряет цель». */
    val goalKept: Boolean get() = !goal.isNullOrBlank() && goalChanges.isEmpty()

    /**
     * У каждого отказа названа причина — вторая половина требования «источники всегда».
     *
     * У отказа источника нет и быть не может, и его источник — сказанная причина; отказ без причины
     * был бы ответом без источника, то есть прямым нарушением задания. Пустой список отказов эту
     * проверку проходит (нарушать нечего) — поэтому она и считается по отказам, а не по ходам.
     */
    val refusalsNamed: Boolean get() = refusalsWithoutReason == 0

    /** Строка итога для отчёта и страницы: те же числа, что в полях. */
    val line: String
        get() = "реплик $turns, ответов $answered (по базе $fromBase, по памяти задачи $fromMemory), " +
            "отказов $refusals, сбоев $errors"
}

/**
 * Отчёт и лог разговора: то, что остаётся от прогона и по чему его разбирают.
 *
 * Устройство повторяет день 24 (`GroundedReport`), потому что и задача та же: показать не только
 * ответ, но и его основание. Отличие одно — ход, а не вопрос: у хода есть память до и после
 * и запрос, которым искали, и без них разбор длинного разговора невозможен. «Модель забыла
 * ограничение» и «ограничение не попало в промпт» выглядят одинаково в ответе, но по-разному
 * в отчёте: первое видно по блоку памяти, второе — по её отсутствию.
 *
 * Лог отдельно от отчёта по той же причине, что и в дни 23–24: отчёт читает человек и ему нужен
 * смысл, лог читает тот, кто разбирает сбой формата, и ему нужны запросы к модели дословно.
 * Три запроса на ход ([ChatTurn.answerMessages], [ChatTurn.memoryMessages], [ChatTurn.rewriteMessages])
 * в лог идут раздельно и подписанными — иначе непонятно, чей промпт дал странный ответ.
 */
object ChatReport {

    /** Итог разговора по ходам. */
    fun summarize(turns: List<ChatTurn>): ChatSummary {
        val last = turns.lastOrNull()
        val memory = last?.memory ?: TaskMemory()
        return ChatSummary(
            turns = turns.size,
            answered = turns.count { it.answered },
            fromBase = turns.count { it.kind == ChatAnswerKind.BASE },
            fromMemory = turns.count { it.fromMemory },
            refusals = turns.count { it.refusal != null },
            refusalsWithoutReason = turns.count { it.refusal != null && it.refusalReason.isNullOrBlank() },
            errors = turns.count { it.error != null },
            answersWithoutSource = turns.count { it.answered && !it.hasSource },
            sourcesTotal = turns.sumOf { it.sources.size },
            claims = turns.sumOf { it.claims.size },
            // Отброшенные цитаты считаются только у ответов по базе: у ответа о разговоре проверять
            // нечего, и любая цифра там была бы выдумкой.
            quotesDropped = turns.filter { it.kind == ChatAnswerKind.BASE }
                .sumOf { it.checks.size - it.claims.size },
            goal = memory.goal,
            goalFixedAt = turns.firstOrNull { !it.memory.goal.isNullOrBlank() }?.index,
            // Переформулировкой считается только смена непустой цели на другую непустую.
            // Первое появление цели ею не является: до него цели не было вовсе, и записать это
            // в «переформулировки» значило бы объявить провалом дня то, что цель как раз и появилась.
            // Пустая цель после непустой тоже не переформулировка, а потеря — но её в этом разговоре
            // быть не может: слияние памяти пустой целью прежнюю не затирает ([TaskMemory.merge]).
            goalChanges = turns.mapNotNull { turn ->
                val before = turn.memoryBefore.goal
                val after = turn.memory.goal
                val reformulated = !before.isNullOrBlank() && !after.isNullOrBlank() && before != after
                if (!reformulated) null else "ход ${turn.index}: «$before» → «$after»"
            },
            clarifications = memory.clarifications.size,
            constraints = memory.constraints.size,
            terms = memory.terms.size,
            memoryUpdated = turns.count { it.memoryChanged },
            memoryUnchanged = turns.count { !it.memoryChanged && it.memoryNote?.startsWith("память без") == true },
            memoryFailed = turns.count { it.memoryNote?.startsWith("память не обновлена") == true }
        )
    }

    /**
     * Отчёт разговора в markdown: настройки, сводка и каждый ход целиком.
     *
     * Ходы идут по порядку и с памятью после каждого: разговор — это последовательность, в которой
     * важно, что знала система **на момент** реплики, поэтому память печатается на ходе, а не одной
     * таблицей в конце.
     */
    fun markdown(sessionId: String, turns: List<ChatTurn>, settings: ChatSettings, model: String): String {
        val summary = summarize(turns)
        return buildString {
            appendLine("# Мини-чат с RAG и памятью задачи")
            appendLine()
            appendLine("Разговор: $sessionId. Модель: $model. Настройки поиска: ${settings.description}.")
            appendLine()
            appendLine("## Итог")
            appendLine()
            appendLine("- реплик: ${summary.turns}; ответов: ${summary.answered} " +
                "(по базе ${summary.fromBase}, по памяти задачи ${summary.fromMemory}); " +
                "отказов: ${summary.refusals}; сбоев: ${summary.errors}")
            appendLine("- ответов без источника: ${summary.answersWithoutSource} " +
                "(источников названо всего: ${summary.sourcesTotal})")
            appendLine("- отказов без причины: ${summary.refusalsWithoutReason} " +
                "(отказов всего: ${summary.refusals})")
            appendLine("- подтверждённых утверждений: ${summary.claims}, " +
                "отброшенных цитат: ${summary.quotesDropped}")
            appendLine("- цель: ${summary.goal ?: "не зафиксирована"}" +
                (summary.goalFixedAt?.let { " (зафиксирована на ходу $it)" } ?: "") +
                if (summary.goalChanges.isEmpty()) "; переформулировок не было" else
                    "; переформулировки: " + summary.goalChanges.joinToString("; "))
            appendLine("- память задачи в конце: уточнений ${summary.clarifications}, " +
                "ограничений ${summary.constraints}, терминов ${summary.terms}")
            appendLine("- память обновлялась на ${summary.memoryUpdated} ходах, " +
                "не менялась на ${summary.memoryUnchanged}, не обновлялась на ${summary.memoryFailed}")
            appendLine()
            turns.forEach { append(turn(it, settings)) }
        }
    }

    /**
     * Лог разговора: что ушло в модель и что она вернула, без пересказа.
     *
     * Запросы печатаются целиком и в том порядке, в каком они происходили (память, запрос поиска,
     * ответ): по логу видно не только содержание, но и последовательность, а она здесь — часть
     * поведения. Ответы модели печатаются сырыми ([ChatTurn.raw], [ChatTurn.memoryRaw]): разбор
     * формата — это то, что по логу и проверяют.
     */
    fun log(sessionId: String, turns: List<ChatTurn>, settings: ChatSettings, model: String): String = buildString {
        appendLine("# Лог мини-чата")
        appendLine()
        appendLine("Разговор: $sessionId. Модель: $model. Настройки поиска: ${settings.description}.")
        appendLine()
        turns.forEach { turn ->
            appendLine("## Ход ${turn.index}: ${turn.question}")
            appendLine()
            appendLine("Запрос поиска: ${turn.query}" + (turn.queryNote?.let { " ($it)" } ?: ""))
            appendLine("Найдено кандидатов: ${turn.found.sources.size}, " +
                "в контексте: ${turn.confidence.considered}, порог: ${turn.confidence.threshold}")
            appendLine()
            appendLine("### Запрос на извлечение памяти → модель")
            appendLine()
            appendLine(code(turn.memoryMessages.joinToString("\n\n") { "${it.role}:\n${it.content}" }))
            appendLine()
            appendLine("### Ответ модели на извлечение памяти")
            appendLine()
            appendLine(code(turn.memoryRaw ?: "(ответа нет)"))
            appendLine()
            appendLine("### Запрос на переписывание → модель")
            appendLine()
            appendLine(code(turn.rewriteMessages.joinToString("\n\n") { "${it.role}:\n${it.content}" }))
            appendLine()
            appendLine("### Запрос на ответ → модель")
            appendLine()
            appendLine(code(turn.answerMessages.joinToString("\n\n") { "${it.role}:\n${it.content}" }))
            appendLine()
            appendLine("### Ответ модели")
            appendLine()
            appendLine(code(turn.raw ?: "(ответа нет: до модели дело не дошло)"))
            appendLine()
        }
    }

    /** Строка хода для консоли: вопрос, вид ответа, источники — без простыней. */
    fun console(turn: ChatTurn): String = buildString {
        append("ход ${turn.index}: ")
        append(turn.question.replace(Regex("\\s+"), " ").take(70))
        append(" → ")
        append(
            when {
                turn.error != null -> "сбой: ${turn.error}"
                turn.refusal != null -> "отказ (${turn.refusal.title})"
                turn.fromMemory -> "ответ по памяти задачи"
                else -> "ответ по базе, источников ${turn.sources.size}"
            }
        )
        append(" [${turn.totalMillis} мс]")
    }

    private fun turn(turn: ChatTurn, settings: ChatSettings): String = buildString {
        appendLine("## Ход ${turn.index}: ${turn.question}")
        appendLine()
        appendLine("Реплика человека: ${turn.at}")
        appendLine()
        if (turn.query != turn.question) {
            appendLine("Запрос поиска: ${turn.query}" + (turn.queryNote?.let { " ($it)" } ?: ""))
            appendLine()
        }
        appendLine("Поиск: кандидатов ${turn.found.sources.size}, в контекст отобрано " +
            "${turn.found.sources.size}; решение о достаточности — ${turn.confidence.decision.title}" +
            (turn.confidence.bestSimilarity?.let { " (лучшая близость ${"%.2f".format(it)}, " +
                "порог ${turn.confidence.threshold})" } ?: " (сравнивать не с чем)"))
        appendLine()
        when {
            turn.error != null -> {
                appendLine("Ответ: не получен — ${turn.error}")
                appendLine()
                appendLine("Источники: нет — $${turn.error}".replace("$${turn.error}", turn.error!!))
            }
            turn.answer != null -> {
                appendLine("Ответ (${turn.kind?.title}):")
                appendLine()
                appendLine(turn.answer)
                appendLine()
                if (turn.claims.isNotEmpty()) {
                    appendLine("Утверждения и цитаты:")
                    appendLine()
                    turn.claims.forEachIndexed { index, claim ->
                        val check = turn.checks.getOrNull(index)
                        appendLine("${index + 1}. ${claim.text}")
                        appendLine("   цитата: «${claim.quote}» — фрагмент [${claim.fragment}], " +
                            (if (check?.found == true) "подтверждена" else "не подтверждена"))
                    }
                    appendLine()
                }
            }
            else -> {
                appendLine("Ответ: отказ — ${turn.refusal?.title ?: "причина не записана"}")
                appendLine()
                appendLine("Причина: ${turn.refusalReason ?: "не записана"}")
            }
        }
        appendLine()
        appendLine("Источники: ${turn.sourcesLine}")
        appendLine()
        if (turn.checks.any { !it.found }) {
            val dropped = turn.checks.filter { !it.found }
            appendLine("Отброшенные цитаты (${dropped.size}):")
            appendLine()
            dropped.forEach { droppedCheck ->
                appendLine("- «${droppedCheck.claim.quote}» — ${droppedCheck.reason ?: "причина не записана"}")
            }
            appendLine()
        }
        appendLine("Память задачи (${turn.memoryNote}):")
        appendLine()
        appendLine(turn.memory.render().ifEmpty { "— пуста" })
        appendLine()
        appendLine("Время: ответ ${turn.millis} мс, память ${turn.memoryMillis} мс, " +
            "запрос ${turn.rewriteMillis} мс; поиск " +
            "${turn.found.millis} мс; порог ${settings.threshold}")
        appendLine()
    }

    /** Дословный блок в логе: тройные кавычки вокруг текста, который может содержать что угодно. */
    private fun code(text: String): String = "```\n$text\n```"
}
