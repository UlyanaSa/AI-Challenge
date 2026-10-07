package com.osvin.aichallenge.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты сборки запроса: режимы обязаны отличаться ровно блоком фрагментов.
 *
 * Проверяется не «промпт непустой», а то, из-за чего сравнение вообще имеет смысл: вопрос в обоих
 * режимах один и тот же, задание одно и то же, а добавляется в режиме с RAG только найденный текст
 * с подписью источника. Разойдись эти части — сравнение мерило бы разницу формулировок, а не пользу
 * поиска, и никакие числа в отчёте этого не показывали бы.
 */
class PromptTest {

    private val question = "Сколько лет Варвара Петровна нянчилась со Степаном Трофимовичем?"

    /** Фрагмент с текстом-меткой: по метке видно, попал фрагмент в запрос или нет. */
    private fun source(rank: Int) = Source(
        rank = rank,
        similarity = 0.62,
        text = "MARKER-$rank нянчилась с ним двадцать два года",
        id = "chunk-$rank",
        source = "book.md",
        title = "Бесы",
        pages = listOf(10),
        section = "Глава первая",
        chunkIndex = 7
    )

    @Test
    fun `с RAG в запрос уходит текст фрагмента с номером и местом в книге`() {
        val messages = Prompt.withContext(question, listOf(source(1), source(2)))

        val user = messages.last().content
        assertTrue(user.contains("MARKER-1"), "текст первого фрагмента должен быть в запросе")
        assertTrue(user.contains("MARKER-2"), "текст второго фрагмента должен быть в запросе")
        assertTrue(
            user.contains("[1] book.md · стр. 10 · Глава первая · чанк #7"),
            "у фрагмента есть подпись с файлом и местом в книге"
        )
        assertTrue(user.contains(question), "вопрос остаётся в запросе")
        assertEquals(Prompt.SYSTEM_WITH_CONTEXT, messages.first().content)
    }

    @Test
    fun `без RAG в запросе нет ни текста базы, ни подписи источника`() {
        val plain = Prompt.withoutContext(question)
        val user = plain.last().content

        assertFalse(user.contains("MARKER"), "текст базы в режиме без RAG не подставляется")
        assertFalse(user.contains("стр."), "подписи источников в режиме без RAG нет")
        assertEquals(question, user.removePrefix(Prompt.QUESTION_PREFIX), "в запросе только вопрос")
    }

    @Test
    fun `задание и вопрос одинаковы в обоих режимах`() {
        val with = Prompt.withContext(question, listOf(source(1)))
        val without = Prompt.withoutContext(question)

        assertTrue(with.first().content.startsWith(Prompt.TASK), "задание общее у обоих режимов")
        assertEquals(Prompt.TASK, without.first().content, "без RAG системное сообщение — это задание без правил")
        assertTrue(
            with.first().content.length > without.first().content.length,
            "в режиме с RAG к заданию добавляются правила работы с фрагментами"
        )
        val tail = Prompt.QUESTION_PREFIX + question
        assertTrue(with.last().content.endsWith(tail), "вопрос идёт последним и в том же виде, что без RAG")
        assertEquals(tail, without.last().content)
    }
}
