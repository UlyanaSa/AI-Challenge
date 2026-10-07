package com.osvin.aichallenge.indexing.chunking

import com.osvin.aichallenge.indexing.model.ChunkMetadata
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.chunkId

/**
 * Структурная стратегия: Markdown разбирается на блоки (заголовки, fenced-код, абзацы), а чанк
 * собирается по логическим границам.
 *
 * Ключевое решение — заголовок открывает раздел, и раздел всегда начинается с нового чанка:
 * начало раздела это наиболее осмысленная граница, и жадная укладка блоков никогда не «приклеит»
 * начало нового раздела к хвосту предыдущего. Исключение — заголовок, за которым нет текста: это
 * название того, что идёт дальше (заголовок части, заголовок, разорванный разрывом страницы),
 * и он открывает чанк следующего раздела, а не становится чанком сам ([packSections]). Внутри
 * раздела блоки укладываются жадно до [maxChunkSize]; заголовок входит в текст первого чанка
 * раздела как сырой markdown, чтобы embedding видел и заголовок, и его текст.
 *
 * Файлы разбираются по отдельности и никогда не склеиваются: граница файла — жёсткая граница
 * чанка, поэтому `source` всегда точен. Блок кода не режется, пока помещается целиком; абзац,
 * который сам больше [maxChunkSize], режется принудительно, но по возможности по границам
 * строк, чтобы не рвать строку посередине без необходимости.
 *
 * Блок помнит своё место в тексте файла (смещения начала и конца), а не только текст: по смещениям
 * чанк спрашивает у файла свои страницы ([DocumentFile.pagesIn]). Смещения — часть разбора, а не
 * отдельный проход по тексту, потому что разбор и так идёт по строкам по порядку; вычислять их
 * потом поиском подстроки было бы и медленнее, и неверно на повторяющихся абзацах.
 */
class StructuralChunker(val maxChunkSize: Int = 2400) : ChunkingStrategy {

    init {
        require(maxChunkSize > 0) { "maxChunkSize должен быть положительным, получено $maxChunkSize" }
    }

    override val type: ChunkingStrategyType = ChunkingStrategyType.STRUCTURAL

    override fun chunk(document: Document): List<DocumentChunk> {
        val chunks = ArrayList<DocumentChunk>()
        var chunkIndex = 0
        for (file in document.files) {
            for (packed in chunkFile(file)) {
                chunks.add(
                    DocumentChunk(
                        id = chunkId(type, document.id, file.name, chunkIndex),
                        content = packed.text,
                        metadata = ChunkMetadata(
                            source = file.name,
                            title = document.title,
                            section = packed.section,
                            chunkIndex = chunkIndex,
                            strategy = type,
                            pages = packed.pages
                        )
                    )
                )
                chunkIndex++
            }
        }
        return chunks
    }

    /** Режет один файл; пустой или состоящий из пробелов файл чанков не даёт. */
    private fun chunkFile(file: DocumentFile): List<PackedChunk> {
        if (file.content.isBlank()) return emptyList()
        return packSections(file, parseBlocks(file.content))
    }

    /**
     * Разбирает текст на блоки. У заголовка [MarkdownBlock.heading] заполнен, у остальных блоков
     * он `null` — по этому признаку [packSections] понимает, где начинается раздел.
     *
     * Смещения блоков считаются по строкам: строки хранят свой отступ от начала текста, а блок
     * берёт отступ первой строки и конец последней. Переводы строк в смещения входят — так
     * смещение остаётся координатой самого текста файла, а не текста, склеенного из строк.
     */
    private fun parseBlocks(content: String): List<MarkdownBlock> {
        val lines = content.split("\n")
        val lineStarts = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            lineStarts[i] = offset
            offset += lines[i].length + 1
        }
        fun endOf(lineIndex: Int) = lineStarts[lineIndex] + lines[lineIndex].length

        val blocks = ArrayList<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                // Закрытые ATX-заголовки ("## Текст ##") тоже допустимы: убираем обрамляющие #.
                val text = heading.groupValues[2].trim().trimEnd('#').trim()
                blocks.add(
                    MarkdownBlock(
                        line,
                        lineStarts[i],
                        endOf(i),
                        heading = text,
                        level = heading.groupValues[1].length
                    )
                )
                i++
                continue
            }

            if (line.startsWith(FENCE)) {
                val fenceCount = line.takeWhile { it == '`' }.length
                // Код не режем здесь: закрывающая линия — не меньше бэктиков и без «хвоста»,
                // иначе блок тянется до конца файла.
                var end = lines.size
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j]
                    val closing = candidate.takeWhile { it == '`' }.length
                    if (closing >= fenceCount && candidate.substring(closing).isBlank()) {
                        end = j + 1
                        break
                    }
                    j++
                }
                blocks.add(
                    MarkdownBlock(
                        text = lines.subList(i, end).joinToString("\n"),
                        start = lineStarts[i],
                        end = endOf(end - 1)
                    )
                )
                i = end
                continue
            }

            // Абзац: строки до пустой строки, заголовка или открытия кода. Маркеры списка
            // («- », «* », «1. ») сюда не вмешиваются — список остаётся одним блоком.
            val paragraph = ArrayList<String>()
            val paragraphStart = i
            while (i < lines.size) {
                val current = lines[i]
                if (current.isBlank() || HEADING.matches(current) || current.startsWith(FENCE)) break
                paragraph.add(current)
                i++
            }
            blocks.add(
                MarkdownBlock(
                    text = paragraph.joinToString("\n"),
                    start = lineStarts[paragraphStart],
                    end = endOf(i - 1)
                )
            )
        }
        return blocks
    }

    /**
     * Группирует блоки в разделы и укладывает каждый раздел в чанки.
     *
     * Раздел — заголовок вместе с текстом до следующего заголовка. Если текста у заголовка нет, это
     * не пустой раздел, а название того, что идёт дальше: так выглядит заголовок части перед главой
     * и заголовок, разорванный разрывом страницы. Такой заголовок присоединяется к следующему
     * разделу, а не открывает свой: искать в чанке без текста нечего, а короткий чанк по близости
     * обгоняет содержательный — пустая единица индекса ещё и портит выдачу. Заголовок в конце файла
     * остаётся разделом: текста за ним нет вовсе, и выбрасывать его было бы потерей данных.
     *
     * Имя раздела берётся у самого глубокого заголовка группы: у части с главой имя даёт глава,
     * а у одного заголовка, разорванного на две строки, — его первая строка (уровни равны, из
     * равных [maxByOrNull] возвращает первый).
     */
    private fun packSections(file: DocumentFile, blocks: List<MarkdownBlock>): List<PackedChunk> {
        val result = ArrayList<PackedChunk>()
        var i = 0

        // Преамбула — всё до первого заголовка; у неё нет раздела.
        val preamble = ArrayList<MarkdownBlock>()
        while (i < blocks.size && blocks[i].heading == null) {
            preamble.add(blocks[i])
            i++
        }
        if (preamble.isNotEmpty()) result += packSection(file, null, preamble)

        while (i < blocks.size) {
            val headings = ArrayList<MarkdownBlock>()
            var body: List<MarkdownBlock> = emptyList()
            do {
                val headingBlock = blocks[i]
                i++
                headings.add(headingBlock)
                body = ArrayList()
                while (i < blocks.size && blocks[i].heading == null) {
                    body.add(blocks[i])
                    i++
                }
            } while (body.isEmpty() && i < blocks.size)
            // Заголовки стоят первыми блоками: они обязаны попасть в первый чанк своего раздела.
            result += packSection(file, headings.maxByOrNull { it.level }?.heading, headings + body)
        }
        return result
    }

    /** Жадная укладка блоков одного раздела; каждый чанк не длиннее [maxChunkSize]. */
    private fun packSection(
        file: DocumentFile,
        section: String?,
        blocks: List<MarkdownBlock>
    ): List<PackedChunk> {
        val chunks = ArrayList<PackedChunk>()
        val current = StringBuilder()
        var start = -1
        var end = -1
        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(PackedChunk(section, current.toString(), file.pagesIn(start, end)))
                current.setLength(0)
                start = -1
            }
        }

        for (block in blocks) {
            if (block.text.length > maxChunkSize) {
                // Блок не влезает даже один: текущий чанк закрываем и режем блок принудительно.
                flush()
                for (piece in splitLongBlock(block)) {
                    chunks.add(PackedChunk(section, piece.text, file.pagesIn(piece.start, piece.end)))
                }
                continue
            }
            val separator = if (current.isEmpty()) 0 else SEPARATOR.length
            if (current.length + separator + block.text.length <= maxChunkSize) {
                if (separator > 0) current.append(SEPARATOR)
                current.append(block.text)
            } else {
                flush()
                current.append(block.text)
            }
            // Границы блока задают границы текста чанка: пока чанк не закрыт, его конец — конец
            // последнего уложенного блока, а начало — начало первого.
            if (start < 0) start = block.start
            end = block.end
        }
        flush()
        return chunks
    }

    /**
     * Принудительная нарезка слишком длинного блока. Идём по строкам и, когда строка не
     * помещается, сначала пытаемся начать новый кусок с её начала (не рвать строку), и лишь
     * строку длиннее [maxChunkSize] режем по символам.
     *
     * Кусок сохраняет своё место в тексте файла: смещение начала — позиция первого символа куска,
     * смещение конца — позиция, на которой кусок закончился. Разрыв между строками уносит с собой
     * перевод строки, поэтому конец куска — символ перед началом следующей строки.
     */
    private fun splitLongBlock(block: MarkdownBlock): List<MarkdownBlock> {
        val pieces = ArrayList<MarkdownBlock>()
        val current = StringBuilder()
        var pieceStart = -1

        fun flush(endExclusive: Int) {
            if (current.isNotEmpty()) {
                pieces.add(MarkdownBlock(current.toString(), pieceStart, endExclusive))
                current.setLength(0)
                pieceStart = -1
            }
        }

        var lineStart = block.start
        for (line in block.text.split("\n")) {
            if (current.isNotEmpty() && line.length <= maxChunkSize &&
                current.length + 1 + line.length > maxChunkSize
            ) {
                flush(lineStart - 1)
            }
            var rest = line
            var consumed = 0
            while (true) {
                val separator = if (current.isEmpty()) 0 else 1
                val room = maxChunkSize - current.length - separator
                if (rest.length <= room) {
                    if (current.isEmpty()) pieceStart = lineStart + consumed
                    if (separator == 1) current.append('\n')
                    current.append(rest)
                    consumed += rest.length
                    break
                }
                if (room > 0) {
                    if (current.isEmpty()) pieceStart = lineStart + consumed
                    if (separator == 1) current.append('\n')
                    current.append(rest, 0, room)
                    rest = rest.substring(room)
                    consumed += room
                }
                flush(lineStart + consumed)
            }
            // Плюс перевод строки, который разделяет строки блока.
            lineStart += line.length + 1
        }
        flush(block.end)
        return pieces
    }

    /**
     * Блок разметки вместе с местом, которое он занимает в тексте файла: `[start, end]`.
     *
     * [level] заполнен только у заголовков — это число `#` в нём. Уровень нужен там, где заголовок
     * выбирает имя раздела: имя даёт самый глубокий заголовок группы.
     */
    private data class MarkdownBlock(
        val text: String,
        val start: Int,
        val end: Int,
        val heading: String? = null,
        val level: Int = 0
    )

    /** Чанк до сборки метаданных: текст, раздел и страницы, посчитанные по смещениям блоков. */
    private data class PackedChunk(val section: String?, val text: String, val pages: List<Int>)

    private companion object {
        const val SEPARATOR = "\n\n"
        const val FENCE = "```"
        val HEADING = Regex("(#{1,6})\\s+(.*)")
    }
}
