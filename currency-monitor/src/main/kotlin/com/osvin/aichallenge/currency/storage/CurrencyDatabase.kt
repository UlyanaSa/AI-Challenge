package com.osvin.aichallenge.currency.storage

import com.osvin.aichallenge.currency.CurrencyStorageException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Имя таблицы истории: одно на объявление схемы здесь и на запросы к ней в хранилище. */
internal const val CURRENCY_RATES_TABLE = "currency_rates"

/** Имя таблицы часовых сводок: объявление схемы здесь, запросы — в хранилище сводок. */
internal const val CURRENCY_HOURLY_SUMMARIES_TABLE = "currency_hourly_summaries"

/**
 * Файл истории курсов: открытие, прагмы, схема и один замок на все обращения к драйверу.
 *
 * Соединение одно на процесс и живёт столько же, сколько сервис: пишет в него планировщик,
 * читают инструменты, и живут они в одном процессе. Пул соединений здесь был бы лишним — он
 * завёл бы несколько соединений к файлу, который SQLite всё равно пускает на запись по одному,
 * и выигрыш в параллелизме чтений не окупил бы ни настройки, ни закрытия. Вместо пула — замок:
 * обращения сериализуются, и драйвер видит те же строки в том же порядке, в каком они записаны.
 *
 * Замок — монитор, а не `Mutex`: [close] объявлен интерфейсом хранилища как не-suspend, и мьютекс
 * в нём пришлось бы брать блокирующим ожиданием. Блокирующее ожидание здесь никого не задерживает:
 * каждое обращение к драйверу идёт на `Dispatchers.IO`, у которого потоков хватает на то, чтобы
 * один из них постоял в мониторе. Закрытие — исключение из этого правила и единственное: оно
 * приходит с остановки процесса, ждать в нём нечего, и оно тоже берёт тот же замок.
 *
 * Прагмы задаются при открытии. `journal_mode=WAL` — чтобы читатель не ждал писателя: история
 * дописывается по расписанию, а инструмент читает её в тот же момент, и в режиме журнала по
 * умолчанию эти двое блокировали бы друг друга. `busy_timeout=5000` — потому что WAL не отменяет
 * блокировку писателя писателем: если по тому же файлу пройдёт второй процесс (например, ручной
 * `--once` рядом с работающим сервисом), драйвер подождёт пять секунд, а не откажет сразу.
 *
 * Схема объявляется здесь же и через `IF NOT EXISTS`: файл обычный, его открывают повторно после
 * перезапуска, и повторное открытие не должно быть ошибкой. Строки таблиц читают и пишут хранилища
 * ([SqliteCurrencyRateRepository] — курсы, [SqliteCurrencyHourlySummaryRepository] — часовые сводки),
 * а здесь только DDL: у схемы должно быть одно место, и вопрос «какие таблицы есть в файле»
 * выясняется из одного файла, а не из двух хранилищ.
 *
 * Часовые сводки лежат в том же файле, что и курсы, хотя ряд у них свой: сводка часа считается
 * по минутной истории того же часа. Разнести их по файлам значило бы завести два соединения
 * и вопрос «а не отстал ли один из двух» там, где нужен один согласованный взгляд на данные.
 * Таблица у сводок своя: у них другой ключ (час и валюта), свои числа и переписывание вместо
 * дописывания, и складывать два ряда в одну таблицу значило бы держать в каждой строке половину
 * столбцов пустыми.
 *
 * @param path Файл базы; каталог создаётся при открытии.
 */
class CurrencyDatabase(private val path: Path) : AutoCloseable {

    /** Замок на соединение: без него две корутины писали бы в одно соединение одновременно. */
    private val lock = Any()

    private var currentConnection: Connection? = null

    /** Закрыта ли база: закрытая не открывается заново молча — иначе [close] ничего не значил бы. */
    private var closed = false

    /**
     * Чтение под замком, на `Dispatchers.IO`.
     *
     * @param what Что делали: этим начинается текст отказа, чтобы по нему было видно, какая
     *        выборка не удалась.
     * @throws CurrencyStorageException База закрыта или драйвер отказал.
     */
    suspend fun <T> read(what: String, block: (Connection) -> T): T = guarded(what, block)

    /**
     * Запись одной транзакцией под замком, на `Dispatchers.IO`.
     *
     * Автофиксация включается обратно в любом случае: после неудачной записи следующее чтение
     * не должно идти внутри незакрытой транзакции, а после удачной коммитить уже нечего.
     *
     * @param what Что делали: этим начинается текст отказа.
     * @throws CurrencyStorageException База закрыта, драйвер отказал или транзакция отменена.
     */
    suspend fun <T> write(what: String, block: (Connection) -> T): T = guarded(what) { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            // Откат обязателен: половина обновления в истории выглядела бы как «в этот час знали
            // только доллар». Если не удался и он, исходная причина важнее — её и бросаем, а отказ
            // отката остаётся при ней подавленным, чтобы не потеряться совсем.
            try {
                connection.rollback()
            } catch (rollbackError: SQLException) {
                error.addSuppressed(rollbackError)
            }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Закрывает соединение; повторный вызов ничего не делает.
     *
     * Идемпотентность — не удобство: историю закрывают в `finally` при остановке процесса, и
     * второй проход по тому же `finally` не должен превращаться в отказ.
     */
    override fun close() {
        synchronized(lock) {
            closed = true
            val current = currentConnection ?: return
            currentConnection = null
            try {
                current.close()
            } catch (error: SQLException) {
                throw CurrencyStorageException("база курсов не закрыта", error)
            }
        }
    }

    private suspend fun <T> guarded(what: String, block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (closed) throw CurrencyStorageException("$what: база курсов закрыта")
                try {
                    block(currentConnection ?: open().also { currentConnection = it })
                } catch (error: SQLException) {
                    throw CurrencyStorageException("$what: база данных отказала", error)
                }
            }
        }

    /**
     * Открывает файл и приводит его в рабочее состояние.
     *
     * Каталог создаётся до открытия: путь по умолчанию (`currency.db` рядом с процессом) и путь
     * из `CURRENCY_DB` задаёт человек, и каталога под файлом может не быть — тогда открытие
     * упало бы с «файл не найден», хотя причина в другом.
     */
    private fun open(): Connection {
        val file = path.toAbsolutePath()
        try {
            file.parent?.let { Files.createDirectories(it) }
        } catch (error: IOException) {
            throw CurrencyStorageException("каталог истории не создан: $file", error)
        }

        val opened = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            opened.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute(CREATE_RATES_TABLE)
                statement.execute(CREATE_CURRENCY_TIME_INDEX)
                statement.execute(CREATE_HOURLY_SUMMARIES_TABLE)
                statement.execute(CREATE_HOURLY_SUMMARY_KEY_INDEX)
            }
        } catch (error: SQLException) {
            // Соединение наружу не отдаётся, поэтому убирается здесь: иначе неудачное открытие
            // оставило бы за собой ещё один дескриптор к тому же файлу.
            runCatching { opened.close() }
            throw error
        }
        return opened
    }

    private companion object {

        /** Строка — один курс в один момент; порядок строк задаёт автоинкрементный ключ. */
        val CREATE_RATES_TABLE = """
            CREATE TABLE IF NOT EXISTS $CURRENCY_RATES_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                currency TEXT NOT NULL,
                rate_to_rub TEXT NOT NULL,
                received_at TEXT NOT NULL
            )
        """.trimIndent()

        /**
         * Индекс под выборки: все они фильтруют по валюте и упорядочивают по времени получения.
         * Ключ автоинкремента в индекс не входит: он нужен только как разрешение ничьей между
         * записями одного момента, а такие пары внутри одной валюты единичны.
         */
        const val CREATE_CURRENCY_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_currency_rates_currency_received_at " +
                "ON $CURRENCY_RATES_TABLE (currency, received_at)"

        /**
         * Строка — сводка одной валюты за один час: ключ строки — час и валюта.
         *
         * Числа лежат текстом по той же причине, что и курсы: `REAL` вернул бы 95.42 уже другим
         * числом, а сводка — это деньги. `change_percent` допускает NULL: у нулевого курса
         * процент не определён, и ноль на этом месте читался бы как «курс не менялся».
         */
        val CREATE_HOURLY_SUMMARIES_TABLE = """
            CREATE TABLE IF NOT EXISTS $CURRENCY_HOURLY_SUMMARIES_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hour TEXT NOT NULL,
                currency TEXT NOT NULL,
                first_rate TEXT NOT NULL,
                last_rate TEXT NOT NULL,
                change_rate TEXT NOT NULL,
                change_percent TEXT,
                min_rate TEXT NOT NULL,
                max_rate TEXT NOT NULL,
                average_rate TEXT NOT NULL,
                samples INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * Уникальность по часу и валюте — это ключ сводки, а не украшение: час закрывается заново
         * на каждом обходе, пока по нему есть записи, и повтор обязан заменять строку, а не
         * добавлять вторую. На этот индекс опирается `INSERT OR REPLACE` в хранилище сводок;
         * без него повторный обход копил бы копии, и суточное изменение считало бы один и тот же
         * час столько раз, сколько обходов по нему прошло.
         */
        const val CREATE_HOURLY_SUMMARY_KEY_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_currency_hourly_summaries_hour_currency " +
                "ON $CURRENCY_HOURLY_SUMMARIES_TABLE (hour, currency)"
    }
}

/**
 * Момент в виде текста для базы: ISO-8601 UTC, секунды — тот же предел, что у курса.
 *
 * Усечение здесь, а не только при создании записи, потому что границы окон и «до какого момента»
 * приходят от вызывающего с любым числом знаков: сравнивать их с усечёнными строками можно,
 * лишь приведя к той же точности. Текст момента — свойство схемы, а не одного хранилища:
 * время курса и час сводки лежат в одном файле и обязаны быть усечены одинаково, иначе
 * сравнение строк перестало бы совпадать со сравнением времён.
 */
internal fun Instant.stored(): String =
    DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.SECONDS))
