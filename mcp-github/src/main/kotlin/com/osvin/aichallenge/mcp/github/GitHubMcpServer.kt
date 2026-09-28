package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.mcpServer
import com.osvin.aichallenge.mcp.runStdioServer

/**
 * Локальный MCP-сервер GitHub: отдаёт агенту данные пользователя из GitHub по протоколу MCP —
 * его репозитории, один репозиторий по имени и последние коммиты.
 *
 * Смысл отдельного сервера в том же, что и у сервера проекта: доступ к данным даёт инструмент,
 * а не подсказка в промпте. Отличие — данные не наши, а чужие: сервер ходит в GitHub REST API
 * по токену, который ищет на машине цепочка источников ([GitHubCredentials]) — переменная
 * окружения, файл, связка ключей macOS, `gh` или `git`. Токен не спрашивают у человека заново,
 * если готовый доступ уже есть. Поэтому сервер и отдельный процесс: у него своя настройка
 * (адрес API и путь к файлу токена) и свой выход в сеть, а общий модуль связал бы эти два
 * сервера одним fat JAR и одной точкой входа.
 *
 * Про доступ сервер отчитывается сам, инструментом `github_access`: найден ли токен, кто вошёл,
 * какие у токена права и откуда он взят. Это и заменило поле для токена в интерфейсе клиента:
 * вводить его руками там, где машина уже вошла в GitHub, — лишний шаг, а показать, чем именно
 * вошёл сервер, полезно и без него.
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
     * Аргумент `repository`: короткое имя репозитория владельца токена.
     *
     * Объявление одно на оба инструмента: имя одно и то же, а описание расходиться между ними
     * не должно — модель выбирает аргумент по нему и в `get_repository`, и в `get_recent_commits`.
     * Обязательный: владельца в имени нет, а без репозитория инструменту нечего читать.
     */
    val repositoryArgument = DeclaredArgument(
        name = "repository",
        description = "короткое имя репозитория владельца токена (например ai_challenge_task1), " +
            "без владельца — он всегда владелец доступа",
        required = true
    )

    /**
     * Аргумент `limit`: сколько последних коммитов вернуть.
     *
     * Необязательный: «последние коммиты» и без числа понятны, а умолчание названо в описании,
     * поэтому модель выбирает между «сколько есть по умолчанию» и своим числом, а не угадывает.
     * Тип `integer`, а не `string`: это счётчик, и клиент вправе проверить его до вызова.
     */
    val commitsLimitArgument = DeclaredArgument(
        name = "limit",
        description = "сколько последних коммитов вернуть: целое число от " +
            "${COMMITS_LIMIT_RANGE.first} до ${COMMITS_LIMIT_RANGE.last}; без аргумента — " +
            DEFAULT_COMMITS_LIMIT,
        type = "integer",
        required = false
    )

    /**
     * Инструменты сервера, читающие переданный источник данных GitHub.
     *
     * Источник приходит параметром, а не берётся из окружения здесь: список инструментов нужен
     * и консольному режиму (`--list-tools`), где в GitHub никто не ходит, — поэтому объявление
     * отделено от доступа к сети. Доступ к сети и токен появляются только у поднятого сервера.
     */
    fun tools(api: GitHubApi): List<ServerTool<GitHubApi>> = listOf(
        getRepositoriesTool(visibilityArgument),
        githubAccessTool(),
        getRepositoryTool(repositoryArgument),
        getRecentCommitsTool(repositoryArgument, commitsLimitArgument)
    )
}

/**
 * Точка входа MCP-сервера GitHub.
 *
 * Без аргументов — сервер на stdio; с `--list-tools` — список инструментов и выход. Разбор
 * режимов общий у всех MCP-серверов проекта ([runStdioServer]); токен ищется в момент вызова
 * инструмента, поэтому список инструментов печатается и без него — и без доступа к GitHub тоже.
 *
 * Цепочка источников собирается один раз на процесс: найденный токен переживает вызовы
 * ([GitHubCredentials] кэширует успех), а неудачный поиск повторяется на каждом вызове — чтобы
 * человек, сохранивший токен после первого отказа, не поднимал сервер заново.
 */
fun main(args: Array<String>) {
    val config = GitHubConfig.fromEnvironment()
    val api = GitHubApiImpl(config, GitHubCredentials(defaultSources(config)))
    val tools = GitHubMcpServer.tools(api)
    runStdioServer(args, GitHubMcpServer.NAME, GitHubMcpServer.VERSION, tools) {
        mcpServer(GitHubMcpServer.NAME, GitHubMcpServer.VERSION, api, tools)
    }
}
