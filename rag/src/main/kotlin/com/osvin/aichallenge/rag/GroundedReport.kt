package com.osvin.aichallenge.rag

/**
 * Вопрос дня 24: ответ предыдущего режима и grounded-ответ на **той же** выдаче поиска.
 *
 * Одно поле [previous] и одно [grounded] держат сравнение честным: выдача одна, модель одна, вопрос
 * один, и разница между ответами — это разница этапов дня 24 (проверка достаточности, требование
 * цитат, программная проверка). Если бы grounded-режим искал сам, разница включила бы и разницу
 * выдач, а её нельзя списать ни на один из этапов.
 */
data class GroundedTrial(
    val control: ControlQuestion,
    val previous: Run,
    val grounded: GroundedAnswer,
    val check: GroundedCheck
)

/**
 * Отчёт дня 24: подтверждённые ответы против ответов предыдущего дня.
 *
 * Три взгляда, и центр тяжести здесь сдвинут относительно дня 23. Консоль — таблица «что вышло
 * по вопросам»: верен ли ответ, есть ли источник и цитата, подтверждает ли цитата ответ, был ли
 * отказ. Файл сравнения — метрики §17 и **готовые ответы в том виде, в каком их видит пользователь**
 * (ответ, источники, цитаты): задание дня в том и состоит, чтобы утверждение можно было проверить,
 * и отчёт обязан показывать ровно то, что показывает система. Лог — весь путь запроса по §19, включая
 * решение о достаточности, контекст, ушедший в модель, и результат проверки каждой цитаты.
 *
 * Отдельно от [StageReport], потому что это другой отчёт: у дня 23 сравнивались режимы по средней
 * оценке, здесь — по проверяемости ответа. Общий код здесь один — проверка фактов ([Check]) и шапка
 * с базой и индексом; сводить их в один класс значило бы завести отчёт с двумя несравнимыми наборами
 * метрик и флагом «какой из них печатать».
 */
class GroundedReport(
    private val header: RunHeader,
    private val stages: StageConfig,
    /** Просили ли у API ответ строго в виде JSON: условие прогона, а не деталь реализации. */
    private val jsonFormat: Boolean
) {

    /** Короткая сводка: таблица по вопросам и метрики дня. */
    fun console(trials: List<GroundedTrial>, summary: GroundingSummary): String = buildString {
        appendLine("Grounded RAG: ответы с источниками и цитатами. Модель: ${header.model}.")
        appendLine(describe())
        appendLine()
        appendLine(row("#", "вопрос", "пред. RAG", "grounded", "источник", "цитата", "подтв.", "отказ"))
        for (trial in trials) {
            val check = trial.check
            appendLine(
                row(
                    trial.control.id,
                    shorten(trial.control.question, QUESTION_WIDTH),
                    if (check.previousCorrect) "верно" else "нет",
                    if (check.correct) "верно" else "нет",
                    mark(check.hasSource),
                    mark(check.hasQuote),
                    mark(check.grounded),
                    abstention(check)
                )
            )
        }
        appendLine()
        append(totals(summary))
    }

    /**
     * Файл сравнения: метрики §17, таблица §18, ответы пользователю и разбор проверок.
     *
     * Ответы печатаются в том виде, в каком их получает пользователь ([render]): три блока — ответ,
     * источники, цитаты. Это не украшение отчёта, а его главный аргумент: проверить утверждение
     * можно только по цитате рядом с источником, и если отчёт покажет ответ без них, проверять будет
     * нечего.
     */
    fun markdown(trials: List<GroundedTrial>, summary: GroundingSummary): String = buildString {
        appendLine("# Grounded RAG: цитаты, источники и режим «не знаю»")
        appendLine()
        appendLine(describe())
        appendLine()
        appendLine("## Метрики дня (§17)")
        appendLine()
        append(metrics(summary))
        appendLine()
        appendLine("## Проверка по вопросам (§18)")
        appendLine()
        appendLine(
            "| # | Вопрос | Ответ верен | Пред. RAG верен | Source | Quote | Quote подтверждает | " +
                "Не знаю | Выдумано |"
        )
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for (trial in trials) {
            val check = trial.check
            appendLine(
                "| ${trial.control.id} | ${trial.control.question} " +
                    "| ${mark(check.correct)} " +
                    "| ${mark(check.previousCorrect)} " +
                    "| ${mark(check.hasSource)} " +
                    "| ${mark(check.hasQuote)} " +
                    "| ${mark(check.grounded)} " +
                    "| ${abstention(check)} " +
                    "| ${fabricated(check)} |"
            )
        }
        appendLine()
        appendLine("«Quote подтверждает» — все утверждения ответа подтверждены цитатами из " +
            "процитированных чанков и цитаты несут факты, которые называет ответ. Для вопроса без " +
            "ответа в базе правильным поведением считается отказ, и отсутствие источника и цитаты " +
            "там ошибкой не считается (§18).")
        appendLine()
        appendLine("## Ответы как их видит пользователь")
        for (trial in trials) {
            appendLine()
            appendLine("### ${trial.control.id}. ${trial.control.question}")
            appendLine()
            appendLine(render(trial.grounded))
            appendLine()
            appendLine("**Проверка:** ${verdict(trial)}")
            appendLine()
            appendLine("Предыдущий режим (день 23, та же выдача):")
            appendLine()
            append(quoteText(trial.previous.answer.text))
            appendLine()
            appendLine("Сырой ответ модели: `${truncate(trial.grounded.raw)}`")
        }
        appendLine()
        appendLine("## Разбор проверок")
        appendLine()
        for (trial in trials) {
            appendLine()
            appendLine("### ${trial.control.id}")
            appendLine()
            append(checks(trial))
        }
    }

    /**
     * Лог пути запроса (§19): от исходного вопроса до результата проверки цитат.
     *
     * Здесь же то, чего нет в других отчётах дня: решение о достаточности и его числа. Без них
     * «система отказалась» неотличимо от «до модели не дошло», а это разные поломки: первая лечится
     * порогом, вторая — обращением к модели.
     */
    fun log(trials: List<GroundedTrial>): String = buildString {
        appendLine("# Путь запроса по этапам: от вопроса до проверки цитат")
        appendLine()
        appendLine(describe())
        for (trial in trials) {
            val grounded = trial.grounded
            appendLine()
            appendLine("## ${trial.control.id}. ${trial.control.question}")
            appendLine()
            appendLine("**Исходный вопрос.** ${trial.control.question}")
            appendLine()
            appendLine("**Переписанный запрос.** ${grounded.retrieval.trace?.rewritten ?: "переписывания не было"}")
            appendLine()
            appendLine("**Выдача поиска (Retrieved Top-${stages.retrievalTopK}).**")
            appendLine()
            append(trace(grounded.retrieval.trace))
            appendLine()
            appendLine("**Достаточность контекста.** ${grounded.confidence.decision.title}; " +
                "фрагментов в контексте ${grounded.confidence.considered}, лучшая близость " +
                "${grounded.confidence.bestSimilarity?.let { "%.3f".format(it) } ?: "—"}, " +
                "порог ${"%.2f".format(grounded.confidence.threshold)}.")
            appendLine()
            appendLine("**Контекст, переданный модели.**")
            appendLine()
            append(
                if (grounded.messages.isEmpty()) "модель не спрашивали: контекст недостаточен"
                else grounded.messages.joinToString("\n\n") { message ->
                    "${message.role}:\n${message.content}"
                }
            )
            appendLine()
            appendLine()
            appendLine("**Ответ модели (сырой).** ${truncate(grounded.raw)}")
            appendLine()
            appendLine("Замечание этапа: ${grounded.note ?: "нет"}")
            appendLine()
            appendLine("**Источники (из метаданных чанков).**")
            appendLine()
            append(grounded.sources.joinToString("\n") { it.label }.ifEmpty { "нет" })
            appendLine()
            appendLine()
            appendLine("**Проверка цитат (§11, §13).**")
            appendLine()
            append(checks(trial))
            appendLine()
            appendLine("**Решение.** ${verdict(trial)}")
        }
    }

    /** Ответ в том виде, в каком его видит пользователь (§3): ответ, источники, цитаты. */
    fun render(grounded: GroundedAnswer): String {
        if (grounded.refused) {
            return buildString {
                appendLine("Ответ:")
                appendLine(grounded.answer ?: GroundedAnswer.REFUSAL_TEXT)
                appendLine()
                appendLine("Источники: нет — отказ не сопровождается источниками (§10).")
                appendLine("Цитаты: нет — подтверждать нечего (§10).")
                appendLine(
                    "Причина отказа: ${grounded.refusal?.title}" +
                        (grounded.note?.let { " ($it)" } ?: "")
                )
            }.trimEnd()
        }
        return buildString {
            appendLine("Ответ:")
            appendLine(grounded.answer.orEmpty())
            appendLine()
            appendLine("Источники:")
            grounded.sources.forEachIndexed { index, source ->
                appendLine("${index + 1}. ${source.source}")
                source.section?.let { appendLine("   section: $it") }
                appendLine("   chunk_id: ${source.chunkId} (фрагмент [${source.fragment}])")
                appendLine(
                    "   страницы: ${source.pages.joinToString(", ").ifEmpty { "—" }}; " +
                        "близость ${"%.3f".format(source.similarity)}" +
                        (source.rerankScore?.let { ", оценка второго этапа ${"%.3f".format(it)}" } ?: "")
                )
            }
            appendLine()
            appendLine("Цитаты:")
            grounded.claims.forEachIndexed { index, claim ->
                appendLine("${index + 1}. «${claim.quote}»")
                appendLine("   утверждение: ${claim.text}")
                appendLine("   фрагмент [${claim.fragment}]")
            }
        }.trimEnd()
    }

    /** Шапка: база, индекс, модель и условия прогона. */
    private fun describe(): String = buildString {
        appendLine(
            "База: ${header.baseChars} символов, ${header.baseTokens} токенов, ${header.basePages} страниц, " +
                "${header.chunks} чанков (${header.strategy.name.lowercase()})."
        )
        appendLine("Векторы: ${header.embedding}. Модель: ${header.model}.")
        appendLine(
            if (header.reused) "Индекс взят готовым из файла: повторной индексации при запросах нет."
            else "Индекс собран этим прогоном за ${seconds(header.indexMillis)}."
        )
        appendLine(
            "Режимы: предыдущий — конвейер дня 23 (переписывание — ${stages.rewrite ?: "нет"}, " +
                "второй этап — ${stages.rerank ?: "нет"}), ответ тот же, но без источников и цитат; " +
                "grounded — та же выдача, проверка достаточности и ответ с цитатами."
        )
        appendLine(
            "Порог достаточности: ${"%.2f".format(stages.threshold)} — то же число, что у фильтра (§8). " +
                "Ответ модели запрошен ${if (jsonFormat) "объектом JSON (response_format)" else "свободным текстом"}."
        )
        appendLine(
            "Первый Top-K (кандидаты): ${stages.retrievalTopK}; финальный Top-K (контекст): " +
                "${stages.finalTopK}."
        )
    }

    /** Метрики §17: четыре показателя дня и точность ответов для сравнения режимов. */
    private fun metrics(summary: GroundingSummary): String = buildString {
        appendLine("| Метрика | Значение | Как считалась |")
        appendLine("| --- | --- | --- |")
        appendLine(
            "| Answer Accuracy | ${rate(summary.rightAnswers, summary.questions)} | " +
                "верных ответов и уместных отказов из ${summary.questions} вопросов |"
        )
        appendLine(
            "| Source Coverage | ${rate(summary.withSource, summary.answered)} | " +
                "ответов с источником из ${summary.answered} обычных ответов |"
        )
        appendLine(
            "| Quote Coverage | ${rate(summary.withQuote, summary.answered)} | " +
                "ответов с цитатой из ${summary.answered} обычных ответов |"
        )
        appendLine(
            "| Grounded Answer Rate | ${rate(summary.grounded, summary.answered)} | " +
                "ответов, подтверждённых цитатами, из ${summary.answered} обычных ответов |"
        )
        appendLine(
            "| Correct Abstention Rate | ${rate(summary.validAbstentions, summary.abstainNeeded)} | " +
                "уместных отказов из ${summary.abstainNeeded} вопросов, ответа на которые в контексте не было |"
        )
        appendLine()
        appendLine("Подтверждение ответа и охваты цитат считаются по обычным ответам: у отказа " +
            "источника и цитаты нет по построению (§10), и включать его в знаменатель значило бы " +
            "наказывать систему за правильный отказ дважды.")
        appendLine()
        appendLine("Факты: предыдущий режим назвал ${summary.factsPrevious} из ${summary.factsTotal}, " +
            "grounded — ${summary.factsGrounded} из ${summary.factsTotal}.")
        appendLine("Отказы: всего ${summary.abstained}, из них уместных ${summary.validAbstentions} " +
            "и лишних ${summary.wrongAbstentions}.")
        appendLine("Выдуманные ссылки на фрагменты: ${summary.fabricatedSources}; цитаты, которых нет " +
            "в процитированном чанке: ${summary.invalidQuotes}.")
    }

    /** То же коротко, для консоли. */
    private fun totals(summary: GroundingSummary): String = buildString {
        appendLine("Вопросов: ${summary.questions}; обычных ответов ${summary.answered}, отказов ${summary.abstained}.")
        appendLine(
            "Answer Accuracy: ${rate(summary.rightAnswers, summary.questions)} " +
                "(верных ответов ${summary.correct} + уместных отказов ${summary.validAbstentions}); " +
                "Source Coverage: ${rate(summary.withSource, summary.answered)}; " +
                "Quote Coverage: ${rate(summary.withQuote, summary.answered)}."
        )
        appendLine(
            "Grounded Answer Rate: ${rate(summary.grounded, summary.answered)}; " +
                "Correct Abstention Rate: ${rate(summary.validAbstentions, summary.abstainNeeded)} " +
                "(лишних отказов ${summary.wrongAbstentions})."
        )
        appendLine("Предыдущий режим был верен в ${summary.previousCorrect} из ${summary.questions}.")
        appendLine("Выдуманных ссылок ${summary.fabricatedSources}, невалидных цитат ${summary.invalidQuotes}.")
    }

    /** Разбор этапов для одного вопроса: кандидаты, фильтр, оценки и место в контексте. */
    private fun trace(trace: Trace?): String {
        trace ?: return "трейса нет: поиск шёл напрямую, без этапов дня 23."
        return buildString {
            appendLine("| # поиска | Фрагмент | Близость | Фильтр | Оценка этапа | Позиция | В контексте |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for (candidate in trace.candidates) {
                appendLine(
                    "| ${candidate.source.rank} | ${candidate.source.label} " +
                        "| ${"%.3f".format(candidate.similarity)} " +
                        "| ${if (candidate.accepted) "прошёл" else "отсеян"} " +
                        "| ${candidate.rerankScore?.let { "%.3f".format(it) } ?: "—"} " +
                        "| ${candidate.rerankRank ?: "—"} " +
                        "| ${candidate.finalRank ?: "—"} |"
                )
            }
            appendLine()
            appendLine("Замечание этапов: ${trace.note ?: "нет"}")
        }
    }

    /** Проверка цитат одного вопроса: что нашлось в чанке, а что нет. */
    private fun checks(trial: GroundedTrial): String {
        val grounded = trial.grounded
        if (grounded.checks.isEmpty()) {
            return "цитат нет: " + (grounded.note ?: "модель вернула ответ без утверждений")
        }
        return buildString {
            appendLine("| # | Утверждение | Фрагмент | Чанк | Цитата в чанке | Причина отказа |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            grounded.checks.forEachIndexed { index, check ->
                appendLine(
                    "| ${index + 1} | ${shorten(check.claim.text.replace('\n', ' '), CLAIM_WIDTH)} " +
                        "| [${check.claim.fragment}] " +
                        "| ${check.chunkId ?: "—"} " +
                        "| ${if (check.found) "да" else "нет"} " +
                        "| ${check.reason ?: "—"} |"
                )
            }
        }
    }

    /** Строка решения по вопросу: что именно вышло — подтверждённый ответ, отказ или потеря. */
    private fun verdict(trial: GroundedTrial): String {
        val check = trial.check
        val grounded = trial.grounded
        return when {
            check.wrongAbstention ->
                "лишний отказ: правильный фрагмент был в контексте, ответ потерян"
            check.abstained ->
                "отказ: ${grounded.refusal?.title} (${grounded.note ?: "без замечаний"})"
            check.grounded && check.correct -> "подтверждённый ответ: верен и подтверждён цитатами"
            check.grounded -> "подтверждённый цитатами, но неверный по фактам набор"
            !check.hasQuote -> "ответ без цитат: подтверждать нечем"
            check.invalidQuotes > 0 ->
                "часть утверждений не подтвердилась: цитаты отброшены, причины в таблице проверок"
            else -> "ответ есть, но связь цитат с фактами не подтвердилась"
        }
    }

    /** Столбец «Не знаю»: отказ, и был ли он уместен. */
    private fun abstention(check: GroundedCheck): String = when {
        !check.abstained -> "—"
        check.validAbstention -> "да"
        else -> "лишний"
    }

    /** Столбец «Выдумано»: ссылки вне контекста и невалидные цитаты, если они были. */
    private fun fabricated(check: GroundedCheck): String = when {
        check.fabricatedSources + check.invalidQuotes == 0 -> "—"
        else -> "${check.fabricatedSources} ссылок, ${check.invalidQuotes} цитат"
    }

    /** Ответ предыдущего режима в виде цитаты: в файле сравнения он читается как чужой текст. */
    private fun quoteText(text: String): String =
        text.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

    /** Доля словами и числом: «8 из 9 (89 %)». */
    private fun rate(part: Int, total: Int): String =
        if (total == 0) "нет данных" else "$part из $total (${part * 100 / total} %)"

    private fun mark(value: Boolean): String = if (value) "✓" else "—"

    private fun truncate(text: String?): String = text
        ?.replace(Regex("\\s+"), " ")
        ?.take(RAW_LIMIT)
        ?.let { if (it.length >= RAW_LIMIT) "$it…" else it }
        ?: "—"

    private fun seconds(millis: Long): String = "%.1f с".format(millis / 1000.0)

    private fun shorten(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 1) + "…"

    /** Таблица в консоли: колонки выравниваются по самой длинной ячейке в столбце. */
    private fun row(vararg cells: String): String =
        cells.mapIndexed { index, cell -> shorten(cell, COLUMN_WIDTHS[index]).padEnd(COLUMN_WIDTHS[index]) }
            .joinToString(" ")
            .trimEnd()

    private val COLUMN_WIDTHS = listOf(4, QUESTION_WIDTH, 9, 9, 9, 7, 7, 9)

    private companion object {
        const val QUESTION_WIDTH = 44
        const val CLAIM_WIDTH = 60
        const val RAW_LIMIT = 400
    }
}
