package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.DialogBranch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Транспорт для проверки стратегий: записывает запросы, ответ задаёт функция от запроса и номера вызова. */
private class StubClient(private val answer: (DeepSeekRequest, Int) -> String) : LlmClient {
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

/** Агент с записью логов вместо печати в консоль. */
private fun engine(llm: LlmClient, logs: MutableList<String> = mutableListOf()) =
    LlmAgent(llm, logger = AgentLogger { logs += it })

/** Диалог с узнаваемым текстом: сообщения с меткой ветки или основной линии. */
private fun dialog(messages: Int, branchId: String? = null, from: Int = 1): List<ChatMessage> =
    (from until from + messages).map {
        ChatMessage(if (it % 2 == 1) "user" else "assistant", "сообщение $it", branchId)
    }

/** Ответ служебного вызова памяти: записи обоих типов — тип каждая называет сама. */
private const val MEMORY_JSON =
    """{"memory":[{"layer":"working","value":"цель — собрать ТЗ"},""" +
        """{"layer":"working","value":"бюджет — 5 000 рублей"},""" +
        """{"layer":"long_term","value":"хранилище — Room"}]}"""

/** Ответ с записями, которым в память нельзя: неизвестный тип и тип вне стратегии. */
private const val BAD_MEMORY_JSON =
    """{"memory":[{"layer":"short_term","value":"пользователь поздоровался"},""" +
        """{"layer":"настроение","value":"боевой"}]}"""

/** Запрос к модели, в котором обновлялась память: у него видно новый кусок диалога. */
private fun DeepSeekRequest.isMemoryUpdate(): Boolean =
    messages.last().content.contains("Новые сообщения диалога:")

private val TRUNK = dialog(4)
private val BRANCH_A = listOf(
    ChatMessage("user", "ветка А: подписка", "a"),
    ChatMessage("assistant", "ответ А", "a")
)
private val BRANCH_B = listOf(
    ChatMessage("user", "ветка Б: разовый платёж", "b"),
    ChatMessage("assistant", "ответ Б", "b")
)
private val TREE = TRUNK + BRANCH_A + BRANCH_B
private val BRANCHES = listOf(DialogBranch("a", forkedAfter = 4), DialogBranch("b", forkedAfter = 4))

class ContextStrategiesTest {

    /** Стратегия по значению из запроса: неизвестное значение — сжатие, как в дне 9. */
    @Test
    fun strategyFromWireKeepsUnknownRequestsOnCompression() {
        assertEquals(ContextStrategy.SLIDING_WINDOW, ContextStrategy.fromWire("sliding_window"))
        assertEquals(ContextStrategy.BRANCHES, ContextStrategy.fromWire(" BRANCHES "))
        assertEquals(ContextStrategy.MEMORY, ContextStrategy.fromWire("memory"))
        assertEquals(
            ContextStrategy.MEMORY,
            ContextStrategy.fromWire("facts"),
            "стратегия дня 10 читается как память агента"
        )
        assertEquals(ContextStrategy.SUMMARY, ContextStrategy.fromWire(null))
        assertEquals(ContextStrategy.SUMMARY, ContextStrategy.fromWire("выдумка"))
    }

    /** Скользящее окно: в модель уходят только последние сообщения, старшие отброшены. */
    @Test
    fun slidingWindowKeepsOnlyLastMessages() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Что дальше?",
            AgentOptions(
                history = dialog(20),
                strategy = ContextStrategy.SLIDING_WINDOW,
                windowMessages = 6
            )
        )

        val messages = llm.requests.last().messages
        assertEquals(8, messages.size, "system prompt + окно из 6 сообщений + текущий вопрос")
        assertEquals("сообщение 15", messages[1].content, "окно начинается с последних сообщений")
        assertEquals("Что дальше?", messages.last().content)
        assertTrue(messages.none { it.content == "сообщение 14" }, "старшее сообщение отброшено")
        assertEquals(14, result.tokens.droppedMessages)
        assertEquals(6, result.tokens.windowMessages)
        assertEquals("sliding_window", result.tokens.strategy)
        assertTrue(result.tokens.history < result.tokens.historyRawTokens, "окно меньше всей истории")
        assertEquals(2, llm.requests.size, "обновление рабочей памяти окном и ответ модели")
        assertTrue(
            logs.any { it.startsWith("Скользящее окно истории") && it.contains("отброшено сообщений: 14") },
            "в логе видно, что отброшено: $logs"
        )
        assertTrue(logs.any { it.contains("стратегия контекста: скользящее окно") }, "стратегия видна в логе запроса")
    }

    /** Окно больше диалога: ничего не отбрасываем. */
    @Test
    fun slidingWindowKeepsWholeShortDialog() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }

        val result = engine(llm).run(
            "Что дальше?",
            AgentOptions(history = dialog(4), strategy = ContextStrategy.SLIDING_WINDOW, windowMessages = 10)
        )

        assertEquals(6, llm.requests.last().messages.size, "system prompt + 4 сообщения + вопрос")
        assertEquals(0, result.tokens.droppedMessages)
    }

    /** Границы окна: клиент не может ни выключить контекст, ни запросить всю историю целиком. */
    @Test
    fun slidingWindowClampsRequestedSize() = runBlocking {
        val zero = StubClient { _, _ -> "ответ" }
        engine(zero).run(
            "Что дальше?",
            AgentOptions(history = dialog(6), strategy = ContextStrategy.SLIDING_WINDOW, windowMessages = 0)
        )
        assertEquals(4, zero.requests.last().messages.size, "нулевое окно зажато до двух сообщений")

        val huge = StubClient { _, _ -> "ответ" }
        val result = engine(huge).run(
            "Что дальше?",
            AgentOptions(history = dialog(300), strategy = ContextStrategy.SLIDING_WINDOW, windowMessages = 500)
        )
        assertEquals(102, huge.requests.last().messages.size, "окно зажато до сотни сообщений")
        assertEquals(100, result.tokens.windowMessages)
    }

    /** Скользящее окно ведёт рабочую память: важное из отброшенных сообщений не теряется. */
    @Test
    fun slidingWindowKeepsWorkingMemory() = runBlocking {
        val llm = StubClient { request, call -> if (request.isMemoryUpdate() && call == 1) MEMORY_JSON else "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Дальше — сроки",
            AgentOptions(
                history = dialog(20),
                strategy = ContextStrategy.SLIDING_WINDOW,
                windowMessages = 6
            )
        )

        assertEquals(2, llm.requests.size, "обновление памяти + ответ модели")
        val messages = llm.requests.last().messages
        assertEquals(
            "Рабочая память задачи (данные текущей задачи):\n" +
                "- цель — собрать ТЗ\n- бюджет — 5 000 рублей",
            messages[1].content,
            "рабочая память идёт сразу после system prompt"
        )
        assertEquals("сообщение 15", messages[2].content, "после памяти — окно последних сообщений")
        assertEquals(
            listOf(MemoryRecord("working", "цель — собрать ТЗ"), MemoryRecord("working", "бюджет — 5 000 рублей")),
            result.tokens.memory.working
        )
        assertTrue(result.tokens.memory.workingTokens > 0, "блок рабочей памяти посчитан в отчёте")
        assertEquals(340, result.tokens.memory.updateTokens, "цена обновления памяти в отчёте")
        assertEquals(14, result.tokens.memory.shortTermDropped)
        assertEquals("sliding_window", result.tokens.strategy)
        assertTrue(
            logs.any { it.contains("рабочая память: 2 записей") && it.contains("отклонено записей: 1") },
            "в логе видны слои памяти и отклонённая запись: $logs"
        )
    }

    /** Память агента: оба блока слоёв уходят в запрос перед окном, вид решает слой. */
    @Test
    fun memoryStrategySendsBothLayersWithWindow() = runBlocking {
        val llm = StubClient { request, call -> if (request.isMemoryUpdate() && call == 1) MEMORY_JSON else "ответ" }

        val result = engine(llm).run(
            "Дальше — сроки",
            AgentOptions(
                history = dialog(20),
                strategy = ContextStrategy.MEMORY,
                windowMessages = 6
            )
        )

        assertEquals(2, llm.requests.size, "обновление памяти + ответ модели")
        val messages = llm.requests.last().messages
        assertEquals(
            "Долговременная память (профиль, решения, знания):\n- хранилище — Room",
            messages[1].content,
            "долговременный слой идёт сразу после system prompt"
        )
        assertEquals(
            "Рабочая память задачи (данные текущей задачи):\n" +
                "- цель — собрать ТЗ\n- бюджет — 5 000 рублей",
            messages[2].content,
            "рабочий слой идёт после долговременного"
        )
        assertEquals("сообщение 15", messages[3].content, "после слоёв памяти — окно последних сообщений")
        assertEquals(
            listOf(MemoryRecord("working", "цель — собрать ТЗ"), MemoryRecord("working", "бюджет — 5 000 рублей")),
            result.tokens.memory.working
        )
        assertEquals(listOf(MemoryRecord("long_term", "хранилище — Room")), result.tokens.memory.longTerm)
        assertTrue(result.tokens.memory.longTermTokens > 0, "блок долговременной памяти посчитан в отчёте")
        assertEquals(6, result.tokens.memory.shortTermMessages, "в отчёте видно, сколько сообщений ушло в запрос")
        assertEquals(14, result.tokens.memory.shortTermDropped, "и сколько отброшено окном")
        assertEquals(0, result.tokens.memory.rejected)
        assertEquals(340, result.tokens.memory.updateTokens, "цена обновления памяти в отчёте")
        assertEquals(14, result.tokens.droppedMessages)
        assertEquals("memory", result.tokens.strategy)
    }

    /** Память накапливается: прежние записи слоёв уходят в служебный запрос, а не теряются. */
    @Test
    fun memoryAccumulatesAcrossMessages() = runBlocking {
        val updates = mutableListOf<String>()
        val llm = StubClient { request, _ ->
            if (request.isMemoryUpdate()) {
                updates += request.messages.last().content
                MEMORY_JSON
            } else {
                "ответ"
            }
        }
        val agent = engine(llm)

        agent.run("Первое", AgentOptions(strategy = ContextStrategy.MEMORY))
        agent.run("Второе", AgentOptions(history = dialog(2), strategy = ContextStrategy.MEMORY))

        assertEquals(4, llm.requests.size, "по два вызова на сообщение: память и ответ")
        assertTrue(
            updates[0].contains("Долговременная память пока пуста"),
            "первое обновление начинает слои с нуля: ${updates[0]}"
        )
        assertTrue(
            updates[1].contains("Прежняя память — Рабочая память задачи:\n- цель — собрать ТЗ"),
            "второе обновление видит прежние записи рабочего слоя: ${updates[1]}"
        )
        assertTrue(
            updates[1].contains("Прежняя память — Долговременная память:\n- хранилище — Room"),
            "второе обновление видит прежние записи долговременного слоя: ${updates[1]}"
        )
        assertTrue(
            llm.requests.last().messages.any { it.content.startsWith("Долговременная память") },
            "в запрос уходит накопленная память"
        )
    }

    /** Сломанное обновление память не портит: остаются прежние записи обоих слоёв. */
    @Test
    fun memoryStaysWhenUpdateIsNotJson() = runBlocking {
        val llm = StubClient { request, call ->
            when {
                request.isMemoryUpdate() && call == 1 -> MEMORY_JSON
                request.isMemoryUpdate() -> "извините, не могу"
                else -> "ответ"
            }
        }
        val logs = mutableListOf<String>()
        val agent = engine(llm, logs)

        agent.run("Первое", AgentOptions(strategy = ContextStrategy.MEMORY))
        val second = agent.run("Второе", AgentOptions(strategy = ContextStrategy.MEMORY))

        assertEquals(2, second.tokens.memory.working.size, "прежние записи рабочего слоя остались")
        assertEquals(1, second.tokens.memory.longTerm.size, "прежние записи долговременного слоя остались")
        assertTrue(
            llm.requests.last().messages.any { it.content.contains("- бюджет — 5 000 рублей") },
            "память ушла в запрос несмотря на сбой обновления"
        )
        assertTrue(logs.any { it.contains("Память не обновилась") }, "сбой обновления виден в логе")
    }

    /** Без сессии память всё равно ведётся: слои лежат по профилю, сессию требует только сводка. */
    @Test
    fun memoryWorksWithoutSession() = runBlocking {
        val llm = StubClient { request, call -> if (request.isMemoryUpdate() && call == 1) MEMORY_JSON else "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Что дальше?",
            AgentOptions(history = dialog(20), strategy = ContextStrategy.MEMORY, windowMessages = 6)
        )

        assertEquals(2, llm.requests.size, "память обновляется и без сессии: служебный вызов + ответ модели")
        assertEquals(2, result.tokens.memory.working.size, "рабочий слой наполнился")
        assertEquals(1, result.tokens.memory.longTerm.size, "долговременный слой наполнился")
        assertEquals(14, result.tokens.droppedMessages, "окно всё равно применяется")
        assertTrue(
            llm.requests.last().messages.any { it.content.startsWith("Рабочая память задачи") },
            "рабочая память ушла в запрос"
        )
        assertTrue(logs.none { it.contains("сессия не названа") }, "сессии памяти не нужно — жаловаться не на что: $logs")
    }

    /** Запись чужого типа в память не попадает: тип называет модель, а принимает его код. */
    @Test
    fun recordsOfForeignTypeAreRejectedHere() = runBlocking {
        val llm = StubClient { request, _ -> if (request.isMemoryUpdate()) BAD_MEMORY_JSON else "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Дальше — сроки",
            AgentOptions(history = dialog(6), strategy = ContextStrategy.SLIDING_WINDOW)
        )

        assertEquals(
            2,
            result.tokens.memory.rejected,
            "неизвестный тип и запись типа, которого стратегия не ведёт, отклонены"
        )
        assertTrue(result.tokens.memory.working.isEmpty(), "сохранять нечего: типы записей не те")
        assertTrue(result.tokens.memory.longTerm.isEmpty(), "окно долговременную память не ведёт")
        assertTrue(logs.any { it.contains("отклонено записей: 2") }, "отклонённые записи видны в логе: $logs")
    }

    /** Ветки: в модель уходит путь активной ветки, соседняя ветка не попадает. */
    @Test
    fun branchSendsTrunkAndOwnMessagesOnly() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Что дальше?",
            AgentOptions(
                history = TREE,
                strategy = ContextStrategy.BRANCHES,
                branches = BRANCHES,
                activeBranchId = "a"
            )
        )

        val contents = llm.requests.single().messages.map { it.content }
        assertEquals(
            listOf("сообщение 1", "сообщение 1", "сообщение 2", "сообщение 3", "сообщение 4", "ветка А: подписка", "ответ А", "Что дальше?"),
            contents,
            "system prompt из первого сообщения, общий путь до ветвления и сообщения ветки"
        )
        assertTrue(contents.none { it.startsWith("ветка Б") }, "соседняя ветка в запрос не уходит")
        assertEquals(2, result.tokens.excludedMessages, "сообщения соседней ветки посчитаны")
        assertEquals("a", result.tokens.branchId)
        assertEquals("branches", result.tokens.strategy)
        assertTrue(
            logs.any { it.startsWith("Ветки диалога") && it.contains("общий путь до точки ветвления: 4 сообщ.") },
            "в логе видно точку ветвления: $logs"
        )
    }

    /** Переключение ветки меняет контекст следующего же запроса. */
    @Test
    fun switchingBranchChangesContext() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val agent = engine(llm)
        val options = AgentOptions(history = TREE, strategy = ContextStrategy.BRANCHES, branches = BRANCHES)

        agent.run("Что дальше?", options.copy(activeBranchId = "a"))
        agent.run("Что дальше?", options.copy(activeBranchId = "b"))

        val first = llm.requests[0].messages.map { it.content }
        val second = llm.requests[1].messages.map { it.content }
        assertTrue(first.any { it == "ветка А: подписка" } && first.none { it.startsWith("ветка Б") })
        assertTrue(second.any { it == "ветка Б: разовый платёж" } && second.none { it.startsWith("ветка А") })
        assertEquals(8, first.size, "system prompt + 4 общих сообщения + 2 своих + вопрос")
        assertEquals(8, second.size)
    }

    /** Вложенная ветка: путь собирается от родителя до точки ветвления. */
    @Test
    fun nestedBranchKeepsCheckpointPath() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val tree = TREE + listOf(
            ChatMessage("user", "ветка Б1: только подписка", "b1"),
            ChatMessage("assistant", "ответ Б1", "b1")
        )
        val branches = BRANCHES + DialogBranch("b1", parentId = "b", forkedAfter = 5)

        engine(llm).run(
            "Что дальше?",
            AgentOptions(history = tree, strategy = ContextStrategy.BRANCHES, branches = branches, activeBranchId = "b1")
        )

        val contents = llm.requests.single().messages.map { it.content }
        assertTrue(contents.contains("ветка Б: разовый платёж"), "в путь входит первый шаг родительской ветки")
        assertTrue(contents.contains("ветка Б1: только подписка") && contents.contains("ответ Б1"))
        assertTrue(contents.none { it == "ответ Б" }, "за точкой ветвления сообщения родителя не уходят")
        assertTrue(contents.none { it.startsWith("ветка А") })
    }

    /** Неизвестная ветка — не повод падать: работаем как с основной линией. */
    @Test
    fun unknownBranchFallsBackToTrunk() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }

        engine(llm).run(
            "Что дальше?",
            AgentOptions(history = TREE, strategy = ContextStrategy.BRANCHES, branches = BRANCHES, activeBranchId = "нет-такой")
        )

        val contents = llm.requests.single().messages.drop(1).map { it.content }
        assertEquals(listOf("сообщение 1", "сообщение 2", "сообщение 3", "сообщение 4", "Что дальше?"), contents)
    }

    /** Метка ветки — служебная: в API уходят сообщения без неё. */
    @Test
    fun branchTagsDoNotReachApi() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }

        engine(llm).run(
            "Что дальше?",
            AgentOptions(history = TREE, strategy = ContextStrategy.BRANCHES, branches = BRANCHES, activeBranchId = "a")
        )

        assertTrue(
            llm.requests.single().messages.none { it.branchId != null },
            "в запросе к модели метки веток не остаётся"
        )
    }

    /** Стратегия «вся история» — поведение дня 9: вся история и никаких служебных вызовов. */
    @Test
    fun fullStrategySendsWholeHistory() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run("Что дальше?", AgentOptions(history = dialog(20)))

        assertEquals(1, llm.requests.size)
        assertEquals(22, llm.requests.single().messages.size, "system prompt + 20 сообщений + вопрос")
        assertEquals(0, result.tokens.droppedMessages)
        assertEquals("full", result.tokens.strategy)
        assertTrue(logs.none { it.startsWith("Скользящее окно") }, "своего блока у стратегии нет")
    }

    /** Разбор памяти: модель любит обрамлять JSON текстом — это не должно ломать память. */
    @Test
    fun extractorParsesFencedJsonAndRejectsGarbage() {
        val extractor = MemoryExtractor()

        assertEquals(
            listOf(MemoryRecord("working", "цель — собрать ТЗ")),
            extractor.parse(
                "Вот результат:\n```json\n" +
                    """{"memory":[{"layer":"working","value":"цель — собрать ТЗ"}]}""" +
                    "\n```"
            )?.records
        )
        assertEquals(0, extractor.parse("""{"memory":[]}""")?.records?.size, "пустая память — не сбой разбора")
        assertNull(extractor.parse("извините, не могу"))
        assertNull(extractor.parse(""))

        val twoRecords = extractor.parse(
            """{"memory":[{"layer":"long_term","value":"хранилище — Room"},""" +
                """{"layer":"long_term","value":"хранилище — SQLite"}]}"""
        )?.records
        assertEquals(2, twoRecords?.size, "разбор не теряет записи: повторы сводит слияние, а не разбор")

        val rejected = extractor.parse(
            """{"memory":[{"layer":"настроение","value":"боевой"},""" +
                """{"layer":"working","value":"  "}]}"""
        )
        assertEquals(2, rejected?.rejected, "неизвестный тип и пустая запись отклонены")
        assertTrue(rejected?.records?.isEmpty() == true)
    }
}
