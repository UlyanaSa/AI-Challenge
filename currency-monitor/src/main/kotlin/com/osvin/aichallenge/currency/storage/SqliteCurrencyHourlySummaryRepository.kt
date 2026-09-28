package com.osvin.aichallenge.currency.storage

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummary
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryRepository
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

/**
 * Часовые сводки в том же файле SQLite, что и история курсов, — на JDBC и без ORM.
 *
 * Файл один на два ряда, и это не экономия: сводку часа считают по минутной истории, поэтому
 * читать её и писать надо из одного места, а два файла дали бы два соединения и вопрос «а что
 * если один отстал». Соединение с замком приходит снаружи ([CurrencyDatabase]) — тот же, что
 * у истории курсов, и закрывает его владелец файла, а не это хранилище.
 *
 * Числа лежат текстом по той же причине, что и курсы: `REAL` вернул бы 95.42 уже другим
 * числом, а сводка — это деньги. Час — тоже текст, ISO-8601 UTC: пока у всех строк точность
 * одна, лексикографический порядок совпадает с хронологическим, и `ORDER BY hour` сортирует
 * по времени, не разбирая даты.
 *
 * Запись — `INSERT OR REPLACE` по ключу «час + валюта»: час закрывается заново на каждом
 * обходе, пока по нему есть записи, и повторная запись обязана быть безобидной. Ограничение
 * уникальности объявлено индексом в схеме ([CurrencyDatabase]), а не заменой через удаление:
 * так повторный запуск процесса с той же базой ведёт себя так же, как повторный обход в нём.
 *
 * @param database Открытая база с историей курсов и сводками.
 */
class SqliteCurrencyHourlySummaryRepository(
    private val database: CurrencyDatabase
) : CurrencyHourlySummaryRepository {

    override suspend fun save(summaries: List<CurrencyHourlySummary>): Int =
        database.write("часовые сводки не сохранены") { connection ->
            var written = 0
            connection.prepareStatement(UPSERT_SUMMARY).use { statement ->
                summaries.forEach { summary -> written += statement.bind(summary) }
            }
            written
        }

    override suspend fun between(from: Instant, to: Instant): List<CurrencyHourlySummary> =
        database.read("часовые сводки не прочитаны") { connection ->
            connection.prepareStatement(SELECT_BETWEEN).use { statement ->
                statement.setString(1, from.stored())
                statement.setString(2, to.stored())
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) rows.summary()?.let(::add) }
                }
            }
        }

    /** Кладёт сводку в подготовленную вставку: порядок полей тот же, что в [UPSERT_SUMMARY]. */
    private fun PreparedStatement.bind(summary: CurrencyHourlySummary): Int {
        setString(1, summary.hour.stored())
        setString(2, summary.currency.code)
        setString(3, summary.firstRate.toPlainString())
        setString(4, summary.lastRate.toPlainString())
        setString(5, summary.change.toPlainString())
        // Процента может не быть: у нулевого курса он не определён. `setString` с null пишет
        // NULL — это и есть «неизвестно», в отличие от нуля, который читался бы как «не менялся».
        setString(6, summary.changePercent?.toPlainString())
        setString(7, summary.minRate.toPlainString())
        setString(8, summary.maxRate.toPlainString())
        setString(9, summary.averageRate.toPlainString())
        setInt(10, summary.samples)
        return executeUpdate()
    }

    /**
     * Строка сводки; null — код валюты в строке не наш (такую валюту не отслеживаем).
     *
     * Строка чужой валюты пропускается, а не превращается в отказ: таблицу можно открыть
     * и поправить руками, и одна лишняя строка не должна ронять чтение всего окна. Своих
     * строк это не касается: сводки пишет сервис из закрытого перечисления.
     */
    private fun ResultSet.summary(): CurrencyHourlySummary? {
        val currency = Currency.byCode(getString("currency")) ?: return null
        return CurrencyHourlySummary(
            currency = currency,
            hour = Instant.parse(getString("hour")),
            firstRate = BigDecimal(getString("first_rate")),
            lastRate = BigDecimal(getString("last_rate")),
            change = BigDecimal(getString("change_rate")),
            changePercent = getString("change_percent")?.let { BigDecimal(it) },
            minRate = BigDecimal(getString("min_rate")),
            maxRate = BigDecimal(getString("max_rate")),
            averageRate = BigDecimal(getString("average_rate")),
            samples = getInt("samples")
        )
    }

    private companion object {

        val UPSERT_SUMMARY = """
            INSERT OR REPLACE INTO $CURRENCY_HOURLY_SUMMARIES_TABLE (
                hour, currency, first_rate, last_rate, change_rate, change_percent,
                min_rate, max_rate, average_rate, samples
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        /** Окно входит обеими границами; равенство часов разрешается порядком вставки. */
        const val SELECT_BETWEEN =
            "SELECT hour, currency, first_rate, last_rate, change_rate, change_percent, " +
                "min_rate, max_rate, average_rate, samples FROM $CURRENCY_HOURLY_SUMMARIES_TABLE " +
                "WHERE hour >= ? AND hour <= ? ORDER BY hour ASC, id ASC"
    }
}
