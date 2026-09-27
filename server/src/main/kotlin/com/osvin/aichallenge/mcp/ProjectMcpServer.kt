package com.osvin.aichallenge.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantStore
import com.osvin.aichallenge.agent.ProfileStore
import com.osvin.aichallenge.agent.UserProfile
import com.osvin.aichallenge.invariants.JsonFileInvariantStore
import com.osvin.aichallenge.profile.JsonFileProfileStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Инструмент проекта, объявленный по MCP: имя, описание, аргументы и чтение данных.
 *
 * Спецификация одна на всю жизнь инструмента: по ней сервер регистрирует инструмент
 * и по ней же проверяется то, что увидел клиент ([ProjectMcpServer.tools]). Отдельного
 * списка «для клиента» нет — он разошёлся бы с зарегистрированным молча.
 *
 * @param name Имя для вызова.
 * @param description Что инструмент отдаёт — словами, по которым его выбирает модель.
 * @param arguments Аргументы вызова.
 * @param read Что вернуть: текст ответа инструмента.
 */
data class ProjectTool(
    val name: String,
    val description: String,
    val arguments: List<ProjectToolArgument>,
    val read: suspend (profileId: String, invariants: InvariantStore, profiles: ProfileStore) -> String
)

/**
 * Аргумент инструмента.
 *
 * @param name Имя в вызове.
 * @param description Что аргумент значит — это описание уезжает в схему инструмента.
 * @param type Тип по JSON Schema.
 * @param required Аргумент обязателен: без него вызов не собирается.
 */
data class ProjectToolArgument(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = false
)

/**
 * Локальный MCP-сервер проекта: отдаёт ассистенту его собственные данные по протоколу MCP.
 *
 * Смысл сервера в том, что **доступ к данным проекта даёт не подсказка в промпте, а
 * инструмент**: инварианты и профиль перечисляются клиенту списком с описаниями и схемой
 * аргументов, и вызываются отдельным запросом протокола. Поэтому один и тот же набор
 * инструментов увидят и наш агент, и любой сторонний MCP-клиент — без правок в них.
 *
 * Инструменты только читают: правила объявляет человек, профиль правит его шторка в
 * интерфейсе, и превращать чтение ассистентом в запись было бы подменой решения человека.
 * Читаются те же хранилища, что и у сервера приложения (`JsonFileInvariantStore`,
 * `JsonFileProfileStore`), поэтому ответ инструмента и ответ маршрута не расходятся.
 */
object ProjectMcpServer {

    /** Имя сервера в рукопожатии: по нему видно, кто ответил на подключение. */
    const val NAME = "ai-challenge-project"

    /** Версия сервера в рукопожатии. */
    const val VERSION = "1.0.0"

    /** Формат ответа инструментов: с отступами — его читает и человек, и модель. */
    private val JSON = Json { prettyPrint = true }

    /**
     * Адрес данных в аргументах: чьи правила и профиль читать.
     *
     * Необязателен: в приложении пользователь один, и его данные — это данные по умолчанию
     * ([DEFAULT_PROFILE]), поэтому требовать идентификатор в каждом вызове значило бы
     * заставлять модель повторять то, что и так известно.
     */
    private val PROFILE_ARGUMENT = ProjectToolArgument(
        name = "profile_id",
        description = "чей набор читать; без него — данные профиля по умолчанию"
    )

    /**
     * Инструменты проекта: что сервер объявляет клиенту и что отдаёт на вызов.
     *
     * Пустое хранилище — не ошибка вызова: правила проекта действуют с первого запуска
     * ([Invariant.DEFAULT]), поэтому нетронутое хранилище отдаёт именно их, а не пустоту.
     * Подставляются они при чтении, а не записью в хранилище: инструмент ничего не меняет.
     */
    val tools: List<ProjectTool> = listOf(
        ProjectTool(
            name = "list_invariants",
            description = "Правила проекта (инварианты), которые ассистент не имеет права нарушать: " +
                "вид правила и его формулировка.",
            arguments = listOf(PROFILE_ARGUMENT),
            read = { profileId, invariants, _ ->
                JSON.encodeToString(invariants.get(profileId) ?: Invariant.DEFAULT)
            }
        ),
        ProjectTool(
            name = "user_profile",
            description = "Профиль пользователя: объявленные им предпочтения — кто он, на чём пишет " +
                "и каким хочет видеть ответ.",
            arguments = listOf(PROFILE_ARGUMENT),
            read = { profileId, _, profiles ->
                JSON.encodeToString(profiles.get(profileId) ?: UserProfile.DEFAULT)
            }
        ),
    )

    /**
     * Сервер с объявленными инструментами: осталось подключить транспорт.
     *
     * Уведомлений об изменении списка нет (`listChanged = false`): инструменты объявлены
     * кодом и на живом соединении не появляются, поэтому обещать клиенту уведомления
     * было бы обещанием, которого сервер не сдержит.
     */
    fun create(invariants: InvariantStore, profiles: ProfileStore): Server {
        val server = Server(
            serverInfo = Implementation(NAME, VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )
        tools.forEach { tool ->
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.schema(),
            ) { request -> tool.call(request, invariants, profiles) }
        }
        return server
    }

    /** Схема аргументов инструмента: из неё клиент берёт имена, типы и обязательность. */
    private fun ProjectTool.schema(): ToolSchema = ToolSchema(
        properties = buildJsonObject {
            arguments.forEach { argument ->
                put(
                    argument.name,
                    buildJsonObject {
                        put("type", argument.type)
                        put("description", argument.description)
                    }
                )
            }
        },
        required = arguments.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }
    )

    /**
     * Вызов инструмента: аргумент профиля — единственный вход, поэтому читается одним способом.
     *
     * Сбой чтения возвращается результатом с `isError`, а не ошибкой протокола: так его
     * видит модель и может поправить вызов ([CallToolResult]), тогда как ошибка протокола
     * до модели не доходит.
     */
    private suspend fun ProjectTool.call(
        request: CallToolRequest,
        invariants: InvariantStore,
        profiles: ProfileStore
    ): CallToolResult {
        val profileId = request.params.arguments?.get(PROFILE_ARGUMENT.name).asText()
            ?: DEFAULT_PROFILE
        val text = try {
            read(profileId, invariants, profiles)
        } catch (error: Exception) {
            return CallToolResult(
                content = listOf(TextContent("инструмент $name не смог прочитать данные: ${error.message}")),
                isError = true
            )
        }
        return CallToolResult(content = listOf(TextContent(text)))
    }

    /** Текст аргумента вызова; null — аргумента нет или это не строка. */
    private fun JsonElement?.asText(): String? = when (val argument = this) {
        is JsonPrimitive -> argument.content.takeIf { it.isNotEmpty() }
        else -> null
    }
}

/**
 * Точка входа локального MCP-сервера: работает на stdio, пока клиент не закроет соединение.
 *
 * Ни порта, ни регистрации нет — сервер поднимает клиент процессом (как и большинство
 * MCP-серверов), поэтому запуск сводится к команде, а конец работы — к концу входного
 * потока: закрывая соединение, клиент закрывает и сервер.
 */
fun main(): Unit = runBlocking {
    val protocolOutput = stdoutForProtocol()
    logsToStderr()
    val server = ProjectMcpServer.create(
        invariants = JsonFileInvariantStore(JsonFileInvariantStore.defaultFile()),
        profiles = JsonFileProfileStore(JsonFileProfileStore.defaultFile())
    )
    val closed = CompletableDeferred<Unit>()
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOutput.asSink().buffered()
    )
    transport.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
    server.close()
}

/**
 * Забирает стандартный вывод под протокол: печатать в него больше нельзя никому.
 *
 * Кадр JSON-RPC — это строка в общем выводе, поэтому одна чужая строка (`println` из
 * библиотеки, приветствие логгера) ломает кадр: клиент разбирает её как сообщение
 * протокола и теряет ответ. Забрать вывод у процесса целиком надёжнее, чем надеяться,
 * что печатать в него станут только мы: печать уводится в поток ошибок, а транспорт
 * получает прежний вывод — тот самый, который видит клиент.
 */
private fun stdoutForProtocol(): java.io.PrintStream {
    val protocol = System.out
    System.setOut(System.err)
    return protocol
}

/**
 * Логи сервера уводит в поток ошибок: стандартный вывод занят протоколом.
 *
 * Уровень поднят с `trace` до `warn` из `logback.xml`: у клиента есть свой разбор протокола,
 * поэтому в поток ошибок сервер пишет то, что случилось, а не каждый шаг рукопожатия —
 * шаги SDK видны по требованию, правкой уровня. Свои сообщения ([PROJECT_LOGGER]) остаются
 * на `info`: их пишем мы, и их в потоке ошибок ищут.
 */
private fun logsToStderr() {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = ConsoleAppender<ILoggingEvent>().apply {
        name = "MCP-STDERR"
        target = "System.err"
        encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = "%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n"
            start()
        }
        this.context = context
        start()
    }
    root.detachAndStopAllAppenders()
    root.level = Level.WARN
    root.addAppender(appender)
    context.getLogger(PROJECT_LOGGER).level = Level.INFO
}

/** Логи проекта: их пишем мы, поэтому они видны в потоке ошибок сервера. */
private const val PROJECT_LOGGER = "com.osvin.aichallenge"
