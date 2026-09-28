package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.currency.CurrencyRateRepository
import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import java.nio.file.Path
import java.time.Clock

/**
 * Данные, без которых инструменты пайплайна не работают: история курсов, каталог отчётов
 * и часы.
 *
 * Не инструменты и не сервер, а то, что приходит в обработчик вызова: объявление инструмента
 * общее для консольного режима и для поднятого сервера, а хранилище и каталог есть только
 * у поднятого. Часы здесь по той же причине, по которой они у службы сводок: имя файла
 * по умолчанию зависит от момента записи, и в проверке этот момент задаётся, а не берётся
 * из системных часов — иначе проверка зависела бы от дня прогона.
 *
 * @param repository История курсов: та же, что у службы, — читается, но не пишется.
 * @param outputDir Каталог отчётов `saveToFile`.
 * @param clock Часы сервера: по ним называется файл по умолчанию.
 */
data class PipelineToolsData(
    val repository: CurrencyRateRepository,
    val outputDir: Path,
    val clock: Clock = Clock.systemUTC()
)

/**
 * MCP-сервер пайплайна: три инструмента, которые вызываются цепочкой.
 *
 * Смысл сервера — в разделении работы между инструментами, а не в одном инструменте, который
 * делает всё: первый получает данные, второй их обрабатывает, третий сохраняет результат.
 * Поэтому ответ первого целиком принимает второй, а ответ второго — третий: между шагами
 * ходит документ, и по нему видно, что передача данных состоялась, а не что инструменты
 * пересказали друг другу одно и то же.
 *
 * Цепочку собирает вызывающий — агент или человек: порядок вызовов объявлен описаниями
 * инструментов («ответ этого принимает тот»), а не зашит в сервер. Так цепочка остаётся
 * видимой в ленте вызовов и объяснимой: зашитый в сервер порядок выглядел бы одним
 * инструментом, а отказ на середине — отказом всего.
 *
 * Один и тот же набор увидят и наш агент, и любой сторонний MCP-клиент: устройство сервера
 * общее с остальными серверами проекта (объявление [ServerTool], сборка `mcpServer`, запуск
 * `runStdioServer`).
 */
object PipelineMcpServer {

    /** Имя сервера в рукопожатии: по нему видно, чьи инструменты пришли. */
    const val NAME = "ai-challenge-pipeline"

    /** Версия сервера в рукопожатии. */
    const val VERSION = "1.0.0"

    /**
     * Класс точки входа: им же поднимает сервер приложение (`:server`), поэтому имя вынесено
     * константой, а не написано в двух местах — переименование разошлось бы молча.
     */
    const val MAIN_CLASS = "com.osvin.aichallenge.pipeline.ApplicationKt"

    /** Первый шаг цепочки: получить курсы. */
    const val EXCHANGE_RATES_TOOL = "getExchangeRates"

    /** Второй шаг: обработать полученное в сводку. */
    const val SUMMARIZE_RATES_TOOL = "summarizeRates"

    /** Третий шаг: сохранить сводку в файл. */
    const val SAVE_TO_FILE_TOOL = "saveToFile"

    /**
     * База по умолчанию — рубль.
     *
     * Её же считает рублёвой служба курсов: в истории лежат значения «сколько рублей стоит
     * единица валюты», и без пересчёта ответ отдаёт именно их. Другая база — уже вычисление,
     * и оно делается явно ([exchangeRates]).
     */
    const val DEFAULT_BASE = "RUB"

    /** Валюта, в которой хранится история: её код и есть отсутствие пересчёта. */
    const val RUB = "RUB"

    val baseArgument = DeclaredArgument(
        name = "base",
        description = "валюта, к которой пересчитать курсы: RUB (по умолчанию), EUR, USD или GEL",
        required = false
    )

    val currenciesArgument = DeclaredArgument(
        name = "currencies",
        description = "какие валюты показать через запятую: EUR, USD, GEL; по умолчанию — все три",
        required = false
    )

    val ratesArgument = DeclaredArgument(
        name = "rates",
        description = "ответ getExchangeRates целиком, как его вернул инструмент: сводка считается по нему",
        required = true
    )

    val nameArgument = DeclaredArgument(
        name = "name",
        description = "имя файла без каталогов; по умолчанию — rates-ГГГГ-ММ-ДД-ЧЧММ.md",
        required = false
    )

    val contentArgument = DeclaredArgument(
        name = "content",
        description = "текст для сохранения: например, сводка из summarizeRates",
        required = true
    )

    /** Инструменты сервера: объявление цепочки целиком — в одном списке. */
    fun tools(): List<ServerTool<PipelineToolsData>> = listOf(
        getExchangeRatesTool(baseArgument, currenciesArgument),
        summarizeRatesTool(ratesArgument),
        saveToFileTool(nameArgument, contentArgument)
    )
}
