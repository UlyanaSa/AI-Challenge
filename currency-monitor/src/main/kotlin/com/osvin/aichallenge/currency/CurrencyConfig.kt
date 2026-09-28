package com.osvin.aichallenge.currency

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * Настройки сервиса: куда ходить за курсами, где хранить историю и как часто обновлять.
 *
 * Всё приходит из окружения переменных, а не из аргументов командной строки: сервис запускают
 * и как обычный процесс на VPS (`java -jar`), и как MCP-сервер процессом-ребёнком — а ребёнку
 * аргументы назначает тот, кто его поднял, и настраивать сервис через чужую команду запуска
 * значило бы держать настройку в двух местах. Окружение наследуется в обоих случаях.
 *
 * Значения по умолчанию рассчитаны на запуск «просто так»: публичный источник ЦБ, база рядом
 * с процессом и минута между обновлениями. База по умолчанию — файл в текущем каталоге, а не
 * в домашнем: сервис на VPS запускают из своего каталога, и история должна лежать там, где
 * её видно, а не в месте, о котором знает только запускающий.
 *
 * @param apiBase Корень API поставщика курсов.
 * @param databasePath Файл истории SQLite; каталог создаётся при открытии базы.
 * @param interval Промежуток между обновлениями; по умолчанию минута.
 */
data class CurrencyConfig(
    val apiBase: String = DEFAULT_API_BASE,
    val databasePath: Path = defaultDatabasePath(),
    val interval: Duration = DEFAULT_INTERVAL
) {

    companion object {

        /** Переменная с корнем API поставщика: так проверки и VPS подставляют свой источник. */
        const val API_BASE_ENV = "CURRENCY_API_BASE"

        /** Переменная с путём к файлу истории. */
        const val DATABASE_ENV = "CURRENCY_DB"

        /** Переменная с промежутком между обновлениями. */
        const val INTERVAL_ENV = "CURRENCY_INTERVAL"

        /** Публичный источник ЦБ: отдаёт курсы к рублю без ключа и без регистрации. */
        const val DEFAULT_API_BASE = "https://www.cbr-xml-daily.ru"

        /**
         * Минута между обновлениями — требование закреплённого чата курсов.
         *
         * Лента чата строится по минутам, и ряд истории должен быть минутным. Плата за это —
         * до 1440 запросов в сутки к бесплатному источнику, у которого курс всё равно меняется
         * раз в сутки: ЦБ публикует его на следующий день. Поэтому при дневном источнике
         * достаточно `CURRENCY_INTERVAL=PT1H` — лента тогда станет часовой, а нагрузка упадёт
         * в шестьдесят раз. По умолчанию выбрана минута: чат — то, ради чего служба работает,
         * а экономить запросы владелец может, зная свой источник.
         */
        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(1)

        /**
         * Настройки из окружения: незаданная или пустая переменная — значение по умолчанию.
         *
         * Пустая строка считается отсутствием, а не значением: `CURRENCY_DB=` в скрипте
         * запуска иначе означала бы файл с пустым именем, а причина нашлась бы только
         * при открытии базы.
         *
         * @throws IllegalArgumentException [INTERVAL_ENV] не разобран или не больше нуля.
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): CurrencyConfig = CurrencyConfig(
            apiBase = env[API_BASE_ENV]?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE,
            databasePath = env[DATABASE_ENV]?.takeIf { it.isNotBlank() }
                ?.let { Paths.get(it).toAbsolutePath() }
                ?: defaultDatabasePath(),
            interval = env[INTERVAL_ENV]?.takeIf { it.isNotBlank() }
                ?.let { parseInterval(it) }
                ?: DEFAULT_INTERVAL
        )

        /**
         * Промежуток между обновлениями из переменной окружения.
         *
         * Формат — ISO-8601 (`PT1H`, `PT30M`, `PT45S`), а не «число секунд»: он читается
         * человеком в скрипте запуска и не требует помнить единицы измерения. Опечатка или
         * ноль — отказ запуска с внятным текстом: «обновляться каждые ноль секунд» означало бы
         * цикл без пауз, который сам себя съест запросами к чужому API, и молча подменять такое
         * значение часом было бы хуже, чем не запуститься.
         */
        private fun parseInterval(value: String): Duration = try {
            Duration.parse(value).also {
                require(!it.isZero && !it.isNegative) {
                    "$INTERVAL_ENV: промежуток должен быть больше нуля, а не «$value»"
                }
            }
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException(
                "$INTERVAL_ENV: ожидается промежуток в формате ISO-8601 (PT1H, PT30M, PT45S), а не «$value»",
                error
            )
        }
    }
}

/** Файл истории по умолчанию: рядом с процессом, в его рабочем каталоге. */
internal fun defaultDatabasePath(): Path = Paths.get(DEFAULT_DATABASE_FILE).toAbsolutePath()

/** Имя файла истории по умолчанию. */
internal const val DEFAULT_DATABASE_FILE = "currency.db"
