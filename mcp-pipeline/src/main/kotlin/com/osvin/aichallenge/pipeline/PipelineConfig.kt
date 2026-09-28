package com.osvin.aichallenge.pipeline

import com.osvin.aichallenge.currency.CurrencyConfig
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Настройки сервера пайплайна: где история курсов и куда писать отчёты.
 *
 * История берётся по переменной службы курсов (`CURRENCY_DB`): второго адреса у тех же данных
 * быть не должно — разошлись бы молча, и пайплайн складывал бы в файл курсы не из той базы,
 * которую показывает лента приложения. Поэтому путь читает то же разбирательство настроек
 * ([CurrencyConfig.fromEnvironment]), а не своя копия: копия разошлась бы с оригиналом при
 * первой же правке умолчания.
 *
 * Каталог отчётов — своя переменная ([OUTPUT_DIR_ENV]): запись на диск это то, чего у службы
 * курсов нет вовсе, и держать отчёты рядом с базой значило бы смешивать данные с их
 * представлением.
 *
 * @param databasePath Файл истории курсов.
 * @param outputDir Каталог, в который пишет `saveToFile`.
 */
data class PipelineConfig(val databasePath: Path, val outputDir: Path) {

    companion object {
        /** Переменная окружения с каталогом отчётов. */
        const val OUTPUT_DIR_ENV = "PIPELINE_OUTPUT_DIR"

        /**
         * Каталог отчётов по умолчанию: `rates-reports` рядом с процессом.
         *
         * Относительным путём, а не в домашнем каталоге: запуск из чужого каталога не должен
         * разбрасывать файлы по машине, а имя по умолчанию названо в ответе инструмента — по нему
         * человек и находит, куда писать. Без создания каталога здесь: каталог заводит запись
         * (`saveToFile`), и пустой каталог от одного лишь запуска сервера был бы мусором.
         */
        const val DEFAULT_OUTPUT_DIR = "rates-reports"

        /** Настройки из окружения: база — от службы курсов, каталог отчётов — свой. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): PipelineConfig = PipelineConfig(
            databasePath = CurrencyConfig.fromEnvironment(env).databasePath,
            outputDir = env[OUTPUT_DIR_ENV]?.takeIf { it.isNotBlank() }
                ?.let { Paths.get(it).toAbsolutePath() }
                ?: Paths.get(DEFAULT_OUTPUT_DIR).toAbsolutePath()
        )
    }
}
