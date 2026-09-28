package com.osvin.aichallenge.currency.storage

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateRepository
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * История курсов в файле SQLite: выборки и вставки на JDBC, без ORM.
 *
 * Запросов здесь три с половиной, и все они — «последняя запись по валюте», «записи за окно»
 * и «запись до момента»: столько, сколько спрашивают сводка и инструменты. ORM на таком наборе
 * дала бы разбор схемы и прокси ради того, что здесь видно целиком, а `Room` не подходит ещё
 * и потому, что это Android-библиотека с обработкой аннотаций, а модуль живёт на JVM.
 *
 * Курс хранится текстом (`BigDecimal.toPlainString()`) и читается обратно через
 * `BigDecimal(текст)`. Числовой столбец в SQLite — это `Double` (`REAL`), и 95.42 ложится в него
 * не собой: ближайшее двоичное число — 95.4200000000000017053025658242404460906982421875.
 * Вернуть из него ровно 95.42 можно, только укоротив, а укорочение — это округление, которого
 * при чтении курса делать нечем: неизвестно, сколько знаков после запятой у него было. Текст
 * сохраняет десятичное значение как есть, вместе с нулями в конце, которые числовой столбец
 * потерял бы.
 *
 * Время тоже текст, ISO-8601 в UTC с точностью до секунды. Точность важна: пока у всех записей
 * она одна, лексикографический порядок текста совпадает с хронологическим, и `ORDER BY` в SQL
 * сортирует по времени верно, не умея разбирать даты. «10:00:00Z» и «10:00:00.5Z» — пример,
 * где это перестало бы работать: `'Z'` больше `'.'`, и полносекундная запись встала бы позже
 * дробной, хотя случилась раньше. Секунда — тот же предел, что у [CurrencyRate.receivedAt].
 *
 * Валюта в ответе не читается из строки: все выборки и так фильтруют по одной валюте, поэтому
 * строка отдаёт только цену и время — колонка, по которой уже отобрано, ничего нового не скажет.
 *
 * @param database Соединение и замок на него; владеет базой вызывающий и закрывает её вместе
 *        с хранилищем.
 */
class SqliteCurrencyRateRepository(private val database: CurrencyDatabase) : CurrencyRateRepository {

    /**
     * Хранилище по пути к файлу: база открывается и закрывается вместе с ним.
     *
     * Нужен затем, что путь — это всё, чем располагает `main` (в настройках лежит `Path`),
     * и заводить в точке входа отдельную сущность только ради соединения было бы незачем.
     */
    constructor(path: Path) : this(CurrencyDatabase(path))

    override suspend fun save(rates: List<CurrencyRate>): Int = database.write("курсы не сохранены") { connection ->
        var inserted = 0
        // По одной вставке, без `executeBatch`: строк в обновлении ровно столько, сколько валют,
        // а точный счётчик строк нужен вызывающему, чтобы отличить частичную запись от полной.
        connection.prepareStatement(INSERT_RATE).use { statement ->
            rates.forEach { rate ->
                statement.setString(1, rate.currency.code)
                statement.setString(2, rate.rateToRub.toPlainString())
                statement.setString(3, rate.receivedAt.stored())
                inserted += statement.executeUpdate()
            }
        }
        inserted
    }

    override suspend fun latest(): List<CurrencyRate> = database.read("последние курсы не прочитаны") { connection ->
        // Обход перечисления, а не один запрос с оконной функцией: валют три, и порядок ответа
        // задаётся здесь же, где объявлен список валют, — сортировкой результата это пришлось бы
        // выражать отдельно и повторять в каждой выборке.
        connection.prepareStatement(SELECT_LATEST).use { statement ->
            Currency.entries.mapNotNull { currency ->
                statement.setString(1, currency.code)
                statement.executeQuery().use { rows -> if (rows.next()) rows.rate(currency) else null }
            }
        }
    }

    override suspend fun between(currency: Currency, from: Instant, to: Instant): List<CurrencyRate> =
        database.read("история за период не прочитана") { connection ->
            connection.prepareStatement(SELECT_BETWEEN).use { statement ->
                statement.setString(1, currency.code)
                statement.setString(2, from.stored())
                statement.setString(3, to.stored())
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.rate(currency)) }
                }
            }
        }

    override suspend fun previous(currency: Currency, before: Instant): CurrencyRate? =
        database.read("предыдущий курс не прочитан") { connection ->
            connection.prepareStatement(SELECT_PREVIOUS).use { statement ->
                statement.setString(1, currency.code)
                statement.setString(2, before.stored())
                statement.executeQuery().use { rows -> if (rows.next()) rows.rate(currency) else null }
            }
        }

    override fun close() {
        database.close()
    }

    private companion object {

        const val INSERT_RATE =
            "INSERT INTO $CURRENCY_RATES_TABLE (currency, rate_to_rub, received_at) VALUES (?, ?, ?)"

        /**
         * Последняя запись валюты. `id` в хвосте сортировки разрешает ничью: два обновления
         * с одним моментом (так бывает при ручном запуске сразу после планового) различимы
         * только порядком вставки, а не временем.
         */
        const val SELECT_LATEST =
            "SELECT rate_to_rub, received_at FROM $CURRENCY_RATES_TABLE " +
                "WHERE currency = ? ORDER BY received_at DESC, id DESC LIMIT 1"

        /** Записи окна по возрастанию времени; равенство моментов разрешается порядком вставки. */
        const val SELECT_BETWEEN =
            "SELECT rate_to_rub, received_at FROM $CURRENCY_RATES_TABLE " +
                "WHERE currency = ? AND received_at >= ? AND received_at <= ? " +
                "ORDER BY received_at ASC, id ASC"

        /**
         * Предыдущая запись — строго раньше момента. Нестрогое сравнение запрещено на уровне
         * запроса: все валюты одного обновления записаны одним моментом, и `<=` вернуло бы
         * сам текущий курс, а «изменение» всегда оказалось бы нулевым.
         */
        const val SELECT_PREVIOUS =
            "SELECT rate_to_rub, received_at FROM $CURRENCY_RATES_TABLE " +
                "WHERE currency = ? AND received_at < ? ORDER BY received_at DESC, id DESC LIMIT 1"
    }
}

/**
 * Момент в виде текста для базы: ISO-8601 UTC, секунды — тот же предел, что у курса.
 *
 * Усечение здесь, а не только при создании [CurrencyRate], потому что границы окна и «до какого
 * момента» приходят от вызывающего с любым числом знаков: сравнивать их с усечёнными строками
 * можно, лишь приведя к той же точности.
 */
private fun Instant.stored(): String =
    DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.SECONDS))

/** Строка истории: цена и момент — валюта известна из запроса и передаётся вызывающим. */
private fun ResultSet.rate(currency: Currency): CurrencyRate = CurrencyRate(
    currency = currency,
    rateToRub = BigDecimal(getString("rate_to_rub")),
    receivedAt = Instant.parse(getString("received_at"))
)
