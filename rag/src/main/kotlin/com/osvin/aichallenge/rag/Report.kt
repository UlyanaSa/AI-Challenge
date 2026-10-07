package com.osvin.aichallenge.rag

import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.pipeline.Tokens
import kotlinx.serialization.Serializable

/**
 * Расход токенов режима: сколько насчитал API и у скольких ответов он его вообще сообщил.
 *
 * Сериализуемый, потому что эти же числа отдаются странице дня 22 как есть: переписывать их
 * в её собственный тип значило бы завести второе описание одних и тех же метрик.
 */
@Serializable
data class TokensUsed(val prompt: Int, val completion: Int, val reported: Int)

/**
 * Итоговые метрики прогона: то, что задание просит посчитать по десяти вопросам.
 *
 * Отдельным типом, а не строками отчёта, потому что те же числа показывает страница дня 22.
 * Считать их второй раз на странице значило бы завести второй набор формул, и он разошёлся бы
 * с отчётом при первой же правке — а сверять отчёт со страницей тогда было бы нечем.
 *
 * [factsTotal] — все факты набора: по ним видно, что средняя оценка считается по вопросам,
 * а «сколько фактов нашлось» — по фактам, и это разные вопросы к одному прогону.
 *
 * Сериализуемый по той же причине, что и [TokensUsed]: это метрики задания, и страница показывает
 * ровно их, а не свой пересчёт.
 */
@Serializable
data class Summary(
    val questions: Int,
    /** Вопросы, у которых ответ есть в базе: только по ним считается попадание источника. */
    val scored: Int,
    val averageWithout: Double,
    val averageWith: Double,
    val factsWithout: Int,
    val factsWith: Int,
    val factsTotal: Int,
    /** Попаданий ожидаемого источника в Top-K. */
    val hits: Int,
    val hitRate: Double,
    val better: Int,
    val same: Int,
    val worse: Int,
    val answered: Int,
    val retrievalErrors: Int,
    val generationErrors: Int,
    val without: TokensUsed,
    val with: TokensUsed,
    val millisWithout: Long,
    val millisWith: Long,
    val retrievalMillis: Long
)

/** Прогон одного вопроса в одном режиме: ответ, найденные источники и сверка с ожиданием. */
data class Run(val control: ControlQuestion, val answer: Answer, val check: AnswerCheck)

/**
 * Сравнение режимов на одном вопросе.
 *
 * Разница считается по оценкам, но существует не вместо них, а рядом: у вопроса с одним фактом шаг
 * в один балл — это весь вопрос, у вопроса с двумя — половина, и складывать такие шаги в одно число
 * «RAG лучше на N» значило бы выдавать разные вещи за одну.
 */
data class Comparison(val control: ControlQuestion, val withRag: Run, val withoutRag: Run) {

    /** Сколько баллов режим с базой отыграл: отрицательное число — потерял. */
    val scoreGain: Int get() = withRag.check.score - withoutRag.check.score

    /** Чем кончился вопрос в режиме с базой: верно, ошибка поиска или ошибка генерации. */
    val outcome: Outcome get() = Check.outcome(control, withRag.check)
}

/**
 * Что известно о прогоне целиком: по этим числам читается сравнение.
 *
 * Числа нужны в отчёте, потому что без них сравнение не воспроизводится: «с RAG 17 из 20» ничего
 * не значит, пока не сказано, какая модель отвечала, каким чанкером резался корпус, сколько
 * фрагментов уходило в запрос и был ли индекс собран заново или взят готовым.
 */
data class RunHeader(
    val model: String,
    /** Провайдер векторов словами: модель Ollama или хеширование. */
    val embedding: String,
    val strategy: ChunkingStrategyType,
    val topK: Int,
    val baseChars: Int,
    val baseTokens: Int,
    val basePages: Int,
    val chunks: Int,
    /** Индекс взят готовым, а не собран этим прогоном: тогда время сборки не показывается. */
    val reused: Boolean,
    val indexMillis: Long
)

/**
 * Отчёт сравнения: печать в консоль, файл сравнения и лог запросов.
 *
 * Три взгляда на один прогон, и каждый нужен своему читателю. Консоль — короткая сводка: прогон
 * идёт минутами, и числа нужны сразу. Файл сравнения — ответы целиком: таблица оценок говорит, что
 * сошлось, а текст ответа — почему. Лог — путь запроса: вопрос, выдача поиска с идентификаторами
 * чанков и близостями, контекст, который увидела модель, и её ответ; по нему разбирают, на каком
 * шаге ответ разошёлся с ожиданием, и его нельзя собрать заново после прогона — контекст уходит
 * вместе с процессом.
 *
 * Сокращать ответы в файле сравнения значило бы выбросить то, ради чего прогон запускался, поэтому
 * в консоль идёт сводка, а тексты — в файл.
 */
class Report(private val header: RunHeader) {

    /** Сводка прогона: то, что считается по всем вопросам сразу. */
    fun console(comparisons: List<Comparison>): String = buildString {
        appendLine("Сравнение режимов: ${header.model}.")
        appendLine(describeRun())
        appendLine()
        appendLine(row("#", "вопрос", "без RAG", "с RAG", "источник", "итог"))
        for (comparison in comparisons) {
            appendLine(
                row(
                    comparison.control.id,
                    shorten(comparison.control.question, QUESTION_WIDTH),
                    score(comparison.withoutRag.check),
                    score(comparison.withRag.check),
                    if (comparison.control.absent) "—" else hit(comparison.withRag.check),
                    comparison.outcome.title
                )
            )
        }
        appendLine()
        append(totals(comparisons))
    }

    /** Файл сравнения: шапка, итоговые метрики, таблица по вопросам и ответы целиком. */
    fun markdown(comparisons: List<Comparison>): String = buildString {
        appendLine("# Сравнение ответов с RAG и без RAG")
        appendLine()
        appendLine(describeRun())
        appendLine()
        appendLine("## Итоговые метрики")
        appendLine()
        append(totals(comparisons))
        appendLine()
        appendLine("## Сравнение по вопросам")
        appendLine()
        appendLine(
            "| # | Вопрос | Ожидание | Без RAG | С RAG | Источники Top-${header.topK} | Источник найден | Итог |"
        )
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        for (comparison in comparisons) {
            appendLine(
                "| ${comparison.control.id} | ${comparison.control.question} " +
                    "| ${comparison.control.expected} " +
                    "| ${score(comparison.withoutRag.check)} " +
                    "| ${score(comparison.withRag.check)} " +
                    "| ${sourceList(comparison)} " +
                    "| ${if (comparison.control.absent) "—" else hit(comparison.withRag.check)} " +
                    "| ${comparison.outcome.title} |"
            )
        }
        appendLine()
        appendLine("Оценка: 0 — ответ неверен или отсутствует, 1 — есть не все ожидаемые факты, " +
            "2 — есть все; у обоих режимов шкала одна. «Источник найден» — ожидаемый текст базы " +
            "попал в выдачу поиска; у вопроса без ответа в базе прочерк: искать нечего.")
        appendLine()
        appendLine("## Вопросы и ответы")
        for (comparison in comparisons) {
            appendLine()
            appendLine("### ${comparison.control.id}. ${comparison.control.question}")
            appendLine()
            appendLine("Ожидание (${comparison.control.section}, " + place(comparison.control) + "):")
            appendLine()
            appendLine(comparison.control.expected)
            appendLine()
            appendLine("Проверяемые факты:")
            for ((index, fact) in comparison.control.facts.withIndex()) {
                appendLine(
                    "- ${fact.text} — без RAG: ${mark(comparison.withoutRag.check.facts[index])}, " +
                        "с RAG: ${mark(comparison.withRag.check.facts[index])}"
                )
            }
            appendLine()
            appendLine("**Без RAG** — оценка ${comparison.withoutRag.check.score} из 2:")
            appendLine()
            appendLine(answerText(comparison.withoutRag.answer))
            appendLine()
            appendLine("**С RAG** — оценка ${comparison.withRag.check.score} из 2:")
            appendLine()
            appendLine(answerText(comparison.withRag.answer))
            appendLine()
            appendLine(sourceDetails(comparison.withRag.answer))
        }
    }

    /**
     * Лог запросов: путь от вопроса до ответа по каждому режиму.
     *
     * Здесь печатается то, чего нет в файле сравнения: полный запрос к модели — системное сообщение
     * и пользовательское вместе с подставленным контекстом. Это и есть доказательство, что модель
     * видела именно найденные фрагменты, а не что-то ещё; после прогона восстановить его неоткуда,
     * потому что контекст собирается в памяти и уходит вместе с процессом.
     */
    fun log(comparisons: List<Comparison>): String = buildString {
        appendLine("# Лог запросов")
        appendLine()
        appendLine(describeRun())
        for (comparison in comparisons) {
            appendLine()
            appendLine("## ${comparison.control.id}. ${comparison.control.question}")
            appendLine()
            appendLine("Вопрос в модель: ${Prompt.QUESTION_PREFIX}${comparison.control.question}")
            appendLine()
            appendLine("Поиск (Top-${header.topK}):")
            appendLine()
            append(sourceDetails(comparison.withRag.answer))
            appendLine()
            appendLine("### Без RAG")
            appendLine()
            append(request(comparison.withoutRag.answer))
            appendLine()
            appendLine("### С RAG")
            appendLine()
            append(request(comparison.withRag.answer))
        }
    }

    /** Шапка: чем и на каких настройках получены числа. */
    private fun describeRun(): String = buildString {
        appendLine(
            "База: ${header.baseChars} символов, ${header.baseTokens} токенов, ${header.basePages} страниц, " +
                "${header.chunks} чанков (${header.strategy.name.lowercase()})."
        )
        appendLine("Векторы: ${header.embedding}. Поиск: Top-${header.topK}. Модель: ${header.model}.")
        appendLine(
            if (header.reused) {
                "Индекс взят готовым из файла: повторной индексации при запросах нет."
            } else {
                "Индекс собран этим прогоном за ${seconds(header.indexMillis)}."
            }
        )
    }

    /** Итоговые метрики задания: средние оценки, попадание источника в Top-K, ошибки, цена. */
    private fun totals(comparisons: List<Comparison>): String {
        val summary = summary(comparisons)
        return buildString {
            appendLine("Вопросов: ${summary.questions} (из них с ответом в базе ${summary.scored}, " +
                "без ответа ${summary.questions - summary.scored}).")
            appendLine("Средняя оценка без RAG: ${averageText(summary.averageWithout, summary.questions)} " +
                "(${summary.factsWithout} из ${summary.factsTotal} фактов).")
            appendLine("Средняя оценка с RAG: ${averageText(summary.averageWith, summary.questions)} " +
                "(${summary.factsWith} из ${summary.factsTotal} фактов).")
            appendLine("Source Hit Rate (Top-${header.topK}): ${summary.hits} из ${summary.scored}" +
                (if (summary.scored == 0) "" else " (${(summary.hitRate * 100).toInt()}%)") +
                " — вопросы без ответа в базе не считаются.")
            appendLine("По вопросам: с RAG выше в ${summary.better}, столько же в ${summary.same}, " +
                "ниже в ${summary.worse}.")
            appendLine("Итог: верно ${summary.answered}, ошибок поиска ${summary.retrievalErrors}, " +
                "ошибок генерации ${summary.generationErrors}.")
            appendLine("Токены: без RAG ${tokens(summary.without)}, " +
                "с RAG ${tokens(summary.with)}.")
            appendLine("Время: без RAG ${seconds(summary.millisWithout)}, " +
                "с RAG ${seconds(summary.millisWith)} " +
                "(плюс поиск ${seconds(summary.retrievalMillis)}).")
        }
    }

    /**
     * Итоговые метрики прогона: то, что задание просит посчитать по десяти вопросам.
     *
     * Отдельным типом, а не строками отчёта, потому что те же числа показывает страница дня 22.
     * Считать их второй раз на странице значило бы завести второй набор формул, и он разошёлся бы
     * с отчётом при первой же правке — а сверять отчёт со страницей тогда было бы нечем.
     */
    fun summary(comparisons: List<Comparison>): Summary {
        val scored = comparisons.filterNot { it.control.absent }
        val hits = scored.count { it.withRag.check.retrievedFacts.any { it } }
        return Summary(
            questions = comparisons.size,
            scored = scored.size,
            averageWithout = average(comparisons.map { it.withoutRag.check.score }),
            averageWith = average(comparisons.map { it.withRag.check.score }),
            factsWithout = comparisons.sumOf { it.withoutRag.check.matched },
            factsWith = comparisons.sumOf { it.withRag.check.matched },
            factsTotal = comparisons.sumOf { it.control.facts.size },
            hits = hits,
            hitRate = if (scored.isEmpty()) 0.0 else hits.toDouble() / scored.size,
            better = comparisons.count { it.scoreGain > 0 },
            same = comparisons.count { it.scoreGain == 0 },
            worse = comparisons.count { it.scoreGain < 0 },
            answered = comparisons.count { it.outcome == Outcome.ANSWERED },
            retrievalErrors = scored.count { it.outcome == Outcome.RETRIEVAL_ERROR },
            generationErrors = comparisons.count { it.outcome == Outcome.GENERATION_ERROR },
            without = usage(comparisons.map { it.withoutRag.answer }),
            with = usage(comparisons.map { it.withRag.answer }),
            millisWithout = comparisons.sumOf { it.withoutRag.answer.elapsedMillis },
            millisWith = comparisons.sumOf { it.withRag.answer.elapsedMillis },
            retrievalMillis = comparisons.sumOf { it.withRag.answer.retrieval.millis }
        )
    }

    /** Токены режима: сколько насчитал API и сколько ответов вообще их сообщили. */
    private fun usage(answers: List<Answer>): TokensUsed = TokensUsed(
        prompt = answers.sumOf { it.promptTokens ?: 0 },
        completion = answers.sumOf { it.completionTokens ?: 0 },
        reported = answers.count { it.promptTokens != null }
    )

    /** Источники вопроса списком: номер в выдаче, место в книге, близость. */
    private fun sourceList(comparison: Comparison): String = comparison.withRag.answer.sources
        .joinToString("<br>") { "**[${it.rank}]** ${it.label} — ${"%.3f".format(it.similarity)}" }
        .ifEmpty { "—" }

    /** Подробности выдачи: идентификаторы чанков, метаданные и близость каждого фрагмента. */
    private fun sourceDetails(answer: Answer): String = buildString {
        if (answer.sources.isEmpty()) {
            appendLine("Найденных фрагментов нет: поиск в этом режиме не выполнялся.")
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
        appendLine("**Ответ модели** (${answer.mode.title}, токенов ${answer.promptTokens ?: "—"} запрос, " +
            "${answer.completionTokens ?: "—"} ответ, остановка ${answer.finishReason ?: "—"}, " +
            "время ${seconds(answer.elapsedMillis)}):")
        appendLine()
        appendLine(answerText(answer))
    }

    /** Текст ответа или пометка: пустой ответ — результат прогона, а не пропуск в отчёте. */
    private fun answerText(answer: Answer): String {
        val text = answer.text.trim()
        return if (text.isEmpty()) "*(пустой ответ)*" else text
    }

    /** Оценка словами рядом с числом: «2 из 2» читается, «2» — нет. */
    private fun score(check: AnswerCheck): String = "${check.score} / ${check.total}"

    /** Попадание ожидаемого текста в выдачу: то, чем задание отделяет ошибку поиска от ответа. */
    private fun hit(check: AnswerCheck): String = if (check.retrievedFacts.any { it }) "да" else "нет"

    private fun mark(found: Boolean): String = if (found) "засчитано" else "нет"

    private fun place(question: ControlQuestion): String =
        if (question.absent) "ответа в базе нет" else "страницы " + question.pages.joinToString(", ")

    /** Средняя оценка набора по шкале 0–2. */
    private fun average(scores: List<Int>): Double =
        if (scores.isEmpty()) 0.0 else scores.sum().toDouble() / scores.size

    /** Та же средняя словами: у пустого набора среднего нет, и прочерк честнее нуля. */
    private fun averageText(value: Double, questions: Int): String =
        if (questions == 0) "—" else "%.1f из 2".format(value)

    private fun tokens(used: TokensUsed): String {
        // Прочерк вместо нуля: если API не сообщил расход, «0 токенов» выглядело бы измерением.
        if (used.reported == 0) return "не сообщены"
        return "${used.prompt} + ${used.completion} (запрос + ответ)"
    }

    private fun seconds(millis: Long): String = "%.1f с".format(millis / 1000.0)

    private fun shorten(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 1) + "…"

    private fun row(vararg cells: String): String = cells.joinToString(" | ")

    private companion object {

        /** Ширина колонки вопроса в консольной таблице: длинный вопрос ломает её построчность. */
        const val QUESTION_WIDTH = 56
    }
}
