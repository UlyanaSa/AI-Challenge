package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Транспорт: ответ задаёт функция от запроса и номера вызова. */
private class LayerStubClient(private val answer: (DeepSeekRequest, Int) -> String) : LlmClient {
    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", answer(request, requests.size)), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = 300,
                completionTokens = 40,
                totalTokens = 340,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(0)
            )
        )
    }
}

/** Агент с тихим логом: тесты памяти смотрят на запросы и отчёт. */
private fun layerAgent(llm: LlmClient, working: MemoryStore? = null, longTerm: MemoryStore? = null) = LlmAgent(
    llm,
    logger = AgentLogger.Silent,
    workingMemory = working ?: InMemoryMemoryStore(),
    longTermMemory = longTerm ?: InMemoryMemoryStore()
)

/** Диалог с узнаваемым текстом: сообщения чередуют роли. */
private fun layerDialog(messages: Int): List<ChatMessage> =
    (1..messages).map { ChatMessage(if (it % 2 == 1) "user" else "assistant", "сообщение $it") }

/** Запрос к модели, в котором обновлялась память: у него видно новый кусок диалога. */
private fun DeepSeekRequest.isMemoryUpdateCall(): Boolean =
    messages.last().content.contains("Новые сообщения диалога:")

private const val TASK_JSON =
    """{"memory":[{"layer":"working","value":"цель — учёт расходов"}]}"""

private const val LONG_TERM_JSON =
    """{"memory":[{"layer":"long_term","value":"имя — Иван"},""" +
        """{"layer":"long_term","value":"хранилище — Room"}]}"""

/**
 * Типы памяти: что попадает в каждый тип, где что хранится и что уходит в запрос.
 * Типов ровно три, и названы они словами задания — это проверяется отдельно.
 */
class MemoryLayersTest {

    /** Типы памяти названы как в задании: подписи и пояснения живут только в [MemoryLayer]. */
    @Test
    fun memoryTypesAreNamedAsInTheTask() {
        assertEquals(
            listOf(
                "краткосрочная (текущий диалог)",
                "рабочая (данные текущей задачи — общие для всех чатов)",
                "долговременная (профиль, решения, знания)"
            ),
            MemoryLayer.entries.map { it.caption },
            "три типа памяти и их пояснения — словами задания"
        )
        assertEquals(
            listOf(MemoryLayer.SHORT_TERM, MemoryLayer.WORKING, MemoryLayer.LONG_TERM).map { it.wire },
            MemoryLayer.entries.map { it.wire },
            "значения на проводе: короткое, рабочее, долговременное"
        )
        assertEquals(MemoryLayer.LONG_TERM, MemoryLayer.ofWire(" LONG_TERM "), "тип читается без регистра и пробелов")
        assertNull(MemoryLayer.ofWire("долговременная"), "по-русски называется подпись, а не значение провода")
        assertNull(MemoryLayer.ofWire(""))

        assertEquals(
            listOf(false, true, true),
            MemoryLayer.entries.map { it.writable },
            "краткосрочная память — сообщения диалога: в неё не пишут вручную"
        )
        assertEquals(
            MemoryLayers.catalogue().map { it.layer },
            MemoryLayer.entries.map { it.wire },
            "каталог снимка — те же три типа: интерфейс рисует выбор по нему"
        )
    }

    /** Слияние типа: та же фраза обновляет запись, лишние вытесняются самыми старыми. */
    @Test
    fun mergeUpdatesSameTextAndEvictsOldest() {
        val merged = MemoryRules.merge(
            existing = listOf(
                MemoryRecord("working", "цель — собрать ТЗ"),
                MemoryRecord("working", "срок — 6 недель")
            ),
            incoming = listOf(MemoryRecord("working", "цель — собрать ТЗ")),
            layer = MemoryLayer.WORKING
        )
        assertEquals(
            listOf(
                MemoryRecord("working", "срок — 6 недель"),
                MemoryRecord("working", "цель — собрать ТЗ")
            ),
            merged.records,
            "повтор фразы не плодит записей: она переезжает в конец, то есть становится свежей"
        )
        assertEquals(0, merged.evicted)

        val reworded = MemoryRules.merge(
            existing = listOf(MemoryRecord("working", "хранилище — Room")),
            incoming = listOf(MemoryRecord("working", "хранилище — SQLite")),
            layer = MemoryLayer.WORKING
        )
        assertEquals(2, reworded.records.size, "пересказанная фраза — другая запись: ключа у записи нет")
        assertEquals(1, MemoryRules.forget(reworded.records, "хранилище — room").size, "а удаляется по тексту")

        val capped = MemoryRules.merge(
            existing = emptyList(),
            incoming = (1..25).map { MemoryRecord("working", "запись $it") },
            layer = MemoryLayer.WORKING
        )
        assertEquals(MemoryRules.MAX_WORKING, capped.records.size, "рабочий тип держит свой предел")
        assertEquals(5, capped.evicted)
        assertEquals("запись 6", capped.records.first().value, "вытесняются самые старые записи")
    }

    /** Запись без известного типа или без текста в память не попадает. */
    @Test
    fun normalizeRejectsUnknownLayerAndBlankValue() {
        assertNull(MemoryRules.normalize(MemoryRecord("настроение", "боевой")))
        assertNull(MemoryRules.normalize(MemoryRecord("", "боевой")))
        assertNull(MemoryRules.normalize(MemoryRecord("working", "   ")))
        assertEquals(
            MemoryRecord("working", "цель — учёт"),
            MemoryRules.normalize(MemoryRecord(" WORKING ", " цель — учёт "))
        )
        assertEquals(
            MemoryRules.MAX_CHARS,
            MemoryRules.normalize(MemoryRecord("long_term", "я".repeat(500)))?.value?.length,
            "память — выжимка: длинная запись обрезается"
        )
    }

    /** Слои живут по профилю: рабочую память прошлой задачи видно из нового чата, как и долговременную. */
    @Test
    fun workingAndLongTermMemoryAreSharedByChats() = runBlocking {
        val working = InMemoryMemoryStore()
        val longTerm = InMemoryMemoryStore()
        var memoryCall = 0
        val llm = LayerStubClient { request, _ ->
            if (request.isMemoryUpdateCall()) {
                memoryCall++
                if (memoryCall == 1) TASK_JSON else LONG_TERM_JSON
            } else {
                "ответ"
            }
        }
        val agent = layerAgent(llm, working, longTerm)

        val first = agent.run("Собираем ТЗ на учёт расходов", AgentOptions(strategy = ContextStrategy.MEMORY))
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            first.tokens.memory.working
        )
        assertTrue(first.tokens.memory.longTerm.isEmpty(), "в первом диалоге долговременная память ещё пуста")

        val second = agent.run("Что мы решали раньше?", AgentOptions(strategy = ContextStrategy.MEMORY))
        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            second.tokens.memory.working,
            "новый чат видит рабочую память прошлой задачи: она лежит по профилю, а не по сессии"
        )
        assertEquals(
            listOf(MemoryRecord("long_term", "имя — Иван"), MemoryRecord("long_term", "хранилище — Room")),
            second.tokens.memory.longTerm,
            "новый диалог видит долговременную память, накопленную прошлым"
        )

        val contents = llm.requests.last().messages.map { it.content }
        assertTrue(
            contents.any { it.startsWith("Рабочая память задачи") && it.contains("цель — учёт расходов") },
            "в запрос нового чата уходит рабочая память прошлой задачи: $contents"
        )
        assertTrue(
            contents.any { it.startsWith("Долговременная память") && it.contains("Room") },
            "в запрос нового диалога уходит долговременная память: $contents"
        )
        assertTrue(
            llm.requests.last().messages.none { it.content == "сообщение 1" },
            "краткосрочная память нового диалога пуста: сообщений прошлого в запросе нет"
        )

        assertEquals(1, working.get(DEFAULT_PROFILE).size, "рабочая память лежит по профилю, а не по чату")
        assertEquals(2, longTerm.get(DEFAULT_PROFILE).size, "долговременная память лежит по тому же профилю")
    }

    /** Тип, которого стратегия не ведёт, в запрос не идёт и не переписывается. */
    @Test
    fun strategyReadsOnlyItsOwnLayers() = runBlocking {
        val longTerm = InMemoryMemoryStore().apply {
            put(DEFAULT_PROFILE, listOf(MemoryRecord("long_term", "хранилище — Room")))
        }
        val llm = LayerStubClient { request, _ -> if (request.isMemoryUpdateCall()) TASK_JSON else "ответ" }
        val agent = layerAgent(llm, longTerm = longTerm)

        agent.run(
            "Что дальше?",
            AgentOptions(history = layerDialog(6), strategy = ContextStrategy.SLIDING_WINDOW)
        )

        val contents = llm.requests.last().messages.map { it.content }
        assertTrue(
            contents.none { it.startsWith("Долговременная память") },
            "окно долговременную память не читает: $contents"
        )
        assertTrue(contents.any { it.startsWith("Рабочая память задачи") }, "рабочая память в запросе есть")
        assertEquals(
            listOf(MemoryRecord("long_term", "хранилище — Room")),
            longTerm.get(DEFAULT_PROFILE),
            "чужая память не переписана"
        )
    }

    /** Модель назвала чужой тип: краткосрочная память — это диалог, остальное — не из задания. */
    @Test
    fun recordsOfForeignTypeAreRejected() = runBlocking {
        val llm = LayerStubClient { request, _ ->
            if (request.isMemoryUpdateCall()) {
                """{"memory":[{"layer":"working","value":"цель — учёт расходов"},""" +
                    """{"layer":"short_term","value":"пользователь поздоровался"},""" +
                    """{"layer":"настроение","value":"боевой"}]}"""
            } else {
                "ответ"
            }
        }
        val working = InMemoryMemoryStore()

        val result = layerAgent(llm, working = working).run(
            "Собираем ТЗ",
            AgentOptions(strategy = ContextStrategy.MEMORY)
        )

        assertEquals(
            listOf(MemoryRecord("working", "цель — учёт расходов")),
            result.tokens.memory.working,
            "запись своего типа сохранена"
        )
        assertEquals(2, result.tokens.memory.rejected, "чужие типы в память не попали")
        assertEquals(1, working.get(DEFAULT_PROFILE).size)
    }

    /** Стратегии без памяти типов не трогают: ни чтения, ни служебных вызовов. */
    @Test
    fun strategiesWithoutMemoryLeaveLayersEmpty() = runBlocking {
        listOf(ContextStrategy.FULL, ContextStrategy.BRANCHES, ContextStrategy.SUMMARY).forEach { strategy ->
            val llm = LayerStubClient { _, _ -> "ответ" }
            val working = InMemoryMemoryStore()
            val agent = layerAgent(llm, working = working)

            val result = agent.run(
                "Что дальше?",
                AgentOptions(history = layerDialog(4), sessionId = "chat", strategy = strategy)
            )

            assertEquals(1, llm.requests.size, "$strategy служебных вызовов не делает")
            assertTrue(result.tokens.memory.working.isEmpty(), "$strategy рабочую память не ведёт")
            assertTrue(result.tokens.memory.longTerm.isEmpty(), "$strategy долговременную память не ведёт")
            assertTrue(working.get("chat").isEmpty(), "$strategy память не наполняет")
        }
    }

    /** Краткосрочная память описана в отчёте числами: сколько ушло и сколько не ушло. */
    @Test
    fun shortTermMemoryIsReported() = runBlocking {
        val llm = LayerStubClient { request, _ -> if (request.isMemoryUpdateCall()) TASK_JSON else "ответ" }

        val result = layerAgent(llm).run(
            "Что дальше?",
            AgentOptions(
                history = layerDialog(20),
                sessionId = "chat",
                strategy = ContextStrategy.MEMORY,
                windowMessages = 6
            )
        )

        assertEquals(6, result.tokens.memory.shortTermMessages, "в запрос ушло окно")
        assertEquals(14, result.tokens.memory.shortTermDropped, "остальное отброшено")
        assertEquals(340, result.tokens.memory.updateTokens, "цена обновления памяти посчитана")
        assertTrue(result.tokens.memory.workingTokens > 0, "рабочая память посчитана в токенах")
        assertEquals(0, result.tokens.memory.rejected)
        assertEquals(0, result.tokens.memory.evicted)
    }
}
