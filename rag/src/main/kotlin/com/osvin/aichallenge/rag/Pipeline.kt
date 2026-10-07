package com.osvin.aichallenge.rag

/**
 * Кандидат и всё, что с ним сделали этапы: то, из-за чего вопрос попал или не попал в контекст.
 *
 * Здесь лежит метаданные, которых не видно в готовом контексте: место в векторной выдаче
 * ([Source.rank]), результат фильтрации ([accepted]), оценка второго этапа ([rerankScore]) и обе
 * позиции после него ([rerankRank], [finalRank]). Одной строки отчёта хватало бы, чтобы сказать
 * «фрагмент не попал в ответ»; чтобы сказать, **почему** он не попал — а это и есть задание
 * (§11, §15) — нужны все эти поля: кандидат мог не дойти до фильтра, быть отсечённым порогом или
 * проиграть конкуренцию после reranking, и это три разные ошибки с разными лекарствами.
 *
 * [rerankScore] у отсечённого кандидата пуст: второй этап его не видел, и ноль в этом поле читался бы
 * как «оценён низко», то есть как суждение, которого не было.
 */
data class Candidate(
    /** Фрагмент в том виде, в каком его нашёл поиск: `rank` — место в векторной выдаче. */
    val source: Source,
    /** Прошёл ли кандидат порог близости. */
    val accepted: Boolean,
    /** Оценка второго этапа: выше — полезнее. Пусто у отсечённых порогом. */
    val rerankScore: Double? = null,
    /** Место после второго этапа; пусто у отсечённых порогом. */
    val rerankRank: Int? = null,
    /** Место в контексте; пусто у тех, кто не попал в финальный Top-K. */
    val finalRank: Int? = null
) {

    /** Близость кандидата: число поиска, с которым он пришёл на фильтр. */
    val similarity: Double get() = source.similarity

    /** Кандидат ушёл в запрос: именно это, а не «был найден», меряет пользу этапов. */
    val passed: Boolean get() = finalRank != null
}

/**
 * Путь запроса по этапам: вопрос, переписанный запрос, кандидаты и время каждого этапа.
 *
 * Хранится целиком, потому что восстановить его после прогона нечем: контекст собирается в памяти,
 * а порядок и оценки этапов задним числом не выводятся из готового ответа. Это тот же аргумент, что
 * у лога дня 22, но здесь он сильнее: этапов стало пять, и «почему в контексте именно эти три
 * фрагмента» — вопрос, на который без трейса не ответить.
 *
 * [rewritten] пуст, когда переписывания нет: у базового режима дня 22 этапов rewrite, filter
 * и rerank нет вовсе, и трейс всё равно ведётся — иначе сравнивать режимы было бы нечем.
 */
data class Trace(
    val original: String,
    val rewritten: String?,
    /** Сколько кандидатов просили у поиска: `Top-K` первого этапа. */
    val retrievalTopK: Int,
    /** Порог фильтрации: `0.0` — фильтра нет. */
    val threshold: Double,
    val candidates: List<Candidate>,
    val retrievalMillis: Long,
    val rewriteMillis: Long,
    val rerankMillis: Long,
    /** Сколько кандидатов оценил второй этап: остальным хватило близости. */
    val rerankScoredByModel: Int = 0,
    /** Что пошло не так на этапах: сбой формата ответа, пустая выдача после фильтра. */
    val note: String? = null
) {

    /** Сколько кандидатов прошло порог. */
    val accepted: Int get() = candidates.count { it.accepted }

    /** Сколько кандидатов порог отсеял. */
    val removed: Int get() = candidates.size - accepted

    /** Сколько фрагментов ушло в запрос. */
    val passed: Int get() = candidates.count { it.passed }

    /** Порог отсеял всех: контекста нет вовсе, и это отдельное состояние конвейера. */
    val empty: Boolean get() = candidates.isNotEmpty() && accepted == 0
}

/**
 * Улучшенный конвейер: переписывание, расширенный поиск, фильтр, второй этап, финальный Top-K.
 *
 * Конвейер — это [SourceFinder], а не отдельный агент: агент дня 22 не меняется ни на строку, он
 * по-прежнему просит фрагменты по вопросу, и в режиме без RAG поиска не делает. Отсюда два свойства,
 * которые задание требует держать: **исходный вопрос уходит в модель**, потому что агент передаёт
 * в промпт тот вопрос, который получил, а переписанный живёт внутри конвейера и служит только
 * поиску; и **два Top-K независимы** ([retrievalTopK] против [finalTopK]), потому что число
 * кандидатов нужно этапам, а число фрагментов в контексте — модели.
 *
 * Порядок вызовов намеренный и повторяет путь задания: переписывание (может не быть) → поиск
 * ([finder] уже настроен на [retrievalTopK]) → фильтр → второй этап (может не быть) → отсечение
 * до [finalTopK]. Второй этап получает уже отфильтрованных кандидатов: считать полезность
 * фрагментов, которые в контекст не попадут, — это платить за них моделью (у LLM-реранкера —
 * токенами и временем) без влияния на результат.
 *
 * Сортировка после второго этапа устойчива (`sortedByDescending` в Kotlin сохраняет порядок равных),
 * поэтому при равных оценках первым остаётся тот, кто был выше по близости. Это не деталь: без
 * устойчивости порядок равных зависел бы от реализации сортировки, и второй прогон на тех же данных
 * мог бы дать другую выдачу.
 *
 * Финальные фрагменты перенумеровываются с единицы: модель ссылается на источники номерами из
 * запроса, и номер в контексте обязан совпадать с позицией в контексте, а не с местом в векторной
 * выдаче (после reranking это разные числа).
 */
class RagPipeline(
    private val finder: SourceFinder,
    private val rewriter: QueryRewriter?,
    private val filter: SimilarityFilter,
    private val reranker: Reranker?,
    private val finalTopK: Int
) : SourceFinder {

    override suspend fun find(question: String): Retrieval {
        val rewrite = rewriter?.rewrite(question)
        val query = rewrite?.rewritten ?: question
        return refine(question, finder.find(query), rewrite)
    }

    /**
     * Второй участок конвейера над готовой выдачей: фильтр, второй этап, финальный Top-K, трейс.
     *
     * Отдельным методом, потому что этим участком пользуется не только прогон: подбор порога
     * (задание §18) считается на **одной и той же** векторной выдаче для нескольких порогов.
     * Сходить в поиск на каждый порог значило бы получить четыре выдачи одного вопроса, и разница
     * между строками таблицы порогов включала бы разницу выдач — то есть мерила бы не порог.
     *
     * [rewrite] передаётся, чтобы трейс знал про переписанный запрос: первая половина конвейера
     * уже прошла, и её результат виден в выдаче, но не в самом запросе.
     */
    suspend fun refine(question: String, found: Retrieval, rewrite: Rewrite? = null): Retrieval {
        val kept = filter.keep(found.sources)
        val rerankStarted = System.nanoTime()
        val rerank = reranker?.rank(question, kept)
        val rerankMillis = if (reranker == null) 0L else (System.nanoTime() - rerankStarted) / 1_000_000
        val ordered = (rerank?.scored ?: kept.map { Scored(it, it.similarity) })
            .sortedByDescending { it.score }
        val final = ordered.take(finalTopK)

        val placed = final.mapIndexed { index, scored -> scored.source.id to index + 1 }.toMap()
        val reranked = ordered.mapIndexed { index, scored -> scored.source.id to index + 1 }.toMap()
        val scores = rerank?.scored?.associate { it.source.id to it.score }.orEmpty()
        val keptIds = kept.map { it.id }.toSet()

        val trace = Trace(
            original = question,
            rewritten = rewrite?.rewritten,
            retrievalTopK = found.sources.size,
            threshold = filter.threshold,
            candidates = found.sources.map { source ->
                Candidate(
                    source = source,
                    accepted = source.id in keptIds,
                    rerankScore = if (source.id in keptIds) scores[source.id] else null,
                    rerankRank = if (source.id in keptIds) reranked[source.id] else null,
                    finalRank = placed[source.id]
                )
            },
            retrievalMillis = found.millis,
            rewriteMillis = rewrite?.millis ?: 0,
            rerankMillis = rerankMillis,
            rerankScoredByModel = rerank?.scoredByModel ?: 0,
            note = listOfNotNull(
                rewrite?.takeIf { !it.changed && rewriter != null }
                    ?.let { "переписывание не изменило запрос" },
                rerank?.note,
                "фильтр отсеял всех кандидатов".takeIf { kept.isEmpty() && found.sources.isNotEmpty() }
            ).joinToString("; ").ifEmpty { null }
        )

        // Контекст — финальные фрагменты, перенумерованные с единицы; в трейсе они остались
        // с номерами поиска и этапов, потому что трейс отвечает на «почему», а контекст — на «что».
        val sources = final.mapIndexed { index, scored -> scored.source.copy(rank = index + 1) }

        // Время поиска здесь — время всей подготовки контекста: переписывание, векторная выдача,
        // второй этап. Агенту это время и нужно: оно то, чего не было в режиме без RAG. Разбивка
        // по этапам остаётся в трейсе — по ней видно, что именно оказалось дорогим.
        return Retrieval(
            sources = sources,
            queryVector = found.queryVector,
            millis = trace.rewriteMillis + found.millis + rerankMillis,
            trace = trace
        )
    }
}
