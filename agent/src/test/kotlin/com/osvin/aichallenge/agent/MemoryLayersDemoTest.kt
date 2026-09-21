package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Три типа памяти на одной сцене: что попадает в каждый тип, что из этого уходит в запрос
 * и как это меняет ответ агента.
 *
 * Сцена — сбор ТЗ: пользователь называет себя (долговременная память), задачу с числами
 * и сроками (рабочая память) и решения, которые переживут эту задачу (тоже долговременная).
 * Рабочая и долговременная память живут по профилю, поэтому их записи видит и другой чат;
 * краткосрочная — это сообщения самого диалога, и в новом чате её нет.
 * Этапы делят один прогон: первый наполняет память, второй смотрит, что из этого видно
 * в запросе и в ответе, третий и четвёртый — явный выбор: какой тип памяти назвала модель
 * и что сохраняет пользователь. Живой режим (`-Pdemo.live=1`) отвечает настоящая модель,
 * без флага — подставленные ответы, а числа остаются те же.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MemoryLayersDemoTest {

    /** Этап 1: один диалог — что попало в каждый слой и сколько это стоило. */
    @Test
    fun stage1_layersOfOneDialog() = runBlocking {
        stage("Этап 1: раскладка по типам памяти (окно $LAYER_WINDOW сообщений, стратегия «память агента»)")

        val llm = layerClient()
        val logs = mutableListOf<String>()
        val agent = demoAgent(llm, logs)
        val dialog = mutableListOf<ChatMessage>()
        var serviceTokens = 0

        SCENE.forEach { message ->
            val result = agent.run(
                message,
                options(
                    dialog.toList(),
                    maxTokens = LAYER_ANSWER_BUDGET,
                    sessionId = LAYER_SESSION,
                    strategy = ContextStrategy.MEMORY,
                    windowMessages = LAYER_WINDOW
                )
            )
            serviceTokens += result.tokens.memory.updateTokens
            dialog += ChatMessage("user", message)
            dialog += ChatMessage("assistant", result.reply)
        }
        LayerStores.dialog = dialog

        val probe = agent.run(
            PROBE_TZ,
            options(
                dialog,
                maxTokens = LAYER_ANSWER_BUDGET,
                sessionId = LAYER_SESSION,
                strategy = ContextStrategy.MEMORY,
                windowMessages = LAYER_WINDOW
            )
        )
        serviceTokens += probe.tokens.memory.updateTokens

        val memory = probe.tokens.memory
        log("сообщений сцены: ${SCENE.size}, в запросе — окно ${memory.shortTermMessages}, отброшено ${memory.shortTermDropped}")
        log("")
        log("рабочая память (${memory.working.size} записей, ${memory.workingTokens} ток.): данные этой задачи, общие для всех чатов")
        memory.working.forEach { log("- ${describe(it)}") }
        log("")
        log("долговременная память (${memory.longTerm.size} записей, ${memory.longTermTokens} ток.): переживёт этот диалог")
        memory.longTerm.forEach { log("- ${describe(it)}") }
        log("")
        log("отклонено записей: ${memory.rejected}, вытеснено: ${memory.evicted}")
        log("на обновление памяти ушло $serviceTokens ток. за ${SCENE.size + 1} ходов (служебный вызов на каждый ход)")
        log("")
        log("ответ на «собери ТЗ»: ${probe.reply}")
        if (demoOnLiveApi) {
            log("важных деталей в ответе: ${Detail.scoreOf(probe.reply)} из ${Detail.size}")
        } else {
            log("ответ подставлен: сравнить ответы конфигураций даёт только живой режим (-Pdemo.live=1)")
        }
        logs.lastOrNull { it.startsWith("Память агента") }?.let { log(""); log(it) }

        assertTrue(
            memory.working.isNotEmpty() && memory.working.all { it.layer == MemoryLayer.WORKING.wire },
            "в рабочей памяти только записи её типа: ${memory.working}"
        )
        assertTrue(
            memory.longTerm.isNotEmpty() && memory.longTerm.all { it.layer == MemoryLayer.LONG_TERM.wire },
            "в долговременной памяти только записи её типа: ${memory.longTerm}"
        )
        assertTrue(
            memory.working.any { it.value.contains("300 000") },
            "числа задачи остаются в рабочей памяти: ${memory.working}"
        )
        assertTrue(
            memory.longTerm.any { it.value.contains("Иван") },
            "профиль пользователя уходит в долговременную память: ${memory.longTerm}"
        )
        assertTrue(memory.shortTermDropped > 0, "краткосрочный слой ограничен окном: ${memory.shortTermDropped}")
        assertTrue(serviceTokens > 0, "обновление памяти стоит токенов: $serviceTokens")
    }

    /** Этап 2: влияние слоёв на ответ — один вопрос, три конфигурации памяти. */
    @Test
    fun stage2_effectOfLayersOnAnswer() = runBlocking {
        stage("Этап 2: влияние памяти на ответ (вопрос: «$PROBE_PROFILE»)")

        // Конфигурация 1: окно со своими пустыми слоями — ни прошлой истории, ни записей памяти.
        val windowLlm = layerClient(NO_MEMORY)
        val windowLogs = mutableListOf<String>()
        val window = demoAgent(windowLlm, windowLogs, working = InMemoryMemoryStore(), longTerm = InMemoryMemoryStore())
            .run(
                PROBE_PROFILE,
                options(
                    emptyList(),
                    maxTokens = LAYER_ANSWER_BUDGET,
                    sessionId = "demo-window-${LAYER_SESSION}",
                    strategy = ContextStrategy.SLIDING_WINDOW,
                    windowMessages = LAYER_WINDOW
                )
            )
        val windowRequest = windowLlm.requests.last()

        // Конфигурация 2: память агента — новый чат с пустой историей, но оба слоя памяти
        // наполнены прошлым диалогом: они живут по профилю, а не по чату.
        val newChatLlm = layerClient(NO_MEMORY)
        val newChat = demoAgent(newChatLlm, mutableListOf())
            .run(
                PROBE_PROFILE,
                options(
                    emptyList(),
                    maxTokens = LAYER_ANSWER_BUDGET,
                    sessionId = "demo-new-${LAYER_SESSION}",
                    strategy = ContextStrategy.MEMORY,
                    windowMessages = LAYER_WINDOW
                )
            )
        val newChatRequest = newChatLlm.requests.last()

        // Конфигурация 3: тот же чат, где задача уже собрана: те же слои памяти и история.
        val sameChatLlm = layerClient(NO_MEMORY)
        val sameChat = demoAgent(sameChatLlm, mutableListOf())
            .run(
                PROBE_PROFILE,
                options(
                    LayerStores.dialog,
                    maxTokens = LAYER_ANSWER_BUDGET,
                    sessionId = LAYER_SESSION,
                    strategy = ContextStrategy.MEMORY,
                    windowMessages = LAYER_WINDOW
                )
            )
        val sameChatRequest = sameChatLlm.requests.last()

        log("")
        log(
            String.format(
                Locale.ROOT,
                "%-26s %6s %6s %8s %9s %8s %9s",
                "конфигурация", "сообщ", "вход", "рабочая", "долговрем.", "в запросе", "в ответе"
            )
        )
        listOf(
            "окно, пустые слои" to (window to windowRequest),
            "память, новый чат" to (newChat to newChatRequest),
            "память, тот же чат" to (sameChat to sameChatRequest)
        ).forEach { (title, pair) ->
            val (result, request) = pair
            log(
                String.format(
                    Locale.ROOT,
                    "%-26s %6d %6d %8d %9d %8d %9s",
                    title,
                    request.messages.size,
                    result.tokens.promptTokens,
                    result.tokens.memory.working.size,
                    result.tokens.memory.longTerm.size,
                    Detail.inRequest(request).size,
                    answerScore(result.reply)
                )
            )
        }
        log("")
        log("рабочая память живёт по профилю: у нового чата с пустой историей она та же, что у чата с историей")
        log("«в запросе» — сколько важных сведений сцены видно модели; «в ответе» — сколько из них она повторила")
        log("«в ответе» пусто без -Pdemo.live=1: подставленный ответ от контекста не зависит")
        if (demoOnLiveApi) {
            log("")
            log("окно: ${window.reply}")
            log("память, новый чат: ${newChat.reply}")
            log("память, тот же чат: ${sameChat.reply}")
        }

        val windowContents = windowRequest.messages.map { it.content }
        val newChatContents = newChatRequest.messages.map { it.content }
        assertTrue(
            windowContents.none { it.startsWith("Долговременная память") },
            "стратегия окна долговременный слой не ведёт: $windowContents"
        )
        assertTrue(
            newChatContents.any { it.startsWith("Долговременная память") && it.contains("Иван") },
            "в новом чате виден профиль из прошлого диалога: $newChatContents"
        )
        assertTrue(
            newChatContents.any { it.startsWith("Рабочая память задачи") },
            "рабочая память прошлой задачи уходит в запрос и из нового чата: $newChatContents"
        )
        // Рабочая память живёт по профилю, а не по чату: чат с пустой историей видит те же
        // записи, что и чат, в котором задача собиралась.
        assertEquals(
            sameChat.tokens.memory.working,
            newChat.tokens.memory.working,
            "записи рабочей памяти у нового чата и у чата с историей одни и те же"
        )
        assertEquals(0, newChat.tokens.memory.shortTermMessages, "истории прошлого диалога в новом чате нет")
        assertEquals(0, window.tokens.memory.shortTermMessages, "у чата со своими слоями краткосрочная память пуста")
        assertTrue(
            Detail.inRequest(newChatRequest).size > Detail.inRequest(windowRequest).size,
            "память профиля доносит до модели то, чего в запросе окна нет ни из истории, ни из его слоёв: " +
                "${Detail.inRequest(newChatRequest).map { it.title }} против ${Detail.inRequest(windowRequest).map { it.title }}"
        )
    }

    /** Этап 3: тип записи называет модель, а код принимает только типы из задания. */
    @Test
    fun stage3_typeFromModelDecidesWhereDataGoes() = runBlocking {
        stage("Этап 3: какая запись в какой тип попала (подставленный ответ модели)")

        // Этап про типы памяти, а не про модель: ответ подставлен всегда, иначе он неповторяем.
        val llm = LayerRecordingClient(CannedMemoryClient(TYPES_JSON))
        val logs = mutableListOf<String>()
        val agent = demoAgent(llm, logs, working = InMemoryMemoryStore(), longTerm = InMemoryMemoryStore())

        val result = agent.run(
            "Решение по хранилищу — Room, дальше собираем ТЗ; ещё мне нравится джаз.",
            options(
                emptyList(),
                maxTokens = LAYER_ANSWER_BUDGET,
                sessionId = "demo-routing",
                strategy = ContextStrategy.MEMORY
            )
        )

        log("")
        log("модель вернула ${TYPES_JSON.count { it == '{' } - 1} записей — куда какая попала:")
        TYPES_JSON_RECORDS.forEach { (layer, value) ->
            val memoryLayer = MemoryLayer.ofWire(layer)
            val place = memoryLayer?.let { "→ ${layerName(it)}" } ?: "→ отклонена: типа нет в задании"
            log("- «$value» типом $layer $place")
        }
        log("")
        log("типы памяти: ${MemoryLayer.entries.joinToString(", ") { "${it.title} (${it.wire})" }}")
        log("отклонено записей: ${result.tokens.memory.rejected}")
        log("")
        log("рабочая память: ${result.tokens.memory.working.joinToString("; ") { it.value }}")
        log("долговременная память: ${result.tokens.memory.longTerm.joinToString("; ") { it.value }}")

        assertEquals(
            listOf(MemoryRecord("working", "цель — собрать ТЗ")),
            result.tokens.memory.working,
            "запись рабочего типа осталась в рабочей памяти"
        )
        assertEquals(
            listOf(
                MemoryRecord("long_term", "музыка — джаз"),
                MemoryRecord("long_term", "хранилище — Room")
            ),
            result.tokens.memory.longTerm,
            "записи долговременного типа ушли в долговременную память"
        )
        assertEquals(1, result.tokens.memory.rejected, "типа нет в задании — запись отклонена, а не угадана")
    }

    /** Этап 4: явный выбор пользователя — какую фразу и в какой тип памяти сохранить. */
    @Test
    fun stage4_userChoosesWhereToSave() = runBlocking {
        stage("Этап 4: явная запись в память — тип выбирает пользователь, фраза пишется одной строкой")

        val working = InMemoryMemoryStore()
        val longTerm = InMemoryMemoryStore()
        val writer = MemoryWriter(working, longTerm)
        val session = "demo-explicit"

        log("типы памяти (по этому каталогу рисуется выбор в интерфейсе):")
        writer.layers(session).types.forEach { type ->
            val write = if (type.writable) "→ можно писать" else "→ пишется сам сообщениями"
            log("- ${type.title} (${type.hint}), ${type.layer} $write")
        }
        log("")

        listOf(
            MemoryLayer.LONG_TERM.wire to "стиль — отвечать кратко",
            MemoryLayer.WORKING.wire to "цель — собрать ТЗ",
            MemoryLayer.SHORT_TERM.wire to "пользователь поздоровался",
            "настроение" to "боевой"
        ).forEach { (layer, value) ->
            val write = writer.remember(session, layer, value)
            val place = when (write) {
                is MemoryWrite.Written -> "→ ${layerName(write.layer)}"
                is MemoryWrite.Rejected -> "→ отклонено: ${write.reason}"
            }
            log("- «$value» типом $layer $place")
        }
        log("")
        log("после записи:")
        writer.layers(session).working.forEach { log("- рабочая: ${describe(it)}") }
        writer.layers(session).longTerm.forEach { log("- долговременная: ${describe(it)}") }
        log("")
        val goal = "цель — собрать ТЗ"
        log("забываем «$goal»: ${forgetLine(writer.forget(session, MemoryLayer.WORKING.wire, goal))}")
        log("забываем «боевой»: ${forgetLine(writer.forget(session, "настроение", "боевой"))}")

        assertEquals(
            listOf(MemoryRecord("long_term", "стиль — отвечать кратко")),
            writer.layers(session).longTerm,
            "фраза ушла в названный тип памяти"
        )
        assertTrue(writer.layers(session).working.isEmpty(), "забытая запись рабочую память очистила")
        assertEquals(
            MemoryWriter.NOT_WRITABLE,
            assertIs<MemoryWrite.Rejected>(
                writer.remember(session, MemoryLayer.SHORT_TERM.wire, "пользователь поздоровался")
            ).reason,
            "краткосрочную память пишет сам чат"
        )
        assertEquals(
            MemoryWriter.UNKNOWN_LAYER,
            assertIs<MemoryWrite.Rejected>(writer.remember(session, "настроение", "боевой")).reason,
            "типа нет в задании — память не меняется"
        )
        assertEquals(
            MemoryWriter.UNKNOWN_LAYER,
            assertIs<MemoryForget.Rejected>(writer.forget(session, "настроение", "боевой")).reason,
            "и забыть такую запись нельзя"
        )
    }
}

/** Общее для этапов: слои памяти и диалог первого этапа — этапы делят один прогон. */
private object LayerStores {
    val working = InMemoryMemoryStore()
    val longTerm = InMemoryMemoryStore()
    var dialog: List<ChatMessage> = emptyList()
}

/** Сцена демонстрации: профиль пользователя, данные задачи и решения по ней. */
private val SCENE = listOf(
    "Меня зовут Иван, я техлид на Android. Отвечай кратко, без вступлений.",
    "Собираем ТЗ на приложение учёта личных расходов.",
    "Бюджет проекта — 300 000 рублей, срок — 6 недель.",
    "Регистрации быть не должно: данные хранятся только на устройстве.",
    "Хранилище — Room: проект уже на Kotlin Multiplatform.",
    "Графики рисуем на Compose, сторонние библиотеки не тянем.",
    "Сводку за неделю показываем раз в неделю по пятницам.",
    "Итого: собираем ТЗ на первую версию."
)

/** Вопрос про саму задачу: по ответу видно, что стратегия сохранила из начала диалога. */
private const val PROBE_TZ =
    "Собери короткое ТЗ: цель, бюджет, срок, ограничения и принятые решения. Только то, что мы зафиксировали."

/** Вопрос про пользователя: ответить на него можно только долговременной памятью. */
private const val PROBE_PROFILE = "Что ты обо мне знаешь и что мы решили по прошлому проекту?"

/** Размер окна: маленький, чтобы сцена действительно упиралась в предел краткосрочного слоя. */
private const val LAYER_WINDOW = 4

/**
 * Бюджет ответа демонстрации: у модели с рассуждениями они тратят тот же бюджет,
 * что и текст, поэтому он взят с запасом (в дне 10 маленький бюджет давал пустой ответ).
 */
private const val LAYER_ANSWER_BUDGET = 32_768

/** Сессия демонстрации: по ней живёт краткосрочная память — история диалога; оба хранимых слоя живут по профилю. */
private const val LAYER_SESSION = "demo-memory"

/** Важные сведения сцены: по ним видно, что дошло до модели и что вернулось в ответе. */
private enum class Detail(val title: String, val markers: List<String>) {
    NAME("имя пользователя", listOf("иван")),
    ROLE("роль и платформа", listOf("техлид", "android")),
    BUDGET("бюджет 300 000 ₽", listOf("300 000", "300000")),
    TERM("срок 6 недель", listOf("6 недель", "шесть недель")),
    NO_SIGNUP("без регистрации", listOf("регистрац")),
    DEVICE("данные на устройстве", listOf("на устройстве")),
    STORAGE("решение — Room", listOf("room")),
    COMPOSE("графики на Compose", listOf("compose"));

    companion object {
        val size: Int get() = entries.size

        /** Сколько важных сведений сцены видно модели в этом запросе. */
        fun inRequest(request: DeepSeekRequest): List<Detail> {
            val text = request.messages.joinToString("\n") { it.content.lowercase(Locale.ROOT) }
            return entries.filter { detail -> detail.markers.any { text.contains(it) } }
        }

        /** Сколько важных сведений сцены вернулось в ответе. */
        fun scoreOf(answer: String): Int {
            val text = answer.lowercase(Locale.ROOT)
            return entries.count { detail -> detail.markers.any { text.contains(it) } }
        }
    }
}

/** Детали в ответе считаются только в живом режиме: подставленный ответ от контекста не зависит. */
private fun answerScore(reply: String): String =
    if (demoOnLiveApi) Detail.scoreOf(reply).toString() else "—"

/** Название слоя для короткой строки демонстрации. */
private fun layerName(layer: MemoryLayer): String = when (layer) {
    MemoryLayer.SHORT_TERM -> "краткосрочная"
    MemoryLayer.WORKING -> "рабочая"
    MemoryLayer.LONG_TERM -> "долговременная"
}

/** Строка записи памяти: текст и тип, в котором она лежит. */
private fun describe(record: MemoryRecord): String {
    val layer = MemoryLayer.ofWire(record.layer)?.let(::layerName) ?: record.layer
    return "${record.value} [$layer]"
}

/** Строка забывания: что осталось в рабочей памяти или почему запрос не принят. */
private fun forgetLine(forget: MemoryForget): String = when (forget) {
    is MemoryForget.Forgotten -> forget.layers.working.joinToString("; ") { describe(it) }
        .ifEmpty { "рабочая память пуста" }

    is MemoryForget.Rejected -> "отказ: ${forget.reason}"
}

/** Агент демонстрации: общие слои памяти по умолчанию, лог — в собираемые строки. */
private fun demoAgent(
    llm: LlmClient,
    logs: MutableList<String>,
    working: MemoryStore = LayerStores.working,
    longTerm: MemoryStore = LayerStores.longTerm
) = LlmAgent(llm, logger = AgentLogger { logs += it }, workingMemory = working, longTermMemory = longTerm)

/** Транспорт демонстрации: живой API — по явному флагу, иначе подставленные ответы. */
private fun layerClient(memoryReply: String = EXTRACTED): LayerRecordingClient =
    LayerRecordingClient(if (demoOnLiveApi) liveClient() else CannedMemoryClient(memoryReply))

/** Транспорт с записью запросов: по ним видно, что именно ушло в модель. */
private class LayerRecordingClient(private val delegate: LlmClient) : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        return delegate.complete(request)
    }
}

/**
 * Подставленный транспорт: на служебный запрос памяти отвечает готовым JSON,
 * на запрос сцены — подставным ответом. Токены считает тот же счётчик, что и агент.
 */
private class CannedMemoryClient(private val memoryReply: String = EXTRACTED) : LlmClient {

    val requests = mutableListOf<DeepSeekRequest>()

    override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
        requests += request
        val answer = if (request.messages.last().content.contains("Новые сообщения диалога:")) {
            memoryReply
        } else {
            CANNED_LAYER_REPLY
        }
        val promptTokens = EstimatingTokenCounter.countPrompt(request.messages)
        return DeepSeekResponse(
            choices = listOf(DeepSeekResponse.Choice(ChatMessage("assistant", answer), "stop")),
            usage = DeepSeekResponse.Usage(
                promptTokens = promptTokens,
                completionTokens = CANNED_REPLY_TOKENS,
                totalTokens = promptTokens + CANNED_REPLY_TOKENS,
                completionTokensDetails = DeepSeekResponse.Usage.CompletionTokensDetails(CANNED_REASONING_TOKENS)
            )
        )
    }
}

/** Записи, которые «извлекла» модель: тип каждой она называет сама. */
private const val EXTRACTED =
    """{"memory":[{"layer":"long_term","value":"имя — Иван"},{"layer":"long_term","value":"роль — техлид на Android"},""" +
        """{"layer":"working","value":"цель — учёт личных расходов"},{"layer":"working","value":"бюджет — 300 000 рублей"},""" +
        """{"layer":"working","value":"срок — 6 недель"},""" +
        """{"layer":"working","value":"ограничение — без регистрации, данные на устройстве"},""" +
        """{"layer":"working","value":"сводка — раз в неделю по пятницам"},""" +
        """{"layer":"long_term","value":"решение — хранилище Room"},{"layer":"long_term","value":"графики — на Compose"},""" +
        """{"layer":"long_term","value":"знание — проект на Kotlin Multiplatform"}]}"""

/** Пустое извлечение: на вопрос «что ты обо мне знаешь» запоминать нечего. */
private const val NO_MEMORY = """{"memory":[]}"""

/** Ответ с записями двух типов памяти и одной записью типа, которого в задании нет. */
private const val TYPES_JSON =
    """{"memory":[{"layer":"long_term","value":"музыка — джаз"},""" +
        """{"layer":"working","value":"цель — собрать ТЗ"},""" +
        """{"layer":"long_term","value":"хранилище — Room"},""" +
        """{"layer":"настроение","value":"боевой"}]}"""

/** Записи из [TYPES_JSON] в том порядке, в каком их вернула модель: тип и текст. */
private val TYPES_JSON_RECORDS = listOf(
    "long_term" to "музыка — джаз",
    "working" to "цель — собрать ТЗ",
    "long_term" to "хранилище — Room",
    "настроение" to "боевой"
)

/** Подставленный ответ сцены: те же важные детали, что просит вопрос. */
private const val CANNED_LAYER_REPLY =
    "ТЗ: цель — учёт личных расходов; бюджет 300 000 рублей; срок 6 недель; " +
        "без регистрации, данные на устройстве; хранилище — Room; графики на Compose."
