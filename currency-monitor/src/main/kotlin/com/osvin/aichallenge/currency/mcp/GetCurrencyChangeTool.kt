package com.osvin.aichallenge.currency.mcp

import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.currency.summary.CurrencyChange
import com.osvin.aichallenge.currency.summary.CurrencyChangeWindow
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

/**
 * Нижняя граница окна: изменение меньше часа по часовым сводкам не выражается.
 *
 * Границы объявлены открыто, а не спрятаны в обработчике: их же проверяет маршрут сервера
 * приложения, чтобы ответить 400 до обращения к сервису. Два числа в двух местах разошлись бы,
 * и отказ на границе выглядел бы как ошибка службы, а не как неверный запрос.
 */
const val MIN_CHANGE_HOURS = 1

/**
 * Верхняя граница окна — месяц.
 *
 * Предел объявлен, а не оставлен бесконечным: ряд сводок начинается с запуска службы, и «за
 * сколько часов» без границы превращалось бы в окно длиной в чужую опечатку (`hours=99999999`),
 * то есть в выборку всей таблицы. Месяц — тот же предел, что у периода `MONTH` в сводке
 * за период: шире него истории всё равно нет.
 */
const val MAX_CHANGE_HOURS = 720

/** Что сказать, когда сохранённых сводок в окне нет: пустой ответ выглядит как сбой. */
private const val NO_CHANGE_NOTE =
    "Сохранённых часовых сводок за это окно нет: служба ещё не закрыла ни одного часа. " +
        "Сводка часа появляется после того, как час прошёл; текущие курсы отдаёт get_currency_rates."

/**
 * Инструмент `get_currency_change`: изменение курсов за окно, собранное из часовых сводок.
 *
 * Аргумент — число часов, и он обязателен: у вопроса «как изменился курс» без окна нет ответа,
 * а значение по умолчанию означало бы ответ не на тот вопрос, который задали. Объявлен строкой
 * без списка значений (как `page` у GitHub): часов слишком много, чтобы печатать их схемой,
 * поэтому разбор ручной и с отказом в перечне границ — по отказу модель исправляется точнее,
 * чем по одному «неверный аргумент».
 *
 * Отвечает по сохранённым часовым сводкам, а не по минутной истории: тот же ряд, что видит
 * закреплённый чат, — иначе ответ инструмента и лента в приложении разошлись бы при первой
 * же пропущенной записи. Окно считается целыми часами и кончается последним закрытым часом:
 * про текущий, ещё не закончившийся час, сводки нет по определению.
 */
fun getCurrencyChangeTool(hours: DeclaredArgument): ServerTool<CurrencyToolsData> = ServerTool(
    name = CurrencyMcpServer.CHANGE_TOOL,
    description = "Как изменились курсы EUR, USD и GEL за последние N часов: первый и последний " +
        "курс окна, изменение в рублях и процентах, минимум, максимум и среднее по каждой валюте. " +
        "Считается по сохранённым часовым сводкам, поэтому окно измеряется целыми часами.",
    arguments = listOf(hours)
) { data, request ->
    val asked = request.argument(hours.name)?.trim()?.toIntOrNull()
    val allowed = "допустимое значение — целое число часов от $MIN_CHANGE_HOURS до $MAX_CHANGE_HOURS"
    when {
        asked == null -> CallToolResult(
            content = listOf(
                TextContent("hours не задан или не число: ${request.argument(hours.name) ?: "не задан"}; $allowed")
            ),
            isError = true
        )

        asked !in MIN_CHANGE_HOURS..MAX_CHANGE_HOURS -> CallToolResult(
            content = listOf(TextContent("hours вне допустимого: $asked; $allowed")),
            isError = true
        )

        else -> try {
            CallToolResult(content = listOf(TextContent(changeReport(data.hourly.change(asked)))))
        } catch (error: CurrencyStorageException) {
            CallToolResult(
                content = listOf(TextContent(error.message ?: "часовые сводки прочитать не удалось")),
                isError = true
            )
        }
    }
}

/**
 * Ответ инструмента: спрошенное окно, покрытое окно и изменение по каждой валюте.
 *
 * Спрошенное и покрытое стоят рядом намеренно: сохранённых часов может быть меньше, чем
 * спрошено, — служба могла стоять, история могла только начаться. Молча вернуть числа за
 * меньшее окно значило бы выдать их за изменение за сутки; поэтому `hoursCovered` и границы
 * `from`/`to` показывают, что именно измерено.
 *
 * Валюты идут все три, даже если по одной из них сводок нет: «по гелю за сутки ничего не
 * известно» — это ответ, а пропущенная валюта читалась бы как «гель не отслеживается».
 */
internal fun changeReport(window: CurrencyChangeWindow): String =
    CURRENCY_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("hours", window.hours)
            put("from", window.from?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("to", window.to?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("hoursCovered", window.hoursCovered)
            putJsonArray("currencies") { window.changes.forEach { add(changeJson(it)) } }
            if (window.hoursCovered == 0) put("note", NO_CHANGE_NOTE)
        }
    )

/** Изменение одной валюты: числа как числа, неизвестное — null. */
private fun changeJson(change: CurrencyChange): JsonObject = buildJsonObject {
    put("currency", change.currency.code)
    put("firstRate", change.firstRate.asJson())
    put("lastRate", change.lastRate.asJson())
    put("change", change.change.asJson())
    put("changePercent", change.changePercent.asJson())
    put("minRate", change.minRate.asJson())
    put("maxRate", change.maxRate.asJson())
    put("averageRate", change.averageRate.asJson())
    put("samples", change.samples)
    put("hours", change.hours)
}
