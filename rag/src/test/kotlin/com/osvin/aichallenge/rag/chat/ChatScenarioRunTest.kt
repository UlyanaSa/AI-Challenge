package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.rag.Confidence
import com.osvin.aichallenge.rag.ConfidenceCheck
import com.osvin.aichallenge.rag.Fact
import com.osvin.aichallenge.rag.Refusal
import com.osvin.aichallenge.rag.Retrieval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты проверок сценария: сверка фактов, цель в ответе и переформулировки цели.
 *
 * Проверки сценария решают приговор дня, поэтому ошибка в них не «портит число», а меняет вердикт:
 * пропущенный факт записывает неполный ответ в недостачу, а лишняя переформулировка объявляет
 * провалом день, где цель как раз и держалась. Живой прогон показал оба случая, и тесты ниже
 * закрепляют их как поведение: факт сверяется по нормализованному ответу (регистр и «ё» не важны),
 * цель считается названной только при двух словах сценария, а первое появление цели не считается
 * её сменой. Отдельно защищается отказ: `refusal = true` выполняется только отказом с названной
 * причиной, а не пустым отказом.
 *
 * Ходы и сценарии собираются здесь руками ([turn], [probeScene]) — проверки чистые функции над
 * `ChatTurn`, и разбирать их ошибку через сессию с моделью значило бы искать причину через две
 * подмены и сеть.
 */
class ChatScenarioRunTest {

    /**
     * Факт, названный с большой буквы, обязан засчитаться.
     *
     * Это и был дефект живого прогона: сверка шла по сырому тексту ответа, и «Петра» в начале
     * фразы не совпадало с признаком «петр» в нижнем регистре. Проверка обязана нормализовать
     * ответ так же, как день 22 (`Check.normalize`), иначе она мерила регистр, а не сведения.
     */
    @Test
    fun `факт, названный с большой буквы, засчитывается`() {
        val step = ChatScenarioStep(
            text = "Под «сыном» я имею в виду Петра Степановича — так его и называй.",
            facts = listOf(Fact("термин назван по значению", listOf("петр")))
        )
        val turn = turn(answer = "Под «сыном» имеем в виду Петра Степановича.")

        val check = ChatScenarioRunner.check(1, step, turn, probeScene(goalKeywords = listOf("степан", "сына")))

        assertEquals(listOf(true), check.facts, "факт с прописной буквой обязан засчитаться")
        assertEquals(1, check.factsMatched)
        assertTrue(check.passed, "нарушений поведения быть не должно")
    }

    /**
     * Цель считается названной только при двух словах сценария.
     *
     * Ответ, назвавший одно слово цели, не возвращается к разговору: он может быть про ту же тему
     * случайно. Поэтому `goalRestated` требует двух слов цели (MIN_GOAL_WORDS), и тест держит обе стороны
     * границы — одно слово не проходит, два проходят.
     */
    @Test
    fun `goalRestated требует двух слов цели и не срабатывает на одном`() {
        val scenario = probeScene(goalKeywords = listOf("степан", "главу"))
        val oneWord = turn(answer = "Степан Трофимович был наставником молодёжи.")
        val twoWords = turn(answer = "Мы разбираем первую главу про Степана.")

        assertFalse(
            ChatScenarioRunner.goalRestated(oneWord, scenario),
            "одного слова цели недостаточно"
        )
        assertTrue(
            ChatScenarioRunner.goalRestated(twoWords, scenario),
            "два слова цели обязаны засчитаться, включая прописную букву"
        )
    }

    /**
     * Шаг с ожиданием цели отмечает ответ одним словом как нарушение, а двумя — как выполненный.
     *
     * Проверка идёт тем же путём, что в прогоне: через [ChatScenarioRunner.check], чтобы тест
     * держал не только чистую функцию, но и то, что при невыполнении в отчёт попадает причина.
     */
    @Test
    fun `шаг с целью не проходит на одном слове и проходит на двух`() {
        val scenario = probeScene(goalKeywords = listOf("степан", "главу"))
        val step = ChatScenarioStep(text = "Напомни, что мы разбираем.", goal = true)

        val oneWord = ChatScenarioRunner.check(1, step, turn(answer = "Мы говорим о Степане."), scenario)
        val twoWords = ChatScenarioRunner.check(1, step, turn(answer = "Мы разбираем главу о Степане."), scenario)

        assertEquals(false, oneWord.goalOk)
        assertFalse(oneWord.passed)
        assertTrue(oneWord.failures.any { "цель" in it }, "причина обязана назвать цель")
        assertEquals(true, twoWords.goalOk)
        assertTrue(twoWords.passed)
    }

    /**
     * Первое появление цели — не переформулировка, и день с одной неизменной целью принят.
     *
     * Это второй дефект живого прогона: ход, в котором цель появилась, попадал в [ChatSummary.goalChanges],
     * и `goalKept` становился `false` у сценария, где цель как раз и держится. Переформулировкой
     * считается только смена непустой цели на другую непустую.
     */
    @Test
    fun `goalChanges пуст, когда цель появилась и больше не менялась`() {
        val goal = "разобрать первую главу"
        val turns = listOf(
            turn(index = 1, memoryBefore = TaskMemory(), memory = TaskMemory(goal = goal)),
            turn(index = 2, memoryBefore = TaskMemory(goal = goal), memory = TaskMemory(goal = goal))
        )

        val summary = ChatReport.summarize(turns)

        assertTrue(summary.goalChanges.isEmpty(), "первое появление цели переформулировкой не считается")
        assertEquals(goal, summary.goal)
        assertEquals(1, summary.goalFixedAt)
        assertTrue(summary.goalKept, "цель зафиксирована и не менялась — день держит цель")
    }

    /**
     * Смена непустой цели на другую непустую — переформулировка.
     *
     * Тест держит и второе появление цели, и её настоящую смену в одном разговоре: первое в список
     * попасть не должно, второе — обязано.
     */
    @Test
    fun `goalChanges фиксирует смену непустой цели на другую непустую`() {
        val turns = listOf(
            turn(index = 1, memoryBefore = TaskMemory(), memory = TaskMemory(goal = "первая глава")),
            turn(
                index = 2,
                memoryBefore = TaskMemory(goal = "первая глава"),
                memory = TaskMemory(goal = "вторая глава")
            )
        )

        val summary = ChatReport.summarize(turns)

        assertEquals(1, summary.goalChanges.size, "смена цели обязана попасть в переформулировки")
        assertTrue(
            summary.goalChanges.single().startsWith("ход 2:"),
            "переформулировка подписывается ходом, на котором случилась"
        )
        assertFalse(summary.goalKept, "цель менялась — это уже не «цель держится»")
    }

    /**
     * Отказ без причины не проходит ожидание `refusal = true`.
     *
     * Требование дня — отказ называет причину; «источников нет» без причины неотличимо от забытого
     * блока. Поэтому шаг с ожиданием отказа выполнен только при непустой причине: отказ с пустой
     * причиной — нарушение, а отказ с названной причиной — выполнение.
     */
    @Test
    fun `отказ без причины не считается выполненным ожиданием refusal`() {
        val step = ChatScenarioStep(text = "Чем закончилась история?", refusal = true)
        val scenario = probeScene(goalKeywords = listOf("степан", "главу"))

        val withoutReason = turn(
            answer = null,
            refusal = Refusal.NO_EVIDENCE,
            refusalReason = "   "
        )
        val withReason = turn(
            answer = null,
            refusal = Refusal.NO_EVIDENCE,
            refusalReason = "в срез первой главы этот эпизод не входит"
        )

        val failed = ChatScenarioRunner.check(1, step, withoutReason, scenario)
        val passed = ChatScenarioRunner.check(1, step, withReason, scenario)

        assertEquals(false, failed.refusalOk)
        assertFalse(failed.passed)
        assertTrue(failed.failures.any { "причина" in it }, "причина обязана требовать причину отказа")
        assertEquals(true, passed.refusalOk)
        assertTrue(passed.passed)
    }

    // ——— заготовки ———

    /** Минимальный ход: проверкам нужны индекс, ответ, отказ и память до и после. */
    private fun turn(
        index: Int = 1,
        answer: String? = null,
        refusal: Refusal? = null,
        refusalReason: String? = null,
        memoryBefore: TaskMemory = TaskMemory(),
        memory: TaskMemory = memoryBefore,
        memoryNote: String? = "память без изменений"
    ) = ChatTurn(
        index = index,
        question = "вопрос",
        query = "вопрос",
        queryNote = null,
        found = Retrieval(sources = emptyList(), queryVector = emptyList(), millis = 0),
        confidence = ConfidenceCheck(
            decision = Confidence.SUFFICIENT,
            considered = 0,
            bestSimilarity = null,
            threshold = 0.0
        ),
        // Отказ ответа не несёт: вид записи `null` у отказа, как и в живом ходе.
        kind = if (refusal == null) ChatAnswerKind.DIALOGUE else null,
        answer = answer,
        sources = emptyList(),
        claims = emptyList(),
        checks = emptyList(),
        refusal = refusal,
        refusalReason = refusalReason,
        error = null,
        memoryBefore = memoryBefore,
        memory = memory,
        memoryNote = memoryNote,
        answerMessages = emptyList(),
        memoryMessages = emptyList(),
        rewriteMessages = emptyList(),
        raw = null,
        memoryRaw = null,
        promptTokens = null,
        completionTokens = null,
        millis = 0,
        memoryMillis = 0,
        rewriteMillis = 0
    )

    /** Сценарий с заданными словами цели: остальные ожидания проверкам фактов и цели не нужны. */
    private fun probeScene(goalKeywords: List<String>) = ChatScenario(
        name = "scene-test",
        title = "Проверочный сценарий",
        goal = "разобрать первую главу про Степана Трофимовича",
        goalKeywords = goalKeywords,
        expectedConstraints = 0,
        expectedTerms = 0,
        steps = listOf(ChatScenarioStep(text = "вопрос"))
    )
}
