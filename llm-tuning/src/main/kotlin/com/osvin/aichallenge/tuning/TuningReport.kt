package com.osvin.aichallenge.tuning

import java.util.Locale

/**
 * Отчёт сравнения профилей: сводка по профилям и разбор по вопросам.
 *
 * Отчёт считает только то, что измерено, и печатает это дважды — в файл разметкой markdown и
 * в консоль тем же текстом ([markdown]) или без разметки для терминала ([plain]). Одним
 * текстом, а не двумя, потому что второй текст разошёлся бы с первым при первой же правке
 * колонки, и в файле с консолью оказались бы разные числа. Разметка при этом не «украшение»:
 * отчёт дня читают в просмотрщике markdown, и таблица там должна быть таблицей.
 *
 * Своих чисел у отчёта нет ни одного: всё берётся из [TuningResult] и [TuningAnswer], а размер
 * в памяти — из снимка демона ([ResourceSnapshot]). Констант-ожиданий в отчёте нет намеренно:
 * как только в печати появится «правильная» оценка, сравнение станет подгонкой под неё, а не
 * измерением — а смысл дня именно в том, чтобы числа получились сами.
 */
object TuningReport {

    /** Заголовок отчёта: по нему видно, что сравнивалось, ещё до таблиц. */
    private const val TITLE = "Сравнение сборок локальной модели: вопросы по Kotlin/KMP"

    /** Заголовок сводной таблицы: одна строка на профиль. */
    private const val SUMMARY_TITLE = "Профили в сравнении"

    /** Колонки сводки: сначала оценка, потом её состав, потом цена ответа. */
    private val SUMMARY_HEADER = listOf(
        "профиль", "оценка", "полных", "частичных", "неверных", "отказов", "факты",
        "время, мс", "токены запроса", "токены ответа", "токенов/с", "в памяти"
    )

    /**
     * Колонки по вопросам: ровно то, по чему видно, где профиль потерял баллы.
     *
     * Токенов и времени прогона здесь нет: они в сводке, а в строке вопроса важнее, чем кончился
     * этот вопрос — оценкой, фактами и причиной отказа.
     */
    private val QUESTION_HEADER = listOf("вопрос", "оценка", "факты", "время, мс", "отказ")

    /** Отчёт разметкой markdown: файл прогона и печать по умолчанию. */
    fun markdown(results: List<TuningResult>, resources: Map<String, ResourceSnapshot>): String =
        render(results, resources, markdown = true)

    /**
     * Тот же отчёт без разметки: для терминала, где `|` и `#` читаются как мусор.
     *
     * Колонки те же и в том же порядке, отличаются только разделители — так два вида отчёта
     * нельзя перепутать по числам, а человек, привыкший к консольной таблице, не ищет в ней
     * другой набор колонок.
     */
    fun plain(results: List<TuningResult>, resources: Map<String, ResourceSnapshot>): String =
        render(results, resources, markdown = false)

    /** Общий текст обоих видов отчёта: отличается только разметкой, не содержимым. */
    private fun render(
        results: List<TuningResult>,
        resources: Map<String, ResourceSnapshot>,
        markdown: Boolean
    ): String = buildString {
        appendLine(if (markdown) "# $TITLE" else TITLE)
        appendLine()
        appendLine(intro(results))
        appendLine()
        appendLine(if (markdown) "## $SUMMARY_TITLE" else SUMMARY_TITLE)
        appendLine()
        appendLine(
            table(
                SUMMARY_HEADER,
                results.map { result -> summaryRow(result, resources[result.tuning.id]) },
                markdown
            )
        )
        for (result in results) {
            appendLine()
            appendLine(
                if (markdown) "## ${result.tuning.id} — ${result.tuning.title}"
                else "${result.tuning.id} — ${result.tuning.title}"
            )
            appendLine()
            appendLine(profileFacts(result, resources[result.tuning.id]))
            appendLine()
            appendLine(
                table(QUESTION_HEADER, result.answers.map(::questionRow), markdown)
            )
        }
    }.trimEnd() + "\n"

    /** Что и на чём сравнивалось: без этого числа отчёта нельзя ни повторить, ни прочитать. */
    private fun intro(results: List<TuningResult>): String {
        val questions = results.firstOrNull()?.answers?.size ?: 0
        val requests = results.sumOf { it.answers.size }
        return "Вопросов в наборе: $questions — про Kotlin и Kotlin Multiplatform, ответ берётся " +
            "из знаний модели, базы у кейса нет. Оценка — та же проверка, что в дне 22 " +
            "(Check.evaluate): 0 — ответа нет или он неверен, 1 — часть фактов, 2 — все факты; " +
            "отказ считается нулём и из набора не выпадает. Профилей в прогоне: ${results.size}, " +
            "всего обращений к модели: $requests."
    }

    /** Строка сводки: профиль и его измеренные числа. */
    private fun summaryRow(result: TuningResult, snapshot: ResourceSnapshot?): List<String> = listOf(
        result.tuning.id,
        decimal(result.averageScore),
        result.full.toString(),
        result.partial.toString(),
        result.wrong.toString(),
        result.failed.toString(),
        "${result.matchedFacts}/${result.totalFacts}",
        result.averageMillis.toString(),
        result.promptTokens.toString(),
        result.completionTokens.toString(),
        decimal(result.tokensPerSecond),
        memoryCell(snapshot)
    )

    /** Строка вопроса: чем кончился, сколько фактов взял, сколько это стоило времени. */
    private fun questionRow(answer: TuningAnswer): List<String> = listOf(
        answer.id,
        answer.score.toString(),
        "${answer.matched}/${answer.total}",
        answer.millis.toString(),
        answer.failed ?: "—"
    )

    /**
     * Чем профиль спрашивал: условие ответа целиком, числами.
     *
     * Температура, окно и бюджет печатаются у каждого профиля, а не один раз на отчёт: профили
     * отличаются именно ими, и строка рядом с числами — единственное место, где видно, что
     * «optimized» означало в этом прогоне. Температура — транспорта, а не запроса: у локального
     * провайдера транспорт перекрывает температуру запроса своей, и эффективно действует его.
     */
    private fun profileFacts(result: TuningResult, snapshot: ResourceSnapshot?): String {
        val config = result.tuning.config
        val request = buildString {
            append("запрос: модель ${modelName(config.ollamaModel, snapshot)}")
            append(", температура ${decimal(config.ollamaTemperature)}")
            append(", рассуждение ${if (config.ollamaThink) "да" else "нет"}")
            append(", окно ${config.ollamaNumCtx} ток.")
            append(", бюджет ${budgetText(result.tuning.maxTokens)}")
            append(", системная инструкция: ${if (result.tuning.systemPrompt.isNullOrBlank()) "нет" else "есть"}")
        }
        return "чем отличается: ${result.tuning.note}\n$request"
    }

    /** Модель со сборкой из снимка демона: квантование печатается, когда демон его назвал. */
    private fun modelName(model: String, snapshot: ResourceSnapshot?): String {
        val details = listOfNotNull(snapshot?.quantization, snapshot?.parameterSize)
        return if (details.isEmpty()) model else "$model (${details.joinToString(", ")})"
    }

    /** Бюджет ответа: у профилей он задан, но тип допускает и отсутствие — и это тоже печатается. */
    private fun budgetText(maxTokens: Int?): String =
        if (maxTokens == null) "не задан (num_predict демона)" else "$maxTokens ток. (num_predict)"

    /** Размер в памяти: у снимка с диска видеопамяти нет, и VRAM тогда не печатается. */
    private fun memoryCell(snapshot: ResourceSnapshot?): String {
        if (snapshot == null) return "—"
        val size = gibibytes(snapshot.sizeBytes)
        return if (snapshot.sizeVramBytes > 0) "$size (VRAM ${gibibytes(snapshot.sizeVramBytes)})" else size
    }

    /** Таблица в выбранном виде: разметка или колонки терминала. */
    private fun table(header: List<String>, rows: List<List<String>>, markdown: Boolean): String =
        if (markdown) markdownTable(header, rows) else plainTable(header, rows)

    /** Таблица markdown: шапка, разделитель и строки — как их читает просмотрщик. */
    private fun markdownTable(header: List<String>, rows: List<List<String>>): String = buildString {
        appendLine("| " + header.joinToString(" | ") + " |")
        appendLine("|" + header.joinToString("|") { "---" } + "|")
        rows.forEach { appendLine("| " + it.joinToString(" | ") + " |") }
    }.trimEnd()

    /** Таблица без разметки: колонки выровнены по самой длинной ячейке, разделены двумя пробелами. */
    private fun plainTable(header: List<String>, rows: List<List<String>>): String {
        val widths = header.indices.map { column ->
            (rows.map { it[column].length } + header[column].length).maxOrNull() ?: 0
        }
        fun line(cells: List<String>) =
            cells.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd()
        return (listOf(header) + rows).joinToString("\n", transform = ::line)
    }

    /** Число с двумя знаками: средние и скорости сравнивают по цифрам, а не по «примерно». */
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** Размер в гибибайтах: байты демона читаются только после пересчёта, и он один на отчёт. */
    private fun gibibytes(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f ГиБ", bytes / GIBIBYTE)

    /** Сколько байтов в гибибайте: делитель один, чтобы размеры в отчёте были сравнимы. */
    private const val GIBIBYTE = 1024.0 * 1024.0 * 1024.0
}
