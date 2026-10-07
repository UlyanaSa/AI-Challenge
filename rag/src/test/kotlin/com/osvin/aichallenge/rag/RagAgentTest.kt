package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Тесты агента: путь «вопрос → поиск → запрос → ответ» целиком, но без сети.
 *
 * Индекс здесь настоящий — маленький корпус, та же нарезка, тот же файл индекса и тот же поиск,
 * что в прогоне; подставным остаётся только клиент модели. Так проверяется то, что нельзя увидеть
 * в юнит-тесте промпта: в режиме с RAG в запрос уходит найденный текст базы, в режиме без RAG
 * поиска не происходит вовсе, а параметры обращения к модели в обоих режимах совпадают. Ошибка
 * любого из этих трёх мест не сломала бы прогон — она сделала бы неверным его вывод.
 */
class RagAgentTest {

    /** Метка в тексте базы: по ней видно, попал фрагмент в запрос к модели или нет. */
    private val marker = "MARKER-ALPHA"

    private val question = "Сколько лет Варвара Петровна нянчилась со Ставрогиным?"

    /** Корпус из одной страницы: текст, разметка страницы и короткий файл для нарезки. */
    private fun base(dir: Path): DayBase {
        val raw = "<!-- page:10 -->\n\n$marker: нянчилась с ним двадцать два года как нянька."
        val paged = PageMarkers.stripAndIndex(raw)
        val document = Document(
            id = "day",
            title = null,
            files = listOf(DocumentFile("day.md", paged.content, paged.pages))
        )
        val text = dir.resolve("day.md")
        Files.writeString(text, raw)
        return DayBase(document, text)
    }

    /** Индекс базы: хеширование вместо Ollama, фиксированное окно вместо структурного — тест не ходит в сеть. */
    private suspend fun retriever(dir: Path, topK: Int = 3): Retriever {
        val embedding = Embedding(
            provider = HashingEmbeddingProvider(),
            kind = "hashing",
            model = null,
            note = "тест",
            features = null
        )
        val index = RagIndex(dir, embedding, ChunkingStrategyType.FIXED_SIZE)
        index.build(base(dir))
        return index.retriever(topK)
    }

    @Test
    fun `с RAG в запрос уходит найденный текст базы, без RAG поиска нет`() = runBlocking {
        val dir = Files.createTempDirectory("rag-agent")
        val llm = FakeLlm()
        val agent = RagAgent(llm, retriever(dir), model = "test-model")

        val with = agent.ask(question, Mode.WITH_RAG)
        val without = agent.ask(question, Mode.WITHOUT_RAG)

        val withUser = llm.requests[0].messages.last().content
        val withoutUser = llm.requests[1].messages.last().content
        assertTrue(withUser.contains(marker), "режим с RAG подставляет найденный фрагмент")
        assertTrue(withUser.contains("стр. 10"), "у фрагмента есть место в книге")
        assertFalse(withoutUser.contains(marker), "режим без RAG не подставляет базу")
        assertEquals(1, with.sources.size, "найденный фрагмент возвращается вместе с ответом")
        assertEquals(listOf(10), with.sources.first().pages)
        assertEquals(withUser, with.messages.last().content, "в ответе остался тот же запрос, что ушёл в модель")
        assertEquals(Prompt.SYSTEM_WITHOUT_CONTEXT, without.messages.first().content)
        assertTrue(without.sources.isEmpty(), "в режиме без RAG источников нет")
    }

    @Test
    fun `параметры обращения к модели и расход токенов одинаковы в обоих режимах`() = runBlocking {
        val dir = Files.createTempDirectory("rag-agent")
        val llm = FakeLlm()
        val agent = RagAgent(llm, retriever(dir), model = "test-model")

        val with = agent.ask(question, Mode.WITH_RAG)
        val without = agent.ask(question, Mode.WITHOUT_RAG)

        val (first, second) = llm.requests
        assertEquals(first.model, second.model)
        assertEquals(first.temperature, second.temperature)
        assertEquals(first.maxTokens, second.maxTokens)
        assertEquals(FakeLlm.PROMPT_TOKENS, with.promptTokens)
        assertEquals(FakeLlm.COMPLETION_TOKENS, without.completionTokens)
    }

    @Test
    fun `режим без RAG не обращается к поиску`() = runBlocking {
        val forbidden = object : SourceFinder {
            override suspend fun find(question: String): Retrieval =
                error("режим без RAG не должен искать")
        }
        val agent = RagAgent(FakeLlm(), forbidden, model = "test-model")

        val answer = agent.ask(question, Mode.WITHOUT_RAG)

        assertEquals(FakeLlm.ANSWER, answer.text)
        assertEquals(0L, answer.retrieval.millis)
        assertTrue(answer.retrieval.queryVector.isEmpty(), "вектора вопроса без поиска тоже нет")
    }

    /** Клиент модели, который записывает запросы и отвечает заготовкой: сеть в тестах не нужна. */
    private class FakeLlm : LlmClient {

        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(ChatMessage(role = "assistant", content = ANSWER), finishReason = "stop")
                ),
                usage = DeepSeekResponse.Usage(
                    promptTokens = PROMPT_TOKENS,
                    completionTokens = COMPLETION_TOKENS,
                    totalTokens = PROMPT_TOKENS + COMPLETION_TOKENS
                )
            )
        }

        companion object {
            const val ANSWER = "Она нянчилась с ним двадцать два года."
            const val PROMPT_TOKENS = 111
            const val COMPLETION_TOKENS = 22
        }
    }
}
