package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Консольный режим сервера: флаг [LIST_TOOLS_FLAG] печатает его инструмент и выходит.
 *
 * Проверяется договор команды с человеком: инструмент назван, а у аргумента `visibility`
 * напечатаны и тип, и необязательность, и все три значения. Значения здесь важны не меньше
 * имени инструмента: по этой печати человек убеждается, что сервер объявляет ровно те
 * видимости, о которых договаривались, — и токен для этого не нужен.
 */
class GitHubMcpServerCliTest {

    @Test
    fun `--list-tools печатает инструмент и значения его аргумента`() {
        val printed = captureStdout { main(arrayOf(LIST_TOOLS_FLAG)) }

        assertTrue("get_repositories" in printed, "инструмент не назван")
        assertTrue(GitHubMcpServer.visibilityArgument.description in printed, "описание аргумента не напечатано")
        listOf("visibility", "string", "необязательный", "all", "public", "private").forEach { part ->
            assertTrue(part in printed, "в выводе нет «$part»")
        }
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
