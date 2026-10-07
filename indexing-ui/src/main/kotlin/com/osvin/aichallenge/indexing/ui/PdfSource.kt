package com.osvin.aichallenge.indexing.ui

import com.osvin.aichallenge.indexing.model.PageText
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import kotlin.math.round
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** PDF (по ссылке или из файла) — в текст корпуса. */
data class LoadedPdf(
    /** Имя файла корпуса: одно имя без каталогов, с расширением `.md`. */
    val fileName: String,
    /** Текст корпуса: абзацы и маркеры страниц ([PageText.reflow]). */
    val content: String,
    /** Сколько страниц в PDF. Пустые страницы маркера не получат, но в счёт входят. */
    val pages: Int
)

/**
 * Ошибка загрузки, которую можно показать человеку как есть: «по ссылке HTML, а не PDF», «сервер
 * ответил 404», «PDF защищён паролем». Текст сообщения и есть объяснение — отдельного кода для
 * каждого случая не нужно, а странице нечего с ним делать, кроме как показать его рядом с полем.
 */
class PdfLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Строка вёрстки: текст вместе с кеглем.
 *
 * Кегль — единственный признак заголовка, который есть в PDF: ни стилей, ни уровней в текстовом
 * потоке нет, а размер шрифта есть у каждой строки. Кегль округляется до половины пункта: один и
 * тот же стиль встречается в файле как 11.0 и 10.98, и без округления это были бы разные «размеры»,
 * а значит и разные уровни заголовков.
 */
private data class StyledLine(val text: String, val size: Float)

/**
 * Читает страницы по строкам, сохраняя кегль.
 *
 * Штатный [PDFTextStripper] отдаёт страницу одной строкой текста, и кегль в ней теряется вместе с
 * координатами — а заголовок отличается от текста именно им. Поэтому строки собираются по одной:
 * `writeString` вызывается разобранной строкой, а `startPage` отмечает начало страницы.
 *
 * [pages] — какие страницы нужны: ненужные остаются в списке пустыми, чтобы физические номера
 * страниц не сдвинулись (см. [DayCorpus.PAGES]).
 */
private class LayoutStripper(private val pages: IntRange? = null) : PDFTextStripper() {

    private val collected = ArrayList<List<StyledLine>>()
    private var current = ArrayList<StyledLine>()
    private var started = false
    private var number = 0

    override fun startPage(page: PDPage) {
        // Первый вызов открывает первую страницу: до неё собирать было нечего, и пустой список
        // сдвинул бы нумерацию страниц на одну.
        if (started) collected += current
        number += 1
        current = ArrayList()
        started = true
    }

    override fun writeString(text: String, positions: List<TextPosition>) {
        // Ненужные страницы остаются в списке пустыми, а не выпадают из него: по позиции в списке
        // [PageText.reflow] считает физический номер страницы, и выпавшая страница сдвинула бы
        // нумерацию всех последующих.
        if (pages != null && number !in pages) return
        val line = text.trim()
        if (line.isEmpty()) return
        val size = positions.firstOrNull()?.fontSizeInPt ?: 0f
        current += StyledLine(line, round(size * 2) / 2)
    }

    /** Страницы после разбора: по одной на страницу PDF, пустая страница — пустым списком. */
    fun finishedPages(): List<List<StyledLine>> = if (current.isEmpty()) collected else collected + listOf(current)
}

/**
 * Разметка заголовков по кеглю: из вёрстки — в знаки `#`, которые понимает конвейер.
 *
 * Основной текст — самый частый кегль (считается по знакам, а не по строкам: подписи, листинги и
 * номера страниц тоже строки, но текста в них мало). Всё, что заметно крупнее, — заголовок, и
 * уровень даётся по порядку размеров: самый крупный кегль — `#`, следующий — `##`, и так далее.
 *
 * Это разметка вёрстки, а не смысла, и уровни в ней — порядок размеров, а не иерархия книги:
 * у PDF дня текст набран 20 pt, а крупнее — главы (23 pt), части (28 pt) и титулы (33 pt), и строка
 * получает столько знаков `#`, сколько размеров её крупнее: глава — `###`, часть — `##`, титул — `#`.
 * Конвейеру этого достаточно: `#` открывает раздел, а его текст становится `section`
 * в метаданных чанка — тот самый, по которому режет структурная стратегия.
 *
 * Обратная сторона решения та же, что и у самого PDF: размер шрифта бывает и у не-заголовка
 * (номера страниц, ячейки таблиц, рекламные врезы). Поэтому мелкие надписи — всё, что крупнее
 * текста меньше чем на [STEP_PT], — заголовками не считаются, длинная строка тоже, а точка в конце
 * её отменяет: заголовок называет место в книге, а не доказывает мысль.
 *
 * Уровней ровно [MAX_LEVEL]: больше markdown не различает, и самый мелкий из лишних размеров
 * остаётся текстом — вёрстка бывает и глубже шести уровней, и тогда теряет место в книге не начало,
 * а хвост. Надписи мельче текста отсекаются раньше, порогом [STEP_PT]: у книги дня есть кегль
 * в 15 pt — это сноски, и заголовками они не становятся.
 */
private object Headings {

    /**
     * Заголовок должен быть крупнее текста хотя бы на столько пунктов.
     *
     * Два, а не один: кегль текста и подписи к рисунку или ячейки таблицы различаются на пункт,
     * и при пороге в один пункт заголовками становились бы подписи.
     */
    private const val STEP_PT = 2.0

    /** Строка заголовка: длинная строка — это абзац, набранный крупно. */
    private const val MAX_CHARS = 80

    /** Предел уровней заголовка в markdown; самый мелкий из лишних размеров остаётся текстом. */
    private const val MAX_LEVEL = 6

    /** Размечает страницы: строки-заголовки получают знаки `#` по своему уровню. */
    fun markup(pages: List<List<StyledLine>>): List<String> {
        val lines = pages.flatten()
        if (lines.isEmpty()) return emptyList()

        val body = bodySize(lines)
        val levels = lines.asSequence()
            .map { it.size }
            .filter { it >= body + STEP_PT }
            .distinct()
            .sortedDescending()
            .take(MAX_LEVEL)
            .mapIndexed { index, size -> size to index + 1 }
            .toMap()

        return pages.map { page -> markedUpPage(page, levels) }
    }

    /**
     * Размечает страницу, склеивая заголовок, разорванный вёрсткой.
     *
     * Длинный заголовок книга переносит на две строки тем же кеглем, и без склейки раздел получал бы
     * имя своей последней строки. Признак продолжения: следующая строка того же уровня начинается
     * со строчной буквы, чего настоящий заголовок не делает, — за «Глава первая» у книги дня идёт
     * «Вместо введения…», и склеивать их значило бы потерять название главы. Признак не всесилен:
     * название, которое вторая строка продолжает с имени собственного («…биографии многочтимого /
     * Степана Трофимовича Верховенского»), остаётся двумя заголовками, и раздел называется
     * по второй строке.
     */
    private fun markedUpPage(page: List<StyledLine>, levels: Map<Float, Int>): String {
        val blocks = ArrayList<String>()
        var index = 0
        while (index < page.size) {
            val line = page[index]
            val level = levels[line.size]?.takeIf { isHeading(line.text) }
            if (level == null) {
                blocks += line.text
                index++
                continue
            }
            var title = line.text
            while (index + 1 < page.size && continues(page[index + 1], line.size, levels)) {
                title = "$title ${page[index + 1].text}"
                index++
            }
            blocks += "#".repeat(level) + " " + title
            index++
        }
        return blocks.joinToString("\n")
    }

    /** Продолжение заголовка на следующей строке: тот же кегль и строчная буква в начале. */
    private fun continues(next: StyledLine, size: Float, levels: Map<Float, Int>): Boolean =
        next.size == size && next.text.firstOrNull()?.isLowerCase() == true &&
            levels[next.size] != null

    /** Кегль основного текста: самый частый по числу знаков. */
    private fun bodySize(lines: List<StyledLine>): Float {
        val weight = HashMap<Float, Int>()
        for (line in lines) weight.merge(line.size, line.text.length, Int::plus)
        return weight.maxByOrNull { it.value }?.key ?: 0f
    }

    /**
     * Похожа ли строка на заголовок: короткая, без точки в конце и с буквой внутри.
     *
     * Буква нужна против крупно набранных чисел — «1», «2», «2024» на титуле и в колонцифрах:
     * названием места в книге число не бывает.
     */
    private fun isHeading(text: String): Boolean =
        text.length <= MAX_CHARS && !text.endsWith(".") && text.any { it.isLetter() }
}

/**
 * Документ снаружи — по ссылке или локальным файлом: получить байты PDF, разобрать его по страницам
 * и превратить в текст корпуса.
 *
 * Оба входа ведут в один разбор: скачанный и выбранный в браузере файл отличаются только тем, откуда
 * взялись байты. Проверки (размер, заголовок `%PDF-`) стоят на входе в разбор, а не в скачивании,
 * поэтому локальный файл проходит ровно те же, что и файл по ссылке.
 *
 * Здесь живёт всё, чего нет в конвейере: сеть, чужой формат и вёрстка. Конвейер получает на вход
 * готовый `Document` и о происхождении текста не знает — поэтому PDFBox и HTTP-клиент подключены к
 * модулю страницы, а не к `:indexing`.
 *
 * **Страницы берутся физические.** PDF умеет хранить собственные номера страниц (метки вроде
 * `A-1` или римских), но они есть далеко не всегда, а когда есть — зависят от вёрстки конкретного
 * издания. Физический номер страницы есть всегда и совпадает с тем, что видно в просмотрщике,
 * поэтому в метаданные идёт он.
 *
 * **Ограничения выбраны явно.** Размер файла ограничен [MAX_BYTES]: страница локальная, и качать
 * по ссылке гигабайты в память — это не «поддержка больших PDF», а способ уронить сервер. Таймауты
 * разделены: соединение и весь запрос — разные ожидания, и висят они по-разному.
 */
class PdfSource(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {

    /**
     * Скачивает и разбирает PDF по ссылке [url].
     *
     * Бросает [PdfLoadException] с текстом для человека: ссылка не http(s), сервер не отдал файл,
     * по ссылке не PDF, PDF зашифрован, PDF не разбирается, текстового слоя нет.
     */
    suspend fun load(url: String): LoadedPdf {
        val uri = uri(url)
        return parse(download(uri), fileNameOf(uri))
    }

    /**
     * Разбирает уже полученные байты PDF.
     *
     * [name] — исходное имя файла: у ссылки последний сегмент пути, у локального файла — имя,
     * которое дал браузер. Оно определяет имя файла корпуса, поэтому приводится к безопасному виду
     * здесь же и не может увести запись за пределы каталога документов.
     */
    suspend fun parse(bytes: ByteArray, name: String, pages: IntRange? = null): LoadedPdf {
        if (bytes.size > MAX_BYTES) {
            throw PdfLoadException("Файл больше ${MAX_BYTES / (1024 * 1024)} МБ — столько страница не разбирает")
        }
        if (bytes.isEmpty()) throw PdfLoadException("Пустой файл: в нём нет ни одного байта")
        if (!startsWithPdfMagic(bytes)) throw PdfLoadException(notPdfMessage(bytes))

        val (content, total) = extract(bytes, pages)
        return LoadedPdf(fileNameFor(name), content, total)
    }

    /** Ссылка обязана быть http(s) с хостом: `file:` и прочие схемы — это не «документ по ссылке». */
    private fun uri(url: String): URI {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw PdfLoadException("Пустая ссылка")
        val uri = try {
            URI(trimmed)
        } catch (cause: Exception) {
            throw PdfLoadException("Ссылка не разбирается: ${cause.message}", cause)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw PdfLoadException("Ссылка должна начинаться с http:// или https://, получено «${uri.scheme ?: trimmed}»")
        }
        if (uri.host.isNullOrBlank()) {
            throw PdfLoadException("В ссылке нет имени хоста: $trimmed")
        }
        return uri
    }

    private suspend fun download(uri: URI): ByteArray {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/pdf,*/*")
            .GET()
            .build()

        // Скачивание синхронное, но уходит с потока сервера: пока файл едет, страница продолжает
        // отвечать на опрос состояния индексации.
        val response = withContext(Dispatchers.IO) {
            try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (cause: IOException) {
                // У ConnectException сообщения обычно нет, а сказать надо именно про адрес.
                val reason = if (cause is ConnectException) {
                    "нет соединения — проверьте адрес и порт"
                } else {
                    cause.message ?: cause::class.simpleName
                }
                throw PdfLoadException("Не удалось скачать документ с ${uri.host}: $reason", cause)
            } catch (cause: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PdfLoadException("Скачивание прервано", cause)
            }
        }

        if (response.statusCode() !in 200..299) {
            throw PdfLoadException("Сервер вернул ${response.statusCode()} вместо документа")
        }
        // Читается на байт больше предела: по нему видно, что файл длиннее, не вычитывая его целиком.
        return withContext(Dispatchers.IO) {
            response.body().use { it.readNBytes(MAX_BYTES + 1) }
        }
    }

    /**
     * Разбор PDF: текст по страницам, абзацы, заголовки и маркеры страниц.
     *
     * [pages] ограничивает разбор страницами документа дня; `null` — берутся все. Ограничение живёт
     * здесь, а не после разбора: отбросить лишние страницы готового текста значило бы держать в
     * памяти весь PDF, а потом выбирать из него — при том что нужное известно заранее.
     */
    private suspend fun extract(bytes: ByteArray, pages: IntRange? = null): Parsed = withContext(Dispatchers.IO) {
        val document = try {
            Loader.loadPDF(bytes)
        } catch (cause: InvalidPasswordException) {
            throw PdfLoadException("PDF защищён паролем: текстовый слой закрыт", cause)
        } catch (cause: IOException) {
            throw PdfLoadException("PDF не разбирается: ${cause.message ?: cause::class.simpleName}", cause)
        }

        document.use { pdf ->
            val stripper = LayoutStripper(pages).apply {
                // Порядок по координатам: у PDF порядок потока не обязан совпадать с порядком чтения.
                sortByPosition = true
            }
            // Текст штатного разбора не нужен: он собирается заново, но уже с кеглем каждой строки.
            stripper.getText(pdf)
            // Номер первой страницы — 1, а не начало диапазона: разбор оставляет все страницы PDF
            // на своих местах (ненужные — пустыми), поэтому позиция в списке и есть физический номер.
            val content = PageText.reflow(Headings.markup(stripper.finishedPages()))
            if (content.isBlank()) {
                throw PdfLoadException(
                    if (pages == null) {
                        "В PDF нет текстового слоя: похоже, это сканы — распознавать изображения страница не умеет"
                    } else {
                        "В PDF нет текста на страницах ${pages.first}–${pages.last} — а нужен именно он"
                    }
                )
            }
            Parsed(content, pdf.numberOfPages)
        }
    }

    /** Имя файла из ссылки: последний сегмент пути. */
    private fun fileNameOf(uri: URI): String {
        val segment = uri.path.orEmpty().substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(segment, Charsets.UTF_8)
        } catch (cause: IllegalArgumentException) {
            segment
        }
        // Пустое имя — не имя; тогда файл называется по хосту, из которого пришёл.
        val host = uri.host.orEmpty()
        return fileNameFor(decoded.ifBlank { host.ifBlank { DEFAULT_NAME } })
    }

    /**
     * Имя файла корпуса из имени исходного файла.
     *
     * Каталоги отбрасываются, всё постороннее заменяется дефисом: имя приходит и из ссылки, и из
     * формы загрузки, то есть откуда угодно, а становится и путём на диске, и `source` в метаданных
     * чанка. «../» в имени — это не имя, а попытка записать файл мимо каталога документов.
     */
    private fun fileNameFor(name: String): String {
        val bare = name.substringAfterLast('/').substringAfterLast('\\')
        val base = bare.removeSuffix(".pdf").removeSuffix(".PDF")
        val safe = base.replace(UNSAFE, "-").trim('-', '.').take(MAX_NAME_LENGTH)
        return (safe.ifBlank { DEFAULT_NAME }) + CORPUS_SUFFIX
    }

    private fun startsWithPdfMagic(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * Чем на самом деле оказался ответ: по началу видно, HTML это, JSON или картинка.
     *
     * Признак текста — печатаемые ASCII, а не «буквы и цифры»: в HTML начала строк хватает знаков
     * разметки, и по буквам он выглядел бы двоичными данными.
     */
    private fun notPdfMessage(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, SNIPPET_BYTES), Charsets.UTF_8)
        val printable = head.count { it.code in PRINTABLE_ASCII || it.isWhitespace() } >= head.length * 9 / 10
        val hint = if (printable) {
            " — в начале текст: «${head.replace(Regex("\\s+"), " ").trim().take(SNIPPET_BYTES)}»"
        } else {
            " — в начале двоичные данные: ${bytes.take(4).joinToString(" ") { "%02x".format(it) }}"
        }
        // Формулировка нейтральна к источнику: одинаково годится и для файла из формы, и для ссылки.
        return "Это не PDF$hint. Нужен файл PDF или ссылка на сам файл, а не на страницу с ним"
    }

    /** Разобранный PDF: текст корпуса и число страниц. */
    private data class Parsed(val content: String, val pages: Int)

    private companion object {

        /** Заголовок файла PDF: `%PDF-` в первых байтах. */
        val MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())

        /**
         * Всё, что не буква, цифра, точка, дефис или подчёркивание, в имени файла заменяется дефисом.
         *
         * «Буква» — в смысле Unicode, как и в счётчике токенов: имена файлов бывают русскими, и
         * вычищать из них кириллицу значило бы называть документ «document», теряя единственную
         * зацепку о том, что в нём лежит. Разделители каталогов при этом тоже заменяются — «../»
         * в имени не остаётся.
         */
        val UNSAFE = Regex("[^\\p{L}\\p{N}._-]+")

        const val CORPUS_SUFFIX = ".md"
        const val DEFAULT_NAME = "document"
        const val MAX_NAME_LENGTH = 60
        const val SNIPPET_BYTES = 80
        const val MAX_BYTES = 64 * 1024 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 60L
        const val USER_AGENT = "indexing-ui/1.0 (day 21 local page)"

        /** Печатаемые знаки ASCII: по ним отличаем текст от двоичных данных. */
        val PRINTABLE_ASCII = 32..126
    }
}
