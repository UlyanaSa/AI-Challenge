package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.ResponseFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Тесты дня 24: проверка цитат, достаточность контекста и сборка источников.
 *
 * Проверяется то, из-за чего день и делался: подтверждённость ответа. Ошибка здесь не портит
 * формулировку, а делает ответ недоказуемым или, хуже, выдаёт выдуманную ссылку за доказательство,
 * и заметить её глазами в отчёте нельзя — цитаты выглядят правдоподобно. Поэтому проверяются
 * границы: цитата дословная против пересказа, сокращённая против придуманной, номер фрагмента
 * внутри контекста против выдуманного, отказ до модели против отказа после неё.
 *
 * Модель и поиск подставные: тест меряет решения проверок, а не качество embeddings и не поведение
 * чужого API. Живые ответы модели в проверки не годятся — они меняются от прогона к прогону,
 * и тест на них падал бы через раз; всё, что зависит от модели, проверяется в живом прогоне.
 */
class GroundingTest {

    private fun source(
        rank: Int = 1,
        text: String,
        id: String = "structural:book/book.md#7",
        pages: List<Int> = listOf(8),
        section: String = "Глава первая",
        similarity: Double = 0.6
    ): Source = Source(
        rank = rank,
        similarity = similarity,
        text = text,
        id = id,
        source = "book.md",
        title = null,
        pages = pages,
        section = section,
        chunkIndex = 7
    )

    /** Выдача с контекстом из одного чанка: дальше проверяются только цитаты по этому тексту. */
    private fun retrieval(sources: List<Source>, passed: Boolean = true): Retrieval = Retrieval(
        sources = sources,
        queryVector = listOf(0.1f, 0.2f),
        millis = 5,
        trace = Trace(
            original = "вопрос",
            rewritten = null,
            retrievalTopK = 10,
            threshold = 0.4,
            candidates = sources.map { candidate ->
                Candidate(
                    source = candidate,
                    accepted = passed,
                    rerankScore = 0.8,
                    rerankRank = candidate.rank,
                    finalRank = if (passed) candidate.rank else null
                )
            },
            retrievalMillis = 5,
            rewriteMillis = 0,
            rerankMillis = 1
        )
    )

    private val chunk = "Она скончалась в Париже, быв с ним последние три года в разлуке " +
        "и оставив ему пятилетнего сына."

    private fun claim(quote: String, fragment: Int = 1): Claim =
        Claim(text = "утверждение", fragment = fragment, quote = quote)

    private class FakeLlm(private val answers: List<String>) : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            val answer = answers.getOrElse(requests.size - 1) { answers.last() }
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(
                        ChatMessage(role = "assistant", content = answer),
                        finishReason = "stop"
                    )
                ),
                usage = null
            )
        }
    }

    @Test
    fun `цитата подтверждается дословным фрагментом, а пересказ и обрывок — нет`() {
        val sources = listOf(source(text = chunk))

        val exact = Citations.validate(claim("быв с ним последние три года в разлуке"), sources)
        val paraphrase = Citations.validate(claim("жена умерла за границей"), sources)
        val tooShort = Citations.validate(claim("сына"), sources)

        assertTrue(exact.found, "дословный фрагмент чанка подтверждает цитату")
        assertNull(exact.reason)
        assertFalse(paraphrase.found, "пересказ цитатой не считается: его нет в тексте чанка")
        assertNotNull(paraphrase.reason)
        assertFalse(tooShort.found, "два слова совпадут почти с любым чанком — это не доказательство")
        assertNotNull(tooShort.reason)
    }

    @Test
    fun `сокращённая многоточием цитата проверяется по частям`() {
        val sources = listOf(source(text = chunk))

        val split = Citations.validate(
            claim("Она скончалась в Париже … оставив ему пятилетнего сына"),
            sources
        )
        val withInventedPart = Citations.validate(
            claim("Она скончалась в Париже … и умерла в Москве"),
            sources
        )

        assertTrue(split.found, "обе части сокращённой цитаты есть в чанке")
        assertFalse(withInventedPart.found, "часть, которой в чанке нет, ломает подтверждение")
        assertTrue(
            assertNotNull(withInventedPart.reason).contains("москве"),
            "в причине видно, какая часть цитаты не нашлась (в нормализованном виде — строчными)"
        )
    }

    @Test
    fun `ссылка на фрагмент вне контекста отсеивается с причиной, а не молча`() {
        val sources = listOf(source(text = chunk))
        val raw = """{"answer":"ответ","insufficient":false,"claims":[
            {"claim":"утверждение","fragment":9,"quote":"$chunk"}]}"""

        val parsed = Citations.parse(raw, sources)
        val check = Citations.validate(parsed.claims.single(), sources)

        assertEquals(1, parsed.claims.size, "утверждение остаётся разобранным: иначе пропадёт улика")
        assertNull(check.chunkId, "фрагмента с таким номером модель не получала")
        assertNotNull(check.reason)
        assertFalse(check.found)
    }

    @Test
    fun `разбор ответа — JSON в обёртке, отказ по флагу, отказ словами и неразобранный ответ`() {
        val sources = listOf(source(text = chunk))

        val fenced = Citations.parse(
            "```json\n{\"answer\":\"ответ\",\"insufficient\":false,\"claims\":[]}\n```",
            sources
        )
        val flagged = Citations.parse("{\"answer\":\"ответ\",\"insufficient\":true}", sources)
        val wording = Citations.parse("В найденных фрагментах нет ответа на этот вопрос.", sources)
        val garbage = Citations.parse("Ответ: трость с серебряным набалдашником.", sources)

        assertEquals("ответ", fenced.answer)
        assertFalse(fenced.insufficient)
        assertTrue(flagged.insufficient, "флаг модели об отказе уважается")
        assertNotNull(flagged.reason)
        assertTrue(wording.insufficient, "отказ словами распознаётся: модель отвечает не только JSON")
        assertNotNull(wording.reason)
        assertTrue(garbage.insufficient, "непонятный ответ — отказ, а не ответ без цитат")
        assertTrue(
            assertNotNull(garbage.reason).contains("не разобран"),
            "видно, что дело в формате, а не в отсутствии сведений"
        )
    }

    @Test
    fun `достаточность контекста различает четыре состояния`() {
        val strong = source(text = chunk, similarity = 0.9)
        val weak = source(text = chunk, similarity = 0.2)

        val empty = Sufficiency.check(
            Retrieval(sources = emptyList(), queryVector = listOf(0.1f), millis = 3, trace = null),
            threshold = 0.4
        )
        val weakSource = source(text = chunk, similarity = 0.2)
        val filtered = Sufficiency.check(
            Retrieval(
                sources = emptyList(),
                queryVector = listOf(0.1f),
                millis = 3,
                trace = Trace(
                    original = "вопрос",
                    rewritten = null,
                    retrievalTopK = 10,
                    threshold = 0.4,
                    candidates = listOf(Candidate(weakSource, accepted = false)),
                    retrievalMillis = 5,
                    rewriteMillis = 0,
                    rerankMillis = 0
                )
            ),
            threshold = 0.4
        )
        val below = Sufficiency.check(
            Retrieval(
                sources = listOf(weak),
                queryVector = listOf(0.1f),
                millis = 3,
                trace = retrieval(listOf(weak)).trace
            ),
            threshold = 0.4
        )
        val enough = Sufficiency.check(retrieval(listOf(strong)), threshold = 0.4)
        val bare = Sufficiency.check(
            Retrieval(sources = listOf(strong), queryVector = listOf(0.1f), millis = 3, trace = null),
            threshold = 0.4
        )

        assertEquals(Confidence.NO_CHUNKS, empty.decision, "поиск не нашёл ничего")
        assertEquals(Confidence.ALL_FILTERED, filtered.decision, "всё отсеяно порогом")
        assertEquals(Confidence.BELOW_THRESHOLD, below.decision, "фрагмент есть, но он слабее порога")
        assertEquals(Confidence.SUFFICIENT, enough.decision)
        assertEquals(Confidence.SUFFICIENT, bare.decision, "без трейса контекстом считается выдача")
        assertTrue(enough.sufficient)
        assertFalse(below.sufficient)
    }

    @Test
    fun `источники собираются из метаданных чанков, а не из текста модели`() = runBlocking {
        val sources = listOf(source(text = chunk))
        // Модель придумывает главу, страницы и идентификатор чанка: в её ответе этих полей нет,
        // и попасть в отчёт они не должны ни при каком разборе.
        val llm = FakeLlm(
            listOf(
                """{"answer":"ответ","insufficient":false,"claims":[
                    {"claim":"жена умерла","fragment":1,"quote":"быв с ним последние три года в разлуке",
                     "section":"Выдуманная глава","pages":[99],"chunk_id":"fake"}]}"""
            )
        )
        val agent = GroundedAgent(llm, model = "test-model", threshold = 0.4)

        val answer = agent.answer("Что осталось у Степана Трофимовича?", retrieval(sources))

        val ref = assertNotNull(answer.sources.singleOrNull())
        assertEquals("Глава первая", ref.section, "глава — из чанка, а не из ответа модели")
        assertEquals(listOf(8), ref.pages, "страницы — из чанка")
        assertEquals("structural:book/book.md#7", ref.chunkId)
        assertEquals(1, ref.fragment, "источник связан с номером фрагмента, на который сослалось утверждение")
        assertEquals("book.md", ref.source)
        assertEquals(ResponseFormat.JSON_OBJECT, llm.requests.single().responseFormat)
    }

    @Test
    fun `отказ не несёт источников и цитат — ни до модели, ни после неё`() = runBlocking {
        val sources = listOf(source(text = chunk, similarity = 0.2))
        val weakLlm = FakeLlm(listOf("ответ"))
        val weakAgent = GroundedAgent(weakLlm, model = "test-model", threshold = 0.4)

        val beforeModel = weakAgent.answer("вопрос", retrieval(sources))

        assertEquals(Refusal.NO_CONTEXT, beforeModel.refusal)
        assertTrue(beforeModel.sources.isEmpty())
        assertTrue(beforeModel.claims.isEmpty())
        assertTrue(
            weakLlm.requests.isEmpty(),
            "при слабом контексте модель не спрашивается: иначе она ответит по памяти"
        )

        val refusingLlm = FakeLlm(
            listOf("""{"answer":"","insufficient":true,"claims":[
                {"claim":"утверждение","fragment":1,"quote":"$chunk"}]}""")
        )
        val refusingAgent = GroundedAgent(refusingLlm, model = "test-model", threshold = 0.4)

        val afterModel = refusingAgent.answer("вопрос", retrieval(listOf(source(text = chunk))))

        assertEquals(Refusal.NO_EVIDENCE, afterModel.refusal)
        assertNull(afterModel.answer)
        assertTrue(afterModel.sources.isEmpty(), "источники не добираются ради отчёта")
        assertTrue(afterModel.claims.isEmpty())
        assertTrue(afterModel.checks.isNotEmpty(), "но проверка цитат сохраняется: она объясняет отказ")
    }

    @Test
    fun `отказ при правильном фрагменте в контексте считается ошибкой, а не правильным поведением`() {
        val control = ControlQuestion(
            id = "q-тест",
            question = "Где скончалась жена?",
            expected = "В Париже.",
            facts = listOf(Fact("в Париже", listOf("париж"))),
            pages = listOf(8),
            section = "Глава первая",
            note = "тест"
        )
        val correct = listOf(source(text = chunk))

        val found = Grounding.check(control, run(control, "в Париже", correct), refusal(control, correct))
        val lost = Grounding.check(
            control,
            run(control, "в Париже", correct),
            refusal(control, listOf(source(text = "другой текст без ответа", id = "structural:book/book.md#2")))
        )

        assertTrue(found.wrongAbstention, "правильный фрагмент был в контексте — отказ потерял ответ")
        assertFalse(found.validAbstention)
        assertTrue(lost.validAbstention, "ответа в контексте не было — отказ честен")
        assertFalse(lost.wrongAbstention)
    }

    /** Ответ предыдущего режима: текст и оценка по мерке дня 22. */
    private fun run(control: ControlQuestion, text: String, sources: List<Source>): Run {
        val retrieval = Retrieval(sources = sources, queryVector = listOf(0.1f), millis = 5)
        return Run(
            control = control,
            answer = Answer(
                question = control.question,
                mode = Mode.WITH_RAG,
                text = text,
                retrieval = retrieval,
                messages = emptyList(),
                promptTokens = null,
                completionTokens = null,
                finishReason = "stop",
                elapsedMillis = 5
            ),
            check = Check.evaluate(control, text, sources)
        )
    }

    /** Отказ grounded-режима на этой же выдаче: ответа в контексте не нашлось. */
    private fun refusal(control: ControlQuestion, sources: List<Source>): GroundedAnswer = GroundedAnswer(
        question = control.question,
        retrieval = retrieval(sources),
        confidence = ConfidenceCheck(Confidence.SUFFICIENT, sources.size, 0.9, 0.4),
        answer = null,
        sources = emptyList(),
        claims = emptyList(),
        checks = emptyList(),
        refusal = Refusal.NO_EVIDENCE,
        messages = emptyList(),
        raw = null,
        promptTokens = null,
        completionTokens = null,
        finishReason = null,
        millis = 0,
        note = null
    )
}
