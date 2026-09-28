package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Что сказать, когда на вход пришёл не тот документ: причину ищут в вызове, а не в истории. */
private const val NOT_A_DOCUMENT =
    "Ожидался ответ getExchangeRates: объект с полями base и rates. Передайте ответ первого " +
        "инструмента целиком, без пересказа."

/** Вид момента в сводке: тот же, что у человека в ленте приложения, — минуты и UTC. */
private val MOMENT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/**
 * Инструмент `summarizeRates`: краткая сводка по ответу первого шага — второй шаг цепочки.
 *
 * Принимает ответ [getExchangeRates] целиком строкой, а не отдельные числа: ради этого шаг
 * и существует. Разложи сводку на аргументы «валюта и курс», её считал бы тот, кто раскладывает, —
 * то есть модель, а не сервер, и в файл уходил бы пересказ модели вместо посчитанного по данным.
 *
 * Сводка — из того, что есть в документе: момент, база, курсы, размах между крайними. Ничего
 * не дочитывается из истории: второй шаг не знает про базу, и «дочитывание» сделало бы шаги
 * неразделимыми (тогда проверка передачи данных между инструментами проверяла бы не передачу,
 * а общий доступ к хранилищу).
 *
 * Отказ по входу — результат с `isError`: причина словами нужна модели, чтобы передать
 * следующий документ, а не оборвать цепочку молчанием.
 */
fun summarizeRatesTool(rates: DeclaredArgument): ServerTool<PipelineToolsData> = ServerTool(
    name = PipelineMcpServer.SUMMARIZE_RATES_TOOL,
    description = "Краткая сводка по ответу getExchangeRates: момент, база, курсы по валютам " +
        "и размах между крайними. Ответ — текст, его принимает saveToFile.",
    arguments = listOf(rates)
) { _, request ->
    val document = request.argument(rates.name)
        ?: return@ServerTool CallToolResult(
            content = listOf(TextContent("Аргумент ${rates.name} не назван: передайте ответ getExchangeRates.")),
            isError = true
        )
    try {
        CallToolResult(content = listOf(TextContent(summarizeRates(document))))
    } catch (error: PipelineInputException) {
        CallToolResult(content = listOf(TextContent(error.message.orEmpty())), isError = true)
    }
}

/**
 * Сводка по документу курсов: `Сводка курсов к RUB на 2026-09-28 10:37 UTC`.
 *
 * Порядок валют — тот, в каком они пришли в документе: сводка описывает ответ первого шага,
 * а не историю, и переставлять в ней строки значило бы показывать не то, что передали.
 * Курсы печатаются как пришли (`toPlainString`) с запятой — сводку читает человек, а запятая
 * это то, чем разделяют дробную часть в ленте приложения. Размах — величина посчитанная,
 * поэтому хвостовые нули у него убраны: `0.0000` у одной валюты читался бы как точность,
 * которой в этом числе нет.
 *
 * Крайние курсы помечаются словами, а не порядком строк: «дороже всех» видно и в файле, где
 * порядок уже ни на что не опирается. Помечаются они только когда валюта в сводке не одна:
 * у единственной строки «дороже всех» и «дешевле всех» — одно и то же, и это не сведение,
 * а шум.
 */
internal fun summarizeRates(document: String): String {
    val root = parseDocument(document)
    val base = (root["base"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw PipelineInputException(NOT_A_DOCUMENT)
    val rates = root["rates"] as? JsonObject ?: throw PipelineInputException(NOT_A_DOCUMENT)
    if (rates.isEmpty()) {
        throw PipelineInputException("В ответе getExchangeRates нет ни одного курса: сводку считать не по чему.")
    }
    val values = rates.map { (code, value) -> code to value.decimal(code) }
    val moment = (root["updatedAt"] as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val highest = values.maxBy { it.second }
    val lowest = values.minBy { it.second }
    val spread = highest.second - lowest.second

    return buildString {
        append("Сводка курсов к ").append(base)
        moment?.let { append(" на ").append(MOMENT.format(it)) }
        append('\n')
        append("Валют: ").append(values.size)
        append(", размах между крайними: ").append(spread.stripTrailingZeros().toPlainString().decimalComma())
        values.forEach { (code, value) ->
            append('\n').append(code).append(' ').append(value.toPlainString().decimalComma())
            if (values.size > 1) {
                if (code == highest.first) append(" — дороже всех")
                if (code == lowest.first) append(" — дешевле всех")
            }
        }
    }
}

/** Документ как JSON: не разобрался — это отказ по входу, а не сбой сервера. */
private fun parseDocument(document: String): JsonObject {
    val element = try {
        Json.parseToJsonElement(document)
    } catch (error: SerializationException) {
        throw PipelineInputException("$NOT_A_DOCUMENT Не разобрано как JSON: ${error.message}")
    }
    return element as? JsonObject ?: throw PipelineInputException(NOT_A_DOCUMENT)
}

/**
 * Курс из документа: число или строка с числом.
 *
 * Строка принимается наравне с числом: документ составляет первый шаг, но его может составить
 * и модель, а `"84.3414"` и `84.3414` для сводки — одно и то же значение. Отвергается то, что
 * числом не является: по пустому значению сводка посчитала бы ноль, а ноль — это утверждение
 * о курсе, которого в документе нет.
 */
private fun kotlinx.serialization.json.JsonElement.decimal(code: String): BigDecimal {
    val text = (this as? JsonPrimitive)?.contentOrNull
        ?: throw PipelineInputException("Курс $code не число: сводку считать не по чему.")
    return try {
        BigDecimal(text)
    } catch (error: NumberFormatException) {
        throw PipelineInputException("Курс $code не число: «$text».")
    }
}

/** Дробная часть через запятую: числа в сводке читает человек. */
private fun String.decimalComma(): String = replace('.', ',')
