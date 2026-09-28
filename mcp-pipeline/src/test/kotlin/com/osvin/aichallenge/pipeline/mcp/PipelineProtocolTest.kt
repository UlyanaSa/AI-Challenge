package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.storage.SqliteCurrencyRateRepository
import com.osvin.aichallenge.mcp.localMcpServerConfig
import com.osvin.aichallenge.mcp.openMcpSession
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Цепочка дня 19 на настоящем процессе: курсы → сводка → файл, вызов к вызову.
 *
 * Проверяется то, чего не видно в проверках отдельных инструментов: сервер объявляет три
 * инструмента, а данные между ними передаются целиком — документ первого шага уходит аргументом
 * второму, текст второго — аргументом третьему. Итог сверяется файлом на диске: сводка в файле
 * равна тексту, который вернул второй инструмент. Это и есть обещание дня — не «инструменты
 * умеют вызываться», а «цепочка собирается из них без потери данных».
 *
 * Процесс настоящий: клиент поднимает сервер пайплайна по протоколу MCP, сервер читает настоящую
 * историю курсов (временная база, посеянная в проверке) и пишет настоящий файл. Подставлено
 * только время курсов — историю пишет служба, и её здесь заменяет одна вставка, — код-путь
 * от этого не меняется: инструмент читает `latest()` так же, как читал бы у службы.
 *
 * Порядок вызовов здесь задан проверкой, а не сервером: в работе порядок выбирает модель
 * по описаниям инструментов, и то, что она его выбирает, видно на живой демонстрации
 * (`:server:pipelineDemo`). Здесь проверяется, что выбранная последовательность работает.
 */
class PipelineProtocolTest {

    private val directory = Files.createTempDirectory("pipeline-protocol")

    @AfterTest
    fun removeFiles() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `три инструмента объявлены, а цепочка доходит до файла`() = runBlocking {
        val database = directory.resolve("currency.db")
        val reports = directory.resolve("reports")
        seed(database)

        val session = openMcpSession(
            localMcpServerConfig(PipelineMcpServer.MAIN_CLASS).copy(
                env = mapOf(
                    "CURRENCY_DB" to database.toString(),
                    "PIPELINE_OUTPUT_DIR" to reports.toString()
                )
            ),
            onServerStderr = { log("сервер: $it") }
        )

        try {
            log("соединение установлено: ${session.serverName} ${session.serverVersion}")
            assertEquals(PipelineMcpServer.NAME, session.serverName)

            val tools = session.listTools()
            assertEquals(
                listOf(
                    PipelineMcpServer.EXCHANGE_RATES_TOOL,
                    PipelineMcpServer.SUMMARIZE_RATES_TOOL,
                    PipelineMcpServer.SAVE_TO_FILE_TOOL
                ),
                tools.map { it.name },
                "агент получил не те инструменты: ${tools.map { it.name }}"
            )
            tools.forEach { tool ->
                assertFalse(tool.description.isNullOrBlank(), "у инструмента ${tool.name} нет описания")
                log("инструмент: ${tool.name}; аргументы: ${tool.arguments.joinToString { it.name }}")
            }

            stage("Шаг 1: курсы")
            val ratesCall = session.client.callTool(PipelineMcpServer.EXCHANGE_RATES_TOOL, emptyMap())
            assertFalse(ratesCall.isError == true, "курсы не отдались: ${ratesCall.text()}")
            val rates = ratesCall.text()
            assertTrue(rates.contains("95.8709"), "в документе курсов нет курса евро: $rates")
            log("документ курсов: $rates")

            stage("Шаг 2: сводка по документу первого шага")
            val summaryCall = session.client.callTool(
                PipelineMcpServer.SUMMARIZE_RATES_TOOL,
                mapOf(PipelineMcpServer.ratesArgument.name to rates)
            )
            assertFalse(summaryCall.isError == true, "сводка не составилась: ${summaryCall.text()}")
            val summary = summaryCall.text()
            assertTrue(summary.contains("95,8709"), "в сводке нет курса из документа: $summary")
            assertTrue(summary.contains("84,3414"), "в сводке нет второго курса: $summary")
            log("сводка: $summary")

            stage("Шаг 3: сводка в файл")
            val fileName = "проверка.md"
            val saveCall = session.client.callTool(
                PipelineMcpServer.SAVE_TO_FILE_TOOL,
                mapOf(
                    PipelineMcpServer.nameArgument.name to fileName,
                    PipelineMcpServer.contentArgument.name to summary
                )
            )
            assertFalse(saveCall.isError == true, "файл не сохранён: ${saveCall.text()}")
            val saved = reports.resolve(fileName)
            assertTrue(Files.exists(saved), "файла нет по названному пути: ${saveCall.text()}")
            assertEquals(summary, Files.readString(saved), "в файле не тот текст, что вернул второй инструмент")
            assertTrue(
                saveCall.text().contains(saved.toString()),
                "ответ третьего шага не называет файл: ${saveCall.text()}"
            )
            log("ответ записи: ${saveCall.text()}")
            log("цепочка дошла до файла: инструментов 3, вызовов 3, потерь данных нет")
        } finally {
            session.close()
        }
    }

    /** История курсов: три валюты на один момент — как их пишет служба за один удар. */
    private suspend fun seed(path: Path) {
        val repository = SqliteCurrencyRateRepository(path)
        try {
            repository.save(
                listOf(
                    CurrencyRate(Currency.EUR, "95.8709".toBigDecimal(), MOMENT),
                    CurrencyRate(Currency.USD, "84.3414".toBigDecimal(), MOMENT),
                    CurrencyRate(Currency.GEL, "32.1693".toBigDecimal(), MOMENT)
                )
            )
        } finally {
            repository.close()
        }
    }

    private companion object {
        val MOMENT: Instant = Instant.parse("2026-09-28T10:37:00Z")
    }
}

/** Строка прогона — в том же виде, что у прочих прогонов проекта. */
private fun log(line: String) = println("[agent] $line")

/** Заголовок этапа: по нему видно, на каком шаге цепочки прогон. */
private fun stage(title: String) = println("[agent] === $title ===")
