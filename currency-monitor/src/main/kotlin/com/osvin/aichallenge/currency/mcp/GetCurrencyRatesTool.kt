package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.mcp.ServerTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.temporal.ChronoUnit

/**
 * Формат ответов инструментов: многострочный JSON.
 *
 * С отступами, как у инструментов проекта: ответ читают и модель, и человек в карточке вызова,
 * а одна строка на весь ответ в карточке не читается. Разбору отступы не мешают — это тот же
 * JSON, и ключи те же.
 */
internal val CURRENCY_JSON = Json { prettyPrint = true }

/** Что сказать, когда истории ещё нет вовсе: пустые курсы без объяснения выглядят как сбой. */
private const val NO_RATES_NOTE =
    "Курсы ещё не собраны: ни одного обновления не было. Значения появятся после первого " +
        "обращения к поставщику."

/**
 * Инструмент `get_currency_rates`: последние сохранённые курсы EUR, USD и GEL к рублю.
 *
 * Аргументов нет: «покажи текущие курсы» — это вопрос про всё, что сервис отслеживает, и
 * список отслеживаемых валют объявлен кодом. Аргумент-выбор валюты позволил бы спросить
 * «только евро», но ответ был бы тем же вычислением с фильтром в конце — за это платили бы
 * лишним полем в схеме, которое модель должна помнить.
 *
 * Отказ хранилища — результат с `isError`, а не исключение: так модель получает причину словами,
 * а сервер продолжает работать (иначе отказ базы выглядел бы как падение инструмента, и клиент
 * увидел бы отказ вызова, которого не делал).
 */
fun getCurrencyRatesTool(): ServerTool<CurrencyToolsData> = ServerTool(
    name = "get_currency_rates",
    description = "Последние сохранённые курсы EUR, USD и GEL к рублю: сколько рублей стоит " +
        "одна единица валюты и когда эти значения получены."
) { data, _ ->
    try {
        CallToolResult(content = listOf(TextContent(ratesReport(data.service.latest()))))
    } catch (error: CurrencyStorageException) {
        CallToolResult(
            content = listOf(TextContent(error.message ?: "историю курсов прочитать не удалось")),
            isError = true
        )
    }
}

/**
 * Ответ инструмента: момент последнего обновления и курсы по кодам валют.
 *
 * Порядок валют — объявление перечисления, а не порядок строк в базе: ответ читает человек,
 * и EUR, USD, GEL в нём должны стоять одинаково при каждом вызове. Валюта без истории в ответ
 * не попадает: пустой объект курса или ноль значили бы «курс равен нулю», а это не так.
 *
 * `updatedAt` — момент самого свежего значения, а не время ответа: агент отвечает по этим
 * данным, и по этому полю видно, насколько они старые. Когда истории нет вовсе, поле null,
 * а рядом стоит [NO_RATES_NOTE]: иначе пустые курсы читались бы как поломка.
 */
internal fun ratesReport(rates: List<CurrencyRate>): String {
    val updatedAt = rates.map { it.receivedAt }.maxOrNull()?.truncatedTo(ChronoUnit.SECONDS)
    return CURRENCY_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("updatedAt", updatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            putJsonObject("rates") {
                Currency.entries.forEach { currency ->
                    val rate = rates.firstOrNull { it.currency == currency } ?: return@forEach
                    put(currency.code, JsonPrimitive(rate.rateToRub))
                }
            }
            if (rates.isEmpty()) put("note", NO_RATES_NOTE)
        }
    )
}
