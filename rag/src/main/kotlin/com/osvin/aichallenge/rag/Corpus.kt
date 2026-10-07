package com.osvin.aichallenge.rag

import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers
import com.osvin.aichallenge.indexing.pipeline.Tokens
import com.osvin.aichallenge.indexing.ui.DayCorpus
import com.osvin.aichallenge.indexing.ui.PdfSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * База дня: корпус, по которому агент ищет ответ, и его файл на диске.
 *
 * База — тот же корпус, что у страницы дня 21: срез романа, собранный из встроенного PDF.
 * Копии текста в проекте нет намеренно: вторая копия книги разошлась бы с первой, и сравнение
 * отвечало бы на вопрос «где в копии», а не «где в файле» (см. `DayCorpus`). Поэтому корпус
 * берётся тем же путём — PDF, разбор по страницам, маркеры `<!-- page:N -->`, — а файл кладётся
 * в рабочий каталог агента: он нужен поиску, и по нему же видно, что именно индексировалось.
 *
 * Файл переживает прогон: если текста в рабочем каталоге уже есть, PDF для него не нужен. Это не
 * кэш ради скорости — так прогон повторяем на машине, где самого PDF нет (он не в репозитории),
 * и числа второго прогона описывают ту же базу, что первого.
 */
data class DayBase(
    /** Документ конвейера: один файл корпуса. */
    val document: Document,
    /** Текст корпуса на диске: поиск и отчёт ссылаются на него, а не на память прогона. */
    val text: Path
) {

    /** Размер корпуса в символах. */
    val chars: Int get() = document.files.sumOf { it.content.length }

    /** Размер корпуса в токенах — по тому же счётчику, что в отчёте дня 21. */
    val tokens: Int get() = Tokens.count(document)

    /** Размеченные страницы корпуса: физические номера страниц PDF. */
    val pages: List<Int> get() = document.files.flatMap { file -> file.pages.map { it.page } }
}

/** Корпус дня в рабочем каталоге агента: чтение и первичная сборка из PDF. */
object Corpus {

    /** Каталог корпуса внутри рабочего каталога. */
    const val DOCUMENTS_DIR: String = "documents"

    /** Имя файла корпуса: то же, что у страницы дня, — текст, извлечённый из PDF. */
    const val FILE_NAME: String = DayCorpus.FILE_NAME

    /**
     * Корпус дня: `null`, если встроенного PDF нет и текста в рабочем каталоге тоже нет.
     *
     * Порядок именно такой: сначала файл рабочего каталога, потом PDF. Прогон дня не должен
     * зависеть от того, лежит ли рядом файл, который в репозиторий не кладут, — а если текста нет
     * вовсе, вызывающий скажет об этом человеку ([MISSING_MESSAGE]) вместо пустой базы.
     */
    suspend fun read(workDir: Path, pdf: PdfSource = PdfSource()): DayBase? {
        val documentsDir = Files.createDirectories(workDir.resolve(DOCUMENTS_DIR))
        val text = documentsDir.resolve(FILE_NAME)

        if (!Files.exists(text)) {
            val content = contentFromPdf(pdf) ?: return null
            Files.writeString(text, content)
        }

        // Маркеры страниц разбираются одинаково для только что разобранного PDF и для файла
        // прошлого прогона: страницы живут в смещениях текста, а не в самом тексте.
        val paged = PageMarkers.stripAndIndex(Files.readString(text))
        val document = Document(
            id = FILE_NAME.removeSuffix(SUFFIX),
            title = null,
            files = listOf(DocumentFile(FILE_NAME, paged.content, paged.pages))
        )
        return DayBase(document, text)
    }

    /** Текст корпуса из встроенного PDF: `null`, если файла рядом с кодом нет. */
    private suspend fun contentFromPdf(pdf: PdfSource): String? {
        val bytes = DayCorpus.bytes() ?: return null
        // Ошибку разбора не глушим: у `PdfLoadException` текст уже написан для человека —
        // «PDF защищён паролем», «нет текста на страницах 1–40», — и он же будет ответом прогона.
        return pdf.parse(bytes, DayCorpus.RESOURCE, DayCorpus.PAGES).content
    }

    /**
     * Чего не хватает для сборки базы: тот же путь к файлу, что называет страница дня 21,
     * но с оговоркой про каталог прогона — у прогона есть второй вход, `--dir` с готовым корпусом.
     */
    const val MISSING_MESSAGE: String =
        "База дня не найдена: файл indexing-ui/src/main/resources" + DayCorpus.RESOURCE +
            " отсутствует, а текста корпуса в рабочем каталоге нет — положите PDF на место " +
            "или укажите --dir с каталогом прошлого прогона"

    private const val SUFFIX = ".md"
}
