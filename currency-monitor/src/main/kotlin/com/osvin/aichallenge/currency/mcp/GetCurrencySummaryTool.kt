package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.summary.CurrencySummary
import com.osvin.aichallenge.currency.summary.SummaryPeriod
import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal

/** Что сказать, когда за период записей нет: пустая сводка без объяснения выглядит как сбой. */
private const val NO_PERIOD_NOTE =
    "За этот период записей нет: сводка пуста. Текущие курсы за всё время наблюдения отдаёт " +
        "инструмент get_currency_rates."

/**
 * Инструмент `get_currency_summary`: сводка по курсам за период.
 *
 * Аргумент обязателен и объявлен списком значений: без периода непонятно, за что считать
 * минимум и среднее, а значение вне списка отвергается до обращения к истории — с перечнем
 * допустимых, чтобы модель могла исправиться по отказу, а не по догадке. Так же устроен
 * `visibility` у сервера GitHub: схема и проверка берут значения из одного места.
 *
 * Значения в ответе — числа, а не строки: агент считает по ним и отвечает словами, а строку
 * ему пришлось бы разбирать заново. Неизвестное значение (пустое окно, нет предыдущего курса)
 * уезжает как null: у числа и у его отсутствия разный смысл, и ноль вместо null был бы
 * утверждением, которого сервис не проверял.
 */
fun getCurrencySummaryTool(period: DeclaredArgument): ServerTool<CurrencyToolsData> = ServerTool(
    name = "get_currency_summary",
    description = "Сводка по курсам за период: текущий и предыдущий курс, изменение в рублях " +
        "и процентах, минимум, максимум и среднее по каждой отслеживаемой валюте.",
    arguments = listOf(period)
) { data, request ->
    when (val asked = request.argument(period.name)?.let { SummaryPeriod.byCode(it) }) {
        null -> CallToolResult(
            content = listOf(
                TextContent(
                    "неизвестный период: ${request.argument(period.name) ?: "не задан"}; " +
                        "допустимые: ${SummaryPeriod.entries.joinToString(", ") { it.code }}"
                )
            ),
            isError = true
        )

        else -> try {
            CallToolResult(content = listOf(TextContent(summaryReport(asked, data.summary.summary(asked)))))
        } catch (error: CurrencyStorageException) {
            CallToolResult(
                content = listOf(TextContent(error.message ?: "историю курсов прочитать не удалось")),
                isError = true
            )
        }
    }
}

/**
 * Ответ инструмента: период и сводка по каждой валюте в объявленном порядке.
 *
 * Валюты идут все три, даже если по одной из них данных нет: «по гелю за сутки ничего не
 * известно» — это ответ, а пропущенная валюта читалась бы как «гель не отслеживается».
 */
internal fun summaryReport(period: SummaryPeriod, summaries: List<CurrencySummary>): String =
    CURRENCY_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("period", period.code)
            putJsonArray("currencies") { summaries.forEach { add(summaryJson(it)) } }
            if (summaries.all { it.samples == 0 }) put("note", NO_PERIOD_NOTE)
        }
    )

/** Сводка одной валюты: числа как числа, неизвестное — null. */
private fun summaryJson(summary: CurrencySummary): JsonObject = buildJsonObject {
    put("currency", summary.currency.code)
    put("currentRate", summary.currentRate.asJson())
    put("previousRate", summary.previousRate.asJson())
    put("change", summary.change.asJson())
    put("changePercent", summary.changePercent.asJson())
    put("minRate", summary.minRate.asJson())
    put("maxRate", summary.maxRate.asJson())
    put("averageRate", summary.averageRate.asJson())
    put("samples", summary.samples)
}

/** Значение курса в JSON: null остаётся null, число остаётся числом. */
private fun BigDecimal?.asJson() = this?.let { JsonPrimitive(it) } ?: JsonNull
