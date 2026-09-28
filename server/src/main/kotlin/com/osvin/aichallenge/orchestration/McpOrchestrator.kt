package com.osvin.aichallenge.orchestration

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.agent.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Оркестратор MCP: по имени вызова находит сервер, выполняет вызов и отвечает результатом.
 *
 * Он **не выбирает инструмент** — выбор делает модель по описаниям. Работа оркестратора ровно
 * в одном: `имя вызова → сервер → выполнение → результат`. Собрать выбор и маршрут в одном месте
 * значило бы вернуть в код знание о том, какой шаг нужен следующим, — то есть зашить workflow,
 * которого здесь и не должно быть.
 *
 * Каждый вызов протоколируется одной строкой с номером запроса, номером вызова, именем, сервером,
 * длительностью и исходом: по этим строкам восстанавливается весь workflow одного запроса
 * человека, включая то, какой сервер выбран (см. §17 задания).
 *
 * Отказы возвращаются агенту структурой `{"success": false, "error": {...}}` с кодом, сервером
 * и причиной: причину модель читает и может исправить вызов, а код отличает «сервера нет» от
 * «инструмента нет» и «сервер не успел». Успешный результат отдаётся как есть, текстом самого
 * инструмента: следующий шаг принимает его аргументом, и обёртка вокруг данных сломала бы
 * передачу между серверами.
 */
class McpOrchestrator(
    private val registry: McpToolRegistry,
    private val onLog: (String) -> Unit = { println("[orchestrator] $it") },
    private val callTimeoutMs: Long = TOOL_CALL_TIMEOUT_MS
) {

    private val requests = AtomicLong()
    private val counters = Mutex()
    private var requestId: String = "1"
    private var iteration = 0

    /**
     * Инструменты для модели: имена в них — квалифицированные.
     *
     * Обёртка вызывает [execute], поэтому маршрут и протокол вызова работают на любом пути,
     * которым модель дойдёт до инструмента, — и в чате приложения, и в демонстрации.
     */
    fun tools(): List<AgentTool> = registry.all().map { registered ->
        AgentTool(
            name = registered.qualifiedName,
            description = "${registered.tool.description} (сервер: ${registered.serverId})",
            parameters = registered.tool.parameters,
            call = { arguments -> execute(registered.qualifiedName, arguments) }
        )
    }

    /**
     * Начинает новый запрос человека: с него считается [iteration] в строках протокола.
     *
     * Номер нужен, чтобы строки разных запросов не смешивались в один workflow: в чате запросы
     * идут подряд, и без номера «вызов 4» нельзя было бы отнести к конкретной просьбе.
     *
     * @return Номер запроса — его же называют строки протокола.
     */
    suspend fun newRequest(): String = counters.withLock {
        requestId = requests.incrementAndGet().toString()
        iteration = 0
        requestId
    }

    /** Выполняет вызов: находит сервер по имени, вызывает инструмент и отвечает результатом. */
    suspend fun execute(toolName: String, arguments: JsonObject): ToolOutcome {
        val call = counters.withLock {
            iteration++
            requestId to iteration
        }
        val registered = registry.find(toolName)
            ?: return failure(call, toolName, null, unknownTool(toolName))

        val startedAt = System.nanoTime()
        val outcome = try {
            withTimeout(callTimeoutMs) { registered.tool.call(arguments) }
        } catch (timedOut: TimeoutCancellationException) {
            log(call, registered, startedAt, success = false)
            return failure(
                call,
                toolName,
                registered.serverId,
                McpOrchestrationError(
                    code = TIMEOUT_CODE,
                    message = "Инструмент не ответил за $callTimeoutMs мс: вызов прекращён."
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log(call, registered, startedAt, success = false)
            return failure(
                call,
                toolName,
                registered.serverId,
                McpOrchestrationError(
                    code = SERVER_ERROR_CODE,
                    message = "Вызов не выполнен: ${error.message ?: error::class.simpleName}"
                )
            )
        }

        log(call, registered, startedAt, success = !outcome.isError)
        if (outcome.isError) return outcome
        if (outcome.text.isBlank()) {
            return failure(
                call,
                toolName,
                registered.serverId,
                McpOrchestrationError(EMPTY_RESULT_CODE, "Инструмент ответил пустым результатом.")
            )
        }
        return outcome
    }

    /**
     * Отказ в виде структуры: код, сервер и причина.
     *
     * Код выбирается по тому, что известно про имя вызова: сервер с таким именем в реестре есть,
     * а инструмента у него нет — это разные причины, и чинятся они по-разному (сервер не поднят
     * или модель позвала то, чего нет).
     */
    private fun unknownTool(toolName: String): McpOrchestrationError {
        val server = toolName.substringBefore('.', missingDelimiterValue = "")
        return if (server.isNotEmpty() && registry.knowsServer(server)) {
            McpOrchestrationError(UNKNOWN_TOOL_CODE, "Сервер $server такого инструмента не объявлял: $toolName.")
        } else {
            McpOrchestrationError(
                SERVER_UNAVAILABLE_CODE,
                "Сервер по имени вызова не подключён: $toolName. Подключённые серверы: " +
                    registry.all().map { it.serverId }.distinct().joinToString()
            )
        }
    }

    /** Ответ-отказ: текст — структура, чтобы модель видела код и сервер, а не только фразу. */
    private fun failure(
        call: Pair<String, Int>,
        toolName: String,
        serverId: String?,
        error: McpOrchestrationError
    ): ToolOutcome = ToolOutcome(
        text = buildJsonObject {
            put("success", JsonPrimitive(false))
            putJsonObject("error") {
                put("code", JsonPrimitive(error.code))
                serverId?.let { put("server", JsonPrimitive(it)) }
                put("tool", JsonPrimitive(toolName))
                put("message", JsonPrimitive(error.message))
            }
            put("iteration", JsonPrimitive(call.second))
        }.toString(),
        isError = true
    )

    /**
     * Строка протокола: запрос, вызов, инструмент, сервер, длительность, исход.
     *
     * Печатается до возврата результата и в обоих исходах — иначе в логе не было бы видно
     * ни отказа, ни того, что вызов вообще был.
     */
    private fun log(call: Pair<String, Int>, registered: RegisteredTool, startedAt: Long, success: Boolean) {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        onLog(
            "requestId=${call.first} iteration=${call.second} tool=${registered.qualifiedName} " +
                "server=${registered.serverId} arguments=${argumentsSummary(arguments!!)} " +
                "duration=${elapsedMs}ms success=$success"
        )
    }

    /**
     * Что ушло инструменту: имена аргументов и их размеры, а не значения.
     *
     * Значения в строку не кладутся: аргументом идёт ответ предыдущего инструмента целиком
     * (документ курсов, текст сводки), и строка протокола превратилась бы в простыню, которую
     * человек не читает. Размеры при этом восстанавливают workflow: по `rates:155` видно, что
     * второму шагу ушёл документ первого, а не пустая строка.
     */
    private fun argumentsSummary(arguments: JsonObject): String =
        if (arguments.isEmpty()) "нет" else arguments.entries.joinToString(",") { (name, value) ->
            "$name:${value.toString().length}"
        }

    companion object {
        /** Код отказа: имя вызова не разобралось в подключённый сервер. */
        const val SERVER_UNAVAILABLE_CODE = "MCP_SERVER_UNAVAILABLE"

        /** Код отказа: сервер есть, инструмента с таким именем у него нет. */
        const val UNKNOWN_TOOL_CODE = "MCP_UNKNOWN_TOOL"

        /** Код отказа: инструмент не ответил за отведённое время. */
        const val TIMEOUT_CODE = "MCP_TOOL_TIMEOUT"

        /** Код отказа: вызов бросил исключение (сервер отключился, транспорт закрылся). */
        const val SERVER_ERROR_CODE = "MCP_CALL_FAILED"

        /** Код отказа: инструмент ответил пустым результатом — дальше передавать нечего. */
        const val EMPTY_RESULT_CODE = "MCP_EMPTY_RESULT"

        /**
         * Срок одного вызова: минута.
         *
         * Инструмент либо отвечает за неё, либо считается неответившим: длинный workflow состоит
         * из вызовов, и один зависший вызов иначе держал бы весь ответ человека — а раунды
         * у агента конечны только по числу, не по времени.
         */
        const val TOOL_CALL_TIMEOUT_MS = 60_000L
    }
}

/** Отказ оркестратора: код для машины, причина для модели. */
private data class McpOrchestrationError(val code: String, val message: String)
