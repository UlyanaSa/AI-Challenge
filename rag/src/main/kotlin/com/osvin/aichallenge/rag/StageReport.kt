package com.osvin.aichallenge.rag

import com.osvin.aichallenge.indexing.pipeline.Tokens

/**
 * Настройки этапов улучшенного конвейера: то, чем один прогон отличается от другого.
 *
 * Отдельно от [RunHeader], потому что это числа другого рода. В шапке дня 22 — то, что описывает
 * базу и индекс (модель, нарезка, размер корпуса), и она одинакова для всех режимов. Здесь — то,
 * чем режимы различаются и что задано прогоном: сколько кандидатов просит поиск, сколько фрагментов
 * уходит в контекст, какой порог стоит в фильтре и какие варианты переписывания и второго этапа
 * выбраны. Без этих чисел строки отчёта нельзя ни повторить, ни объяснить: «порог 0,5» и «порог 0,6» —
 * разные прогоны с разными числами.
 *
 * [rewrite] и [rerank] — имена вариантов, а не флаги: у этапа может быть несколько реализаций
 * ([LlmQueryRewriter], [HeuristicReranker], [LlmReranker]), и отчёт обязан говорить, которая работала.
 * Пустое имя означает, что этапа в этом прогоне нет вовсе, — так выглядит выключенный переписыватель,
 * и это тоже условие прогона.
 */
data class StageConfig(
    val baselineTopK: Int,
    val retrievalTopK: Int,
    val finalTopK: Int,
    val threshold: Double,
    val rewrite: String?,
    val rerank: String?
)

/**
 * Отчёт дня 23: базовый и улучшенный конвейер на одних вопросах, с разбором по этапам.
 *
 * Три взгляда, как и в дне 22, но с новым центром тяжести. Консоль — короткая сводка: сравнение
 * оценок и три числа попаданий. Файл сравнения — таблица по вопросам и ответы целиком: по ней видно,
 * что именно сказала модель. Лог — путь запроса по этапам, и это главное отличие от дня 22: там лог
 * показывал поиск и контекст, здесь — переписанный запрос, всех кандидатов с близостью, отметку
 * фильтра, старую и новую позицию после второго этапа и только потом контекст. Без этого разбора
 * вопрос «почему в контексте именно эти три фрагмента» остаётся без ответа, а задание (§19) требует
 * уметь назвать точный этап, на котором произошла ошибка.
 *
 * Метрики дня 22 (память против базового RAG) считает [Report.summary] — тот же код, что в дне 22:
 * второй набор формул разошёлся бы с первым, а сверять два отчёта стало бы нечем. Метрики этапов
 * считает [Stages.summary].
 */
class StageReport(
    private val header: RunHeader,
    private val stages: StageConfig,
    private val report: Report = Report(header)
) {

    /** Короткая сводка прогона: сравнение режимов и три числа попаданий по этапам. */
    fun console(trials: List<Trial>, summary: StageSummary): String = buildString {
        appendLine("Сравнение с базовым RAG: ${header.model}.")
        appendLine(describe())
        appendLine()
        appendLine(row("#", "вопрос", "без базы", "базовый", "улучшенный", "Hit@3", "потеря"))
        for (trial in trials) {
            val check = Stages.evaluate(trial.control, trial.improved.answer.retrieval.trace)
            appendLine(
                row(
                    trial.control.id,
                    shorten(trial.control.question, QUESTION_WIDTH),
                    score(trial.noBase.check),
                    score(trial.baseline.check),
                    score(trial.improved.check),
                    if (trial.control.absent) "—" else if (check?.inFinal == true) "да" else "нет",
                    check?.loss?.title ?: "—"
                )
            )
        }
        appendLine()
        append(totals(summary))
    }

    /** Файл сравнения: метрики, таблица по вопросам, разбор этапов и ответы целиком. */
    fun markdown(trials: List<Trial>, summary: StageSummary): String = buildString {
        appendLine("# Улучшенный RAG: переписывание, фильтрация и реранкинг")
        appendLine()
        appendLine(describe())
        appendLine()
        appendLine("## Итоговые метрики")
        appendLine()
        append(day22Totals(trials))
        appendLine()
        append(stageTotals(summary))
        appendLine()
        appendLine("## Сравнение по вопросам")
        appendLine()
        appendLine(
            "| # | Вопрос | Без базы | Базовый RAG | Улучшенный RAG | Hit@${stages.retrievalTopK} | " +
                "После фильтра | Hit@${stages.finalTopK} | Где потерялся | Итог |"
        )
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for (trial in trials) {
            val check = Stages.evaluate(trial.control, trial.improved.answer.retrieval.trace)
            appendLine(
                "| ${trial.control.id} | ${trial.control.question} " +
                    "| ${score(trial.noBase.check)} " +
                    "| ${score(trial.baseline.check)} " +
                    "| ${score(trial.improved.check)} " +
                    "| ${mark(check?.inRetrieval)} " +
                    "| ${mark(check?.inFiltered)} " +
                    "| ${mark(check?.inFinal)} " +
                    "| ${check?.loss?.title ?: "—"} " +
                    "| ${Check.outcome(trial.control, trial.improved.check).title} |"
            )
        }
        appendLine()
        appendLine("Оценка 0–2 считается по фактам и у всех трёх режимов одна: 0 — ответа нет или он " +
            "неверен, 1 — часть ожидаемых фактов, 2 — все. «Hit» — ожидаемый текст базы в выдаче " +
            "на этом шаге: у вопроса без ответа в базе искать нечего, и там прочерк. «Где потерялся» — " +
            "первый этап, после которого правильного фрагмента в выдаче не стало: ошибка поиска, " +
            "слишком высокий порог или проигрыш на втором этапе лечатся по-разному.")
        appendLine()
        appendLine("## Кандидаты и этапы")
        appendLine()
        appendLine("Место в выдаче, близость, отметка фильтра, оценка и позиция после второго этапа " +
            "и финальный номер в контексте: по этим числам видно, почему фрагмент ушёл в запрос " +
            "или не ушёл.")
        for (trial in trials) {
            appendLine()
            appendLine("### ${trial.control.id}. ${trial.control.question}")
            appendLine()
            append(trace(trial.improved.answer.retrieval.trace))
        }
        appendLine()
        appendLine("## Вопросы и ответы")
        for (trial in trials) {
            appendLine()
            appendLine("### ${trial.control.id}. ${trial.control.question}")
            appendLine()
            appendLine("Ожидание (${trial.control.section}, ${place(trial.control)}):")
            appendLine()
            appendLine(trial.control.expected)
            appendLine()
            appendLine("Проверяемые факты:")
            for ((index, fact) in trial.control.facts.withIndex()) {
                appendLine(
                    "- ${fact.text} — без базы: ${mark(trial.noBase.check.facts[index])}, " +
                        "базовый: ${mark(trial.baseline.check.facts[index])}, " +
                        "улучшенный: ${mark(trial.improved.check.facts[index])}"
                )
            }
            for (mode in listOf("Без базы" to trial.noBase, "Базовый RAG" to trial.baseline, "Улучшенный RAG" to trial.improved)) {
                appendLine()
                appendLine("**${mode.first}** — оценка ${mode.second.check.score} из 2:")
                appendLine()
                appendLine(answerText(mode.second.answer))
            }
            appendLine()
            appendLine("Источники улучшенного режима:")
            appendLine()
            append(sourceDetails(trial.improved.answer))
        }
    }

    /**
     * Лог запросов: путь каждого вопроса по этапам (§19 задания).
     *
     * Режимы идут в порядке «без базы → базовый → улучшенный», как и в прогоне, и это не формальность:
     * по логу видно, что три ответа получены на один и тот же вопрос одной и той же моделью, и что
     * исходный вопрос не подменялся переписанным — в разделе LLM стоит он же, а переписанный запрос
     * остался выше, в этапе поиска.
     */
    fun log(trials: List<Trial>): String = buildString {
        appendLine("# Лог запросов дня 23")
        appendLine()
        appendLine(describe())
        for (trial in trials) {
            val answer = trial.improved.answer
            val trace = answer.retrieval.trace
            appendLine()
            appendLine("## ${trial.control.id}. ${trial.control.question}")
            appendLine()
            appendLine("### Query Rewrite")
            appendLine()
            appendLine(rewriteStage(trace))
            appendLine()
            appendLine("### Retrieval (Top-${stages.retrievalTopK})")
            appendLine()
            appendLine(candidates(trace))
            appendLine()
            appendLine("### Similarity Filter (${filterName()})")
            appendLine()
            appendLine(filterStage(trace))
            appendLine()
            appendLine("### Reranking (${stages.rerank ?: "без второго этапа"})")
            appendLine()
            appendLine(rerankStage(trace))
            appendLine()
            appendLine("### Final Top-${stages.finalTopK}")
            appendLine()
            appendLine(finalStage(trace))
            appendLine()
            appendLine("### LLM (улучшенный RAG)")
            appendLine()
            append(request(answer))
            appendLine()
            appendLine("### LLM (базовый RAG, Top-${stages.baselineTopK})")
            appendLine()
            appendLine(sourceDetails(trial.baseline.answer))
            appendLine()
            append(request(trial.baseline.answer))
            appendLine()
            appendLine("### LLM (без базы)")
            appendLine()
            append(request(trial.noBase.answer))
        }
    }

    /**
     * Подбор порога: таблица значений и вывод о выбранном.
     *
     * Пороги печатаются все, включая отвергнутые: подбор — это результат прогона, и «выбрали 0,5»
     * без таблицы было бы утверждением без основания. Вывод считается по числам таблицы, а не
     * вписан в код: порог выбирается по попаданию после фильтра при наименьшем числе ошибочно
     * отсечённых правильных фрагментов.
     */
    fun sweep(rows: List<SweepRow>, scored: Int): String = buildString {
        appendLine("# Подбор порога фильтрации")
        appendLine()
        appendLine(describe())
        appendLine()
        appendLine("Выдача поиска для этой таблицы считалась один раз на вопрос: порог меняет только то, " +
            "что происходит после поиска, и повторный поиск примешал бы к разнице порогов разницу выдач. " +
            "Второй этап здесь — эвристический ([HeuristicReranker]): с моделью на каждом пороге " +
            "сравнивались бы не пороги, а её расстановки.")
        appendLine()
        appendLine("| Порог | Остаётся в среднем | Отсеяно | Ошибочно отсеяно | Вопросов без контекста | Hit после фильтра | Hit@${stages.finalTopK} после реранка | Средняя оценка | Факты |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for (row in rows) {
            appendLine(
                "| ${threshold(row.threshold)} | ${"%.1f".format(row.kept)} из ${stages.retrievalTopK} " +
                    "| ${row.removed} | ${row.wronglyFiltered} | ${row.empty} " +
                    "| ${row.filterHits} из $scored | ${row.finalHits} из $scored " +
                    "| ${row.score?.let { "%.2f".format(it) } ?: "—"} " +
                    "| ${row.facts ?: "—"} |"
            )
        }
        appendLine()
        appendLine(bestOf(rows, scored))
    }

    /** Проверка переписывания: что стало с поиском и что — с именами. */
    fun rewrite(cases: List<RewriteCase>, summary: RewriteSummary): String = buildString {
        appendLine("# Проверка Query Rewrite")
        appendLine()
        appendLine(describe())
        appendLine()
        appendLine("Место — лучшая позиция правильного фрагмента в выдаче: по исходному запросу и по " +
            "переписанному. Сравниваются места, а не близости: у двух разных запросов разные векторы, " +
            "и разница близостей ничего не говорит о качестве.")
        appendLine()
        appendLine("| # | Вопрос | Переписанный запрос | Место до | Место после | Потерянные имена | Выдуманные имена |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        for (case in cases) {
            appendLine(
                "| ${case.control.id} | ${case.control.question} " +
                    "| ${case.rewritten} " +
                    "| ${case.originalRank ?: "не найден"} " +
                    "| ${case.rewrittenRank ?: "не найден"} " +
                    "| ${case.lostNames.joinToString(", ").ifEmpty { "—" }} " +
                    "| ${case.inventedNames.joinToString(", ").ifEmpty { "—" }} |"
            )
        }
        appendLine()
        appendLine("Всего вопросов: ${summary.questions}; переписывание изменило запрос в ${summary.changed}.")
        appendLine("Поиск стал лучше в ${summary.helped}, хуже в ${summary.hurt}, без изменений в ${summary.same}.")
        appendLine("Потерянных имён: ${summary.lostNames}; выдуманных (нет ни в вопросе, ни в книге): ${summary.inventedNames}.")
    }

    /** Шапка: база, индекс, модель и настройки этапов. */
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
            "Этапы: переписывание — ${stages.rewrite ?: "нет"}, фильтр — ${filterName()}, " +
                "второй этап — ${stages.rerank ?: "нет"}."
        )
        appendLine(
            "Первый Top-K (кандидаты поиска): ${stages.retrievalTopK}; " +
                "финальный Top-K (в контексте): ${stages.finalTopK}; " +
                "базовый RAG дня 22: Top-${stages.baselineTopK}."
        )
    }

    /** Метрики дня 22 на той же паре режимов: память против базового RAG. */
    private fun day22Totals(trials: List<Trial>): String {
        val summary = report.summary(trials.map { it.day22 })
        return buildString {
            appendLine("### Память против базового RAG (метрики дня 22)")
            appendLine()
            appendLine("Средняя оценка без RAG: ${"%.2f".format(summary.averageWithout)} из 2 " +
                "(${summary.factsWithout} из ${summary.factsTotal} фактов); базового RAG: " +
                "${"%.2f".format(summary.averageWith)} (${summary.factsWith} из ${summary.factsTotal}).")
            appendLine("Source Hit Rate (Top-${stages.baselineTopK}): ${summary.hits} из ${summary.scored}.")
            appendLine("Токены: без RAG ${summary.without.prompt} + ${summary.without.completion}, " +
                "базовый RAG ${summary.with.prompt} + ${summary.with.completion} (запрос + ответ).")
        }
    }

    /** Метрики этапов: попадания по шагам, цена фильтра, ошибки по видам. */
    private fun stageTotals(summary: StageSummary): String = buildString {
        appendLine("### Базовый против улучшенного (метрики задания §20)")
        appendLine()
        appendLine("Средняя оценка базового RAG: ${"%.2f".format(summary.baselineScore)} из 2 " +
            "(${summary.baselineFacts} из ${summary.factsTotal} фактов); улучшенного: " +
            "${"%.2f".format(summary.improvedScore)} (${summary.improvedFacts} из ${summary.factsTotal}).")
        appendLine("По вопросам: улучшенный выше в ${summary.better}, столько же в ${summary.same}, " +
            "ниже в ${summary.worse}.")
        appendLine("Source Hit Rate: базовый ${summary.baselineHits} из ${summary.scored}, " +
            "улучшенный ${summary.improvedHits} из ${summary.scored}.")
        appendLine("По этапам: Hit@${stages.retrievalTopK} после поиска — ${summary.retrievalHits} из " +
            "${summary.scored}; после фильтра — ${summary.filterHits}; в финальном " +
            "Top-${stages.finalTopK} — ${summary.finalHits}.")
        appendLine("Фильтр: отсеяно кандидатов ${summary.removed}, из них правильных " +
            "${summary.wronglyFiltered}; вопросов, где после фильтра не осталось контекста, — " +
            "${summary.emptyContext}.")
        appendLine("Где терялся правильный фрагмент: не найден поиском — ${summary.lostInRetrieval}, " +
            "отсечён порогом — ${summary.lostInFilter}, проиграл на втором этапе — ${summary.lostInRerank}.")
        appendLine("Второй этап изменил порядок кандидатов в ${summary.reordered} вопросах; " +
            "переписывание изменило запрос в ${summary.rewriteChanged}.")
    }

    /** То же коротко, для консоли. */
    private fun totals(summary: StageSummary): String = buildString {
        appendLine("Вопросов: ${summary.questions} (с ответом в базе ${summary.scored}).")
        appendLine("Средняя оценка: без базы ${"%.2f".format(summary.noBaseScore)}, " +
            "базовый RAG ${"%.2f".format(summary.baselineScore)}, " +
            "улучшенный RAG ${"%.2f".format(summary.improvedScore)} из 2.")
        appendLine("Source Hit Rate: базовый ${summary.baselineHits} из ${summary.scored}, " +
            "улучшенный ${summary.improvedHits} из ${summary.scored}.")
        appendLine("По этапам (из ${summary.scored}): поиск Top-${stages.retrievalTopK} — " +
            "${summary.retrievalHits}, после фильтра — ${summary.filterHits}, " +
            "после второго этапа — ${summary.finalHits}.")
        appendLine("Отсеяно кандидатов ${summary.removed}, ошибочно отсеяно правильных " +
            "${summary.wronglyFiltered}.")
        appendLine("Потери: поиск ${summary.lostInRetrieval}, порог ${summary.lostInFilter}, " +
            "второй этап ${summary.lostInRerank}.")
    }

    /** Переписанный запрос и что с ним стало. */
    private fun rewriteStage(trace: Trace?): String = buildString {
        if (trace == null) {
            appendLine("Трейса нет: вопрос шёл базовым поиском.")
            return@buildString
        }
        appendLine("Исходный вопрос: ${trace.original}")
        appendLine()
        appendLine("Запрос в поиск: ${trace.rewritten ?: trace.original}")
        if (trace.rewritten == null) appendLine("Переписывания нет: в поиск ушёл исходный вопрос.")
        if (trace.rewriteMillis > 0) appendLine("Время этапа: ${seconds(trace.rewriteMillis)}.")
        if (trace.note?.contains("не изменило") == true) {
            appendLine("Модель вернула тот же запрос: переписывание ничего не поменяло.")
        }
    }

    /** Кандидаты поиска: номер, место в книге, близость, размер. */
    private fun candidates(trace: Trace?): String = buildString {
        if (trace == null || trace.candidates.isEmpty()) {
            appendLine("Кандидатов нет.")
            return@buildString
        }
        appendLine("Найдено ${trace.candidates.size} кандидатов за ${seconds(trace.retrievalMillis)}:")
        appendLine()
        for (candidate in trace.candidates) {
            appendLine("- **[${candidate.source.rank}]** `${candidate.source.id}` — ${candidate.source.label}; " +
                "близость ${"%.3f".format(candidate.similarity)}; " +
                "символов ${candidate.source.text.length}, токенов ${Tokens.count(candidate.source.text)}")
        }
    }

    /** Итог фильтра: кто остался и кто ушёл. */
    private fun filterStage(trace: Trace?): String = buildString {
        if (trace == null) {
            appendLine("Фильтра нет: вопрос шёл базовым поиском.")
            return@buildString
        }
        appendLine("Прошло ${trace.accepted} из ${trace.candidates.size}, отсеяно ${trace.removed}:")
        appendLine()
        appendLine("- прошли: " + trace.candidates.filter { it.accepted }
            .joinToString(", ") { "#${it.source.rank} (${"%.3f".format(it.similarity)})" }.ifEmpty { "—" })
        appendLine("- отсеяны: " + trace.candidates.filter { !it.accepted }
            .joinToString(", ") { "#${it.source.rank} (${"%.3f".format(it.similarity)})" }.ifEmpty { "—" })
        if (trace.empty) appendLine("Кандидатов не осталось: контекста нет.")
    }

    /** Второй этап: старая позиция → новая и оценка. */
    private fun rerankStage(trace: Trace?): String = buildString {
        if (trace == null || trace.rerankScoredByModel == 0 && stages.rerank == null) {
            appendLine("Второго этапа нет: порядок остался по близости.")
            return@buildString
        }
        appendLine("Порядок до → после (оценка):")
        appendLine()
        for (candidate in trace.candidates.sortedBy { it.rerankRank ?: Int.MAX_VALUE }) {
            val score = candidate.rerankScore?.let { "%.3f".format(it) } ?: "—"
            val from = candidate.source.rank
            val to = candidate.rerankRank ?: "отсеян"
            appendLine("- `#${candidate.source.id}`: $from → $to ($score)")
        }
        if (trace.rerankScoredByModel > 0) {
            appendLine()
            appendLine("Оценено моделью: ${trace.rerankScoredByModel} из ${trace.accepted}.")
        }
        trace.note?.let { appendLine("Замечание этапа: $it.") }
    }

    /** Что ушло в контекст. */
    private fun finalStage(trace: Trace?): String = buildString {
        if (trace == null) {
            appendLine("Трейса нет: вопрос шёл базовым поиском.")
            return@buildString
        }
        val final = trace.candidates.filter { it.passed }.sortedBy { it.finalRank }
        if (final.isEmpty()) {
            appendLine("В контекст не ушло ни одного фрагмента.")
            return@buildString
        }
        appendLine("В контекст ушли ${final.size} фрагмента(ов):")
        appendLine()
        for (candidate in final) {
            appendLine("- **[${candidate.finalRank}]** `${candidate.source.id}` — ${candidate.source.label}; " +
                "был #${candidate.source.rank} по близости ${"%.3f".format(candidate.similarity)}")
        }
    }

    /** Строка этапов по кандидатам: то же, но таблицей — для файла сравнения. */
    private fun trace(trace: Trace?): String = buildString {
        if (trace == null) {
            appendLine("Трейса нет: вопрос шёл базовым поиском.")
            return@buildString
        }
        appendLine("Запрос в поиск: `${trace.rewritten ?: trace.original}`")
        appendLine()
        appendLine("| # поиска | Фрагмент | Близость | Фильтр | Оценка этапа | Позиция после этапа | В контексте |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        for (candidate in trace.candidates) {
            appendLine(
                "| ${candidate.source.rank} | `${candidate.source.id}` — ${candidate.source.label} " +
                    "| ${"%.3f".format(candidate.similarity)} " +
                    "| ${if (candidate.accepted) "прошёл" else "отсеян"} " +
                    "| ${candidate.rerankScore?.let { "%.3f".format(it) } ?: "—"} " +
                    "| ${candidate.rerankRank ?: "—"} " +
                    "| ${candidate.finalRank ?: "—"} |"
            )
        }
    }

    /** Вывод подбора: какой порог выбран и почему. */
    private fun bestOf(rows: List<SweepRow>, scored: Int): String {
        val best = rows.maxWithOrNull(
            compareBy({ it.filterHits - it.wronglyFiltered * 10 }, { it.finalHits }, { it.threshold })
        )
        return buildString {
            appendLine("Вывод: по этим числам выбран порог ${best?.let { threshold(it.threshold) } ?: "—"}.")
            if (best != null) {
                appendLine("Он сохраняет правильный фрагмент в выдаче у ${best.filterHits} из $scored вопросов " +
                    "при ${best.wronglyFiltered} ошибочно отсечённых правильных фрагментах и " +
                    "${"%.1f".format(best.kept)} кандидатах в среднем на вопрос.")
            }
            appendLine("Порог ниже оставляет больше кандидатов (в том числе нерелевантных), порог выше " +
                "начинает выбрасывать нужное — это и есть плата за фильтрацию, и по таблице видно, " +
                "где она перевешивает.")
        }
    }

    private fun filterName(): String = SimilarityFilter(stages.threshold).name

    private fun threshold(value: Double): String = if (value <= 0.0) "без порога" else "%.2f".format(value)

    private fun score(check: AnswerCheck): String = "${check.score} / ${check.total.coerceAtLeast(1)}"

    private fun mark(value: Boolean?): String = if (value == true) "✓" else "—"

    private fun place(control: ControlQuestion): String =
        if (control.pages.isEmpty()) "—" else "страницы " + control.pages.joinToString(", ")

    private fun answerText(answer: Answer): String =
        if (answer.text.isBlank()) {
            "(ответ пустой — остановка ${answer.finishReason ?: "не сообщена"})"
        } else {
            answer.text
        }

    /** Запрос к модели в том виде, в каком он ушёл, и её ответ: звено лога. */
    private fun request(answer: Answer): String = buildString {
        for (message in answer.messages) {
            appendLine("**${message.role}:**")
            appendLine()
            appendLine("```")
            appendLine(message.content)
            appendLine("```")
            appendLine()
        }
        appendLine(
            "Ответ: запрос ${answer.promptTokens ?: "—"} токенов, ответ ${answer.completionTokens ?: "—"} токенов, " +
                "остановка ${answer.finishReason ?: "не сообщена"}, ${seconds(answer.elapsedMillis)}."
        )
        appendLine()
        appendLine(answerText(answer))
    }

    /** Подробности выдачи: идентификаторы чанков, метаданные и близость каждого фрагмента. */
    private fun sourceDetails(answer: Answer): String = buildString {
        if (answer.sources.isEmpty()) {
            appendLine("Найденных фрагментов нет: контекст в этот запрос не подставлялся.")
            return@buildString
        }
        appendLine("Найденные фрагменты (Top-${answer.sources.size}):")
        appendLine()
        for (source in answer.sources) {
            val pages = if (source.pages.isEmpty()) "—" else source.pages.joinToString(", ")
            appendLine(
                "- **[${source.rank}]** `id=${source.id}` — ${source.source}, " +
                    "${source.title ?: "без названия"}, ${source.section ?: "без раздела"}, " +
                    "страницы $pages; близость ${"%.3f".format(source.similarity)}; " +
                    "символов ${source.text.length}, токенов ${Tokens.count(source.text)}"
            )
        }
    }

    private fun row(vararg cells: String): String = "| " + cells.joinToString(" | ")

    private fun shorten(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 1) + "…"

    private fun seconds(millis: Long): String = "%.1f с".format(millis / 1000.0)

    private companion object {
        const val QUESTION_WIDTH = 52
    }
}
