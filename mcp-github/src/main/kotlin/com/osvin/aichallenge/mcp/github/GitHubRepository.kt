package com.osvin.aichallenge.mcp.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Репозиторий GitHub в том виде, в каком его отдаёт инструмент `get_repositories`.
 *
 * Это обещанная форма ответа, а не документ GitHub: её читают модель и человек, и она должна
 * быть одной и той же независимо от того, что именно прислал API. Поэтому имена полей — как их
 * называет инструмент (`fullName`, `url`), а не как их называет GitHub (`full_name`, `html_url`).
 *
 * Разбор ответа GitHub — отдельная форма ([GitHubRepositoryResponse]): у GitHub есть поля,
 * которых в обещании нет, и наоборот — `visibility` может отсутствовать. Совместить обе формы
 * значило бы либо впустить чужие имена в обещание, либо разбирать ответ вручную и потерять
 * типизацию; поэтому между ними стоит маппинг [toRepository].
 *
 * @param id Идентификатор репозитория в GitHub.
 * @param name Короткое имя репозитория.
 * @param fullName Полное имя `владелец/репозиторий`.
 * @param private Флаг приватности GitHub: он есть в любом ответе, а `visibility` — не всегда,
 *        поэтому нормировка видимости опирается на него.
 * @param visibility Видимость словами (`public`, `private`, `internal`); если GitHub её не
 *        прислал, она выведена из [private].
 * @param url Адрес страницы репозитория — то, что GitHub зовёт `html_url`.
 * @param description Описание; null — описания нет или оно пустое.
 */
@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val `private`: Boolean,
    val visibility: String,
    val url: String,
    val description: String? = null
)

/**
 * Ответ GitHub на `GET /user/repos` — ровно в том виде, в каком его присылает API.
 *
 * Имена полей здесь «змеиные», как в документе GitHub, поэтому свойства связываются с ними
 * через [SerialName]. Форма внутренняя: наружу (в обещание инструмента) уходит
 * [GitHubRepository], а этот тип — только шаг разбора.
 *
 * @param private У GitHub это ключевое слово, в Kotlin — идентификатор в обратных кавычках;
 *        `@SerialName("private")` здесь не нужен, имя свойства уже совпадает с именем поля.
 * @param visibility Может отсутствовать: поле появилось позже флага `private`, и на старых
 *        ответах его нет — тогда видимость выводится из [private] в [toRepository].
 * @param description Пустая строка означает «описания нет» и превращается в null.
 */
@Serializable
internal data class GitHubRepositoryResponse(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("html_url") val url: String,
    val `private`: Boolean = false,
    val visibility: String? = null,
    val description: String? = null
)

/**
 * Ответ GitHub в обещанную форму инструмента.
 *
 * Единственное место, где чужая форма превращается в нашу: нормируются только два поля —
 * `visibility` (GitHub мог его не прислать) и `description` (пустая строка — это отсутствие
 * описания, а не описание из пустоты). Держать нормировку в одном месте, а не в разборе и
 * фильтре по отдельности, значит, что обещание инструмента не зависит от порядка чтения.
 */
internal fun GitHubRepositoryResponse.toRepository(): GitHubRepository = GitHubRepository(
    id = id,
    name = name,
    fullName = fullName,
    `private` = `private`,
    visibility = visibility?.takeIf { it.isNotBlank() }
        ?: if (`private`) VISIBILITY_PRIVATE else VISIBILITY_PUBLIC,
    url = url,
    description = description?.takeIf { it.isNotBlank() }
)
