package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.mcpServer
import com.osvin.aichallenge.mcp.runStdioServer

/**
 * Локальный MCP-сервер GitHub: отдаёт агенту репозитории пользователя по протоколу MCP.
 *
 * Смысл отдельного сервера в том же, что и у сервера проекта: доступ к данным даёт инструмент,
 * а не подсказка в промпте. Отличие — данные не наши, а чужие: сервер ходит в GitHub REST API
 * по токену из окружения. Поэтому он и отдельный процесс: у него своя настройка (`GITHUB_TOKEN`)
 * и свой выход в сеть, а общий модуль связал бы эти два сервера одним fat JAR и одной точкой
 * входа.
 *
 * Устройство общее с другими MCP-серверами проекта: объявление инструмента ([ServerTool]),
 * регистрация ([mcpServer]), соединение и режимы запуска ([runStdioServer]).
 */
object GitHubMcpServer {

    /** Имя сервера в рукопожатии: по нему видно, кто ответил на подключение. */
    const val NAME = "ai-challenge-github"

    /** Версия сервера в рукопожатии. */
    const val VERSION = "1.0.0"

    /**
     * Имя точки входа на JVM: его называют те, кто поднимает сервер процессом.
     *
     * `:server` берёт отсюда `java -cp … <mainClass>`, чтобы не держать второй литерал,
     * который разошёлся бы с именем файла при переименовании.
     */
    const val MAIN_CLASS = "com.osvin.aichallenge.mcp.github.GitHubMcpServerKt"

    /**
     * Аргумент `visibility`: какие репозитории вернуть.
     *
     * Необязательный, по умолчанию `all`: спрашивать «покажи всё» отдельным словом значило бы
     * заставлять модель повторять то, что и так известно. Допустимые значения уезжают в схему
     * инструмента как `enum`, поэтому модель выбирает значение, а не угадывает формулировку.
     */
    val visibilityArgument = DeclaredArgument(
        name = "visibility",
        description = "какие репозитории вернуть: $VISIBILITY_ALL — все, $VISIBILITY_PUBLIC — " +
            "только публичные, $VISIBILITY_PRIVATE — только приватные; без аргумента — все",
        values = VISIBILITY_VALUES,
        required = false
    )

    /**
     * Инструменты сервера, читающие переданный источник репозиториев.
     *
     * Источник приходит параметром, а не берётся из окружения здесь: список инструментов нужен
     * и консольному режиму (`--list-tools`), где в GitHub никто не ходит, — поэтому объявление
     * отделено от доступа к сети.
     */
    fun tools(api: GitHubApi): List<ServerTool<GitHubApi>> = listOf(getRepositoriesTool(visibilityArgument))
}

/**
 * Точка входа MCP-сервера GitHub.
 *
 * Без аргументов — сервер на stdio; с `--list-tools` — список инструментов и выход. Разбор
 * режимов общий у всех MCP-серверов проекта ([runStdioServer]); токен читается в момент вызова
 * инструмента, поэтому список инструментов печатается и без него.
 */
fun main(args: Array<String>) {
    val api = GitHubApiImpl(GitHubConfig.fromEnvironment())
    val tools = GitHubMcpServer.tools(api)
    runStdioServer(args, GitHubMcpServer.NAME, GitHubMcpServer.VERSION, tools) {
        mcpServer(GitHubMcpServer.NAME, GitHubMcpServer.VERSION, api, tools)
    }
}
