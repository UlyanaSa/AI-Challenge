package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.TokenCounter
import com.osvin.aichallenge.models.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Правило усечения истории в [ContextBudget].
 *
 * Счётчик токенов здесь свой — один токен на символ плюс один на сообщение. Проверяется
 * правило усечения (что именно отброшено и в каком порядке осталось), а не коэффициенты
 * оценки: с [com.osvin.aichallenge.agent.EstimatingTokenCounter] ожидаемые числа зависели бы
 * от его «символов на токен» для латиницы и кириллицы, и правка коэффициента ломала бы
 * проверку усечения, хотя само правило не менялось.
 */
class ContextBudgetTest {

    @Test
    fun `короткий диалог уходит целиком`() {
        val history = listOf(
            StoredMessage(role = "user", content = "a", createdAt = 1),
            StoredMessage(role = "assistant", content = "b", createdAt = 2)
        )

        val plan = plan(history = history, current = "c", system = "s", maxContextTokens = 100, maxGenerationTokens = 10)

        assertEquals(ContextOutcome.FITS, plan.outcome)
        assertEquals(0, plan.dropped)
        assertEquals(90, plan.budgetTokens)
        assertEquals(
            listOf("system" to "s", "user" to "a", "assistant" to "b", "user" to "c"),
            plan.pairs()
        )
        // Ничего не отброшено — счёт совпадает с полным запросом.
        assertEquals(CharTokenCounter.countPrompt(plan.messages), plan.estimatedTokens)
    }

    @Test
    fun `длинный диалог теряет самые старые сообщения, сохраняя порядок остальных`() {
        val history = listOf(
            StoredMessage(role = "user", content = "m0", createdAt = 1),
            StoredMessage(role = "assistant", content = "m1", createdAt = 2),
            StoredMessage(role = "user", content = "m2", createdAt = 3),
            StoredMessage(role = "assistant", content = "m3", createdAt = 4),
            StoredMessage(role = "user", content = "m4", createdAt = 5),
            StoredMessage(role = "assistant", content = "m5", createdAt = 6)
        )

        // Обязательная часть — 4 токена, каждое сообщение истории — 3: в бюджет 7 влезает одно.
        val plan = plan(history = history, current = "c", system = "s", maxContextTokens = 10, maxGenerationTokens = 3)

        assertEquals(ContextOutcome.TRIMMED, plan.outcome)
        assertEquals(5, plan.dropped)
        // Осталось свежее хвостовое сообщение и только оно: усечение идёт с начала, потому что
        // свежая история и есть контекст вопроса.
        assertEquals(
            listOf("system" to "s", "assistant" to "m5", "user" to "c"),
            plan.pairs()
        )
        assertTrue(plan.estimatedTokens <= plan.budgetTokens)
        assertEquals(CharTokenCounter.countPrompt(plan.messages), plan.estimatedTokens)
    }

    @Test
    fun `история вытесняется целиком до нуля, но вопрос и инструкция остаются`() {
        val history = listOf(
            StoredMessage(role = "user", content = "h0", createdAt = 1),
            StoredMessage(role = "assistant", content = "h1", createdAt = 2),
            StoredMessage(role = "user", content = "h2", createdAt = 3)
        )

        // Обязательная часть — 4 токена, любое сообщение истории — 3: в бюджет 6 не влезает ни одно.
        val plan = plan(history = history, current = "c", system = "s", maxContextTokens = 9, maxGenerationTokens = 3)

        assertEquals(ContextOutcome.TRIMMED, plan.outcome)
        assertEquals(3, plan.dropped)
        assertEquals(listOf("system" to "s", "user" to "c"), plan.pairs())
        assertAnchored(plan, system = "s", current = "c")
        assertEquals(CharTokenCounter.countPrompt(plan.messages), plan.estimatedTokens)
    }

    @Test
    fun `системное сообщение внутри истории не отбрасывается`() {
        val history = listOf(
            StoredMessage(role = "user", content = "a", createdAt = 1),
            StoredMessage(role = "system", content = "note", createdAt = 2),
            StoredMessage(role = "user", content = "b", createdAt = 3)
        )

        // В бюджет 12 влезает только новое несистемное сообщение: старое «a» уходит,
        // хотя оно старше, — а системная вставка посреди истории остаётся.
        val plan = plan(history = history, current = "c", system = "s", maxContextTokens = 15, maxGenerationTokens = 3)

        assertEquals(ContextOutcome.TRIMMED, plan.outcome)
        assertEquals(1, plan.dropped)
        assertEquals(
            listOf("system" to "s", "system" to "note", "user" to "b", "user" to "c"),
            plan.pairs()
        )
        assertAnchored(plan, system = "s", current = "c")
    }

    @Test
    fun `инструкция с вопросом больше окна — отказ, а не усечение`() {
        val history = listOf(
            StoredMessage(role = "user", content = "y", createdAt = 1),
            StoredMessage(role = "assistant", content = "z", createdAt = 2)
        )
        val system = "s".repeat(10)
        val current = "c".repeat(10)

        // Обязательная часть — 22 токена, бюджет — 20: усекать нечего, отказ.
        val plan = plan(history = history, current = current, system = system, maxContextTokens = 25, maxGenerationTokens = 5)

        assertEquals(ContextOutcome.OVERFLOW, plan.outcome)
        assertEquals(2, plan.dropped)
        assertEquals(20, plan.budgetTokens)
        // Отдаётся обязательная часть как есть: клиент получает 400 с причиной, а не
        // ответ без вопроса или без инструкции.
        assertEquals(listOf("system" to system, "user" to current), plan.pairs())
        assertAnchored(plan, system = system, current = current)
        assertEquals(22, plan.estimatedTokens)
        assertEquals(CharTokenCounter.countPrompt(plan.messages), plan.estimatedTokens)
    }

    @Test
    fun `без резерва под ответ запрос отвергается`() {
        // Резерв равен окну: под запрос не остаётся ничего, и усекать историю смысла нет.
        val plan = plan(
            history = emptyList(),
            current = "c",
            system = "s",
            maxContextTokens = 10,
            maxGenerationTokens = 10
        )

        assertEquals(ContextOutcome.OVERFLOW, plan.outcome)
        assertEquals(0, plan.budgetTokens)
        assertEquals(listOf("system" to "s", "user" to "c"), plan.pairs())
        assertAnchored(plan, system = "s", current = "c")
    }

    /** Сборка плана с тестовым счётчиком: правило усечения не должно зависеть от оценки токенов. */
    private fun plan(
        history: List<StoredMessage>,
        current: String,
        system: String,
        maxContextTokens: Int,
        maxGenerationTokens: Int
    ): ContextPlan = ContextBudget.plan(
        history = history,
        current = current,
        system = system,
        maxContextTokens = maxContextTokens,
        maxGenerationTokens = maxGenerationTokens,
        counter = CharTokenCounter
    )

    /** Инструкция первым сообщением, вопрос последним — при любом исходе сборки. */
    private fun assertAnchored(plan: ContextPlan, system: String, current: String) {
        assertEquals(ContextBudget.SYSTEM_ROLE to system, plan.pairs().first())
        assertEquals(ContextBudget.USER_ROLE to current, plan.pairs().last())
    }

    private fun ContextPlan.pairs(): List<Pair<String, String>> = messages.map { it.role to it.content }

    /** Счётчик с видимым правилом: токен на символ и токен на обвязку сообщения. */
    private object CharTokenCounter : TokenCounter {
        override fun count(text: String): Int = text.length

        override fun countPrompt(messages: List<ChatMessage>): Int =
            messages.size + messages.sumOf { count(it.content) }
    }
}
