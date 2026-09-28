package com.osvin.aichallenge.mcp

import com.osvin.aichallenge.agent.DEFAULT_PROFILE
import com.osvin.aichallenge.agent.Invariant
import com.osvin.aichallenge.agent.InvariantStore
import com.osvin.aichallenge.agent.ProfileStore
import com.osvin.aichallenge.agent.UserProfile
import com.osvin.aichallenge.invariants.JsonFileInvariantStore
import com.osvin.aichallenge.profile.JsonFileProfileStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Данные, без которых инструменты проекта не работают: правила проекта и профили.
 *
 * Не инструменты и не сервер, а то, что приходит в обработчик вызова: объявление инструмента
 * общее для консоли и сервера, а хранилища есть только у поднятого сервера.
 */
data class ProjectData(val invariants: InvariantStore, val profiles: ProfileStore)

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
 *
 * Устройство сервера общее с другими MCP-серверами проекта: объявление инструмента
 * ([ServerTool]), регистрация ([mcpServer]), соединение и режимы запуска ([runStdioServer]).
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
    private val PROFILE_ARGUMENT = DeclaredArgument(
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
    val tools: List<ServerTool<ProjectData>> = listOf(
        profileTool(
            name = "list_invariants",
            description = "Правила проекта (инварианты), которые ассистент не имеет права нарушать: " +
                "вид правила и его формулировка."
        ) { profileId, data ->
            JSON.encodeToString(data.invariants.get(profileId) ?: Invariant.DEFAULT)
        },
        profileTool(
            name = "user_profile",
            description = "Профиль пользователя: объявленные им предпочтения — кто он, на чём пишет " +
                "и каким хочет видеть ответ."
        ) { profileId, data ->
            JSON.encodeToString(data.profiles.get(profileId) ?: UserProfile.DEFAULT)
        },
    )

    /** Сервер с объявленными инструментами, читающими переданные хранилища. */
    fun create(invariants: InvariantStore, profiles: ProfileStore): Server =
        mcpServer(NAME, VERSION, ProjectData(invariants, profiles), tools)

    /**
     * Инструмент, у которого вход один — чей набор читать.
     *
     * Способ чтения у обоих инструментов одинаковый, поэтому аргумент разбирается здесь,
     * а не в каждом инструменте: иначе «profile_id» читался бы двумя разными способами,
     * и умолчание могло бы разойтись.
     */
    private fun profileTool(
        name: String,
        description: String,
        read: (profileId: String, data: ProjectData) -> String
    ): ServerTool<ProjectData> = ServerTool(
        name = name,
        description = description,
        arguments = listOf(PROFILE_ARGUMENT)
    ) { data, request ->
        val profileId: String = request.argument(PROFILE_ARGUMENT.name) ?: DEFAULT_PROFILE
        CallToolResult(content = listOf(TextContent(read(profileId, data))))
    }
}

/**
 * Точка входа локального MCP-сервера проекта.
 *
 * Без аргументов — сервер на stdio; с `--list-tools` — список инструментов и выход.
 * Разбор режимов общий у всех MCP-серверов проекта ([runStdioServer]).
 */
fun main(args: Array<String>): Unit = runStdioServer(
    args = args,
    name = ProjectMcpServer.NAME,
    version = ProjectMcpServer.VERSION,
    tools = ProjectMcpServer.tools
) {
    ProjectMcpServer.create(
        invariants = JsonFileInvariantStore(JsonFileInvariantStore.defaultFile()),
        profiles = JsonFileProfileStore(JsonFileProfileStore.defaultFile())
    )
}
