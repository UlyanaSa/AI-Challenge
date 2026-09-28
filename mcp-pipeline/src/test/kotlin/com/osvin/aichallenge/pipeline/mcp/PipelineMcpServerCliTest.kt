package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.mcp.LIST_TOOLS_FLAG
import com.osvin.aichallenge.pipeline.main
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Консольный режим сервера пайплайна: `--list-tools` печатает инструменты и выходит.
 *
 * Проверяется договор команды с человеком — какие инструменты и аргументы она называет, — и то,
 * что ради справки сервер не трогает историю курсов: у процесса нет ни `CURRENCY_DB`, ни базы,
 * а команда всё равно отвечает. Открывай он базу при запуске, справка требовала бы настроенной
 * истории, и на чистой машине её нельзя было бы прочитать.
 *
 * Печать перехватывается до вызова [main]: в консольном режиме сервер не забирает стандартный
 * вывод под протокол, иначе перехватывать было бы нечего.
 */
class PipelineMcpServerCliTest {

    @Test
    fun `--list-tools печатает инструменты цепочки и их аргументы`() {
        val printed = captureStdout { main(arrayOf(LIST_TOOLS_FLAG)) }

        assertTrue(printed.isNotBlank(), "команда ничего не напечатала")
        PipelineMcpServer.tools().forEach { tool ->
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
