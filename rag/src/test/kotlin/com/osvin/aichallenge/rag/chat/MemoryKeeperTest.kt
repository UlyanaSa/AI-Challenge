package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Тесты разбора ответа модели на извлечение памяти: терпимость к формату без потери смысла.
 *
 * Извлечение памяти — служебный вызов, и его ответ портится независимо от ответа по существу:
 * модель окружает JSON пояснением, заворачивает в блок кода, добавляет неизвестные поля или
 * присылает обрезанный объект. Разбор обязан вытащить память из любого читаемого ответа и, если
 * разобрать нечего, честно вернуть `null`, а не пустую память: `null` означает «оставь прежнюю»,
 * и именно от этого зависит, не сотрётся ли цель разговора из-за одного сбитого формата.
 *
 * Тестами здесь проверяется граница разбора, а не его внутренности: валидный ответ разбирается
 * целиком, встроенный в текст — тоже, битый и отсутствующий — нет, неизвестное поле не ломает
 * разбор, а термин без слова не попадает в память. Ошибка любого из этих мест выглядела бы как
 * «память иногда теряет ограничение» — дефект, который в отчёте неотличим от ошибки модели.
 */
class MemoryKeeperTest {

    /** Хранитель с подменным клиентом: `parse` в модель не ходит, а `update` — ходит. */
    private fun keeper(content: String = "{}"): MemoryKeeper = MemoryKeeper(MessageLlm(content), "test-model")

    @Test
    fun `валидный JSON разбирается в состояние памяти целиком`() {
        val update = assertNotNull(
            keeper().parse(
                """{"goal":"разобрать первую главу","clarifications":["уточнение"],""" +
                    """"constraints":["правило"],"terms":[{"term":"сын","meaning":"Пётр Степанович"}]}"""
            )
        )

        assertEquals("разобрать первую главу", update.goal)
        assertEquals(listOf("уточнение"), update.clarifications)
        assertEquals(listOf("правило"), update.constraints)
        assertEquals(listOf(TaskTerm("сын", "Пётр Степанович")), update.terms)
    }

    @Test
    fun `JSON внутри текста и в блоке кода разбирается так же`() {
        val inText = assertNotNull(
            keeper().parse("Вот память: {\"goal\":\"цель\"} — это всё."),
            "ответ с пояснением вокруг JSON — всё ещё ответ"
        )
        assertEquals("цель", inText.goal)

        val inFence = assertNotNull(
            keeper().parse("```json\n{\"goal\":\"цель\",\"constraints\":[\"правило\"]}\n```"),
            "блок кода вокруг JSON — оформление, а не другой формат"
        )
        assertEquals("цель", inFence.goal)
        assertEquals(listOf("правило"), inFence.constraints)
    }

    @Test
    fun `битый JSON не разбирается`() {
        assertNull(keeper().parse("{ это не json"), "начатая, но не закрытая скобка — не объект")
        assertNull(keeper().parse("совсем без объекта"))
        assertNull(keeper().parse(""))
        assertNull(keeper().parse(null), "ответа не было — разбирать нечего")
    }

    @Test
    fun `неизвестные поля игнорируются`() {
        val update = assertNotNull(
            keeper().parse("""{"goal":"цель","итог":"лишнее","clarifications":["у"],"mood":"бодрое"}"""),
            "лишнее поле в ответе модели не делает ответ нечитаемым"
        )

        assertEquals("цель", update.goal)
        assertEquals(listOf("у"), update.clarifications)
    }

    @Test
    fun `термин без слова в память не попадает`() {
        val update = assertNotNull(
            keeper().parse(
                """{"terms":[{"term":"   ","meaning":"значение"},{"term":"сын","meaning":"Пётр"}]}"""
            )
        )

        assertEquals(
            listOf(TaskTerm("сын", "Пётр")), update.terms,
            "термин без слова не имеет значения и не должен попадать в память"
        )
    }

    @Test
    fun `битый ответ модели оставляет память прежней`() = runBlocking {
        val memory = TaskMemory(goal = "прежняя цель")

        val update = MemoryKeeper(MessageLlm("{ не json"), "test-model")
            .update(memory, emptyList(), "вопрос")

        assertEquals(memory, update.memory, "неразобранный ответ не стирает уже зафиксированное")
        assertTrue(
            update.note.startsWith("память не обновлена"),
            "отчёт обязан сказать, что память не обновилась, а не молчать: «${update.note}"
        )
    }

    @Test
    fun `валидный ответ дополняет память, а не заменяет её`() = runBlocking {
        val memory = TaskMemory(constraints = listOf("правило"))

        val update = MemoryKeeper(MessageLlm("""{"goal":"цель"}"""), "test-model")
            .update(memory, emptyList(), "вопрос")

        assertEquals("цель", update.memory.goal)
        assertEquals(
            listOf("правило"), update.memory.constraints,
            "ответ модели, не пересказавший старое правило, не должен его терять"
        )
    }

    /** Клиент модели, отвечающий заготовкой: разбор проверяется без сети. */
    private class MessageLlm(private val content: String) : LlmClient {

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse = DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage(role = "assistant", content = content)))
        )
    }
}
