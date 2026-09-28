package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.McpTool
import com.osvin.aichallenge.mcp.McpToolArgument
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.github.GitHubConfig
import com.osvin.aichallenge.mcp.github.GitHubMcpServer
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import com.osvin.aichallenge.models.GitHubCallResponse
import com.osvin.aichallenge.models.GitHubConnection
import com.osvin.aichallenge.models.GitHubTool
import com.osvin.aichallenge.models.GitHubToolArgument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Инструменты агента из MCP-сервера GitHub: одна долгая сессия, которую открывает человек.
 *
 * Сессия общая на все запросы, а не своя у каждого: MCP-сервер — это отдельный процесс на
 * JVM, и поднимать его на каждый вопрос пользователя значило бы платить за его запуск и
 * рукопожатие в каждом ответе. Но поднимает его теперь человек кнопкой подключения, а не
 * первый запрос к модели: без сессии [tools] отдаёт пустой список, и чат отвечает без
 * инструментов. Ленивое подключение убрано нарочно — по кнопке видно, что GitHub подключён,
 * какие инструменты доступны и чем они отвечают, а подъём процесса «где-то в глубине» на
 * первом вопросе показывал бы ту же картину только после нескольких запросов.
 *
 * Инструменты запоминаются вместе с сессией: у подключённого сервера набор неизменен, а
 * переспрашивается он только при подключении — то есть ровно тогда, когда мог измениться.
 * Для снимка [connection] хранится ещё и разобранная форма схемы ([McpTool]): из неё уезжают
 * типы, обязательность и допустимые значения аргументов, которых в [AgentTool] нет.
 *
 * Неудачное подключение — не исключение, а [GitHubConnect.Rejected] с причиной: причину
 * показывает интерфейс, и она не должна прерывать ответ на запрос. По той же причине
 * недоступный сервер инструментов не отменяет ответ чата: инструменты дополняют ответ, а не
 * являются его условием.
 *
 * Токен GitHub уезжает серверу инструментов окружением процесса ([GitHubConfig.TOKEN_ENV]),
 * а не кодом: сервер приложения его не читает вовсе, поэтому он не может попасть ни в лог,
 * ни в ответ. Он живёт только в приватном поле [token] — и то лишь чтобы не спрашивать его
 * заново при переподключении; в лог идёт только факт, откуда токен взят.
 *
 * @param config Как запустить сервер инструментов: по умолчанию — класс из своего classpath,
 *        как и у любого локального MCP-сервера проекта.
 * @param onLog Куда писать строки о подключении и о логах сервера инструментов.
 */
class GitHubTools(
    private val config: McpServerConfig = localMcpServerConfig(GITHUB_SERVER_MAIN),
    private val onLog: (String) -> Unit = { println("[agent] $it") }
) {

    private val mutex = Mutex()
    private var session: McpSession? = null

    /** Инструменты для модели и ручного вызова: их `call` уже связан с живой сессией. */
    private var modelTools: List<AgentTool> = emptyList()

    /** Разобранные схемы инструментов: из них собран снимок [connection] с аргументами. */
    private var declared: List<McpTool> = emptyList()

    /**
     * Принятый токен — только здесь и нигде больше.
     *
     * Нужен, чтобы переподключение после падения процесса не заставляло человека вводить
     * токен заново, если ни аргумент, ни окружение его не дали. Никогда не логируется и
     * не возвращается в ответах.
     */
    private var token: String? = null

    /**
     * Снимок состояния: подключено ли, кто ответил и какие инструменты доступны.
     *
     * Под замком: запросы к серверу идут параллельно, и снимок не должен попасть между
     * закрытием и подъёмом сессии — иначе он показал бы половину одного и половину другого.
     */
    suspend fun connection(): GitHubConnection = mutex.withLock {
        session?.takeIf { it.isRunning }?.let { snapshot(it) }
            ?: GitHubConnection(connected = false)
    }

    /**
     * Подключается к серверу инструментов GitHub и возвращает состояние соединения.
     *
     * При живом соединении второго процесса не поднимается: возвращается текущий снимок —
     * кнопка может нажаться дважды, и платить за это лишним процессом незачем. Мёртвая
     * сессия (процесс уже умер) закрывается и заменяется новой: транспорт, оставшийся от
     * неё, иначе копился бы на каждое переподключение.
     *
     * Отказ подключения возвращается причиной ([GitHubConnect.Rejected]), а не исключением:
     * причину показывает интерфейс, и падать из-за неё запрос не должен.
     *
     * @param token Токен из интерфейса; null или пусто — взять `GITHUB_TOKEN` из окружения
     *        (или из памяти прошлого подключения, если и там пусто).
     */
    suspend fun connect(token: String?): GitHubConnect = mutex.withLock {
        session?.takeIf { it.isRunning }?.let { return GitHubConnect.Connected(snapshot(it)) }
        // Прежняя сессия к этому месту мертва (живую вернули выше): закрываем её транспорт,
        // чтобы не копить потоки, но сбой закрытия подключение не отменяет.
        closeSession()

        val provided = token?.takeIf { it.isNotBlank() }
        val fromEnvironment = config.env[GitHubConfig.TOKEN_ENV]?.takeIf { it.isNotBlank() }
            ?: System.getenv(GitHubConfig.TOKEN_ENV)?.takeIf { it.isNotBlank() }
        val remembered = this.token
        val accepted = provided ?: fromEnvironment ?: remembered
        this.token = accepted
        onLog(
            when {
                provided != null -> "GitHub: токен принят из интерфейса"
                fromEnvironment != null -> "GitHub: токен принят из окружения"
                remembered != null -> "GitHub: токен принят из памяти прошлого подключения"
                else -> "GitHub: токен не задан — сервер инструментов откажет на вызове"
            }
        )

        val connectConfig = if (provided != null) {
            config.copy(env = config.env + (GitHubConfig.TOKEN_ENV to provided))
        } else {
            config
        }

        try {
            val opened = openMcpSession(connectConfig, onServerStderr = { onLog("github: $it") })
            // Обе выборки — до записи в поля: рукопожатие прошло, и если список не придёт,
            // процесс надо погасить, а не оставить сиротой. Запись в [session] идёт последней,
            // поэтому сбой на любой из выборок не оставляет «подключённую» сессию без процесса.
            val listed: List<McpTool>
            val liveTools: List<AgentTool>
            try {
                listed = opened.listTools()
                liveTools = opened.agentTools()
            } catch (error: Throwable) {
                try {
                    opened.close()
                } catch (closing: Exception) {
                    onLog("GitHub: не удалось погасить процесс после сбоя: ${closing.message}")
                }
                throw error
            }
            session = opened
            declared = listed
            modelTools = liveTools
            onLog(
                "инструменты GitHub (${opened.serverName} ${opened.serverVersion}): " +
                    listed.joinToString { it.name }
            )
            GitHubConnect.Connected(snapshot(opened))
        } catch (cancelled: CancellationException) {
            // Отмена — не отказ сервера инструментов: запрос пользователя оборван,
            // и запоминать это как «GitHub недоступен» было бы неправдой.
            throw cancelled
        } catch (error: Exception) {
            session = null
            declared = emptyList()
            modelTools = emptyList()
            onLog("MCP-сервер GitHub не подключился: ${error.message}")
            GitHubConnect.Rejected(error.message ?: "MCP-сервер GitHub не подключился")
        }
    }

    /**
     * Закрывает сессию инструментов: человек нажал «отключить» или сервер приложения
     * остановился.
     *
     * Без этого процесс MCP-сервера пережил бы остановку приложения: транспорт закрывается
     * вместе с процессом-родителем не всегда, и в системе оставался бы висеть сервер,
     * которому больше некому отвечать. Токен при этом забывается вместе с сессией: он нужен
     * был только подключённому серверу инструментов.
     */
    suspend fun disconnect(): GitHubConnection = mutex.withLock {
        closeSession()
        declared = emptyList()
        modelTools = emptyList()
        token = null
        GitHubConnection(connected = false)
    }

    /**
     * Инструменты на этот запрос: пустой список — соединения нет.
     *
     * Ленивого подъёма здесь нет: процесс поднимает человек кнопкой, а запрос к модели только
     * пользуется тем, что уже есть. Так недоступное соединение не отменяет ответ — модель
     * отвечает без инструментов, — и одновременно не запускает чужой процесс за спиной
     * у человека.
     */
    suspend fun tools(): List<AgentTool> = mutex.withLock {
        if (session?.isRunning == true) modelTools else emptyList()
    }

    /**
     * Прямой вызов инструмента по имени: то, что нажал человек в списке инструментов.
     *
     * Инструмент ищется среди объявленных подключённым сервером и зовётся его же `call`, —
     * отдельного пути вызова мимо списка нет, поэтому ручной вызов и вызов моделью идут
     * одним кодом. Отказ самого инструмента остаётся ответом ([GitHubCallResponse.failed]):
     * вызов дошёл, инструмент словами сказал, почему данных нет. Недоступны только два
     * случая: сессии нет ([GitHubCall.Rejected]) и имени нет в списке ([GitHubCall.UnknownTool]).
     */
    suspend fun call(name: String, arguments: JsonObject): GitHubCall = mutex.withLock {
        if (session?.isRunning != true) return GitHubCall.Rejected(NO_CONNECTION)
        val tool = modelTools.firstOrNull { it.name == name }
            ?: return GitHubCall.UnknownTool(
                "инструмент $name не объявлен сервером: доступны " +
                    modelTools.joinToString(", ") { it.name }
            )
        val outcome = tool.call(arguments)
        GitHubCall.Answered(
            GitHubCallResponse(name = name, result = outcome.text, failed = outcome.isError)
        )
    }

    /**
     * Закрывает сессию инструментов: сервер приложения зовёт это при остановке.
     *
     * Отдельно от [disconnect], потому что у остановки нет ответа, который надо вернуть,
     * а забыть надо то же самое: сессию, инструменты и токен.
     */
    suspend fun close() {
        mutex.withLock {
            closeSession()
            declared = emptyList()
            modelTools = emptyList()
            token = null
        }
    }

    /**
     * Гасит сессию, не превращая сбой закрытия в отказ операции.
     *
     * Транспорт SDK закрывается в несколько шагов, и его сбой не значит, что сессия жива:
     * процесс уже получил конец входного потока. Поэтому причина уходит в лог, а вызывающий
     * (кнопка «отключить» или остановка сервера) доводит своё дело до конца — иначе нажатие
     * возвращало бы 500 при фактически отключённом соединении.
     */
    private suspend fun closeSession() {
        try {
            session?.close()
        } catch (cancelled: CancellationException) {
            // Отмена — не сбой закрытия: её должен увидеть вызывающий.
            throw cancelled
        } catch (error: Exception) {
            onLog("GitHub: сессия закрылась с ошибкой: ${error.message}")
        }
        session = null
    }

    /** Снимок живой сессии: имя и версия сервера из рукопожатия плюс разобранные схемы. */
    private fun snapshot(live: McpSession): GitHubConnection = GitHubConnection(
        connected = true,
        server = live.serverName.ifBlank { null },
        version = live.serverVersion.ifBlank { null },
        tools = declared.map { it.toGitHubTool() }
    )

    companion object {

        /**
         * Точка входа сервера инструментов — та же, что названа в манифесте его JAR.
         *
         * Имя берётся у самого модуля ([GitHubMcpServer.MAIN_CLASS]), а не пишется здесь
         * вторым литералом: переименование файла иначе разошлось бы с тем, что поднимает
         * сервер приложения, и падало бы это только на живом запуске.
         */
        const val GITHUB_SERVER_MAIN = GitHubMcpServer.MAIN_CLASS

        /** Причина отказа, когда вызов пришёл без соединения: вызывать не у кого. */
        private const val NO_CONNECTION =
            "GitHub не подключён: подключитесь кнопкой, тогда инструменты станут доступны"
    }
}

/**
 * Чем закончилось подключение к серверу инструментов GitHub.
 *
 * Отказ здесь — обычный результат, а не исключение: причину показывает интерфейс, и она
 * не должна прерывать ни запрос чата, ни ответ на нажатие кнопки.
 */
sealed interface GitHubConnect {

    /** Соединение установлено: [connection] — его снимок. */
    data class Connected(val connection: GitHubConnection) : GitHubConnect

    /** Соединение не установлено: [reason] — почему, словами сервера. */
    data class Rejected(val reason: String) : GitHubConnect
}

/**
 * Чем закончился прямой вызов инструмента.
 *
 * Отказ инструмента — не этот тип: он приходит внутри [Answered] с `failed=true`, потому что
 * вызов состоялся. Здесь — случаи, когда вызова не было вовсе, и они различаются кодом
 * ответа: нет соединения — 409, нет такого имени — 404.
 */
sealed interface GitHubCall {

    /** Инструмент ответил: [response] — его текст и признак отказа. */
    data class Answered(val response: GitHubCallResponse) : GitHubCall

    /** Соединения нет: инструментов, которые можно было бы позвать, тоже нет. */
    data class Rejected(val reason: String) : GitHubCall

    /** Инструмента с таким именем подключённый сервер не объявлял. */
    data class UnknownTool(val reason: String) : GitHubCall
}

/** Схема MCP-инструмента в форму, которую показывает и разбирает интерфейс. */
private fun McpTool.toGitHubTool(): GitHubTool = GitHubTool(
    name = name,
    description = description,
    arguments = arguments.map { it.toGitHubToolArgument() }
)

/**
 * Аргумент сервера в форму интерфейса: переносится всё, что интерфейсу нужно для формы
 * ручного вызова, — имя, пояснение, тип, обязательность и допустимые значения. Ничего не
 * выводятся заново: эти поля уже разобраны из схемы сервера ([McpToolArgument]), и вторая
 * разборка здесь разошлась бы с первой.
 */
private fun McpToolArgument.toGitHubToolArgument(): GitHubToolArgument = GitHubToolArgument(
    name = name,
    description = description,
    type = type,
    required = required,
    values = values
)
