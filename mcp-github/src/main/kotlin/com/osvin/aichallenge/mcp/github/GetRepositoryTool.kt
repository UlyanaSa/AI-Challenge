package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Формат ответа: с отступами, как у сервера проекта, — его читают и человек, и модель. */
private val JSON = Json { prettyPrint = true }

/**
 * Инструмент `getRepository`: один репозиторий владельца токена.
 *
 * Аргумент — короткое имя, а не `владелец/репозиторий`: владелец один и тот же у токена,
 * и спрашивать его у модели значило бы заставлять её угадывать логин. Поэтому у инструмента
 * с `get_repositories` общий язык имён: имя из списка годится как аргумент без пересборки,
 * и модель может взять его прямо из предыдущего ответа.
 *
 * Пустое имя отвергается до обращения к API: сходить в GitHub за «ничем» — это потраченный
 * лимит запросов и ответ 404, из которого человеку нечего понять.
 *
 * @param repository Объявление аргумента: имя, описание и обязательность — из него же
 *        инструмент читает значение вызова, чтобы имя не разошлось со схемой.
 */
fun getRepositoryTool(repository: DeclaredArgument): ServerTool<GitHubApi> = ServerTool(
    name = "getRepository",
    description = "Репозиторий GitHub по имени: полное имя, видимость, адрес страницы " +
        "и описание — тот же вид, что у get_repositories, но одним объектом.",
    arguments = listOf(repository)
) { api, request ->
    val name = request.argument(repository.name)
    if (name == null) {
        CallToolResult(
            content = listOf(TextContent("не назван обязательный аргумент ${repository.name}")),
            isError = true
        )
    } else {
        readRepository(api, name)
    }
}

/**
 * Чтение одного репозитория и ответ клиенту.
 *
 * Ошибка GitHub — результат с `isError`, а не исключение: сервер продолжает работать, а модель
 * получает причину словами. Ответ — тот же JSON-вид, что у `get_repositories` (pretty-print,
 * имена полей модели), только не массивом: разбирать его обеими сторонами умеет одна и та же
 * форма [GitHubRepository].
 */
private suspend fun readRepository(api: GitHubApi, name: String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(JSON.encodeToString(api.repository(name)))))
} catch (error: GitHubApiException) {
    CallToolResult(
        content = listOf(TextContent(error.message ?: "не удалось получить репозиторий $name из GitHub")),
        isError = true
    )
}
