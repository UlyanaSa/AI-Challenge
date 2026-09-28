package com.osvin.aichallenge.currency.remote

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyConfig
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateException
import com.osvin.aichallenge.currency.CurrencyRateProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.Closeable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Курсы ЦБ РФ: `GET {apiBase}/daily_json.js`.
 *
 * ЦБ отдаёт файл целиком: дату публикации и раздел `Valute` с валютами. Разбор идёт по ключам
 * ([JsonPrimitive.content] → [BigDecimal]), а не через `@Serializable`-DTO с `Double`: готового
 * сериализатора `BigDecimal` в kotlinx.serialization нет, объявить десятичное поле нечем, а
 * `Double` испортил бы значение — 95.8709 из него возвращается уже другим числом. Курс — деньги,
 * поэтому число читается текстом и разбирается в [BigDecimal] без промежуточного `Double`.
 * Незнакомые поля (дата, идентификаторы, названия) разбор не трогает: забираются только нужные.
 *
 * Курс приводится к цене одной единицы: в `Nominal` ЦБ указывает, за сколько единиц названо
 * значение (для евро, доллара и лари — 1, для иены — 100). Без деления `Value` на `Nominal`
 * один и тот же рублёвый курс значил бы разное для разных валют, а номинал пришлось бы тащить
 * через историю, сводку и инструмент до читателя, которому он не нужен. Деление — с масштабом
 * [RATE_SCALE] и [RoundingMode.HALF_UP]: фиксированный масштаб даёт значения одной длины,
 * и запись из истории сравнивается с расчётной без сюрпризов.
 *
 * Отслеживаются только валюты [Currency]; остальные ключи ответа игнорируются. Отсутствие
 * отслеживаемой валюты в ответе — не ошибка, а пустое место: сервис собирает то, что пришло,
 * а кого не хватает, видно вызывающему ([CurrencyRateProvider] описывает это как отличие
 * «поставщик никого не назвал» от «поставщика не было»).
 *
 * [receivedAt] берётся из [clock], а не из даты ответа ЦБ: история отвечает на вопрос «когда
 * сервис это узнал», а дата публикации — «на когда курс назначен»; это разные вопросы, и
 * смешивать их в одной колонке нельзя. Часы — параметр ещё и потому, что иначе проверка
 * разбора зависела бы от текущего времени.
 *
 * HTTP-клиент — параметр: проверки подставляют движок MockEngine и не выходят в сеть, рабочий
 * сервис берёт CIO. Владелец клиента один: созданный здесь закрывается в [close], а переданный
 * закрывает тот, кто его создал, — иначе [close] провайдера утащил бы чужой клиент, которым
 * пользуются другие. `Content-Type` не проверяется: ЦБ отдаёт JSON телом файла, а строка
 * заголовка на пути через прокси — не то, за что стоит отказывать в разборе.
 *
 * Сеть, таймаут, отказ и неразобранный ответ — [CurrencyRateException] с разными текстами:
 * это разные состояния, и по тексту видно, чинить связь, ждать или смотреть формат. Тело ответа
 * в текст ошибки не попадает: там может лежать чужая страница, а человеку нужен факт отказа.
 *
 * @param config Куда ходить за курсами.
 * @param clock Часы для момента получения; по умолчанию системные UTC.
 * @param client HTTP-клиент; null — провайдер создаёт свой на CIO и закрывает его сам.
 */
class CbrCurrencyRateProvider(
    private val config: CurrencyConfig,
    private val clock: Clock = Clock.systemUTC(),
    client: HttpClient? = null
) : CurrencyRateProvider, Closeable {

    /** Кем создан клиент: чужой закрывает тот, кто его передал, а не этот провайдер. */
    private val ownsClient: Boolean = client == null

    /** Клиент обмена: переданный в конструктор или созданный здесь на CIO. */
    private val httpClient: HttpClient = client ?: HttpClient(CIO)

    /**
     * Курсы по валютам, которые назвал ЦБ, в порядке объявления [Currency].
     *
     * Ошибки разбора переводятся в [CurrencyRateException] здесь, а не у вызывающего: только
     * здесь известны и адрес, и форма ответа, а вызывающему нужен один понятный отказ.
     */
    override suspend fun getRates(): List<CurrencyRate> {
        val body = fetch()
        val receivedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return try {
            parseRates(body, receivedAt)
        } catch (error: IllegalArgumentException) {
            // SerializationException — наследник IllegalArgumentException, поэтому одного catch
            // хватает и на нечитаемый текст, и на чужую форму ответа (корень не объект или
            // раздел Valute не объект).
            throw CurrencyRateException("ответ ЦБ не разобран: ${error.message}", error)
        }
    }

    /** Закрывает клиент, если он создан здесь: переданный закрывает его владелец. */
    override fun close() {
        if (ownsClient) httpClient.close()
    }

    /**
     * Файл курсов телом ответа.
     *
     * Адрес собирается из [CurrencyConfig.apiBase] и пути ЦБ, хвостовой слеш корня срезается:
     * источник задают и как `https://www.cbr-xml-daily.ru`, и как `…ru/`, и двойной слеш в пути
     * выглядел бы как чужая ссылка, хотя вёл бы туда же.
     *
     * Код ответа проверяется до чтения тела: не-2xx означает отказ, и разбирать его как курсы
     * незачем. Только код — сам ответ не разбирается и в текст не попадает.
     */
    private suspend fun fetch(): String {
        val response = fromNetwork { httpClient.get("${config.apiBase.trimEnd('/')}/$DAILY_PATH") }
        if (!response.status.isSuccess()) {
            throw CurrencyRateException("ЦБ ответил ${response.status.value}")
        }
        return fromNetwork { response.bodyAsText() }
    }

    /**
     * Обмен с ЦБ: сбой связи и таймаут становятся отказом поставщика.
     *
     * Исключение транспорта наружу не пускается, потому что вызывающий различает состояния
     * сервиса, а не движки HTTP: у него нет ни адреса, ни причин различать сокет и таймаут —
     * и то и другое значит «ЦБ не ответил». Отмена — не сбой: её должен увидеть вызывающий,
     * иначе остановка сбора выглядела бы как отказ сети.
     */
    private suspend fun <T> fromNetwork(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw CurrencyRateException("ЦБ не ответил: ${error.message}", error)
    }

    /**
     * Разбор ответа в курсы отслеживаемых валют.
     *
     * Пустой список здесь — это ответ без нужных валют, а не отказ: раздел `Valute` обязателен
     * у ЦБ, и его отсутствие означает, что разбирать нечего, — поэтому это ошибка формы, а не
     * «курсов нет».
     */
    private fun parseRates(body: String, receivedAt: Instant): List<CurrencyRate> {
        val valute = Json.parseToJsonElement(body).jsonObject[VALUTE]?.jsonObject
            ?: throw SerializationException("в ответе нет раздела «$VALUTE»")
        return valute.mapNotNull { (code, element) ->
            val currency = Currency.byCode(code) ?: return@mapNotNull null
            rateOf(currency, element, receivedAt)
        }.sortedBy { it.currency.ordinal }
    }

    /**
     * Курс одной валюты или null, если запись непригодна.
     *
     * Непригодной она считается, когда нет `Value`, значение или номинал не положительны либо
     * число не разбирается: записав такое в историю, сводка считала бы минимум по нулю, и
     * «минимум за сутки» перестал бы что-либо значить. Отсутствие `Nominal` — не порок: по
     * умолчанию он равен единице, как и у ЦБ, который для валют с номиналом 1 поле всё же
     * отдаёт, но полагаться на это в разборе чужого ответа не стоит.
     */
    private fun rateOf(currency: Currency, element: JsonElement, receivedAt: Instant): CurrencyRate? {
        val block = element as? JsonObject ?: return null
        val value = block[VALUE].decimal() ?: return null
        if (value.signum() <= 0) return null
        val nominal = block[NOMINAL]?.let { it.decimal() ?: return null } ?: BigDecimal.ONE
        if (nominal.signum() <= 0) return null
        return CurrencyRate(
            currency = currency,
            rateToRub = value.divide(nominal, RATE_SCALE, RoundingMode.HALF_UP),
            receivedAt = receivedAt
        )
    }

    private companion object {

        /** Путь файла курсов у ЦБ; корень источника приходит из настроек. */
        const val DAILY_PATH = "daily_json.js"

        /** Раздел ответа ЦБ с валютами. */
        const val VALUTE = "Valute"

        /** Поле с ценой названного номинала. */
        const val VALUE = "Value"

        /** Поле с числом единиц, за которые названа цена. */
        const val NOMINAL = "Nominal"

        /** Знаков после точки у курса: см. KDoc класса про фиксированный масштаб. */
        const val RATE_SCALE = 6
    }
}

/**
 * Число из поля ответа или null, если его там нет и оно не разбирается.
 *
 * Значение берётся текстом и разбирается в [BigDecimal]: сериализатора `BigDecimal` в разборе
 * JSON нет, а `Double` потерял бы точность на десятичной дроби, которой выражают деньги.
 * Не-число (объект, массив, `null`, строка не по форме) — тот же случай «числа нет»: различать
 * это вызывающему незачем, запись всё равно непригодна.
 */
private fun JsonElement?.decimal(): BigDecimal? =
    (this as? JsonPrimitive)?.content?.toBigDecimalOrNull()
