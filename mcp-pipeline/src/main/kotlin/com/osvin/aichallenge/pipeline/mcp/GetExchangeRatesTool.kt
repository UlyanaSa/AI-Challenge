package com.osvin.aichallenge.pipeline.mcp

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyStorageException
import com.osvin.aichallenge.mcp.DeclaredArgument
import com.osvin.aichallenge.mcp.ServerTool
import com.osvin.aichallenge.mcp.argument
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

/**
 * Формат ответов инструментов пайплайна: многострочный JSON.
 *
 * С отступами, как у инструментов проекта: ответ читают и модель, и человек в карточке вызова,
 * а одна строка на весь ответ в карточке не читается. Разбору отступы не мешают — это тот же
 * JSON, и ключи те же.
 */
internal val PIPELINE_JSON = Json { prettyPrint = true }

/**
 * Точность кросс-курса: шесть знаков после запятой.
 *
 * На два знака больше, чем хранит служба (курс ЦБ — четыре): деление на курс базы теряет
 * последнюю цифру, и при четырёх знаках ответ расходился бы с пересчётом человека на этом
 * последнем знаке. Округление `HALF_UP`, а не `DOWN`: значение без округления не сохранить,
 * а вниз округление молча занижало бы все пересчитанные курсы.
 */
private const val CROSS_SCALE = 6

/** Что сказать, когда истории нет вовсе: пустой документ второй шаг обработать не может. */
private const val NO_RATES =
    "Курсы ещё не собраны: ни одного обновления не было. Значения появятся после первого " +
        "обращения службы к поставщику."

/**
 * Инструмент `getExchangeRates`: текущие курсы к выбранной базе — первый шаг цепочки.
 *
 * Отвечает документом, который целиком принимает следующий шаг ([summarizeRates]): ради этого
 * база и момент получения стоят в ответе рядом с курсами, а не отдельными инструментами.
 * Модели остаётся передать ответ дальше, а не пересказывать его: пересказ терял бы цифры,
 * и в файл попадала бы не та сводка, которую посчитал сервер.
 *
 * База — аргумент, а не всегда рубль: история хранится к рублю (так её пишет служба), но вопрос
 * «сколько долларов стоит евро» законен, и пересчёт делается здесь, где есть оба курса. Курс
 * рубля к рублю не пересчитывается — он равен единице по определению, и деление на себя
 * показало бы округление там, где его нет.
 *
 * Отказ хранилища и недопустимый аргумент — результат с `isError`, а не исключение: так модель
 * получает причину словами и может исправить вызов, а сервер продолжает работать.
 */
fun getExchangeRatesTool(
    base: DeclaredArgument,
    currencies: DeclaredArgument
): ServerTool<PipelineToolsData> = ServerTool(
    name = PipelineMcpServer.EXCHANGE_RATES_TOOL,
    description = "Текущие курсы валют к базе (по умолчанию к рублю). Ответ — JSON целиком: " +
        "его принимает summarizeRates, читать и пересказывать его не нужно.",
    arguments = listOf(base, currencies)
) { data, request ->
    try {
        val askedBase = request.argument(base.name)?.let(::baseCurrency) ?: PipelineMcpServer.RUB
        val asked = request.argument(currencies.name)?.let(::askedCurrencies)
        CallToolResult(content = listOf(TextContent(exchangeRates(data.repository.latest(), askedBase, asked))))
    } catch (error: PipelineInputException) {
        CallToolResult(content = listOf(TextContent(error.message.orEmpty())), isError = true)
    } catch (error: CurrencyStorageException) {
        CallToolResult(
            content = listOf(TextContent(error.message ?: "историю курсов прочитать не удалось")),
            isError = true
        )
    }
}

/**
 * Ответ инструмента: база, момент последнего обновления и курсы к этой базе.
 *
 * Порядок валют — объявление перечисления, а не порядок строк в базе: ответ читает человек,
 * и EUR, USD, GEL должны стоять в нём одинаково при каждом вызове. Валюта без истории в курсы
 * не попадает, а называется отдельным полем `missing`: пустой объект или ноль значили бы
 * «курс равен нулю», а это не так, и второй шаг посчитал бы по этому нулю сводку. В `missing`
 * названы только те валюты, о которых спросили: жаловаться на валюту, которой в вызове не было,
 * значило бы отвечать не на заданный вопрос.
 *
 * `updatedAt` — момент самого свежего значения, а не время ответа: по нему видно, насколько
 * данные старые, и он уезжает в сводку.
 */
internal fun exchangeRates(stored: List<CurrencyRate>, base: String, asked: List<String>?): String {
    val rates = stored
        .filter { asked == null || it.currency.code in asked }
        .sortedBy { it.currency.ordinal }
    if (rates.isEmpty()) throw PipelineInputException(NO_RATES)

    val baseRate = stored.firstOrNull { it.currency.code == base }?.rateToRub
    if (base != PipelineMcpServer.RUB && baseRate == null) {
        throw PipelineInputException("Курс базы $base ещё не собран: пересчитать к ней нечем.")
    }
    val absent = (asked ?: Currency.entries.map { it.code })
        .filter { code -> rates.none { it.currency.code == code } }

    return PIPELINE_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("base", JsonPrimitive(base))
            put(
                "updatedAt",
                JsonPrimitive(rates.maxOf { it.receivedAt }.truncatedTo(ChronoUnit.SECONDS).toString())
            )
            putJsonObject("rates") {
                rates.forEach { rate ->
                    put(rate.currency.code, JsonPrimitive(rate.toBase(base, baseRate)))
                }
            }
            if (absent.isNotEmpty()) put("missing", JsonArray(absent.map { JsonPrimitive(it) }))
        }
    )
}

/**
 * Курс к базе: к рублю — как записан, к другой базе — частное от деления на курс самой базы,
 * а у самой базы — единица.
 *
 * Единица именно так, а не делением курса на себя: деление с округлением вернуло бы `1.000000`,
 * и «сколько базы стоит единица базы» выглядело бы вычислением, которого не было.
 */
private fun CurrencyRate.toBase(base: String, baseRate: BigDecimal?): BigDecimal = when {
    currency.code == base -> BigDecimal.ONE
    baseRate == null -> rateToRub
    else -> rateToRub.divide(baseRate, CROSS_SCALE, RoundingMode.HALF_UP)
}

/**
 * База из вызова: рубль или валюта, которую служба отслеживает.
 *
 * Свой список, а не разбор перечисления на месте: рубль не валюта истории (её код не встречается
 * среди собираемых), но база по умолчанию — именно он, и проверка «рубль или известная валюта»
 * стоит в одном месте.
 */
internal fun baseCurrency(value: String): String {
    val code = value.trim().uppercase()
    if (code.isEmpty()) throw PipelineInputException("База названа пусто: назовите RUB, EUR, USD или GEL.")
    if (code == PipelineMcpServer.RUB) return code
    if (Currency.byCode(code) == null) {
        val known = (listOf(PipelineMcpServer.RUB) + Currency.entries.map { it.code }).joinToString(", ")
        throw PipelineInputException("База $code неизвестна: служба следит за $known.")
    }
    return code
}

/**
 * Список валют из аргумента: `EUR, USD` → `[EUR, USD]`.
 *
 * Повторы убираются, а неизвестный код — отказ, а не пропуск: пропущенная валюта выглядела бы
 * как валюта без курса, и причину пришлось бы искать в истории, хотя дело в вызове.
 */
internal fun askedCurrencies(value: String): List<String> {
    val codes = value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    if (codes.isEmpty()) throw PipelineInputException("Список валют пуст: назовите EUR, USD или GEL.")
    codes.forEach { code ->
        if (Currency.byCode(code) == null) {
            throw PipelineInputException(
                "Валюта $code неизвестна: служба следит за ${Currency.entries.joinToString(", ") { it.code }}."
            )
        }
    }
    return codes
}
