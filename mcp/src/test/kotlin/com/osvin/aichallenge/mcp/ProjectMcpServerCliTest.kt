package com.osvin.aichallenge.mcp

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Консольный режим сервера: флаг [LIST_TOOLS_FLAG] печатает его инструменты и выходит.
 *
 * Проверяется договор команды с человеком: какие инструменты и аргументы она называет.
 * Оформление (порядок строк, отступы, строка про `listChanged`) не проверяется — это
 * вид вывода, а не обещание; обещание здесь одно: каждый объявленный инструмент и каждый
 * его аргумент в выводе названы, с типом и обязательностью.
 *
 * Печать перехватывается до вызова [main]: в этом режиме сервер не забирает стандартный
 * вывод под протокол (то есть не заходит в рабочий режим), иначе перехватить было бы нечего.
 */
class ProjectMcpServerCliTest {

    @Test
    fun `--list-tools печатает объявленные инструменты и их аргументы`() {
        val printed = captureStdout { main(arrayOf(LIST_TOOLS_FLAG)) }

        assertTrue(printed.isNotBlank(), "команда ничего не напечатала")
        ProjectMcpServer.tools.forEach { tool ->
            assertTrue(tool.name in printed, "инструмент ${tool.name} не назван")
            assertTrue(tool.description in printed, "описание инструмента ${tool.name} не напечатано")
            tool.arguments.forEach { argument ->
                val required = if (argument.required) "обязательный" else "необязательный"
                val lines = printed.lines().filter { it.trimStart().startsWith("${argument.name}:") }
                assertTrue(
                    lines.any { it.contains(argument.type) && it.contains(required) },
                    "аргумент ${argument.name} инструмента ${tool.name} напечатан без типа " +
                        "(${argument.type}) или обязательности ($required)"
                )
            }
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
