package com.osvin.aichallenge.mcp.github

import kotlinx.serialization.Serializable

/**
 * Коммит GitHub в том виде, в каком его отдаёт инструмент `get_recent_commits`.
 *
 * Это обещанная форма ответа, а не документ GitHub: её читают модель и человек, поэтому поле
 * названо словом отчёта (`message`), а не путём в ответе GitHub (`commit.message`). Разбор
 * ответа — отдельная форма ([GitHubCommitResponse]): у GitHub коммит вложен, и в нём есть
 * поля (родители, деревья, подпись), которых в обещании нет; совместить обе формы значило бы
 * либо впустить чужую вложенность в обещание, либо разбирать ответ вручную и потерять
 * типизацию — поэтому между ними стоит маппинг [toCommit].
 *
 * @param sha Идентификатор коммита в GitHub.
 * @param message Сообщение коммита целиком — вместе с заголовком и телом, как его написал автор.
 * @param author Имя автора из самого коммита; null — GitHub его не назвал (запись сделана
 *        автором, которого GitHub не связывает с учётной записью, и поля вовсе нет).
 */
@Serializable
data class GitHubCommit(
    val sha: String,
    val message: String,
    val author: String?
)

/**
 * Ответ GitHub на `GET /repos/{owner}/{repo}/commits` — ровно в том виде, в каком его
 * присылает API.
 *
 * Форма внутренняя: наружу (в обещание инструмента) уходит [GitHubCommit], а здесь лежит
 * только шаг разбора — коммит GitHub и его вложенный `commit`.
 *
 * @param commit Вложенная часть: то, что записал автор, а не то, что добавил GitHub.
 */
@Serializable
internal data class GitHubCommitResponse(
    val sha: String,
    val commit: GitHubCommitDetails
)

/**
 * Вложенный `commit` ответа GitHub: сообщение и автор записи.
 *
 * `author` — автор коммита, а не пользователь GitHub (тот лежит снаружи, в поле `author`
 * самого коммита). Различать их важно: у коммита, пришедшего почтой или из другой системы,
 * пользователя GitHub нет вовсе, а имя автора есть.
 *
 * @param author Может отсутствовать: GitHub отвечает так на коммиты без автора.
 */
@Serializable
internal data class GitHubCommitDetails(
    val message: String,
    val author: GitHubCommitAuthor? = null
)

/**
 * Автор коммита из вложенного `commit`.
 *
 * @param name Имя автора; может быть пустым — тогда [toCommit] превращает его в «автор
 *        не назван», чтобы инструмент не показывал пустую строку вместо имени.
 */
@Serializable
internal data class GitHubCommitAuthor(val name: String? = null)

/**
 * Ответ GitHub в обещанную форму инструмента.
 *
 * Единственное место, где чужая вложенность превращается в плоскую форму: разбирать
 * `commit.message` в самом обработчике инструмента значило бы, что обещание ответа зависит
 * от формы GitHub, и правка разбора тронула бы инструмент.
 */
internal fun GitHubCommitResponse.toCommit(): GitHubCommit = GitHubCommit(
    sha = sha,
    message = commit.message,
    author = commit.author?.name?.takeIf { it.isNotBlank() }
)
