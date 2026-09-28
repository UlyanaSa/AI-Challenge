package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Значение «вернуть все репозитории» — оно же умолчание аргумента. */
internal const val VISIBILITY_ALL = "all"

/** Значение «только публичные» — совпадает с `visibility` ответа GitHub. */
internal const val VISIBILITY_PUBLIC = "public"

/** Значение «только приватные» — совпадает с `visibility` ответа GitHub. */
internal const val VISIBILITY_PRIVATE = "private"

/**
 * Допустимые значения `visibility` — одна правда для схемы инструмента и для фильтра.
 *
 * Список один и тот же в объявлении (уезжает клиенту как `enum`) и в проверке вызова; две копии
 * разошлись бы, и модель получила бы вызов, который инструмент отвергает.
 */
internal val VISIBILITY_VALUES = listOf(VISIBILITY_ALL, VISIBILITY_PUBLIC, VISIBILITY_PRIVATE)

/** Формат ответа: с отступами, как у сервера проекта, — его читают и человек, и модель. */
private val JSON = Json { prettyPrint = true }

/**
 * Инструмент `get_repositories`: репозитории владельца токена из GitHub.
 *
 * Неизвестное значение `visibility` отвергается до обращения к API: ходить в сеть за тем, что
 * всё равно будет отфильтровано в пустоту, — это потраченный лимит запросов и ложное ощущение,
 * что аргумент принят. Отказ перечисляет допустимые значения, поэтому модель может исправиться
 * по нему, а не по догадке.
 *
 * @param visibility Объявление аргумента: имя, описание и допустимые значения — из него же
 *        инструмент читает значение вызова, чтобы имя не разошлось со схемой.
 */
fun getRepositoriesTool(visibility: DeclaredArgument): ServerTool<GitHubApi> = ServerTool(
    name = "get_repositories",
    description = "Репозитории GitHub, доступные владельцу токена: полное имя, видимость, " +
        "адрес страницы и описание.",
    arguments = listOf(visibility)
) { api, request ->
    val requested = request.argument(visibility.name) ?: VISIBILITY_ALL
    if (requested !in VISIBILITY_VALUES) {
        CallToolResult(
            content = listOf(
                TextContent(
                    "неизвестное значение ${visibility.name}: $requested; допустимые: " +
                        VISIBILITY_VALUES.joinToString(", ")
                )
            ),
            isError = true
        )
    } else {
        readRepositories(api, requested)
    }
}

/**
 * Чтение репозиториев и ответ клиенту.
 *
 * Ошибка GitHub — результат с `isError`, а не исключение: сервер продолжает работать, а модель
 * получает причину словами. Другого пути у ошибки нет — исключение инструмента до модели
 * не доходит, и клиент увидел бы отказ инструмента, которого не вызывал.
 */
private suspend fun readRepositories(api: GitHubApi, visibility: String): CallToolResult = try {
    val repositories = api.repositories().filterBy(visibility)
    CallToolResult(content = listOf(TextContent(JSON.encodeToString(repositories))))
} catch (error: GitHubApiException) {
    CallToolResult(
        content = listOf(TextContent(error.message ?: "не удалось получить репозитории из GitHub")),
        isError = true
    )
}

/**
 * Фильтр по значению аргумента.
 *
 * `all` — без фильтра, остальные — по нормированной в [toRepository] видимости: репозиторий без
 * поля `visibility` в ответе GitHub получает видимость из флага `private`, поэтому фильтр
 * работает и на старых ответах. Фильтровать по флагу `private` в обход видимости не стали:
 * тогда третья видимость GitHub (`internal`) попала бы в «приватные», хотя инструмент обещает
 * только публичные и приватные, а `all` и так отдаёт всё.
 */
private fun List<GitHubRepository>.filterBy(visibility: String): List<GitHubRepository> =
    if (visibility == VISIBILITY_ALL) this else filter { it.visibility == visibility }
