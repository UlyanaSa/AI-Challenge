package com.osvin.aichallenge

import com.osvin.aichallenge.mcp.McpServerConfig
import com.osvin.aichallenge.mcp.localMcpServerConfig

/**
 * Как запустить MCP-сервер инструментов: командой из окружения или классом из своего classpath.
 *
 * Правило одно на все серверы инструментов, поэтому живёт здесь, а не в каждом классе-обёртке:
 * разбор команды по пробелам и решение «окружение или свой classpath» — это одно и то же решение,
 * и вторая копия разошлась бы с первой при первой же правке (например, когда понадобилось бы
 * разбирать кавычки).
 *
 * Команда из окружения нужна там, где сервер инструментов живёт не рядом с приложением: у службы
 * курсов на VPS и у пайплайна, читающего ту же базу на той же машине. Без оболочки, по пробелам:
 * оболочка здесь означала бы, что настройка приложения исполняет произвольный текст из окружения,
 * а кавычки и подстановки — что команду нельзя прочитать глазами.
 *
 * @param env Окружение процесса; подменяется в проверках.
 * @param variable Имя переменной с командой.
 * @param mainClass Класс точки входа для запуска из своего classpath, если переменной нет.
 * @param args Аргументы режима после класса точки входа (у службы курсов — `--mcp`).
 */
internal fun mcpServerConfigFromEnvironment(
    env: Map<String, String>,
    variable: String,
    mainClass: String,
    args: List<String> = emptyList()
): McpServerConfig = env[variable]?.takeIf { it.isNotBlank() }?.let { command ->
    val parts = command.trim().split(WHITESPACE)
    McpServerConfig(command = parts.first(), args = parts.drop(1))
} ?: localMcpServerConfig(mainClass, args = args)

/** Разделитель частей команды запуска: пробелы и переводы строк из окружения. */
private val WHITESPACE = Regex("\\s+")
