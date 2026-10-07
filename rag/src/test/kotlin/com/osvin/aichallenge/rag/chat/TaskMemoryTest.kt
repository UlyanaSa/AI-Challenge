package com.osvin.aichallenge.rag.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты памяти задачи: слияние и печать блока, от которого зависит, что модель знает о разговоре.
 *
 * Память задачи уходит в каждый запрос целиком и не обрезается окном истории, поэтому ошибка здесь
 * не «портит один ответ», а едет через весь разговор: лишний дубль правила выглядит как две разные
 * договорённости, потерянный пункт — как ограничение, которого человек не ставил, а пустой блок
 * вместо памяти читается моделью как «правил нет». Тесты ниже проверяют ровно те свойства слияния
 * и печати, от которых это зависит: повторы схлопываются по нормализованному тексту, свежее
 * переезжает в конец, лишнее вытесняется с начала, а цель без содержания не стирает прежнюю.
 *
 * Проверяется поведение самой памяти, без сессии и без модели: слияние — чистая функция, и разбирать
 * её ошибку через живой вызов значило бы искать причину через две подмены и сеть.
 */
class TaskMemoryTest {

    @Test
    fun `слияние не плодит повторы по регистру, ё и пунктуации`() {
        val memory = TaskMemory(clarifications = listOf("Отвечай кратко"))

        val merged = memory.merge(
            TaskMemoryUpdate(clarifications = listOf("отвечай кратко.", "ОТВЕЧАЙ КРАТКО"))
        )

        assertEquals(
            1, merged.clarifications.size,
            "одна договорённость не должна становиться тремя из-за регистра и точки"
        )

        val constraints = TaskMemory(constraints = listOf("не больше трёх пунктов"))
            .merge(TaskMemoryUpdate(constraints = listOf("не больше трех пунктов")))
        assertEquals(1, constraints.constraints.size, "«ё» и «е» — один и тот же пункт памяти")
    }

    @Test
    fun `повтор переезжает в конец списка как более свежий`() {
        val memory = TaskMemory(constraints = listOf("первое", "второе", "третье"))

        val merged = memory.merge(TaskMemoryUpdate(constraints = listOf("первое")))

        assertEquals(
            listOf("второе", "третье", "первое"), merged.constraints,
            "повтор должен подняться в конец, а не остаться на старом месте"
        )
    }

    @Test
    fun `лимиты списков соблюдаются, лишнее вытесняется с начала`() {
        val merged = TaskMemory().merge(TaskMemoryUpdate(clarifications = (1..25).map { "уточнение-$it" }))

        assertEquals(TaskMemory.MAX_CLARIFICATIONS, merged.clarifications.size)
        assertEquals("уточнение-25", merged.clarifications.last(), "свежее уточнение остаётся")
        assertEquals("уточнение-6", merged.clarifications.first(), "старое вытесняется с начала")

        val constraints = TaskMemory().merge(TaskMemoryUpdate(constraints = (1..12).map { "правило-$it" }))
        assertEquals(TaskMemory.MAX_CONSTRAINTS, constraints.constraints.size)

        val terms = TaskMemory().merge(
            TaskMemoryUpdate(terms = (1..25).map { TaskTerm("термин-$it", "значение-$it") })
        )
        assertEquals(TaskMemory.MAX_TERMS, terms.terms.size)
    }

    @Test
    fun `длинный пункт обрезается до предела памяти`() {
        val merged = TaskMemory().merge(TaskMemoryUpdate(clarifications = listOf("о".repeat(300))))

        val line = merged.clarifications.single()
        assertTrue(line.length <= TaskMemory.MAX_CHARS, "пункт памяти не растёт бесконечно")
        assertTrue(line.endsWith("…"), "обрезанный пункт помечен многоточием, а не молча укорочен")
    }

    @Test
    fun `пустая цель от модели не стирает прежнюю`() {
        val memory = TaskMemory(goal = "разобрать первую главу")

        assertEquals(
            "разобрать первую главу", memory.merge(TaskMemoryUpdate(goal = null)).goal,
            "отсутствие цели в ответе модели не означает «забудь цель»"
        )
        assertEquals(
            "разобрать первую главу", memory.merge(TaskMemoryUpdate(goal = "   ")).goal,
            "пустая строка цели — то же самое, что её отсутствие"
        )
    }

    @Test
    fun `непустая цель заменяет прежнюю`() {
        val memory = TaskMemory(goal = "разобрать первую главу")

        val merged = memory.merge(TaskMemoryUpdate(goal = "разобрать вторую главу"))

        assertEquals(
            "разобрать вторую главу", merged.goal,
            "человек может переформулировать цель, и память обязана её принять"
        )
    }

    @Test
    fun `render печатает только заполненные разделы, а пустая память даёт пустую строку`() {
        assertEquals("", TaskMemory().render(), "пустая память не даёт заголовка без строк")

        val onlyGoal = TaskMemory(goal = "цель").render()
        assertEquals(
            listOf(TaskMemory.HEADER, "цель: цель"), onlyGoal.lines(),
            "заполнена только цель — печатается только её строка"
        )
        assertFalse(onlyGoal.contains(TaskMemory.CLARIFIED_LABEL))
        assertFalse(onlyGoal.contains(TaskMemory.CONSTRAINTS_LABEL))
        assertFalse(onlyGoal.contains(TaskMemory.TERMS_LABEL))

        val full = TaskMemory(
            goal = "цель",
            clarifications = listOf("уточнение"),
            constraints = listOf("правило"),
            terms = listOf(TaskTerm("сын", "Пётр Степанович"))
        ).render()
        assertTrue(full.startsWith(TaskMemory.HEADER), "у непустой памяти есть заголовок блока")
        listOf(
            TaskMemory.GOAL_LABEL,
            TaskMemory.CLARIFIED_LABEL,
            TaskMemory.CONSTRAINTS_LABEL,
            TaskMemory.TERMS_LABEL
        ).forEach { label -> assertTrue(full.contains(label), "заполненный раздел «$label» печатается") }
    }
}
