package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Снимок текущих курсов: ответ `GET /v1/currency`.
 *
 * Это копия серверной модели, как и остальные модели ответов: курсами владеет сервер,
 * а клиент их только печатает. Числа объявлены строкой и читаются [DecimalText]:
 * у чисел на проводе берётся их исходный текст, поэтому «95.8709» доходит до ленты
 * теми же цифрами, а `Double` в деньги не заводится.
 *
 * Пустые значения — не сбой разбора, а ответ сервера: курсов может не быть вовсе
 * (тогда [note] объясняет почему словами), а время обновления может не прийти.
 * Такой снимок не печатается в ленту, но и ошибкой не считается.
 *
 * @param updatedAt Когда сервер посчитал курсы (ISO-8601, UTC); null — метки нет,
 *        тогда строка ленты берёт время удара монитора.
 * @param rates Курсы по коду валюты: значение — цифры, как их послал сервер; null или
 *        пусто — курсов нет, и печатать нечего.
 * @param note Пояснение словами, почему курсов нет; null — пояснять нечего.
 */
@Serializable
data class CurrencySnapshot(
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("rates") val rates: Map<String, @Serializable(with = DecimalText::class) String>? = null,
    @SerialName("note") val note: String? = null
)

/**
 * Изменение курсов за окно: ответ `GET /v1/currency/change?hours=N`.
 *
 * Этим же ответом пользуются оба места, где показано изменение: шапка закреплённого
 * чата спрашивает сутки ([hours] = 24), а сводка часа — один час ([hours] = 1).
 *
 * [hoursCovered] не повторяет [hours] намеренно: сервер говорит, сколько часов из
 * запрошенного окна он действительно наблюдал. У только что установленного приложения
 * наблюдений меньше суток, и [CurrencyChangeCard] честно об этом предупреждает, вместо
 * того чтобы называть неполные данные суточной картиной.
 *
 * @param hours Сколько часов просил клиент.
 * @param hoursCovered Сколько часов из них сервер покрыл наблюдениями.
 * @param from Начало окна (ISO-8601, UTC); null — сервер начала не назвал.
 * @param to Конец окна (ISO-8601, UTC); null — сервер конца не назвал.
 * @param currencies Изменение по валютам; пусто — сервер не назвал ни одной.
 */
@Serializable
data class CurrencyChangeFeed(
    @SerialName("hours") val hours: Int,
    @SerialName("hoursCovered") val hoursCovered: Int,
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("currencies") val currencies: List<CurrencyChangeItem> = emptyList()
)

/**
 * Изменение одной валюты за окно: чем курс был в начале, чем стал и как отличается.
 *
 * Все числа, кроме [samples] и [hours], могут быть null, если сервер их не посчитал, —
 * поэтому в тексте строки каждая из них встречает своё «не пришло», а не ноль:
 * ноль был бы утверждением о курсе, которого сервер не делал.
 *
 * @param currency Код валюты — как на проводе (`EUR`, `USD`, `GEL`).
 * @param firstRate Курс на начало окна; null — начала сервер не наблюдал.
 * @param lastRate Курс сейчас (на конец окна); null — наблюдений нет.
 * @param change Разница между курсами; null — сервер разницу не назвал.
 * @param changePercent Та же разница в процентах; null — процентов нет.
 * @param minRate Минимум за окно; null — не посчитан.
 * @param maxRate Максимум за окно; null — не посчитан.
 * @param averageRate Средний курс за окно; null — не посчитан.
 * @param samples Сколько замеров легло в окно.
 * @param hours Сколько часов окна покрыто наблюдениями по этой валюте.
 */
@Serializable
data class CurrencyChangeItem(
    @SerialName("currency") val currency: String,
    @SerialName("firstRate") @Serializable(with = DecimalText::class) val firstRate: String? = null,
    @SerialName("lastRate") @Serializable(with = DecimalText::class) val lastRate: String? = null,
    @SerialName("change") @Serializable(with = DecimalText::class) val change: String? = null,
    @SerialName("changePercent") @Serializable(with = DecimalText::class) val changePercent: String? = null,
    @SerialName("minRate") @Serializable(with = DecimalText::class) val minRate: String? = null,
    @SerialName("maxRate") @Serializable(with = DecimalText::class) val maxRate: String? = null,
    @SerialName("averageRate") @Serializable(with = DecimalText::class) val averageRate: String? = null,
    @SerialName("samples") val samples: Int,
    @SerialName("hours") val hours: Int
)

/**
 * Строка валюты в тексте: `EUR 95,5000 → 96,0000 (+0,5000, +0,52%)`.
 *
 * Одна на два места — сводку часа в ленте и шапку «изменение за сутки»: это одна и та же
 * картина, и вторая отрисовка разошлась бы с первой на первой же правке формата.
 * Цифры берутся с провода как есть, меняется только разделитель
 * ([decimalComma]); пропущенное значение печатается прочерком, а не нулём.
 *
 * Когда по валюте нет ни одного наблюдения, так и говорится: прочерк и слова «наблюдений
 * нет». Иначе два прочерка с припиской «без изменения» читались бы как «курс стоял на месте»,
 * хотя сервер курса в этом окне и не видел, — а это утверждение о данных, которого не делали.
 * Случай «курсы есть, а изменения сервер не назвал» остаётся отдельным: там прочерк на месте
 * разницы был бы обрывом строки, и разница печатается словами.
 */
fun CurrencyChangeItem.line(): String {
    if (firstRate == null && lastRate == null) return "$currency $ABSENT (наблюдений нет)"
    val from = firstRate?.decimalComma() ?: ABSENT
    val to = lastRate?.decimalComma() ?: ABSENT
    val detail = change?.let { value ->
        value.signed() + (changePercent?.let { ", ${it.signed()}%" } ?: "")
    } ?: "без изменения"
    return "$currency $from → $to ($detail)"
}

/** Прочерк на месте числа, которого сервер не прислал. */
private const val ABSENT = "—"

/**
 * Десятичная запятая: сервер присылает цифры с точкой, а в русском тексте разделитель —
 * запятая. Замена символа, а не разбор числа: цифры остаются ровно теми, что пришли,
 * и ни один разряд не теряется.
 */
internal fun String.decimalComma(): String = replace('.', ',')

/**
 * Число со знаком: положительному сервер знак не пишет, а в тексте он нужен — иначе рост
 * курса не отличить от его отсутствия. Минус приходит с проводом как есть, поэтому
 * на отрицательное число знак не добавляется, иначе вышло бы «+-0,5».
 */
private fun String.signed(): String = (if (startsWith("-")) "" else "+") + decimalComma()
