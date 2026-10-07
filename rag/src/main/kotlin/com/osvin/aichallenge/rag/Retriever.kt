package com.osvin.aichallenge.rag

import com.osvin.aichallenge.indexing.chunking.ChunkingStrategy
import com.osvin.aichallenge.indexing.chunking.FixedSizeChunker
import com.osvin.aichallenge.indexing.chunking.StrategyDefaults
import com.osvin.aichallenge.indexing.chunking.StructuralChunker
import com.osvin.aichallenge.indexing.index.JsonVectorStore
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.PageMarkers
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.indexing.pipeline.DocumentIndexer
import com.osvin.aichallenge.indexing.pipeline.IndexingResult
import com.osvin.aichallenge.indexing.pipeline.SemanticSearch
import com.osvin.aichallenge.indexing.search.SearchResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Найденный фрагмент базы — то, что агент подставляет в запрос, показывает в отчёте и пишет в лог.
 *
 * Здесь лежат все метаданные чанка, а не только текст и близость: по ним видно, откуда взят ответ
 * и можно ли его проверить. Идентификатор ([id]) и номер ([chunkIndex]) находят запись в файле
 * индекса, файл корпуса ([source]) и раздел ([section]) называют место в книге, страницы ([pages])
 * позволяют открыть книгу на нужном месте, а близость ([similarity]) говорит, насколько поиск был
 * уверен. Без этого подстановка фрагментов была бы неотличима от ответа по памяти.
 *
 * Идентификатор отдельно от номера: номер — место в выдаче этого запроса (1, 2, 3…), по нему
 * модель ссылается на источник в ответе, а идентификатор — имя чанка в индексе, оно не меняется
 * между прогонами и по нему чанк находится в файле.
 */
data class Source(
    /** Место в выдаче, начиная с 1: этим номером источники нумеруются в запросе. */
    val rank: Int,
    /** Косинусная близость вектора вопроса и вектора чанка: число той же модели, что на странице дня 21. */
    val similarity: Double,
    /** Текст чанка целиком: в запрос уходит он, а не пересказ. */
    val text: String,
    /** Идентификатор чанка в индексе. */
    val id: String,
    /** Файл корпуса, из которого собран чанк. */
    val source: String,
    /** Название документа, если оно у чанка есть. */
    val title: String?,
    /** Страницы книги, из которых собран чанк. */
    val pages: List<Int>,
    /** Раздел книги (глава), если он был у чанка. */
    val section: String?,
    /** Номер чанка в индексе. */
    val chunkIndex: Int
) {

    /** Подпись источника для запроса и отчёта: «book.md · стр. 8 · Глава первая · чанк #7». */
    val label: String = buildString {
        append(source)
        // Подпись страниц готова в конвейере (`стр. 8–9`) — второй раз слово «стр.» не добавляется.
        append(" · ").append(PageMarkers.format(pages) ?: "страницы не размечены")
        section?.let { append(" · ").append(it) }
        append(" · чанк #").append(chunkIndex)
    }
}

/**
 * Что вернул поиск: найденные фрагменты и то, чем они получены.
 *
 * Фрагментов хватает отчёту и проверке, но не странице дня 22: она показывает путь запроса
 * по звеньям, а звено «вектор вопроса» в выдаче не видно — близость уже посчитана. Вектор кладётся
 * рядом, потому что посчитать его второй раз значило бы надеяться на детерминизм провайдера вместо
 * того, чтобы отдать ровно тот вектор, которым получена выдача (сравнение `SemanticSearch`).
 *
 * [millis] — время звена поиска целиком: от вопроса до фрагментов.
 */
data class Retrieval(
    val sources: List<Source>,
    /** Вектор вопроса: страница показывает его размерность, отчёт о нём не говорит. */
    val queryVector: List<Float>,
    val millis: Long
)

/**
 * Откуда агент берёт фрагменты.
 *
 * Отдельный контракт, а не конкретный поиск, ради главного свойства сравнения: в режиме без RAG
 * поиск обязан не выполняться вовсе, и это должно быть видно из кода агента, а не из логов.
 * С подставным источником, который падает при вызове, «без RAG не искал» проверяется тестом.
 */
interface SourceFinder {
    suspend fun find(question: String): Retrieval
}

/**
 * Поиск по базе: вопрос → вектор → ближайшие чанки → источники с метаданными.
 *
 * Вектор вопроса считает тот же провайдер, что индексировал чанки, и требование это держится
 * в [SemanticSearch] из конвейера: агент его не повторяет, а берёт готовый поиск. Считать запрос
 * вторым способом — например, подставив другую модель — значило бы сравнивать близости из разных
 * пространств, и выдача перестала бы описывать тот индекс, что лежит на диске.
 *
 * `topK` задаётся один на весь прогон, а не подбирается под вопрос: одно и то же число для всех
 * десяти вопросов — условие сравнения, иначе разница между вопросами могла бы прийти из разницы
 * в бюджете контекста.
 */
class Retriever(
    embedding: Embedding,
    store: JsonVectorStore,
    private val topK: Int
) : SourceFinder {

    private val search = SemanticSearch(embedding.provider, store)

    /** Поиск вместе с вектором запроса и временем: то и другое показывает страница дня 22. */
    override suspend fun find(question: String): Retrieval {
        val started = System.nanoTime()
        val found = search.searchWithQuery(question, topK)
        val millis = (System.nanoTime() - started) / 1_000_000
        return Retrieval(
            sources = found.results.mapIndexed { index, result -> source(index + 1, result) },
            queryVector = found.queryVector,
            millis = millis
        )
    }

    private fun source(rank: Int, result: SearchResult): Source {
        val chunk = result.chunk
        val metadata = chunk.metadata
        return Source(
            rank = rank,
            similarity = result.similarity,
            text = chunk.content,
            id = chunk.id,
            source = metadata.source,
            title = metadata.title,
            pages = metadata.pages,
            section = metadata.section,
            chunkIndex = metadata.chunkIndex
        )
    }
}

/**
 * Индекс базы дня: сборка и поиск по одному и тому же файлу.
 *
 * Файл индекса создаётся заново ([JsonVectorStore.create]), а не дополняется: в каталоге прогона
 * могли остаться чанки прошлого корпуса или прошлой нарезки, и они участвовали бы в поиске,
 * не попав ни в один отчёт. Пересборка — отдельное решение прогона ([build]), а не свойство
 * открытия: поиск по готовому индексу идёт как есть.
 *
 * Стратегия нарезки — параметр прогона, а не константа агента: по умолчанию берётся структурная
 * (на этом корпусе она держала эталонный фрагмент в выдаче чаще), но фиксированное окно остаётся
 * доступным, и оба режима всегда идут по одному и тому же индексу.
 */
class RagIndex(
    private val workDir: Path,
    private val embedding: Embedding,
    val strategy: ChunkingStrategyType
) {

    /** Файл индекса в рабочем каталоге: имя различает стратегии, поэтому прогоны не путаются. */
    val file: Path = workDir.resolve("index-" + strategy.name.lowercase() + ".json")

    /** Собран ли индекс: пустой файл считается несобранным — в нём нет ни одного чанка. */
    val exists: Boolean get() = Files.exists(file) && Files.size(file) > 0L

    /** Чанкер по стратегии: настройки берутся из общих значений дня 21, а не задаются заново. */
    private val chunker: ChunkingStrategy = when (strategy) {
        ChunkingStrategyType.FIXED_SIZE -> FixedSizeChunker(
            StrategyDefaults.FIXED_CHUNK_SIZE,
            StrategyDefaults.FIXED_OVERLAP
        )

        ChunkingStrategyType.STRUCTURAL -> StructuralChunker(StrategyDefaults.STRUCTURAL_MAX_CHUNK_SIZE)
    }

    /**
     * Индексирует базу заново: нарезка, векторы, запись файла.
     *
     * [onChunk] получает число готовых чанков и общее — по нему прогон показывает ход работы,
     * потому что embedding идёт секундами, и молчание на это время выглядит как зависание.
     */
    suspend fun build(base: DayBase, onChunk: (done: Int, total: Int) -> Unit = { _, _ -> }): IndexingResult {
        val store = JsonVectorStore.create(file)
        return DocumentIndexer(chunker, embedding.provider, store).index(listOf(base.document), onChunk)
    }

    /** Сколько чанков в готовом индексе: число нужно отчёту, а чтение файла дешевле пересборки. */
    fun chunkCount(): Int = JsonVectorStore.open(file).size

    /**
     * Поиск по индексу с диска.
     *
     * Индекс читается один раз на прогон, а не на вопрос: перечитывание файла перед каждым из десяти
     * вопросов ничего не изменило бы в выдаче, а переиндексация — изменила бы всё: это уже другой
     * индекс, и ответы относились бы к нему, а не к тому, что проверяли.
     */
    fun retriever(topK: Int): Retriever = Retriever(embedding, JsonVectorStore.open(file), topK)
}
