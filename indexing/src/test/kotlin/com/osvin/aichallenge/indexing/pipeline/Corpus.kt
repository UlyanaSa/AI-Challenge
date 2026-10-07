package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers

/**
 * Набор документов эксперимента: часть IV «Принципы организации компонентов» книги
 * «Чистая архитектура» — вступление к части и три главы (12, 13, 14).
 *
 * Книга даёт то, чего не было у синтетического корпуса, — настоящую структуру. Разделы принципов
 * вложены друг в друга (раздел → подраздел), разделы сильно разной длины, между ними списки,
 * листинг кода и подписи рисунков. Только на таком тексте и видно, что даёт нарезка по структуре
 * и чего стоит фиксированное окно.
 *
 * Документ один, файлов в нём четыре: глава — это файл, и граница файла остаётся структурной
 * границей внутри одного документа. Поэтому фиксированная стратегия обязана её нарушить (она
 * склеивает файлы документа в один поток), а структурная — нет; на этом и проверяется признак
 * «граница файла — структурная граница».
 *
 * Набор лежит в тестовых ресурсах и служит фикстурой конвейера: сравнение стратегий требует
 * одного и того же текста от прогона к прогону, а страница дня строит корпус из встроенного PDF
 * ([com.osvin.aichallenge.indexing.ui.DayCorpus]) — из всего файла там берутся только страницы
 * вокруг ответа, и для сравнения стратегий такого среза мало.
 *
 * В репозиторий фикстура не попадает: текст книги под авторским правом, и он перечислен в
 * `.gitignore`. Поэтому файлы могут отсутствовать ([available]) — и тогда проверки, которым нужен
 * этот текст, пропускаются, а не падают.
 *
 * Текст вырезан из вёрстки книги, страницы отмечены маркерами `<!-- page:N -->`; здесь они
 * снимаются с текста и превращаются в карту страниц файла ([DocumentFile.pages]), поэтому абзацы
 * корпуса остаются ровно теми же, что и без разметки, а страницы чанков считаются по смещениям.
 * Ту же разметку — заголовки по кеглю и страницы — страница дня делает сама, читая PDF
 * ([com.osvin.aichallenge.indexing.ui.PdfSource]): фикстура и загруженный документ приходят
 * к конвейеру в одном виде, и стратегии на них работают одинаково.
 */
object Corpus {

    /** Файлы набора в порядке чтения: глава — это файл, и порядок в документе тоже часть условия. */
    val FILES: List<String> = listOf(
        "part-iv-intro.md",
        "chapter-12-components.md",
        "chapter-13-component-cohesion.md",
        "chapter-14-component-coupling.md"
    )

    /**
     * Что печатает прогон, если фикстуры нет рядом с кодом.
     *
     * Сообщение говорит, чего не хватает и куда положить файлы: без него пропущенные проверки
     * выглядели бы как «всё хорошо», а отчёт — как пустой прогон.
     */
    const val MISSING_FIXTURE: String =
        "Фикстура корпуса не найдена: положите главы части IV в indexing/src/test/resources/corpus — " +
            "текст книги под авторским правом и в репозиторий не входит"

    /**
     * Есть ли фикстура рядом с кодом.
     *
     * Файлы перечислены в `.gitignore`, поэтому на свежем клоне их нет: проверять тогда нечего,
     * и тесты, которым нужен этот текст, пропускаются, а не падают — красный прогон из-за
     * отсутствующего файла не проверяет ничего и прячет остальные результаты.
     */
    val available: Boolean by lazy { FILES.all { resourceOrNull(it) != null } }

    /** Документы набора. Читаются один раз: набор в прогоне неизменен. */
    val documents: List<Document> by lazy { load() }

    private fun load(): List<Document> = listOf(
        document(
            id = "clean-architecture-part4",
            title = "Чистая архитектура. Часть IV. Принципы организации компонентов",
            files = FILES
        )
    )

    private fun document(id: String, title: String, files: List<String>) = Document(
        id = id,
        title = title,
        files = files.map { name ->
            val paged = PageMarkers.stripAndIndex(resource(name))
            DocumentFile(name, paged.content, paged.pages)
        }
    )

    private fun resource(name: String): String =
        resourceOrNull(name) ?: error(MISSING_FIXTURE)

    private fun resourceOrNull(name: String): String? =
        Corpus::class.java.getResourceAsStream("/corpus/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
}
