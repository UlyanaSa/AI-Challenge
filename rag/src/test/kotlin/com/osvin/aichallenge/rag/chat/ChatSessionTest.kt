package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.rag.Confidence
import com.osvin.aichallenge.rag.Refusal
import com.osvin.aichallenge.rag.Retrieval
import com.osvin.aichallenge.rag.Source
import com.osvin.aichallenge.rag.SourceFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Текст найденного чанка с меткой: по метке видно, попал фрагмент в промпт ответа или нет. */
private const val CHUNK_TEXT =
    "Степан Трофимович Верховенский носил трость с серебряным набалдашником и волосы до плеч."

/** Ответ модели о самом разговоре: вид `dialogue` без утверждений и цитат. */
private const val DIALOGUE_ANSWER =
    """{"kind":"dialogue","answer":"Мы разбираем первую главу.","insufficient":false,"claims":[]}"""

/**
 * Тесты хода чата: сборка источников, проверка цитат, окно истории и память задачи.
 *
 * Ход разговора — это пять связанных решений (память, запрос, поиск, достаточность, ответ), и ошибка
 * в любом из них не видна в самом ответе: выдуманный источник выглядит как настоящий, вопрос не по
 * тому запросу — как уверенный ответ не по теме, а история вне окна — как «модель забыла разговор».
 * Поэтому здесь проверяется не «чат отвечает», а то, откуда взялись данные ответа и что ушло в модель:
 * источники приходят из метаданных чанка, а не из слов модели; цитата, которой нет в чанке, не
 * засчитывается; ответ о разговоре опирается на память, а не на базу; слабый контекст не подставляет
 * фрагменты; в поиск уходит раскрытый запрос, а в промпт ответа — исходный вопрос и только последние
 * реплики. Подменными остаются обе внешние зависимости — источник фрагментов и клиент модели, — так
 * что ни сеть, ни индекс для этих тестов не нужны, а поведение проверяется по `ChatTurn`, который
 * несёт и промпты, и найденное.
 *
 * Заготовки ответов модели намеренно «шумные»: в тексте ответа модель называет чужие страницы и
 * источники. Это и есть проверка дня — система обязана собрать источник из метаданных и отбросить
 * то, что модель придумала сама.
 */
class ChatSessionTest {

    private fun source(
        rank: Int = 1,
        similarity: Double = 0.71,
        text: String = CHUNK_TEXT,
        id: String = "chunk-$rank",
        pages: List<Int> = listOf(11),
        section: String? = "Глава первая",
        chunkIndex: Int = 7
    ): Source = Source(
        rank = rank,
        similarity = similarity,
        text = text,
        id = id,
        source = "book.md",
        title = null,
        pages = pages,
        section = section,
        chunkIndex = chunkIndex
    )

    /** Сессия чата на подменных поиске и модели: конвейер тот же, что в прогоне, но без индекса. */
    private fun session(
        llm: ScriptedLlm,
        finder: SourceFinder,
        settings: ChatSettings = ChatSettings()
    ): ChatSession = ChatSession(llm, "test-model", settings.pipeline(finder, llm, "test-model"), settings)

    @Test
    fun `ответ по базе берёт источники из метаданных чанка, а не из слов модели`() = runBlocking {
        val llm = ScriptedLlm(
            answer = """{"kind":"base","answer":"Он носил трость (стр. 999, Википедия).",""" +
                """"insufficient":false,"claims":[{"claim":"У него была трость с серебряным """ +
                """набалдашником.","fragment":1,"quote":"трость с серебряным набалдашником"}]}"""
        )

        val turn = session(llm, FixedFinder(listOf(source()))).ask("Как выглядел Степан Трофимович?")

        assertEquals(ChatAnswerKind.BASE, turn.kind)
        assertTrue(turn.answered)
        assertTrue(turn.hasSource)
        val ref = turn.sources.single()
        assertEquals("book.md", ref.source, "файл источника — из метаданных чанка, а не из ответа модели")
        assertEquals(listOf(11), ref.pages, "страницы — из чанка, а не названные моделью")
        assertEquals("chunk-1", ref.chunkId)
        assertEquals(7, ref.chunkIndex)
        assertEquals("Глава первая", ref.section)
        assertFalse(ref.pages.contains(999), "названная моделью страница 999 в источник не попала")
        assertFalse(ref.source.contains("Википеди"), "названный моделью источник в источник не попал")
    }

    @Test
    fun `неподтверждённая цитата отбрасывается, а без подтверждённых — отказ с причиной`() = runBlocking {
        val llm = ScriptedLlm(
            answer = """{"kind":"base","answer":"Он бежал за границу.","insufficient":false,""" +
                """"claims":[{"claim":"Он бежал за границу.","fragment":1,""" +
                """"quote":"он бежал за границу и скрылся в Швейцарии"}]}"""
        )

        val turn = session(llm, FixedFinder(listOf(source()))).ask("Куда он уехал?")

        assertNull(turn.kind, "у отказа нет вида ответа: отказ — это отсутствие ответа")
        assertFalse(turn.answered)
        assertTrue(turn.sources.isEmpty(), "отказ не добирает источники ради требования «источники всегда»")
        assertEquals(Refusal.NO_EVIDENCE, turn.refusal, "цитата не подтвердилась в тексте чанка")
        assertNotNull(turn.refusalReason, "отказ обязан назвать причину")
        assertEquals(1, turn.checks.size)
        assertFalse(turn.checks.single().found)
    }

    @Test
    fun `подтверждённая цитата проходит, а неподтверждённая не тянет за собой источник`() = runBlocking {
        val llm = ScriptedLlm(
            answer = """{"kind":"base","answer":"У него была трость и седина.","insufficient":false,""" +
                """"claims":[{"claim":"У него была трость с серебряным набалдашником.","fragment":1,""" +
                """"quote":"трость с серебряным набалдашником"},""" +
                """{"claim":"У него были волосы до колен.","fragment":1,"quote":"волосы до колен"}]}"""
        )

        val turn = session(llm, FixedFinder(listOf(source()))).ask("Как он выглядел?")

        assertEquals(ChatAnswerKind.BASE, turn.kind)
        assertEquals(2, turn.checks.size, "проверяются все цитаты ответа, включая отброшенные")
        assertEquals(1, turn.claims.size, "в ответ попадает только подтверждённое утверждение")
        assertEquals(1, turn.sources.size, "источник называет подтверждённое утверждение, а не выдумка")
        assertTrue(turn.checks.any { !it.found }, "неподтверждённая цитата видна в проверках, а не исчезает")
    }

    @Test
    fun `ответ о разговоре идёт по памяти задачи без источников базы, но источник у него есть`() = runBlocking {
        val turn = session(ScriptedLlm(answer = DIALOGUE_ANSWER), FixedFinder(listOf(source())))
            .ask("Что мы разбираем?")

        assertEquals(ChatAnswerKind.DIALOGUE, turn.kind)
        assertTrue(turn.fromMemory)
        assertTrue(turn.answered)
        assertTrue(turn.sources.isEmpty(), "ответ о разговоре не подставляет фрагменты базы")
        assertTrue(turn.claims.isEmpty(), "у ответа о разговоре нет цитат из книги")
        assertTrue(turn.hasSource, "источником ответа о разговоре служит сама память задачи")
    }

    @Test
    fun `при недостаточном контексте фрагменты в промпт ответа не попадают, а отказ называет причину`() =
        runBlocking {
            val weak = source(similarity = 0.10, text = "MARKER-CHUNK: к вопросу не относится.")
            val llm = ScriptedLlm(
                answer = """{"kind":"base","answer":"","insufficient":true,"claims":[]}"""
            )

            val turn = session(llm, FixedFinder(listOf(weak))).ask("О чём-то далёком")

            assertEquals(Confidence.ALL_FILTERED, turn.confidence.decision)
            assertTrue(turn.found.sources.isEmpty(), "слабый кандидат отсеян фильтром и до контекста не дошёл")
            assertTrue(
                turn.answerMessages.none { it.content.contains("MARKER-CHUNK") },
                "текст отсеянного фрагмента не должен уходить в промпт ответа"
            )
            assertEquals(Refusal.NO_CONTEXT, turn.refusal)
            assertNotNull(turn.refusalReason, "отказ по контексту обязан называть причину")
            assertFalse(turn.answered)
        }

    @Test
    fun `в поиск уходит запрос переписывателя, а в промпт ответа — исходный вопрос`() = runBlocking {
        val finder = FixedFinder(listOf(source()))
        val rewritten = "Пётр Степанович Верховенский место ссылки"
        val llm = ScriptedLlm(rewrite = rewritten)

        val turn = session(llm, finder).ask("А что у него в руках?")

        assertEquals(rewritten, turn.query, "в поиск уходит раскрытый переписывателем запрос")
        assertEquals(listOf(rewritten), finder.queries, "поиск спрошен именно раскрытым запросом")
        val prompt = turn.answerMessages.last().content
        assertTrue(prompt.contains("А что у него в руках?"), "отвечает модель на исходный вопрос человека")
        assertFalse(prompt.contains(rewritten), "переписанный запрос — средство поиска, а не вопрос модели")
    }

    @Test
    fun `в промпт ответа уходят только последние windowTurns реплик`() = runBlocking {
        val chat = session(
            ScriptedLlm(),
            FixedFinder(emptyList()),
            ChatSettings(windowTurns = 1)
        )

        chat.ask("вопрос-1")
        val second = chat.ask("вопрос-2")
        val third = chat.ask("вопрос-3")

        assertTrue(hasHistory(second.answerMessages, "вопрос-1"), "последняя реплика предыдущего хода в окне")
        assertFalse(hasHistory(third.answerMessages, "вопрос-1"), "реплика за окном в промпт не уходит")
        assertTrue(hasHistory(third.answerMessages, "вопрос-2"), "в окне — последняя реплика, а не первая")
        assertEquals(
            2, third.answerMessages.count { it.role == "user" },
            "окно в одну реплику плюс текущий вопрос — две пользовательские реплики в промпте"
        )
    }

    @Test
    fun `память задачи предыдущего хода видна в промпте следующего`() = runBlocking {
        val chat = session(
            ScriptedLlm(memory = """{"goal":"разобрать первую главу"}"""),
            FixedFinder(emptyList())
        )

        chat.ask("первый вопрос")
        val second = chat.ask("второй вопрос")

        assertEquals("разобрать первую главу", second.memoryBefore.goal)
        assertTrue(
            second.answerMessages.any { message ->
                message.role == "system" &&
                    message.content.contains(TaskMemory.HEADER) &&
                    message.content.contains("разобрать первую главу")
            },
            "цель из памяти задачи обязана быть в промпте следующего ответа"
        )
    }

    @Test
    fun `сброс стирает ленту и память и начинает нумерацию заново`() = runBlocking {
        val chat = session(
            ScriptedLlm(memory = """{"goal":"разобрать первую главу"}"""),
            FixedFinder(emptyList())
        )

        chat.ask("первый")
        chat.ask("второй")
        assertEquals(2, chat.turns().size)
        assertFalse(chat.memory.isEmpty, "память разговора не пуста до сброса")

        chat.reset()

        assertTrue(chat.turns().isEmpty(), "сброс стирает ленту разговора")
        assertTrue(chat.memory.isEmpty, "сброс стирает память задачи вместе с лентой")
        assertEquals(1, chat.ask("заново").index, "нумерация ходов начинается с единицы заново")
    }

    /** Была ли эта реплика человека в промпте отдельным сообщением истории. */
    private fun hasHistory(messages: List<ChatMessage>, text: String): Boolean =
        messages.any { it.role == "user" && it.content == text }

    /** Поиск, возвращающий заранее заданные фрагменты и запоминающий, чем его спрашивали. */
    private class FixedFinder(private val sources: List<Source>) : SourceFinder {

        val queries = mutableListOf<String>()

        override suspend fun find(question: String): Retrieval {
            queries += question
            return Retrieval(sources = sources, queryVector = emptyList(), millis = 1L, trace = null)
        }
    }

    /**
     * Клиент модели с заготовками на каждый из трёх запросов хода.
     *
     * Различие запросов — по системному сообщению: у извлечения памяти, переписывания и ответа они
     * разные, и разводить их по порядку вызовов было бы хрупко. По умолчанию переписыватель отвечает
     * пустой строкой (запрос остаётся исходным вопросом), а ответ — по памяти задачи, чтобы тесты,
     * которым важна только история, не задавали лишних заготовок.
     */
    private class ScriptedLlm(
        private val memory: String = "{}",
        private val rewrite: String = "",
        private val answer: String = DIALOGUE_ANSWER
    ) : LlmClient {

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            val system = request.messages.first().content
            val content = when (system) {
                ChatPrompt.MEMORY_SYSTEM -> memory
                ChatPrompt.REWRITE_SYSTEM -> rewrite
                ChatPrompt.CHAT_SYSTEM -> answer
                else -> error("подменный клиент не знает этот промпт: ${system.take(40)}")
            }
            return DeepSeekResponse(
                choices = listOf(DeepSeekResponse.Choice(ChatMessage(role = "assistant", content = content))),
                usage = DeepSeekResponse.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
            )
        }
    }
}
