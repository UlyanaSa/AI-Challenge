package com.osvin.aichallenge.rag

/**
 * Сверка grounded-ответа с требованиями дня (§13, §14 задания).
 *
 * Ответ считается подтверждённым ([grounded]) только когда сходится всё: ответ есть, у каждого
 * утверждения нашлась цитата в процитированном чанке, и сами цитаты несут те факты, которые ответ
 * называет. Такая строгость — прямое требование §6: цитата, не подтверждающая утверждение, делает
 * ответ «не полностью grounded», и различить это можно только проверкой связи цитат с фактами,
 * а не наличием цитат вообще.
 *
 * Отказ ([abstained]) проверяется отдельно и **не считается отсутствием источника**: по §10 это
 * отдельный корректный исход, и упрекать его в отсутствии цитат нельзя. Но и засчитывать любой отказ
 * нельзя: отказ там, где в контексте был правильный фрагмент, — это потерянный ответ, и он считается
 * в [wrongAbstention]. Что отказ был уместен, решает [abstainNeeded]: вопрос без ответа в базе
 * ([ControlQuestion.absent]) или вопрос, у которого правильный фрагмент не дошёл до контекста, —
 * в обоих случаях верного ответа у системы не было, и признать это честнее, чем ответить по памяти.
 */
data class GroundedCheck(
    val control: ControlQuestion,
    /** Ответ предыдущего режима (день 23) верен по мерке дня 22: все факты на месте. */
    val previousCorrect: Boolean,
    /**
     * Ответ grounded-режима верен по той же мерке: названы все ожидаемые факты; у отказа — `false`.
     *
     * Отказ считается отдельно ([validAbstention]): он верен там, где верного ответа в контексте
     * не было, и складывать его с [correct] значило бы потерять, чем именно система ответила —
     * ответом или признанием нехватки сведений.
     */
    val correct: Boolean,
    val abstained: Boolean,
    /** Отказ был уместен: правильного ответа в контексте не было. */
    val abstainNeeded: Boolean,
    /** §13: все источники ответа — чанки из выдачи поиска, ничего придуманного. */
    val sourcesValid: Boolean,
    val hasSource: Boolean,
    val hasQuote: Boolean,
    /** Все утверждения подтверждены: ни одной отброшенной цитаты. */
    val supported: Boolean,
    /** Факты, названные в ответе, подтверждены цитатами, а не только присутствуют в тексте. */
    val factsCovered: Boolean,
    /** Сколько фактов назвал ответ предыдущего режима: рядом с [groundedFacts] это и есть сравнение. */
    val previousFacts: Int,
    /** Сколько фактов назвал grounded-ответ. */
    val groundedFacts: Int,
    /** Ссылки на фрагменты, которых модель не получала: выдуманные источники (§4). */
    val fabricatedSources: Int,
    /** Цитаты, не найденные в тексте процитированного чанка (§11). */
    val invalidQuotes: Int
) {

    /** Ответ подтверждён по всем требованиям дня. */
    val grounded: Boolean
        get() = !abstained && hasSource && hasQuote && supported && factsCovered

    /** Отказ там, где правильный фрагмент был в контексте: ответ потерян. */
    val wrongAbstention: Boolean get() = abstained && !abstainNeeded

    /** Отказ там, где отвечать было нечем, — правильное поведение (§9, §15). */
    val validAbstention: Boolean get() = abstained && abstainNeeded
}

/**
 * Метрики дня 24 по всему набору (§17 задания).
 *
 * Считаются целые числа, а не доли: доля — это деление, и в отчёте видно, из чего она получена.
 * Источник с цитатой считаются только у обычных ответов: у отказа их нет по построению, и включать
 * его в знаменатель значило бы наказывать систему за правильный отказ дважды — сначала отказом,
 * потом пустым источником.
 *
 * [abstainNeeded] — знаменатель правильных отказов: столько вопросов в прогоне не имели верного
 * ответа в контексте. Число берётся из выдачи, а не из ожиданий набора: вопрос с ответом в базе
 * тоже может не иметь его в контексте (правильный чанк не нашёлся или отсечён порогом), и тогда
 * отказ по нему — не ошибка, а следствие поиска, и это видно рядом с метриками этапов дня 23.
 */
data class GroundingSummary(
    val questions: Int,
    /** Ответы предыдущего режима, верные по мерке дня 22. */
    val previousCorrect: Int,
    /** Ответы grounded-режима, верные по той же мерке. */
    val correct: Int,
    /**
     * Правильные исходы: верные ответы и уместные отказы.
     *
     * Это числитель Answer Accuracy (§17): задание прямо говорит, что для вопросов без достаточного
     * контекста правильным поведением считается отказ (§18), и метрика, считающая такой отказ
     * ошибкой, наказывала бы систему за выполнение требования.
     */
    val rightAnswers: Int,
    /** Вопросы с обычным ответом (не отказ) — знаменатель охватов источника и цитаты. */
    val answered: Int,
    val withSource: Int,
    val withQuote: Int,
    val grounded: Int,
    val abstained: Int,
    val abstainNeeded: Int,
    val validAbstentions: Int,
    val wrongAbstentions: Int,
    val fabricatedSources: Int,
    val invalidQuotes: Int,
    val factsPrevious: Int,
    val factsGrounded: Int,
    val factsTotal: Int
)

/** Сверка grounded-режима с набором и метрики дня 24. */
object Grounding {

    /**
     * Сверка одного вопроса: ответ предыдущего режима, grounded-ответ и требования §13, §14.
     *
     * Правильность ответа считается по тексту ([Check.contains]) — тем же признаком факта, что
     * и в дне 22: сравнивать формулировки значило бы мерить слог. Проверка «цитата подтверждает
     * утверждение» устроена так же: цитата обязана содержать слова-признаки того факта, который
     * называет ответ. Это грубее смыслового сравнения, но проверяемо программно, а не пересказом
     * человеком, — а требование дня в том и состоит, чтобы подтверждение было видно машине (§11).
     *
     * [previousCorrect] нужен для сравнения режимов: без него «grounded ответил верно» ничего
     * не говорит — может быть, предыдущий режим отвечал верно на том же контексте.
     */
    fun check(control: ControlQuestion, previous: Run, grounded: GroundedAnswer): GroundedCheck {
        val answer = grounded.answer
        // Тексты нормализуются здесь, а не внутри [Check.contains]: нормализация — подготовка,
        // и на ненормализованном тексте признак в начале предложения не находится из-за заглавной
        // буквы. Живой прогон это и показал: ответ «Том Консидерана.» не засчитывал факт
        // «том Консидерана», и вопрос выглядел неверным при верном ответе.
        val text = Check.normalize(answer.orEmpty())
        val previousText = Check.normalize(previous.answer.text)

        val facts = control.facts
        val previousCorrect = facts.isEmpty() || facts.all { Check.contains(previousText, it) }
        val correct = answer != null && (facts.isEmpty() || facts.all { Check.contains(text, it) })

        val finalSources = grounded.retrieval.trace?.candidates.orEmpty()
            .filter { it.passed }
            .map { it.source }
        val candidates = grounded.retrieval.trace?.candidates.orEmpty().map { it.source }
            .ifEmpty { grounded.retrieval.sources }
        val correctInContext = finalSources.isNotEmpty() && Stages.correct(finalSources, control).isNotEmpty()
        val abstainNeeded = control.absent || !correctInContext

        val sourceIds = candidates.map { it.id }.toSet()
        val sourcesValid = grounded.sources.all { it.chunkId in sourceIds }

        // Факты, названные в ответе, обязаны быть подтверждены цитатами: цитата из чужого места
        // подтверждает что угодно, если сверять её с ответом лишь по наличию.
        val asserted = facts.filter { Check.contains(text, it) }
        val covered = asserted.isNotEmpty() && asserted.all { fact ->
            grounded.claims.any { claim -> Check.contains(Check.normalize(claim.quote), fact) }
        }

        return GroundedCheck(
            control = control,
            previousCorrect = previousCorrect,
            correct = correct,
            abstained = grounded.refused,
            abstainNeeded = abstainNeeded,
            sourcesValid = sourcesValid,
            hasSource = grounded.sources.isNotEmpty(),
            hasQuote = grounded.claims.isNotEmpty(),
            supported = grounded.claims.isNotEmpty() && grounded.checks.all { it.found },
            factsCovered = covered,
            previousFacts = facts.count { Check.contains(previousText, it) },
            groundedFacts = facts.count { Check.contains(text, it) },
            fabricatedSources = grounded.checks.count { it.chunkId == null },
            invalidQuotes = grounded.checks.count { !it.found && it.chunkId != null }
        )
    }

    /** Метрики дня 24 по всем вопросам набора. */
    fun summary(checks: List<GroundedCheck>, factsTotal: Int): GroundingSummary = GroundingSummary(
        questions = checks.size,
        previousCorrect = checks.count { it.previousCorrect },
        correct = checks.count { it.correct },
        rightAnswers = checks.count { it.correct || it.validAbstention },
        answered = checks.count { !it.abstained },
        withSource = checks.count { !it.abstained && it.hasSource },
        withQuote = checks.count { !it.abstained && it.hasQuote },
        grounded = checks.count { it.grounded },
        abstained = checks.count { it.abstained },
        abstainNeeded = checks.count { it.abstainNeeded },
        validAbstentions = checks.count { it.validAbstention },
        wrongAbstentions = checks.count { it.wrongAbstention },
        fabricatedSources = checks.sumOf { it.fabricatedSources },
        invalidQuotes = checks.sumOf { it.invalidQuotes },
        factsPrevious = checks.sumOf { it.previousFacts },
        factsGrounded = checks.sumOf { it.groundedFacts },
        factsTotal = factsTotal
    )
}
