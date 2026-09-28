package com.osvin.aichallenge.mcp.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Разбор ответа GitHub и перевод его в обещанную форму инструмента.
 *
 * Проверяется на документе, который GitHub присылает на самом деле: имена полей «змеиные»
 * (`full_name`, `html_url`), `visibility` есть не всегда, описание может быть пустой строкой.
 * Сами имена обещания (`fullName`, `url`) — не проверка ради проверки: по ним ответ читает
 * и модель, и человек, и разойтись с документом GitHub они не должны.
 */
class GitHubRepositoryTest {

    @Test
    fun `ответ GitHub разбирается в обещанную форму`() {
        val first = repositories()[0]

        assertEquals(
            GitHubRepository(
                id = 1296269,
                name = "Hello-World",
                fullName = "octocat/Hello-World",
                `private` = false,
                visibility = "public",
                url = "https://github.com/octocat/Hello-World",
                description = "учебный репозиторий"
            ),
            first
        )
    }

    @Test
    fun `видимость из ответа главнее вывода из флага`() {
        val internal = repositories()[1]

        assertEquals("internal", internal.visibility)
        assertEquals(true, internal.`private`)
    }

    @Test
    fun `без поля visibility видимость выводится из флага private`() {
        val (privateRepo, publicRepo) = repositories().drop(2)

        assertEquals("private", privateRepo.visibility)
        assertEquals("public", publicRepo.visibility)
    }

    @Test
    fun `пустое описание — это отсутствие описания`() {
        assertNull(repositories()[3].description)
    }

    /**
     * Ответ GitHub как он есть: три способа задать видимость и два — описание.
     *
     * Репозиторий без поля `visibility` нарочно оставлен: на старых ответах GitHub его нет,
     * и обходной путь `private` должен работать, а не падать.
     */
    private fun repositories(): List<GitHubRepository> =
        Json.decodeFromString<List<GitHubRepositoryResponse>>(GITHUB_ANSWER).map { it.toRepository() }

    private companion object {

        val GITHUB_ANSWER = """
            [
              {
                "id": 1296269,
                "name": "Hello-World",
                "full_name": "octocat/Hello-World",
                "html_url": "https://github.com/octocat/Hello-World",
                "private": false,
                "visibility": "public",
                "description": "учебный репозиторий"
              },
              {
                "id": 2,
                "name": "internal-repo",
                "full_name": "octo/internal-repo",
                "html_url": "https://github.com/octo/internal-repo",
                "private": true,
                "visibility": "internal"
              },
              {
                "id": 3,
                "name": "no-visibility-repo",
                "full_name": "octo/no-visibility-repo",
                "html_url": "https://github.com/octo/no-visibility-repo",
                "private": true
              },
              {
                "id": 4,
                "name": "no-description-repo",
                "full_name": "octo/no-description-repo",
                "html_url": "https://github.com/octo/no-description-repo",
                "private": false,
                "visibility": "public",
                "description": ""
              }
            ]
        """.trimIndent()
    }
}
