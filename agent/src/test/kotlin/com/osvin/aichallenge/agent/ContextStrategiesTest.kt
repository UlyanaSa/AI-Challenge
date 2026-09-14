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

/** Ответ служебного вызова памяти фактов. */
private const val FACTS_JSON =
    """{"facts":[{"key":"цель","value":"собрать ТЗ"},{"key":"бюджет","value":"5 000 рублей"}]}"""

/** Запрос к модели, в котором обновлялась память фактов: у него видно новый кусок диалога. */
private fun DeepSeekRequest.isFactsUpdate(): Boolean =
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

        val messages = llm.requests.single().messages
        assertEquals(8, messages.size, "system prompt + окно из 6 сообщений + текущий вопрос")
        assertEquals("сообщение 15", messages[1].content, "окно начинается с последних сообщений")
        assertEquals("Что дальше?", messages.last().content)
        assertTrue(messages.none { it.content == "сообщение 14" }, "старшее сообщение отброшено")
        assertEquals(14, result.tokens.droppedMessages)
        assertEquals(6, result.tokens.windowMessages)
        assertEquals("sliding_window", result.tokens.strategy)
        assertTrue(result.tokens.history < result.tokens.historyRawTokens, "окно меньше всей истории")
        assertEquals(1, llm.requests.size, "без памяти служебных вызовов нет")
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

        assertEquals(6, llm.requests.single().messages.size, "system prompt + 4 сообщения + вопрос")
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
        assertEquals(4, zero.requests.single().messages.size, "нулевое окно зажато до двух сообщений")

        val huge = StubClient { _, _ -> "ответ" }
        val result = engine(huge).run(
            "Что дальше?",
            AgentOptions(history = dialog(300), strategy = ContextStrategy.SLIDING_WINDOW, windowMessages = 500)
        )
        assertEquals(102, huge.requests.single().messages.size, "окно зажато до сотни сообщений")
        assertEquals(100, result.tokens.windowMessages)
    }

    /** Память фактов: служебный вызов обновляет память, в запрос уходит блок фактов. */
    @Test
    fun factsAreExtractedAndSentWithWindow() = runBlocking {
        val llm = StubClient { request, call -> if (request.isFactsUpdate() && call == 1) FACTS_JSON else "ответ" }

        val result = engine(llm).run(
            "Дальше — сроки",
            AgentOptions(
                history = dialog(20),
                sessionId = "session",
                strategy = ContextStrategy.FACTS,
                windowMessages = 6
            )
        )

        assertEquals(2, llm.requests.size, "обновление памяти + ответ модели")
        val messages = llm.requests.last().messages
        assertEquals(
            "Память диалога (факты):\n- цель: собрать ТЗ\n- бюджет: 5 000 рублей",
            messages[1].content,
            "блок фактов идёт сразу после system prompt"
        )
        assertEquals("сообщение 15", messages[2].content, "после памяти — окно последних сообщений")
        assertEquals(
            listOf(Fact("цель", "собрать ТЗ"), Fact("бюджет", "5 000 рублей")),
            result.tokens.facts
        )
        assertTrue(result.tokens.factsTokens > 0, "блок фактов посчитан в отчёте")
        assertEquals(340, result.tokens.factsUpdateTokens, "цена обновления памяти в отчёте")
        assertEquals(14, result.tokens.droppedMessages)
        assertEquals("facts", result.tokens.strategy)
    }

    /** Память накапливается: прежние факты уходят в служебный запрос, а не теряются. */
    @Test
    fun factsAccumulateAcrossMessages() = runBlocking {
        val updates = mutableListOf<String>()
        val llm = StubClient { request, _ ->
            if (request.isFactsUpdate()) {
                updates += request.messages.last().content
                FACTS_JSON
            } else {
                "ответ"
            }
        }
        val agent = engine(llm)

        agent.run("Первое", AgentOptions(sessionId = "session", strategy = ContextStrategy.FACTS))
        agent.run("Второе", AgentOptions(history = dialog(2), sessionId = "session", strategy = ContextStrategy.FACTS))

        assertEquals(4, llm.requests.size, "по два вызова на сообщение: память и ответ")
        assertTrue(updates[0].contains("Фактов пока нет"), "первое обновление начинает память с нуля")
        assertTrue(
            updates[1].contains("Прежние факты:\n- цель: собрать ТЗ"),
            "второе обновление видит прежние факты: ${updates[1]}"
        )
        assertTrue(
            llm.requests.last().messages.any { it.content.startsWith("Память диалога (факты):") },
            "в запрос уходит накопленная память"
        )
    }

    /** Сломанное обновление память не портит: остаются прежние факты. */
    @Test
    fun factsStayWhenUpdateIsNotJson() = runBlocking {
        val llm = StubClient { request, call ->
            when {
                request.isFactsUpdate() && call == 1 -> FACTS_JSON
                request.isFactsUpdate() -> "извините, не могу"
                else -> "ответ"
            }
        }
        val logs = mutableListOf<String>()
        val agent = engine(llm, logs)

        agent.run("Первое", AgentOptions(sessionId = "session", strategy = ContextStrategy.FACTS))
        val second = agent.run("Второе", AgentOptions(sessionId = "session", strategy = ContextStrategy.FACTS))

        assertEquals(2, second.tokens.facts.size, "прежние факты остались")
        assertTrue(
            llm.requests.last().messages.any { it.content.contains("- бюджет: 5 000 рублей") },
            "память ушла в запрос несмотря на сбой обновления"
        )
        assertTrue(logs.any { it.contains("Память фактов не обновилась") }, "сбой обновления виден в логе")
    }

    /** Без сессии память вести негде: стратегия работает как окно и не тратит служебный вызов. */
    @Test
    fun factsWithoutSessionWorkAsWindow() = runBlocking {
        val llm = StubClient { _, _ -> "ответ" }
        val logs = mutableListOf<String>()

        val result = engine(llm, logs).run(
            "Что дальше?",
            AgentOptions(history = dialog(20), strategy = ContextStrategy.FACTS, windowMessages = 6)
        )

        assertEquals(1, llm.requests.size, "служебного вызова нет")
        assertEquals(0, result.tokens.facts.size)
        assertEquals(14, result.tokens.droppedMessages, "окно всё равно применяется")
        assertTrue(logs.any { it.contains("сессия не названа") }, "причина видна в логе: $logs")
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

        val result = engine(llm, logs).run("Что дальше?", AgentOptions(history = dialog(20), sessionId = "session"))

        assertEquals(1, llm.requests.size)
        assertEquals(22, llm.requests.single().messages.size, "system prompt + 20 сообщений + вопрос")
        assertEquals(0, result.tokens.droppedMessages)
        assertEquals("full", result.tokens.strategy)
        assertTrue(logs.none { it.startsWith("Скользящее окно") }, "своего блока у стратегии нет")
    }

    /** Разбор памяти: модель любит обрамлять JSON текстом — это не должно ломать память. */
    @Test
    fun extractorParsesFencedJsonAndRejectsGarbage() {
        val extractor = FactsExtractor()

        assertEquals(
            listOf(Fact("цель", "собрать ТЗ")),
            extractor.parse("Вот результат:\n```json\n{\"facts\":[{\"key\":\"цель\",\"value\":\"собрать ТЗ\"}]}\n```")?.items
        )
        assertEquals(Facts(), extractor.parse("{\"facts\":[]}"), "пустая память — это не сбой разбора")
        assertNull(extractor.parse("извините, не могу"))
        assertNull(extractor.parse(""))
        assertEquals(
            listOf(Fact("цель", "вторая")),
            extractor.parse("{\"facts\":[{\"key\":\"Цель\",\"value\":\"первая\"},{\"key\":\"цель\",\"value\":\"вторая\"}]}")?.items,
            "повтор ключа обновляет факт, а не плодит записи"
        )
    }
}
