package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Консольный режим сервера: флаг [LIST_TOOLS_FLAG] печатает инструменты и выходит.
 *
 * Проверяется договор команды с человеком: оба инструмента названы, у `get_repositories`
 * напечатаны тип, необязательность и все три значения `visibility`, а у `github_access` —
 * то, что аргументов у него нет. Значения здесь важны не меньше имени инструмента: по этой
 * печати человек убеждается, что сервер объявляет ровно то, о чём договаривались, и ни токен,
 * ни доступ к GitHub для этого не нужны.
 */
class GitHubMcpServerCliTest {

    @Test
    fun `--list-tools печатает оба инструмента и значения аргумента visibility`() {
        val printed = captureStdout { main(arrayOf(LIST_TOOLS_FLAG)) }

        assertTrue("get_repositories" in printed, "инструмент репозиториев не назван")
        assertTrue(GitHubMcpServer.visibilityArgument.description in printed, "описание аргумента не напечатано")
        listOf("visibility", "string", "необязательный", "all", "public", "private").forEach { part ->
            assertTrue(part in printed, "в выводе нет «$part»")
        }

        assertTrue("github_access" in printed, "инструмент отчёта о доступе не назван")
        assertTrue(
            "аргументов нет" in printed,
            "у github_access не напечатано, что аргументов нет: только он объявлен без них"
        )
    }

    /** Печать команды: перехватываем вывод и возвращаем то, что увидел бы человек. */
    private fun captureStdout(block: () -> Unit): String {
        val printed = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(printed, true))
        return try {
            block()
            printed.toString()
        } finally {
            System.setOut(original)
        }
    }
}
