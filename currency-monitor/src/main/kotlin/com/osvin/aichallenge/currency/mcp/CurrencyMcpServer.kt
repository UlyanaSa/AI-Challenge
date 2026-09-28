package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.summary.CurrencySummaryService
import com.osvin.aichallenge.currency.summary.SummaryPeriod
import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool

/**
 * Данные инструментов: сбор курсов и сводка — две службы, поэтому пара.
 *
 * Одним типом, а не двумя параметрами, потому что сервер объявляет инструменты одного вида
 * ([ServerTool] с одними данными на весь список): своих данных у инструментов нет, они лишь
 * читают то, что им передали при сборке сервера.
 */
data class CurrencyToolsData(
    val service: CurrencyService,
    val summary: CurrencySummaryService
)

/**
 * Локальный MCP-сервер сервиса курсов: отдаёт агенту историю и сводку по курсам.
 *
 * Сервер и планировщик живут в одном процессе, но это не сервер собирает курсы: сбор идёт
 * по расписанию в том же процессе независимо от того, подключён клиент или нет, а инструменты
 * только читают уже собранное. Поэтому подключение клиента ничего не запускает и не меняет —
 * и ответ на вопрос «как изменился евро за сутки» не зависит от того, сколько клиент ждал.
 *
 * Про имя сервера в рукопожатии: оно нужно, чтобы клиент видел, кто ответил, и чтобы человек
 * в логе отличал этот сервер от сервера проекта и от GitHub. Версия — версия проекта: сервис
 * поставляется одним fat JAR, и отдельная нумерация части целого только запутала бы.
 */
object CurrencyMcpServer {

    /** Имя сервера в рукопожатии. */
    const val NAME = "ai-challenge-currency"

    /** Версия сервера в рукопожатии. */
    const val VERSION = "1.0.0"

    /**
     * Имя точки входа на JVM: его называют те, кто поднимает сервис процессом.
     *
     * `:server` берёт отсюда `java -cp … <mainClass>`, а VPS — `java -jar <fat jar>`; в обоих
     * случаях класс один и тот же, и держать его литералом в двух местах значило бы разойтись
     * с ним при переименовании файла.
     */
    const val MAIN_CLASS = "com.osvin.aichallenge.currency.ApplicationKt"

    /**
     * Аргумент `period`: за какой период считать сводку.
     *
     * Обязательный: без периода непонятно, за что считать минимум и среднее, и выбор значения
     * за человека означал бы ответ не на тот вопрос, который задали. Значения — из перечисления,
     * то есть та же правда, что у проверки вызова: схема и разбор не могут разойтись.
     */
    val periodArgument = DeclaredArgument(
        name = "period",
        description = "за какой период считать сводку: DAY — последние 24 часа, WEEK — 7 суток, " +
            "MONTH — 30 суток",
        values = SummaryPeriod.entries.map { it.code },
        required = true
    )

    /**
     * Инструменты сервера.
     *
     * Данных здесь нет намеренно: список инструментов нужен и консольному режиму
     * (`--list-tools`), который печатает объявления и выходит, — поднимать ради него базу
     * и сеть было бы незачем. Данные приходят обработчикам при сборке сервера
     * ([com.osvin.aichallenge.mcp.mcpServer]).
     */
    fun tools(): List<ServerTool<CurrencyToolsData>> = listOf(
        getCurrencyRatesTool(),
        getCurrencySummaryTool(periodArgument)
    )
}
