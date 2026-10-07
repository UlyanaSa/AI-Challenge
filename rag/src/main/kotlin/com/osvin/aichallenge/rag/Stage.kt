package com.osvin.aichallenge.rag

/**
 * Прогон одного вопроса целиком: без базы, базовый RAG и улучшенный RAG.
 *
 * Три режима в одном типе, потому что вопрос дня 23 — не «стало ли лучше с новыми этапами вообще»,
 * а «что добавил каждый этап к тому, что уже было». Без базы — нижняя граница (память модели),
 * базовый — то, что дал поиск дня 22, улучшенный — то, что дал конвейер с переписыванием, порогом
 * и вторым этапом. Сравнение идёт по одной и той же выдаче одного и того же индекса, поэтому
 * разница между колонками — это разница этапов, а не прогонов.
 *
 * [day22] возвращает пару дня 22 (базовый режим как «с RAG», прогон без базы), чтобы метрики
 * предыдущего дня считались тем же кодом ([Report.summary]), а не вторым набором формул.
 */
data class Trial(val control: ControlQuestion, val noBase: Run, val baseline: Run, val improved: Run) {

    /** Пара режимов дня 22: базовый RAG против ответа по памяти. */
    val day22: Comparison get() = Comparison(control, baseline, noBase)
}

/**
 * Где правильный фрагмент потерялся по дороге к контексту.
 *
 * Четыре состояния, а не «нашёл / не нашёл», потому что задания §15 просит именно этого: ошибка
 * поиска, слишком высокий порог и ошибка второго этапа лечатся по-разному. Классификация читается
 * из трейса: где правильный фрагмент видели последний раз, там и потерялся.
 */
enum class Loss(val title: String) {
    /** Правильный фрагмент ушёл в контекст: терять нечего. */
    NONE("дошёл до контекста"),

    /** Его не было уже в векторной выдаче — вопрос к embedding, нарезке или переписыванию запроса. */
    RETRIEVAL("не найден в Top-N"),

    /** Он был в выдаче, но близость оказалась ниже порога — цена фильтрации. */
    FILTER("отсечён порогом"),

    /** Он прошёл порог, но второй этап поставил его ниже финального Top-K. */
    RERANK("не попал в финальный Top-K")
}

/**
 * Сверка этапов на одном вопросе: был ли правильный фрагмент на каждом шаге.
 *
 * «Правильный фрагмент» определяется так же, как попадание источника в дне 22, — по тексту: фрагмент
 * считается правильным, если в нём есть хоть один ожидаемый факт ([Fact.keywords]). Это важнее
 * аккуратности метаданных: чанк может лежать на нужной странице и не содержать ответа, и тогда
 * «источник найден» было бы неправдой.
 *
 * Три булева признака — это три числа задания (§20): Hit@N после поиска, Hit после фильтрации
 * и Hit@K после второго этапа. Они монотонны по построению: раз фрагмент не был найден, то он
 * не мог пройти ни фильтр, ни второй этап, и по разнице между признаками видно, какой этап его съел.
 *
 * [removed] и [wronglyFiltered] считаются по всем вопросам, включая тот, ответа на который в базе
 * нет: там нет «правильного фрагмента», но отсечённые кандидаты есть, и они нужны в счёте цены фильтра.
 */
data class StageCheck(
    val control: ControlQuestion,
    val inRetrieval: Boolean,
    val inFiltered: Boolean,
    val inFinal: Boolean,
    val candidates: Int,
    val accepted: Int,
    val passed: Int,
    val removed: Int,
    val wronglyFiltered: Int,
    val reordered: Boolean,
    val loss: Loss
) {

    /** Вопрос с ответом в базе: только по таким считается попадание этапов. */
    val scored: Boolean get() = !control.absent
}

/**
 * Метрики этапов по всему набору — то, что задание просит сравнить (§20).
 *
 * Три Hit-числа стоят подряд, и в этом весь смысл: 10 из 10 после поиска, 9 из 10 после фильтрации
 * и 7 из 10 после второго этапа читаются как «поиск находит всё, фильтр съел один, второй этап
 * ещё два» — то есть как диагноз, а не как итоговая оценка. Проценты от [scored] здесь не считаются:
 * отношения считает тот, кто их показывает (отчёт и страница), а хранятся целые числа попаданий.
 *
 * [wronglyFiltered] — цена фильтра, [reordered] — сколько раз второй этап изменил порядок
 * (без этого числа «реранкер работает» осталось бы утверждением о коде, а не о прогоне).
 */
data class StageSummary(
    val questions: Int,
    val scored: Int,
    val noBaseScore: Double,
    val baselineScore: Double,
    val improvedScore: Double,
    val baselineFacts: Int,
    val improvedFacts: Int,
    val factsTotal: Int,
    val baselineHits: Int,
    val improvedHits: Int,
    val retrievalHits: Int,
    val filterHits: Int,
    val finalHits: Int,
    val removed: Int,
    val wronglyFiltered: Int,
    val emptyContext: Int,
    val reordered: Int,
    val lostInRetrieval: Int,
    val lostInFilter: Int,
    val lostInRerank: Int,
    val rewriteChanged: Int,
    val better: Int,
    val same: Int,
    val worse: Int
)

/** Сверка этапов улучшенного конвейера и её сводка. */
object Stages {

    /**
     * Есть ли ожидаемый факт в этом фрагменте.
     *
     * Текст нормализуется здесь, а не у вызывающего: признак факта — основа слова, и сравнивать его
     * нужно с приведённым текстом, иначе «Ё» и «ё» разойдутся на пустом месте.
     */
    fun containsFact(source: Source, fact: Fact): Boolean =
        Check.contains(Check.normalize(source.text), fact)

    /** Правильные фрагменты набора: те, где есть хоть один ожидаемый факт. */
    fun correct(sources: List<Source>, control: ControlQuestion): List<Source> =
        sources.filter { source -> control.facts.any { containsFact(source, it) } }

    /** Сверка этапов по трейсу улучшенного конвейера; `null`, если трейса нет. */
    fun evaluate(control: ControlQuestion, trace: Trace?): StageCheck? {
        trace ?: return null
        val candidates = trace.candidates.map { it.source }
        val filtered = trace.candidates.filter { it.accepted }.map { it.source }
        val final = trace.candidates.filter { it.passed }.map { it.source }

        val inRetrieval = correct(candidates, control).isNotEmpty()
        val inFiltered = correct(filtered, control).isNotEmpty()
        val inFinal = correct(final, control).isNotEmpty()

        // Порядок второго этапа отличается от векторного, если хоть один дошедший кандидат сменил
        // место: сравнение по позициям, а не по оценкам, потому что оценки у эвристики и у модели
        // в разных шкалах, а вопрос один и тот же — встал ли фрагмент на другое место.
        val reordered = trace.candidates.any { it.rerankRank != null && it.rerankRank != it.source.rank }

        val dropped = trace.candidates.count { !it.accepted }
        val wrongly = trace.candidates.count { !it.accepted && control.facts.any { f -> containsFact(it.source, f) } }

        return StageCheck(
            control = control,
            inRetrieval = inRetrieval,
            inFiltered = inFiltered,
            inFinal = inFinal,
            candidates = candidates.size,
            accepted = filtered.size,
            passed = final.size,
            removed = dropped,
            wronglyFiltered = wrongly,
            reordered = reordered,
            loss = when {
                // У вопроса без ответа в базе правильного фрагмента нет вовсе, и «потерей» это не
                // считается: терять было нечего.
                control.absent -> Loss.NONE
                inFinal -> Loss.NONE
                !inRetrieval -> Loss.RETRIEVAL
                !inFiltered -> Loss.FILTER
                else -> Loss.RERANK
            }
        )
    }

    /**
     * Сводка этапов и двух режимов по набору.
     *
     * Попаданием режима считается то же, что в дне 22 — ожидаемый текст в контексте, — и считается
     * по контексту, который ушёл в запрос: базовый режим берёт выдачу поиска, улучшенный — финальный
     * Top-K. Считать попаданием «источник был в Top-N» для улучшенного режима значило бы хвалить
     * конвейер за фрагменты, которых модель не увидела.
     */
    fun summary(trials: List<Trial>): StageSummary {
        val scored = trials.filterNot { it.control.absent }
        val checks = trials.mapNotNull { evaluate(it.control, it.improved.answer.retrieval.trace) }

        return StageSummary(
            questions = trials.size,
            scored = scored.size,
            noBaseScore = average(trials.map { it.noBase.check.score }),
            baselineScore = average(trials.map { it.baseline.check.score }),
            improvedScore = average(trials.map { it.improved.check.score }),
            baselineFacts = trials.sumOf { it.baseline.check.matched },
            improvedFacts = trials.sumOf { it.improved.check.matched },
            factsTotal = trials.sumOf { it.control.facts.size },
            baselineHits = scored.count { it.baseline.check.retrievedFacts.any { found -> found } },
            improvedHits = scored.count { it.improved.check.retrievedFacts.any { found -> found } },
            retrievalHits = checks.count { it.scored && it.inRetrieval },
            filterHits = checks.count { it.scored && it.inFiltered },
            finalHits = checks.count { it.scored && it.inFinal },
            removed = checks.sumOf { it.removed },
            wronglyFiltered = checks.sumOf { it.wronglyFiltered },
            emptyContext = checks.count { it.accepted == 0 },
            reordered = checks.count { it.reordered },
            lostInRetrieval = checks.count { it.loss == Loss.RETRIEVAL },
            lostInFilter = checks.count { it.loss == Loss.FILTER },
            lostInRerank = checks.count { it.loss == Loss.RERANK },
            rewriteChanged = trials.count { trial ->
                val rewritten = trial.improved.answer.retrieval.trace?.rewritten
                rewritten != null && rewritten.trim() != trial.control.question.trim()
            },
            better = trials.count { it.improved.check.score > it.baseline.check.score },
            same = trials.count { it.improved.check.score == it.baseline.check.score },
            worse = trials.count { it.improved.check.score < it.baseline.check.score }
        )
    }

    /** Средняя оценка по вопросам: пустой список даёт ноль, а не деление на ноль. */
    private fun average(scores: List<Int>): Double =
        if (scores.isEmpty()) 0.0 else scores.sum().toDouble() / scores.size
}
