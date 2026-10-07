package com.osvin.aichallenge.indexing

import com.osvin.aichallenge.indexing.chunking.ChunkingStrategy
import com.osvin.aichallenge.indexing.chunking.FixedSizeChunker
import com.osvin.aichallenge.indexing.chunking.StrategyDefaults
import com.osvin.aichallenge.indexing.chunking.StructuralChunker
import com.osvin.aichallenge.indexing.embedding.EmbeddingProvider
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.index.JsonVectorStore
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.PageMarkers
import com.osvin.aichallenge.indexing.pipeline.AnswerKey
import com.osvin.aichallenge.indexing.pipeline.Corpus
import com.osvin.aichallenge.indexing.pipeline.CutExample
import com.osvin.aichallenge.indexing.pipeline.DemoQueries
import com.osvin.aichallenge.indexing.pipeline.DocumentIndexer
import com.osvin.aichallenge.indexing.pipeline.IndexStatistics
import com.osvin.aichallenge.indexing.pipeline.IndexingResult
import com.osvin.aichallenge.indexing.pipeline.QueryOutcome
import com.osvin.aichallenge.indexing.pipeline.Sections
import com.osvin.aichallenge.indexing.pipeline.SemanticSearch
import com.osvin.aichallenge.indexing.pipeline.StrategyComparison
import com.osvin.aichallenge.indexing.pipeline.StrategyScore
import com.osvin.aichallenge.indexing.pipeline.Tokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.runBlocking

/** Сколько результатов показывать по каждому запросу и считать в оценке. */
private const val TOP_K = 3

/**
 * Точка входа эксперимента: индексация корпуса двумя стратегиями и сравнение поиска.
 *
 * Один и тот же набор документов проходит оба конвейера, в конце — один и тот же поисковый
 * запрос к обоим индексам. Ни документы, ни embeddings, ни запросы между стратегиями не
 * различаются: различается только нарезка, поэтому любое расхождение результатов — её след.
 *
 * Аргумент командной строки — каталог, куда кладутся файлы индексов и отчёт; по умолчанию
 * `build/index` относительно рабочего каталога задачи. Отчёт печатается и сохраняется: числа
 * прогона зависят от корпуса, и их нужно видеть в том же виде, в каком они получены.
 */
fun main(args: Array<String>) = runBlocking {
    // Фикстура лежит рядом с кодом, но в репозиторий не попадает (авторское право): без неё
    // прогонять нечего, и об этом нужно сказать словами, а не пустым отчётом.
    if (!Corpus.available) {
        println(Corpus.MISSING_FIXTURE)
        return@runBlocking
    }
    val outputDir = Paths.get(args.firstOrNull() ?: "build/index").toAbsolutePath()
    val report = IndexingReport(outputDir).run()

    println(report)

    Files.createDirectories(outputDir)
    val reportFile = outputDir.resolve("report.md")
    Files.writeString(reportFile, report)
    println("Отчёт сохранён: $reportFile")
}

/**
 * Прогон эксперимента целиком.
 *
 * Порядок шагов повторяет конвейер задания: корпус → нарезка двумя стратегиями → embeddings →
 * два индекса → поиск по обоим → сравнение. Индексы после записи перечитываются с диска
 * новыми экземплярами хранилища: так проверяется, что embedding вместе с содержимым и
 * метаданными действительно сохранён, а не остался в памяти прогона.
 */
private class IndexingReport(private val outputDir: Path) {

    suspend fun run(): String {
        val documents = Corpus.documents
        val embedder = HashingEmbeddingProvider()

        val fixedPath = outputDir.resolve("index-fixed-size.json")
        val structuralPath = outputDir.resolve("index-structural.json")

        val fixedResult = index(FixedSizeChunker(StrategyDefaults.FIXED_CHUNK_SIZE, StrategyDefaults.FIXED_OVERLAP), fixedPath, documents, embedder)
        val structuralResult = index(StructuralChunker(StrategyDefaults.STRUCTURAL_MAX_CHUNK_SIZE), structuralPath, documents, embedder)

        val fixedStore = JsonVectorStore.open(fixedPath)
        val structuralStore = JsonVectorStore.open(structuralPath)

        val fixedStatistics = IndexStatistics.of(ChunkingStrategyType.FIXED_SIZE, documents, fixedResult.chunks)
        val structuralStatistics = IndexStatistics.of(ChunkingStrategyType.STRUCTURAL, documents, structuralResult.chunks)

        val fixedSearch = SemanticSearch(embedder, fixedStore)
        val structuralSearch = SemanticSearch(embedder, structuralStore)

        val keys = answerKeys(documents)
        val fixedOutcomes = keys.map { QueryOutcome(it, fixedSearch.search(it.query, TOP_K)) }
        val structuralOutcomes = keys.map { QueryOutcome(it, structuralSearch.search(it.query, TOP_K)) }

        val fixedScore = StrategyComparison.score(fixedOutcomes)
        val structuralScore = StrategyComparison.score(structuralOutcomes)
        val cuts = StrategyComparison.cuts(fixedResult.chunks, structuralResult.chunks)

        return buildString {
            appendCorpus(documents, embedder.dimension)
            appendIndexing(documents, fixedResult, structuralResult, fixedPath, structuralPath)
            appendStatistics(fixedStatistics, structuralStatistics, fixedScore, structuralScore)
            appendQueries(fixedOutcomes, structuralOutcomes)
            appendScores(fixedScore, structuralScore)
            appendCuts(cuts)
            appendConclusions(fixedStatistics, structuralStatistics, fixedScore, structuralScore)
        }
    }

    private suspend fun index(
        chunker: ChunkingStrategy,
        path: Path,
        documents: List<Document>,
        embedder: EmbeddingProvider
    ): IndexingResult {
        // Индекс прогона строится заново: open сливает записи по id, и чанки прошлого корпуса
        // остались бы в файле, а значит — и в поиске, который отчёт описывает как свой.
        val store = JsonVectorStore.create(path)
        return DocumentIndexer(chunker, embedder, store).index(documents)
    }

    /** Эталонные ответы: текст раздела берётся из корпуса, отсутствие раздела — ошибка корпуса. */
    private fun answerKeys(documents: List<Document>): List<AnswerKey> =
        DemoQueries.all.map { spec ->
            val answer = Sections.text(documents, spec.source, spec.section)
                ?: error("В корпусе нет раздела «${spec.section}» в файле ${spec.source}")
            AnswerKey(spec.text, spec.source, spec.section, answer)
        }

    private fun StringBuilder.appendCorpus(
        documents: List<Document>,
        dimension: Int
    ) {
        val files = documents.flatMap { it.files }
        appendLine("# День 21. Индексация документов: две стратегии chunking")
        appendLine()
        appendLine("## Корпус")
        appendLine()
        appendLine("| # | Документ | Файлов | Файлы |")
        appendLine("| --- | --- | --- | --- |")
        documents.forEachIndexed { position, document ->
            appendLine(
                "| ${position + 1} | ${document.title ?: document.id} | ${document.files.size} | " +
                    document.files.joinToString(", ") { "`${it.name}`" } + " |"
            )
        }
        appendLine()
        val chars = files.sumOf { it.content.length }
        val tokens = Tokens.count(documents)
        appendLine("- Документов: ${documents.size}, файлов: ${files.size}, символов: ${chars.grouped()}, токенов: ${tokens.grouped()}")
        appendLine("- Токен — слово (буквы и цифры): столько же единиц уходит в embedding, служебные слова не отбрасываются (в отличие от оценки релевантности)")
        appendLine("- Embedding: локальный `HashingEmbeddingProvider`, размерность $dimension, прогон воспроизводим и не зависит от сети")
        appendLine()
    }

    private fun StringBuilder.appendIndexing(
        documents: List<Document>,
        fixed: IndexingResult,
        structural: IndexingResult,
        fixedPath: Path,
        structuralPath: Path
    ) {
        appendLine("## Индексация")
        appendLine()
        appendLine("| Стратегия | Настройка | Чанков | Время | Файл индекса |")
        appendLine("| --- | --- | --- | --- | --- |")
        appendLine("| FIXED_SIZE | chunkSize=${StrategyDefaults.FIXED_CHUNK_SIZE}, overlap=${StrategyDefaults.FIXED_OVERLAP} | ${fixed.chunks.size} | ${fixed.elapsedMillis} мс | `${fixedPath.fileName}` |")
        appendLine("| STRUCTURAL | maxChunkSize=${StrategyDefaults.STRUCTURAL_MAX_CHUNK_SIZE} | ${structural.chunks.size} | ${structural.elapsedMillis} мс | `${structuralPath.fileName}` |")
        appendLine()
        val chars = documents.sumOf { document -> document.files.sumOf { it.content.length } }
        appendLine(
            "Документ (${chars.grouped()} символов, ${Tokens.count(documents).grouped()} токенов) поделён " +
                "на ${fixed.chunks.size} кусочков стратегией FIXED_SIZE и на ${structural.chunks.size} — STRUCTURAL."
        )
        appendLine()
        appendLine("Индексы после записи перечитаны с диска новыми экземплярами `JsonVectorStore` — поиск ниже идёт по сохранённым данным, а не по памяти прогона.")
        appendLine()
    }

    private fun StringBuilder.appendStatistics(
        fixed: IndexStatistics,
        structural: IndexStatistics,
        fixedScore: StrategyScore,
        structuralScore: StrategyScore
    ) {
        appendLine("## Сравнение стратегий")
        appendLine()
        appendLine("| Критерий | Fixed Size | Structural |")
        appendLine("| --- | --- | --- |")
        row("Документов / файлов", "${fixed.documents} / ${fixed.files}", "${structural.documents} / ${structural.files}")
        row("Количество chunks", fixed.chunks.toString(), structural.chunks.toString())
        row("Средний размер chunk", fixed.averageChunkSize.grouped(), structural.averageChunkSize.grouped())
        row("Min / Max размер", "${fixed.minChunkSize} / ${fixed.maxChunkSize}", "${structural.minChunkSize} / ${structural.maxChunkSize}")
        row(
            "Сохранение контекста",
            "перекрытие ${StrategyDefaults.FIXED_OVERLAP} символов между соседними чанками",
            "перекрытия нет, но границы — по логическим блокам"
        )
        row(
            "Сохранение структуры",
            "разделы не размечаются, чанков через границу файлов: ${fixed.fileBoundaryCrossings}",
            "разделов в метаданных: ${structural.sections}, чанков через границу файлов: ${structural.fileBoundaryCrossings}"
        )
        row(
            "Качество Top-$TOP_K поиска",
            "${fixedScore.relevantInTop3} из ${fixedScore.queries} запросов (Top-1: ${fixedScore.relevantAtTop1})",
            "${structuralScore.relevantInTop3} из ${structuralScore.queries} запросов (Top-1: ${structuralScore.relevantAtTop1})"
        )
        row(
            "MRR (в первых $TOP_K)",
            format(2, fixedScore.meanReciprocalRank),
            format(2, structuralScore.meanReciprocalRank)
        )
        row(
            "Полнота метаданных",
            "section: ${percent(fixed.sectionCoverage)}, страницы: ${percent(fixed.pageCoverage)}",
            "section: ${percent(structural.sectionCoverage)}, страницы: ${percent(structural.pageCoverage)}"
        )
        row("Символов в корпусе", fixed.sourceChars.grouped(), structural.sourceChars.grouped())
        row("Токенов в корпусе", fixed.sourceTokens.grouped(), structural.sourceTokens.grouped())
        row(
            "Токенов в чанках",
            "${fixed.chunkTokens.grouped()} (×${format(2, fixed.chunkTokens.toDouble() / fixed.sourceTokens)})",
            "${structural.chunkTokens.grouped()} (×${format(2, structural.chunkTokens.toDouble() / structural.sourceTokens)})"
        )
        appendLine()
    }

    private fun StringBuilder.row(criterion: String, fixed: String, structural: String) {
        appendLine("| $criterion | $fixed | $structural |")
    }

    private fun StringBuilder.appendQueries(
        fixedOutcomes: List<QueryOutcome>,
        structuralOutcomes: List<QueryOutcome>
    ) {
        appendLine("## Поисковые запросы")
        appendLine()
        appendLine("Формат: ранг, similarity (косинус), `source`, `section`, страницы книги и начало текста чанка.")
        appendLine()

        fixedOutcomes.forEachIndexed { position, fixedOutcome ->
            val structuralOutcome = structuralOutcomes[position]
            appendLine("### Запрос ${position + 1}/${fixedOutcomes.size}: ${fixedOutcome.key.query}")
            appendLine()
            appendLine("Эталон: `${fixedOutcome.key.source}` → раздел «${fixedOutcome.key.section}»")
            appendLine()
            appendLine("**FIXED_SIZE**")
            appendLine()
            appendResults(fixedOutcome)
            appendLine()
            appendLine("**STRUCTURAL**")
            appendLine()
            appendResults(structuralOutcome)
            appendLine()
        }
    }

    private fun StringBuilder.appendResults(outcome: QueryOutcome) {
        if (outcome.results.isEmpty()) {
            appendLine("_Ничего не найдено._")
            return
        }
        appendLine("| # | similarity | source | section | Страницы | Начало текста |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        outcome.results.forEachIndexed { rank, result ->
            val metadata = result.chunk.metadata
            appendLine(
                "| ${rank + 1} | ${format(3, result.similarity)} | `${metadata.source}` | " +
                    "${metadata.section ?: "—"} | ${PageMarkers.format(metadata.pages) ?: "—"} | " +
                    "${preview(result.chunk.content)} |"
            )
        }
    }

    private fun StringBuilder.appendScores(fixed: StrategyScore, structural: StrategyScore) {
        appendLine("## Оценка качества Top-$TOP_K")
        appendLine()
        appendLine("Ответ засчитан, если чанк содержит не меньше ${StrategyComparison.RELEVANCE_THRESHOLD} слов эталонного раздела (служебные слова отброшены, доля считается от раздела, поэтому длинный чанк не проигрывает из-за лишних слов). Считается только содержание: чанк фиксированной стратегии, пересёкший границу файлов, содержит ответ и засчитывается как ответ, а его дефект метаданных виден строкой «чанков через границу файлов». Разделы (`section`) при оценке не используются по той же причине: у фиксированной стратегии их нет по построению, и оценка по ним измеряла бы метаданные, а не поиск.")
        appendLine()
        appendLine("| Стратегия | Top-1 | Top-$TOP_K | MRR |")
        appendLine("| --- | --- | --- | --- |")
        appendLine("| FIXED_SIZE | ${fixed.relevantAtTop1}/${fixed.queries} | ${fixed.relevantInTop3}/${fixed.queries} | ${format(2, fixed.meanReciprocalRank)} |")
        appendLine("| STRUCTURAL | ${structural.relevantAtTop1}/${structural.queries} | ${structural.relevantInTop3}/${structural.queries} | ${format(2, structural.meanReciprocalRank)} |")
        appendLine()
    }

    private fun StringBuilder.appendCuts(cuts: List<CutExample>) {
        appendLine("## Примеры разрезов: где Fixed Size разрезал, а Structural сохранил")
        appendLine()
        if (cuts.isEmpty()) {
            appendLine("_Разрезов не найдено._")
            appendLine()
            return
        }
        appendLine("Чанк фиксированной стратегии целиком внутри чанка структурной — значит, граница фиксированного чанка прошла по логически связанному фрагменту. Всего таких чанков: ${cuts.size}.")

        val shown = cuts.sortedByDescending { it.startsMidSection }.take(4)
        appendLine()
        shown.forEachIndexed { position, cut ->
            appendLine("### ${position + 1}. ${cut.section ?: "преамбула"} (`${cut.source}`)")
            appendLine()
            appendLine("- конец предыдущего фиксированного чанка: `…${markupSafe(cut.cutFrom)}`")
            appendLine("- начало разрезанного чанка: `…${markupSafe(cut.cutInto)}`")
            appendLine("- фиксированный чанк: `${cut.fixedChunkId}`, структурный чанк: `${cut.structuralChunkId}`")
            appendLine()
        }
    }

    private fun StringBuilder.appendConclusions(
        fixed: IndexStatistics,
        structural: IndexStatistics,
        fixedScore: StrategyScore,
        structuralScore: StrategyScore
    ) {
        appendLine("## Выводы")
        appendLine()
        appendLine("**Fixed Size**")
        appendLine()
        appendLine("- Простая и предсказуемая: размер чанка задан (${StrategyDefaults.FIXED_CHUNK_SIZE}), соседние чанки перекрываются на ${StrategyDefaults.FIXED_OVERLAP} символов, поэтому разрыв на границе частично компенсируется контекстом.")
        appendLine("- Не сохраняет структуру: чанков через границу файлов — ${fixed.fileBoundaryCrossings}, разделы в метаданных не размечаются (${percent(fixed.sectionCoverage)}), поэтому найденный ответ приходится читать целиком и контекст раздела восстанавливать вручную.")
        appendLine("- Качество Top-$TOP_K: ${fixedScore.relevantInTop3} из ${fixedScore.queries} запросов нашли ответ в первых $TOP_K, MRR = ${format(2, fixedScore.meanReciprocalRank)}.")
        appendLine()
        appendLine("**Structural**")
        appendLine()
        appendLine("- Режет по разделам и абзацам, границы чанков совпадают с логическими: чанк — это готовый ответ (разделов в метаданных: ${structural.sections}, ${percent(structural.sectionCoverage)} чанков знают свой раздел).")
        appendLine("- Границы файлов не нарушаются (${structural.fileBoundaryCrossings} пересечений), поэтому `source` и `section` точны.")
        appendLine("- Цена — размер чанка диктует документ, а не настройка (здесь от ${structural.minChunkSize} до ${structural.maxChunkSize} символов): короткий раздел даёт короткий чанк, длинный доходит до предела. Перекрытия между соседними чанками нет — контекст между ними держится только на смысловой близости, а на тексте без заголовков стратегия вырождается в один чанк на файл.")
        appendLine("- Качество Top-$TOP_K: ${structuralScore.relevantInTop3} из ${structuralScore.queries}, MRR = ${format(2, structuralScore.meanReciprocalRank)}.")
        appendLine()
        val verdict = when {
            structuralScore.meanReciprocalRank > fixedScore.meanReciprocalRank ->
                "точнее оказалась структурная нарезка"
            structuralScore.meanReciprocalRank < fixedScore.meanReciprocalRank ->
                "точнее оказалась фиксированная нарезка"
            else -> "по MRR стратегии сравнялись"
        }
        appendLine(
            "**Итог.** На этом корпусе $verdict: Top-1 — ${structuralScore.relevantAtTop1} из ${structuralScore.queries} против ${fixedScore.relevantAtTop1}, " +
                "Top-$TOP_K — ${structuralScore.relevantInTop3} против ${fixedScore.relevantInTop3}, MRR — ${format(2, structuralScore.meanReciprocalRank)} против ${format(2, fixedScore.meanReciprocalRank)}."
        )
        appendLine()
        appendLine(
            buildString {
                if (structuralScore.relevantInTop3 > fixedScore.relevantInTop3) {
                    append("Выигрыш структурной нарезки виден в полноте Top-$TOP_K: чанк-раздел целиком посвящён теме запроса, тогда как фиксированный чанк несёт в векторе ещё и обрывки соседних. ")
                    if (structuralScore.relevantAtTop1 <= fixedScore.relevantAtTop1) {
                        append("Первым ответ встаёт не чаще: близости у обеих стратегий сжаты — лексический embedding слабо различает соседние чанки одного раздела, и по ним граница чанка решает меньше, чем по принадлежности к разделу. ")
                    }
                } else {
                    append("Полнота Top-$TOP_K у стратегий совпала: логические границы сами по себе не сделали чанки различимее для лексического embedding. ")
                }
                append("Выбор не самоочевиден: фиксированной нарезке не нужна разметка, её размер задан настройкой, а перекрытие сохраняет контекст на стыке — на однородном тексте без заголовков это может оказаться полезнее.")
            }
        )
        appendLine()
        appendLine("Сравнение проведено на ${fixedScore.queries} запросах и корпусе из ${fixed.files} файлов; числа относятся к этому прогону.")
    }

    private fun preview(content: String): String {
        val line = markupSafe(content).take(70)
        return "`$line${if (line.length > 70) "…" else ""}`"
    }

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * Текст для ячейки markdown-таблицы: без переносов строк, без обратных кавычек (они закрыли бы
     * код-спан раньше времени) и без «|», который создал бы лишний столбец.
     */
    private fun markupSafe(text: String): String =
        oneLine(text).replace('`', '\'').replace("|", "\\|")

    private fun percent(value: Double): String = format(0, value * 100) + "%"
}

/** Число с разделением разрядов: «38 412» читается, «38412» — нет. */
private fun Int.grouped(): String =
    toString().reversed().chunked(3).joinToString(" ").reversed()

private fun format(decimals: Int, value: Double): String =
    String.format(Locale.US, "%.${decimals}f", value)
