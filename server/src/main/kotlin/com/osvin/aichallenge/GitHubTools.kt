package com.osvin.aichallenge

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.McpSession
import com.osvin.aichallenge.mcp.McpTool
import com.osvin.aichallenge.mcp.McpToolArgument
import com.osvin.aichallenge.mcp.agentTools
import com.osvin.aichallenge.mcp.github.GitHubMcpServer
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import com.osvin.aichallenge.models.GitHubAccessReport
import com.osvin.aichallenge.models.GitHubCallResponse
import com.osvin.aichallenge.models.GitHubConnection
import com.osvin.aichallenge.models.GitHubTool
import com.osvin.aichallenge.models.GitHubToolArgument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
 * Доступ к GitHub сервер приложения не ищет: ни `GITHUB_TOKEN`, ни файлов, ни связки ключей —
 * всё это перебирает сам сервер инструментов, а сюда состояние приходит отчётом его же
 * инструмента `github_access` ([GitHubAccessReport]). Так секрет остаётся в одном месте:
 * сервер приложения его не знает вовсе, поэтому он не может попасть ни в лог, ни в ответ,
 * ни в текст исключения. Ввод токена в интерфейсе убран вместе с этим — доступ берётся
 * готовым с машины, и вводить человеку нечего. Отвергнут и device flow с OAuth-редиректом:
 * он заставил бы интерфейс вести отдельный разговор с GitHub ради того, что уже лежит
 * на машине, и добавил бы кнопке состояние, которого у неё иначе нет.
 *
 * Подключение и доступ разведены нарочно: [connect] поднимает процесс и спрашивает у него
 * состояние, и «процесс поднялся, а доступа нет» — это успешное подключение с
 * `authorized=false` и подсказкой в `hint`, а не отказ. Отказом ([GitHubConnect.Rejected])
 * остаётся только то, что соединения не вышло: тогда у человека нет ни инструментов, ни
 * отчёта о доступе, и чинить нужно не доступ, а процесс.
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
     * Последний отчёт о доступе, полученный от сервера инструментов при подключении.
     *
     * Хранится, а не спрашивается заново на каждый снимок: `github_access` — вызов процесса
     * инструментов, и снимок, который интерфейс читает при каждой перерисовке, не должен
     * стоить обращения к чужому процессу. Живёт ровно столько же, сколько сессия: [disconnect]
     * и [close] его забывают вместе с ней, а в [connection] он попадает только у живой сессии.
     */
    private var access: GitHubAccessReport = NO_ACCESS

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
     * Доступ не спрашивается у человека и не берётся из окружения приложения: сразу после
     * рукопожатия у сервера инструментов вызывается его же `github_access`, и полученный
     * отчёт ложится в снимок. Так «подключено» и «есть доступ» остаются разными ответами:
     * доступ не найден — это `authorized=false` с подсказкой [GitHubConnection.hint], и
     * подключение от этого не перестаёт быть удавшимся.
     *
     * Повторное нажатие — та же кнопка, что «проверить снова»: доступ у живого соединения
     * читается заново, а не отдаётся из памяти. Доступ мог появиться (человек вошёл в GitHub,
     * положил токен в файл, задал переменную) или пропасть, и снимок обязан говорить о том,
     * что есть сейчас. Второго процесса при этом не поднимается: переспрашивается тот же.
     *
     * Отказ подключения возвращается причиной ([GitHubConnect.Rejected]), а не исключением:
     * причину показывает интерфейс, и падать из-за неё запрос не должен.
     */
    suspend fun connect(): GitHubConnect = mutex.withLock {
        val live = session?.takeIf { it.isRunning }
        if (live != null) {
            try {
                access = readAccess(modelTools)
                logAccess(access)
                return GitHubConnect.Connected(snapshot(live))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Процесс не ответил на проверку доступа — живой сессией это считать нельзя.
                // Причину пишем в лог и поднимаем соединение заново, как будто его и не было:
                // возвращать снимок умершего процесса значило бы врать о состоянии.
                onLog("GitHub: сессия не ответила на проверку доступа: ${error.message}")
            }
        }
        // Прежняя сессия к этому месту мертва (живую и ответившую вернули выше): закрываем её
        // транспорт, чтобы не копить потоки, но сбой закрытия подключение не отменяет.
        closeSession()

        try {
            val opened = openMcpSession(config, onServerStderr = { onLog("github: $it") })
            // Все три выборки — до записи в поля: рукопожатие прошло, и если какая-то не
            // придёт, процесс надо погасить, а не оставить сиротой. Запись в [session] идёт
            // последней, поэтому сбой на любой из выборок не оставляет «подключённую» сессию
            // без процесса.
            val listed: List<McpTool>
            val liveTools: List<AgentTool>
            val report: GitHubAccessReport
            try {
                listed = opened.listTools()
                liveTools = opened.agentTools()
                report = readAccess(liveTools)
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
            access = report
            onLog(
                "инструменты GitHub (${opened.serverName} ${opened.serverVersion}): " +
                    listed.joinToString { it.name }
            )
            logAccess(report)
            GitHubConnect.Connected(snapshot(opened))
        } catch (cancelled: CancellationException) {
            // Отмена — не отказ сервера инструментов: запрос пользователя оборван,
            // и запоминать это как «GitHub недоступен» было бы неправдой.
            throw cancelled
        } catch (error: Exception) {
            session = null
            declared = emptyList()
            modelTools = emptyList()
            access = NO_ACCESS
            onLog("MCP-сервер GitHub не подключился: ${error.message}")
            GitHubConnect.Rejected(error.message ?: "MCP-сервер GitHub не подключился")
        }
    }

    /**
     * Строка о доступе в лог: имя владельца, права и пометка источника.
     *
     * Только пометки: значения токена не знает и сам сервер приложения, поэтому попасть
     * в лог ему неоткуда — а имя владельца и место, откуда взят доступ, нужны, чтобы по логу
     * было видно, чем именно вошёл сервер инструментов.
     */
    private fun logAccess(report: GitHubAccessReport) {
        onLog(
            if (report.authorized) {
                "доступ к GitHub есть: ${report.login ?: "владелец не назван"}" +
                    " (${report.source ?: "источник не назван"})"
            } else {
                "доступа к GitHub нет: ${report.hint ?: "причина не названа"}"
            }
        )
    }

    /**
     * Спрашивает у подключённого сервера, есть ли доступ к GitHub, его же инструментом.
     *
     * Вызов идёт через [AgentTool.call] — тот же путь, что у модели и у ручного вызова, —
     * поэтому отдельного способа поговорить с сервером не появляется. Инструмент отвечает
     * отчётом в JSON, и разбирается он здесь: сервер приложения не выдумывает состояние
     * доступа сам, а переносит то, что сказал владелец инструмента.
     *
     * Неразобравшийся ответ и отсутствие инструмента — не исключения, а тот же «доступа нет»
     * с причиной: соединение состоялось, и человеку нужно знать, что доступ не подтверждён,
     * а не получить отказ подключения из-за формы чужого ответа. Исключением остаётся только
     * сбой самого вызова — тогда процесс инструментов мёртв, и вызывающий закрывает его
     * сессию и честно отвечает отказом подключения.
     *
     * @param tools Инструменты, объявленные подключённым сервером.
     */
    private suspend fun readAccess(tools: List<AgentTool>): GitHubAccessReport {
        val probe = tools.firstOrNull { it.name == GITHUB_ACCESS_TOOL }
            ?: return GitHubAccessReport(
                authorized = false,
                hint = "сервер инструментов не объявил $GITHUB_ACCESS_TOOL, поэтому о доступе " +
                    "к GitHub спросить не у кого: проверьте версию сервера инструментов"
            )
        val outcome = probe.call(JsonObject(emptyMap()))
        if (outcome.isError) {
            return GitHubAccessReport(authorized = false, hint = outcome.text)
        }
        return try {
            ACCESS_JSON.decodeFromString<GitHubAccessReport>(outcome.text)
        } catch (error: Exception) {
            GitHubAccessReport(
                authorized = false,
                hint = "ответ $GITHUB_ACCESS_TOOL не разобрался (${error.message}): " +
                    "состояние доступа неизвестно"
            )
        }
    }

    /**
     * Закрывает сессию инструментов: человек нажал «отключить» или сервер приложения
     * остановился.
     *
     * Без этого процесс MCP-сервера пережил бы остановку приложения: транспорт закрывается
     * вместе с процессом-родителем не всегда, и в системе оставался бы висеть сервер,
     * которому больше некому отвечать. Отчёт о доступе забывается вместе с сессией: он
     * описывал доступ, который был у этого процесса, и пережить его не должен.
     */
    suspend fun disconnect(): GitHubConnection = mutex.withLock {
        closeSession()
        declared = emptyList()
        modelTools = emptyList()
        access = NO_ACCESS
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
     * а забыть надо то же самое: сессию, инструменты и отчёт о доступе.
     */
    suspend fun close() {
        mutex.withLock {
            closeSession()
            declared = emptyList()
            modelTools = emptyList()
            access = NO_ACCESS
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

    /**
     * Снимок живой сессии: имя и версия сервера из рукопожатия, разобранные схемы и доступ,
     * который сервер инструментов назвал при подключении.
     *
     * Отчёт о доступе переносится целиком и без пересказа: его поля описывают состояние,
     * а не решение сервера приложения, и переписанные здесь они разошлись бы с тем, что
     * инструмент ответил.
     */
    private fun snapshot(live: McpSession): GitHubConnection = GitHubConnection(
        connected = true,
        authorized = access.authorized,
        login = access.login,
        scopes = access.scopes,
        scopesReported = access.scopesReported,
        source = access.source,
        hint = access.hint,
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

        /**
         * Имя инструмента, которым сервер инструментов рассказывает о доступе к GitHub.
         *
         * Строка, а не ссылка на константу модуля инструментов: это имя провода — по нему
         * сервер приложения находит инструмент в списке объявленных, — и оно должно читаться
         * там же, где проверяется, что инструмент вообще объявлен. Скопированным оно быть
         * не может: если владелец переименует инструмент, `authorized` честно станет false
         * с подсказкой, а не соврёт о доступе, которого никто не подтверждал.
         */
        const val GITHUB_ACCESS_TOOL = "github_access"

        /** Причина отказа, когда вызов пришёл без соединения: вызывать не у кого. */
        private const val NO_CONNECTION =
            "GitHub не подключён: подключитесь кнопкой, тогда инструменты станут доступны"

        /**
         * Состояние доступа, которого нет: сессии нет — спрашивать о доступе некого.
         *
         * Умолчание поля [access] и то, что снимок получает без сессии: `hint` здесь пуст
         * нарочно — «доступ не найден» и «не подключались» разные состояния, и подсказка
         * о поиске доступа человеку нужна только после подключения, когда он её увидит
         * на экране доступа, а не на кнопке подключения.
         */
        private val NO_ACCESS = GitHubAccessReport(authorized = false)
    }
}

/**
 * Разбор ответа инструмента `github_access`.
 *
 * Отдельный разбор, а не общий JSON сервера: `authorized` здесь обязателен, и ответ без него
 * — это не отчёт о доступе, а что-то другое. Незнакомые поля пропускаются: сервер инструментов
 * может добавлять к отчёту подробности, и падать из-за них сервер приложения не должен.
 */
private val ACCESS_JSON = Json { ignoreUnknownKeys = true }

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
