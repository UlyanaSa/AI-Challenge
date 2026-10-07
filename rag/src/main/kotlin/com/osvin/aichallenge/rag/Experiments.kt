package com.osvin.aichallenge.rag

/**
 * Одна строка подбора порога: что даёт один и тот же порог на всём наборе (§18 задания).
 *
 * Числа разделены на две группы, и это разделение — суть эксперимента. Первая группа говорит, что
 * порог сделал с выдачей: сколько кандидатов в среднем осталось ([kept]), сколько отсеяно
 * ([removed]), сколько из отсеянных были правильными ([wronglyFiltered]) и у скольких вопросов
 * после фильтра не осталось ничего ([empty]). Вторая — что из этого вышло: попадание правильного
 * фрагмента после фильтра ([filterHits]) и после второго этапа ([finalHits]), а при живом прогоне
 * ещё и качество ответов ([score], [facts]).
 *
 * Без первой группы порог подбирался бы по итоговой оценке, и высокая оценка при съеденных
 * правильных фрагментах выглядела бы успехом: ответ мог уцелеть на другом фрагменте, а база при
 * этом стала хуже.
 */
data class SweepRow(
    val threshold: Double,
    val kept: Double,
    val removed: Int,
    val wronglyFiltered: Int,
    val empty: Int,
    val filterHits: Int,
    val finalHits: Int,
    /** Средняя оценка ответов и засчитанные факты; пусто, если ответы не спрашивались. */
    val score: Double? = null,
    val facts: Int? = null
)

/**
 * Подбор порога фильтрации на одной выдаче.
 *
 * Ключевое решение: **выдача считается один раз на вопрос**, а не на каждый порог. Порог меняет
 * только то, что происходит после поиска; сходить в поиск ещё раз значило бы получить другую выдачу
 * (эмбеддинги — не гарантированно побитово повторяемые числа), и разница между строками таблицы
 * включала бы не только разницу порогов. Поэтому кандидаты берутся один раз, а каждый порог
 * проходит по ним второй участок конвейера ([RagPipeline.refine]) — тот же код, что в прогоне,
 * чтобы таблица описывала настоящий конвейер, а не его копию в эксперименте.
 *
 * Второй этап в подборе — по умолчанию эвристический ([HeuristicReranker]): модель на каждом пороге
 * дала бы три набора оценок, и «порог 0,5 лучше» могло бы означать «в этот раз модель расставила
 * иначе». Эвристика детерминирована, поэтому таблица описывает влияние порога, а вклад реранкера
 * меряется отдельно — сравнением режимов.
 *
 * [`answer`] — необязательный шаг: с ним считается качество конечных ответов на каждом пороге
 * (это требует обращений к модели), без него — только retrieval. Обе части задания §18 нужны,
 * но стоят по-разному, и разделять их должен вызывающий, а не этот класс.
 */
class ThresholdSweep(
    private val finder: SourceFinder,
    private val reranker: Reranker?,
    private val finalTopK: Int,
    private val thresholds: List<Double>
) {

    suspend fun run(
        controls: List<ControlQuestion>,
        answer: suspend (ControlQuestion, Retrieval) -> AnswerCheck? = { _, _ -> null }
    ): List<SweepRow> {
        val found = controls.associateWith { control -> finder.find(control.question) }

        return thresholds.map { threshold ->
            // Переписывания в подборе нет: вопрос идёт в поиск как есть — тогда таблица описывает
            // влияние порога, а не совместное влияние порога и переписывания.
            val pipeline = RagPipeline(
                finder = finder,
                rewriter = null,
                filter = SimilarityFilter(threshold),
                reranker = reranker,
                finalTopK = finalTopK
            )

            var kept = 0
            var removed = 0
            var wrongly = 0
            var empty = 0
            var filterHits = 0
            var finalHits = 0
            val checks = mutableListOf<AnswerCheck>()

            for (control in controls) {
                val refined = pipeline.refine(control.question, found.getValue(control))
                val check = Stages.evaluate(control, refined.trace) ?: continue
                kept += check.accepted
                removed += check.removed
                wrongly += check.wronglyFiltered
                if (check.accepted == 0) empty += 1
                if (check.scored) {
                    if (check.inFiltered) filterHits += 1
                    if (check.inFinal) finalHits += 1
                }
                answer(control, refined)?.let { checks += it }
            }

            SweepRow(
                threshold = threshold,
                kept = if (controls.isEmpty()) 0.0 else kept.toDouble() / controls.size,
                removed = removed,
                wronglyFiltered = wrongly,
                empty = empty,
                filterHits = filterHits,
                finalHits = finalHits,
                score = if (checks.isEmpty()) null else checks.sumOf { it.score }.toDouble() / checks.size,
                facts = if (checks.isEmpty()) null else checks.sumOf { it.matched }
            )
        }
    }
}

/**
 * Один вопрос в проверке Query Rewrite (§16 задания): что изменилось в поиске от переписывания.
 *
 * Сравниваются две выдачи одного вопроса — по исходному запросу и по переписанному. [originalRank]
 * и [rewrittenRank] — лучшее место правильного фрагмента в каждой из них: не только «нашёлся ли»,
 * но и «поднялся ли». Место, а не близость, потому что близости двух разных запросов сравнивать
 * нельзя — это разные векторы, и разница между ними ничего не говорит о качестве.
 *
 * Две проверки на выдумку и потерю сущностей — то, что задание требует смотреть глазами:
 * [lostNames] — имена из вопроса, которых не осталось в переписанном запросе (переписывание
 * потеряло того, о ком спрашивают), [inventedNames] — имена из переписанного запроса, которых нет
 * ни в вопросе, ни в книге (модель придумала сущность). Второе проверяется по тексту базы: имя,
 * которого нет в книге, искать бессмысленно, и «поиск ухудшился» здесь — вина переписывания.
 *
 * Сравниваются основы ([HeuristicReranker.stem]), а не словоформы, и это тоже исправленная ошибка
 * первого прогона: в вопросе «после смерти его первой жены» рядом стоят «Степана Трофимовича»,
 * а в переписанном запросе — «Степан Трофимович Верховенский». По словоформам это два разных слова,
 * и проверка объявляла потерянными оба имени — притом что модель назвала героя полнее, чем вопрос.
 * Проверка, которая врёт на правильном поведении, хуже отсутствующей: по ней нельзя ни выбирать
 * вариант переписывания, ни отчитываться о нём.
 */
data class RewriteCase(
    val control: ControlQuestion,
    val rewritten: String,
    val originalRank: Int?,
    val rewrittenRank: Int?,
    val lostNames: List<String>,
    val inventedNames: List<String>
) {

    /** Правильный фрагмент нашёлся по исходному запросу. */
    val originalHit: Boolean get() = originalRank != null

    /** Правильный фрагмент нашёлся по переписанному запросу. */
    val rewrittenHit: Boolean get() = rewrittenRank != null

    /** Переписывание подняло правильный фрагмент: он был ниже или не находился вовсе. */
    val helped: Boolean get() = when {
        !rewrittenHit -> false
        !originalHit -> true
        else -> rewrittenRank!! < originalRank!!
    }

    /** Переписывание опустило правильный фрагмент или потеряло его. */
    val hurt: Boolean get() = when {
        !rewrittenHit -> originalHit
        !originalHit -> false
        else -> rewrittenRank!! > originalRank!!
    }
}

/**
 * Проверка Query Rewrite: две выдачи на вопрос и разбор переписанного запроса.
 *
 * Поиск берётся тот же, что в конвейере (один индекс, один провайдер векторов), и это единственный
 * способ сравнить запросы честно: другое число кандидатов или другая модель сдвинули бы места,
 * и разница приписывалась бы переписыванию.
 *
 * Имена собственные из переписанного запроса сверяются с текстом базы ([corpus]): то, чего в книге
 * нет, модель придумала. Проверка нарочно грубая — сравнение по началу словоформы и по всему тексту
 * книги сразу, — но она ловит именно то, что задание просит ловить: выдуманную сущность.
 */
class RewriteEval(
    private val finder: SourceFinder,
    private val corpus: String,
    private val rewrite: QueryRewriter
) {

    suspend fun run(controls: List<ControlQuestion>): List<RewriteCase> = controls.map { control ->
        val before = finder.find(control.question)
        val rewritten = rewrite.rewrite(control.question)
        val after = finder.find(rewritten.rewritten)
        val text = Check.normalize(rewritten.rewritten)
        val names = namesOf(control.question)

        RewriteCase(
            control = control,
            rewritten = rewritten.rewritten,
            originalRank = bestRank(control, before.sources),
            rewrittenRank = bestRank(control, after.sources),
            lostNames = names.filterNot { name -> mentioned(text, name) },
            inventedNames = namesOf(rewritten.rewritten)
                .filterNot { name -> names.any { mentioned(it, name) } }
                .filterNot { name -> mentioned(normalizedCorpus, name) }
        )
    }

    /**
     * Есть ли в тексте упоминание имени.
     *
     * Сравниваются начала слов, и одно начало должно быть началом другого: в вопросе «история Петра
     * Степановича», в переписанном запросе — «Пётр Степанович Верховенский». По пяти буквам основы
     * («петра» против «пётр») это разные слова, и проверка объявляла имя потерянным там, где модель
     * просто поставила его в именительный падеж. Начала же сравнимы: «пётр» — начало «петра»,
     * поэтому смотрятся оба направления. Это вторая исправленная ошибка этой проверки после
     * словоформ: обе всплыли на живых прогонах дня 23 и обе — про то, что проверка не должна
     * называть потерей то, что потерей не является.
     */
    private fun mentioned(text: String, name: String): Boolean {
        val needle = stemOf(name)
        if (needle.isEmpty()) return true
        return text.split(' ').any { word ->
            val stem = stemOf(word)
            stem.isNotEmpty() && (stem.startsWith(needle) || needle.startsWith(stem))
        }
    }

    /** Основа слова по нормализованному виду: «ё» и «е» — одна буква, и регистр не важен. */
    private fun stemOf(word: String): String = HeuristicReranker.stem(Check.normalize(word))

    /** Лучшее место правильного фрагмента: меньший ранг из тех, где есть ожидаемый факт. */
    private fun bestRank(control: ControlQuestion, sources: List<Source>): Int? =
        Stages.correct(sources, control).minOfOrNull { it.rank }

    /**
     * Имена собственные строки: слова с заглавной буквы, кроме первого — оно с заглавной по правилу
     * начала предложения. Так же они берутся и в эвристическом реранкере: одно правило на два места.
     */
    private fun namesOf(text: String): List<String> = text
        .split(' ')
        .drop(1)
        .map { it.trim().trim(',', '.', '?', '!', ':', ';') }
        .filter { it.length >= 3 && it.first().isUpperCase() }
        .distinct()

    private val normalizedCorpus: String by lazy { Check.normalize(corpus) }
}

/**
 * Сводка проверки переписывания: сколько раз помогло, сколько раз навредило и что случилось с именами.
 *
 * Считается отдельно от [RewriteCase], потому что читает её человек в отчёте: «помогло 3, навредило 1»
 * говорит о пользе этапа, а список случаев — о том, каким именно способом.
 */
data class RewriteSummary(
    val questions: Int,
    val changed: Int,
    val helped: Int,
    val hurt: Int,
    val same: Int,
    val lostNames: Int,
    val inventedNames: Int
) {

    companion object {

        fun of(cases: List<RewriteCase>): RewriteSummary = RewriteSummary(
            questions = cases.size,
            changed = cases.count { it.rewritten.trim() != it.control.question.trim() },
            helped = cases.count { it.helped },
            hurt = cases.count { it.hurt },
            same = cases.count { !it.helped && !it.hurt },
            lostNames = cases.sumOf { it.lostNames.size },
            inventedNames = cases.sumOf { it.inventedNames.size }
        )
    }
}
