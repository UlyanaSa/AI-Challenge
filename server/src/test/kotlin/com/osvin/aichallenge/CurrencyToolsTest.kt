package com.osvin.aichallenge

import com.osvin.aichallenge.currency.mcp.CurrencyMcpServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки подключения сервера приложения к сервису курсов.
 *
 * Проверяется то, что нельзя увидеть в сборке и легко перепутать на живом запуске: какой
 * процесс поднимается по умолчанию и что делает команда из окружения. Ошибка здесь выглядит
 * не как падение, а как «у агента почему-то нет инструментов курсов», и искать её пришлось бы
 * на VPS.
 */
class CurrencyToolsTest {

    @Test
    fun `without a command the service is started from this classpath`() {
        val config = currencyMcpServerConfig(emptyMap())

        assertTrue(config.command.endsWith("bin/java"), config.command)
        assertTrue(config.args.contains(CurrencyMcpServer.MAIN_CLASS), config.args.toString())
    }

    @Test
    fun `command from the environment replaces the local start`() {
        val config = currencyMcpServerConfig(
            mapOf(CURRENCY_MCP_COMMAND_ENV to "ssh vps java -jar /opt/currency-monitor/currency-monitor.jar")
        )

        assertEquals("ssh", config.command)
        assertEquals(listOf("vps", "java", "-jar", "/opt/currency-monitor/currency-monitor.jar"), config.args)
    }

    @Test
    fun `blank command means the local start rather than an empty one`() {
        val config = currencyMcpServerConfig(mapOf(CURRENCY_MCP_COMMAND_ENV to "  "))

        assertTrue(config.command.endsWith("bin/java"), config.command)
    }

    @Test
    fun `without a service the tools list is empty and nothing is started`() = runBlocking {
        // Приложение работает и без сервиса курсов: проверка доказывает, что в этом случае
        // не поднимается ни один процесс, — иначе тесты и запуски без курсов платили бы
        // за чужую JVM.
        assertEquals(emptyList(), CurrencyTools(config = null).tools())
    }
}
