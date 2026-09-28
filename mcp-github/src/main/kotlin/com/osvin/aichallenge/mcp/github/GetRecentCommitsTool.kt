package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Сколько коммитов вернуть, если аргумент не назван: свежих коммитов хватает без уточнений. */
internal const val DEFAULT_COMMITS_LIMIT = 10

/**
 * Границы аргумента `limit` — одна правда для объявления в схеме и для проверки вызова.
 *
 * Одна константа, а не два числа в описании и в коде: разойтись они могут только молча, и тогда
 * модель получила бы вызов, который инструмент отвергает, — ровно то, от чего границы и защищают.
 */
internal val COMMITS_LIMIT_RANGE = 1..100

/** Формат ответа: с отступами, как у сервера проекта, — его читают и человек, и модель. */
private val JSON = Json { prettyPrint = true }

/**
 * Инструмент `get_recent_commits`: последние коммиты репозитория владельца токена.
 *
 * Границы `limit` проверяются до обращения к API: сходить в GitHub за сотней коммитов, чтобы
 * показать их не больше десяти, — потраченный лимит запросов, а неверное число там всё равно
 * стало бы отказом GitHub, текст которого человеку ничего не объясняет. Отказ называет
 * допустимый диапазон, поэтому модель может исправиться по нему, а не по догадке.
 *
 * @param repository Объявление аргумента с именем репозитория; то же, что у `get_repository`.
 * @param limit Объявление аргумента с числом коммитов: имя, тип и описание — из него же
 *        инструмент читает значение вызова, чтобы имя не разошлось со схемой.
 */
fun getRecentCommitsTool(
    repository: DeclaredArgument,
    limit: DeclaredArgument
): ServerTool<GitHubApi> = ServerTool(
    name = "get_recent_commits",
    description = "Последние коммиты репозитория GitHub: идентификатор, сообщение и автор, " +
        "свежие первыми; по умолчанию $DEFAULT_COMMITS_LIMIT коммитов.",
    arguments = listOf(repository, limit)
) { api, request ->
    val name = request.argument(repository.name)
    val requested = request.argument(limit.name)
    val parsed = requested?.toIntOrNull()
    when {
        name == null -> refusal("не назван обязательный аргумент ${repository.name}")

        requested != null && (parsed == null || parsed !in COMMITS_LIMIT_RANGE) -> refusal(
            "не число коммитов: $requested; допустимо целое число от ${COMMITS_LIMIT_RANGE.first} " +
                "до ${COMMITS_LIMIT_RANGE.last}"
        )

        else -> readCommits(api, name, parsed ?: DEFAULT_COMMITS_LIMIT)
    }
}

/** Отказ до обращения к API: причина словами — то же, чем отвечает и ошибка GitHub. */
private fun refusal(reason: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(reason)), isError = true)

/**
 * Чтение коммитов и ответ клиенту.
 *
 * Ошибка GitHub — результат с `isError`, а не исключение: сервер продолжает работать, а модель
 * получает причину словами. Пустой список коммитов ошибкой не является: у нового репозитория
 * их просто нет, и это ответ, а не отказ.
 */
private suspend fun readCommits(api: GitHubApi, name: String, limit: Int): CallToolResult = try {
    val commits = api.commits(name, limit)
    CallToolResult(content = listOf(TextContent(JSON.encodeToString(commits))))
} catch (error: GitHubApiException) {
    CallToolResult(
        content = listOf(TextContent(error.message ?: "не удалось получить коммиты $name из GitHub")),
        isError = true
    )
}
