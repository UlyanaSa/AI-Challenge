package com.osvin.aichallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Кто подключается: имя и версия клиента уходят серверу на рукопожатии.
 *
 * Не «ai-challenge» вообще, а агент проекта: в логах сервера видно, кто именно пришёл
 * за инструментами, — а к одному серверу их может ходить несколько.
 */
val MCP_CLIENT_INFO: Implementation = Implementation(name = "ai-challenge-agent", version = "1.0.0")

/**
 * Как запустить MCP-сервер: команда, аргументы и добавки к окружению.
 *
 * Транспорт дня — stdio: клиент поднимает процесс сервера и говорит с ним кадрами
 * JSON-RPC по его стандартным потокам. Так устроено большинство MCP-серверов (их
 * запускают как дочерний процесс, а не как службу на порту), поэтому адреса у сервера
 * нет вовсе — есть команда запуска. Именно она и есть вся настройка подключения:
 * `npx -y @modelcontextprotocol/server-filesystem /tmp` или `java -cp … ProjectMcpServerKt`.
 *
 * @param command Исполняемый файл сервера.
 * @param args Аргументы командной строки.
 * @param env Переменные окружения поверх унаследованных.
 */
data class McpServerConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

/**
 * Аргумент инструмента: что за аргумент и как его понимать.
 *
 * Разбирается из схемы сервера ([McpTool.inputSchema]), а не объявляется у нас: имена,
 * типы и обязательность — слово сервера, и наша копия разошлась бы с ним молча.
 *
 * @param name Имя аргумента — то, что уезжает в вызове.
 * @param type Тип значения по схеме сервера; null — сервер его не назвал.
 * @param required Аргумент обязателен: без него вызов отвергнут.
 * @param description Пояснение сервера: что аргумент значит. Нужно не только модели,
 *        но и человеку — по нему он понимает, что подставить в ручном вызове.
 * @param values Допустимые значения, если сервер объявил их списком (`enum`). Пустой
 *        список — значения не ограничены схемой. Хранится рядом с аргументом, а не
 *        разбирается заново читателем схемы: выбор из списка нужен интерфейсу, а схема
 *        на проводе — документ, который читают ещё и глазами.
 */
data class McpToolArgument(
    val name: String,
    val type: String?,
    val required: Boolean,
    val description: String? = null,
    val values: List<String> = emptyList()
)

/**
 * Инструмент MCP в том виде, в каком его видит агент.
 *
 * @param name Имя для вызова.
 * @param description Что инструмент делает, словами сервера.
 * @param inputSchema Схема аргументов как есть: её увидят и человек, и модель.
 */
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject
) {
    /** Аргументы вызова: имена и требования из схемы — то, по чему собирается вызов. */
    val arguments: List<McpToolArgument> = inputSchema.readArguments()
}

/**
 * Живая сессия с MCP-сервером: пока она открыта, процесс сервера работает.
 *
 * Владение процессами здесь, а не у SDK: транспорт MCP работает с потоками и про
 * процесс ничего не знает, поэтому гасить его обязан тот, кто запустил, — [close].
 *
 * @param process Процесс сервера, поднятый при подключении.
 * @param client Протокольный клиент SDK: рукопожатие уже пройдено, запросы доступны.
 */
class McpSession internal constructor(
    private val process: Process,
    val client: Client
) {

    /** Имя сервера из рукопожатия: кто ответил на подключение. */
    val serverName: String get() = client.serverVersion?.name ?: ""

    /** Версия сервера из рукопожатия. */
    val serverVersion: String get() = client.serverVersion?.version ?: ""

    /** Объявлены ли сервером инструменты: без этой возможности список спрашивать не у чего. */
    val supportsTools: Boolean get() = client.serverCapabilities?.tools != null

    /** Работает ли серверный процесс: после [close] — нет. */
    val isRunning: Boolean get() = process.isAlive

    /**
     * Список инструментов сервера — то, ради чего сессия открывается на этом шаге.
     *
     * Спрашивается у сервера каждый раз, а не кэшируется: список может меняться на живом
     * соединении (сервер о новых инструментах уведомляет), и копия на нашей стороне
     * рано или поздно разошлась бы с настоящей.
     */
    suspend fun listTools(): List<McpTool> = client.listTools().tools.map { it.toMcpTool() }

    /**
     * Закрывает сессию и гасит процесс сервера.
     *
     * Порядок важен: сначала закрывается транспорт (кадры протокола и потоки), и лишь
     * потом процесс — иначе сервер остался бы висеть, не увидев конца входного потока.
     */
    suspend fun close() {
        client.close()
        process.destroy()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    private companion object {

        /** Сколько ждать добровольного выхода сервера, прежде чем гасить его силой. */
        const val SHUTDOWN_SECONDS = 5L
    }
}

/**
 * Подключается к MCP-серверу и проходит рукопожатие.
 *
 * Возвращается готовая сессия: сервер представился, его возможности известны, можно
 * спрашивать инструменты. Подключение — не мгновенное: сервер поднимается процессом,
 * обменивается приветствием и лишь потом отвечает на запросы, поэтому на рукопожатие
 * отведён срок ([handshakeTimeout]) — молчащий сервер не должен вешать вызывающего.
 *
 * Схема владения: процесс поднимает эта функция, поэтому если рукопожатие не удалось,
 * процесс гасится здесь же — иначе неудачное подключение оставляло бы за собой сироту.
 *
 * @param config Команда запуска сервера.
 * @param handshakeTimeout Сколько ждать рукопожатия.
 * @param onServerStderr Строки, которые сервер написал в свой поток ошибок: сервер
 *        отвечает за диагностику, а общий вывод отдан протоколу, поэтому логи приходят сюда.
 *        Вызывается по строке: поток читается куском, и вызывающий получает строки, а не куски.
 * @throws McpConnectionException Сервер не поднялся или рукопожатие не прошло.
 */
suspend fun openMcpSession(
    config: McpServerConfig,
    handshakeTimeout: Duration = 30.seconds,
    onServerStderr: (String) -> Unit = {}
): McpSession {
    val process = try {
        ProcessBuilder(listOf(config.command) + config.args)
            .apply { environment().putAll(config.env) }
            .start()
    } catch (error: Exception) {
        throw McpConnectionException("сервер MCP не запустился: ${config.command}", error)
    }

    // Стандартный вывод сервера — это канал протокола: всё остальное сервер пишет в stderr.
    val client = Client(MCP_CLIENT_INFO)
    try {
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
            classifyStderr = { read ->
                read.lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotEmpty() }
                    .forEach(onServerStderr)
                // Строки сервера не логируем повторно: их уже получил вызывающий.
                StdioClientTransport.StderrSeverity.IGNORE
            },
        )
        withTimeout(handshakeTimeout) { client.connect(transport) }
    } catch (error: Throwable) {
        client.close()
        process.destroy()
        throw McpConnectionException(
            "сервер MCP ${config.command} не прошёл рукопожатие: ${error.message}",
            error
        )
    }
    return McpSession(process, client)
}

/** Подключение к MCP-серверу не состоялось: процесс не поднялся или рукопожатие не прошло. */
class McpConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Команда запуска локального сервера проекта: тот же процесс, что и сервер приложения.
 *
 * Сервер проекта — обычная Kotlin-программа в модуле `:server`, поэтому клиенту
 * для подключения к нему не нужен ни менеджер пакетов, ни внешний сервер: классы
 * берутся из того же classpath ([classpath]), что и у вызывающего. Так же запускается
 * и сервер инструментов GitHub — он тоже локальный.
 *
 * @param mainClass Класс с точкой входа сервера.
 * @param classpath Где искать классы; по умолчанию — classpath текущего процесса.
 * @param env Переменные окружения поверх унаследованных: так дочернему процессу передают
 *        токен и адрес API. Именно «поверх»: `ProcessBuilder` начинает с окружения родителя,
 *        поэтому переменные, которые нужны и родителю, и серверу инструментов (адрес
 *        подставного GitHub в проверках), не приходится перечислять второй раз.
 */
fun localMcpServerConfig(
    mainClass: String,
    classpath: String = System.getProperty("java.class.path").orEmpty(),
    env: Map<String, String> = emptyMap()
): McpServerConfig = McpServerConfig(
    command = File(System.getProperty("java.home"), "bin/java").absolutePath,
    args = listOf("-cp", classpath, mainClass),
    env = env
)

/** Схема сервера глазами агента: аргументы верхнего уровня и отметка об обязательности. */
private fun JsonObject.readArguments(): List<McpToolArgument> {
    val required = (this["required"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
        .orEmpty()
    val properties = this["properties"]?.jsonObject ?: return emptyList()
    return properties.map { (name, schema) ->
        val described = schema as? JsonObject
        McpToolArgument(
            name = name,
            type = described?.get("type").asText(),
            required = name in required,
            description = described?.get("description").asText(),
            // Значения ограничены только тогда, когда сервер объявил их списком:
            // отсутствие `enum` — это «любое значение типа», а не пустой выбор.
            values = (described?.get("enum") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        )
    }
}

/** Текст значения схемы; null — значения нет или оно не строка. */
private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * Схема инструмента обратно в JSON: у SDK она разобрана в тип, а на нашей стороне
 * схема — тот же документ, что был на проводе, потому что её читают человек и модель.
 */
private val SCHEMA_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Инструмент SDK в нашу форму: список сервера и то, что видит агент, — один и тот же список. */
private fun Tool.toMcpTool(): McpTool = McpTool(
    name = name,
    description = description,
    inputSchema = SCHEMA_JSON.encodeToJsonElement(ToolSchema.serializer(), inputSchema).jsonObject
)
