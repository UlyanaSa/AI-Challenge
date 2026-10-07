package com.osvin.aichallenge.indexing.ui

import com.osvin.aichallenge.indexing.chunking.ChunkingStrategy
import com.osvin.aichallenge.indexing.chunking.FixedSizeChunker
import com.osvin.aichallenge.indexing.chunking.StructuralChunker
import com.osvin.aichallenge.indexing.embedding.Feature
import com.osvin.aichallenge.indexing.index.JsonVectorStore
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentChunk
import com.osvin.aichallenge.indexing.model.DocumentFile
import com.osvin.aichallenge.indexing.model.PageMarkers
import com.osvin.aichallenge.indexing.ollama.Embedding
import com.osvin.aichallenge.indexing.pipeline.AnswerFragment
import com.osvin.aichallenge.indexing.pipeline.DocumentIndexer
import com.osvin.aichallenge.indexing.pipeline.IndexingResult
import com.osvin.aichallenge.indexing.pipeline.SemanticSearch
import com.osvin.aichallenge.indexing.pipeline.Tokens
import com.osvin.aichallenge.indexing.search.CosineBreakdown
import com.osvin.aichallenge.indexing.search.SearchResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Чем кончилось добавление документа по ссылке. */
sealed interface AddOutcome {

    /** Документ добавлен в корпус, индекс перестраивается с теми же настройками. */
    data class Added(val file: CorpusFileDto, val documents: SetupDto, val status: StatusDto) : AddOutcome

    /** Документ не добавлен; сообщение показывается человеку как есть. */
    data class Rejected(val message: String) : AddOutcome
}

/** Чем кончился запуск индексации: принято или отвергнуто с причиной. */
sealed interface StartOutcome {

    /** Индексация запущена (или уже идёт — тогда это её же состояние). */
    data class Accepted(val status: StatusDto) : StartOutcome

    /** Настройки не прошли проверку чанкеров; сообщение показывается человеку как есть. */
    data class Rejected(val message: String) : StartOutcome
}

/**
 * Состояние страницы дня 21: корпус, индексация по кнопке и поиск по эталонному вопросу.
 *
 * Настройки нарезки приходят с кнопки ([start]): страница показывает `chunk`, `overlap`
 * и `max_chunk`, и прогон обязан идти с теми числами, которые видит человек, — иначе настройки
 * были бы украшением. Проверяет их не сервер, а конструкторы чанкеров: правила «`overlap` меньше
 * `chunkSize`» уже записаны там, и второе место с теми же правилами разошлось бы с первым.
 *
 * Индексация запускается по нажатию и идёт в фоне: страница опрашивает [status] и рисует прогресс,
 * поэтому сервер обязан отвечать на запросы, пока считается embedding. Фоновый запуск — не
 * оптимизация, а условие работы интерфейса: в одном потоке с индексацией страница не смогла бы
 * показать её ход.
 *
 * Прогресс считается по стратегиям (две фазы): число чанков становится известно только после
 * нарезки, а нарезка идёт внутри индексатора, поэтому общий процент складывается из «фаза +
 * доля чанков текущей фазы». Обещать заранее общее число чанков было бы выдумкой.
 *
 * Индекс пишется на диск теми же `JsonVectorStore`, что и в прогоне сравнения, и поиск идёт по
 * перечитанным с диска индексам: страница показывает ровно тот конвейер, что описан в отчёте,
 * а не его упрощённую копию в памяти.
 */
class IndexingSession(
    /** Рабочий каталог прогона: индексы и загруженные по ссылке документы. */
    private val workDir: Path,
    private val scope: CoroutineScope,
    /**
     * Чем считать векторы: провайдер выбирается при запуске страницы ([EmbeddingProviders]).
     *
     * Сессия его не выбирает сама: у `:indexing-ui` нет ни демона, ни модели, а у прогона в консоли
     * своя причина считать хешированные признаки — выбор сделан один раз на входе, и обе стратегии
     * и поиск обязаны считать одним и тем же провайдером, иначе similarity сравнивала бы векторы
     * из разных пространств.
     */
    private val embedding: Embedding,
    private val pdfSource: PdfSource = PdfSource()
) {

    /** Каталог загруженных документов: рядом с индексами, потому что это тоже результат прогона. */
    private val documentsDir: Path = workDir.resolve(DOCUMENTS_DIR)

    /** Имя файла корпуса дня в каталоге документов; `null` — встроенного PDF рядом с кодом нет. */
    private var builtInFileName: String? = null

    /**
     * Готовый прогон: настройки, с которыми он сделан, результаты, поиск и оба хранилища.
     *
     * Хранилища держатся рядом с поиском потому, что страница показывает не только близость, но и
     * векторы: близость раскладывается на слагаемые именно теми векторами, что лежат в индексе.
     */
    private data class Built(
        val settings: ChunkSettingsDto,
        val fixed: IndexingResult,
        val structural: IndexingResult,
        val fixedSearch: SemanticSearch,
        val structuralSearch: SemanticSearch,
        val fixedStore: JsonVectorStore,
        val structuralStore: JsonVectorStore
    )

    private val lock = Any()

    /**
     * Документы прогона: PDF дня ([DayCorpus]) и всё, что загружено PDF-ом — по ссылке или файлом.
     *
     * Текста книги в проекте нет: есть сам PDF, и всё, что о нём известно (страницы, заголовки),
     * извлечено из его вёрстки. Так эталон и выдача описывают один и тот же файл, а не копию книги,
     * разошедшуюся с оригиналом, — и вопрос дня, заданный заранее, проверяется на том же документе,
     * по которому он задан.
     *
     * Документы лежат файлами в [documentsDir] и поднимаются при старте: если бы они жили только
     * в памяти, после перезапуска страницы корпус молча терял бы их, а чанки оставались бы в файле
     * индекса — и выдача описывала бы набор, которого больше нет.
     */
    private var documents: List<Document> = loadCorpus()

    private var state = "idle"
    private var phase: String? = null
    private var phaseIndex = 0
    private var done = 0
    private var total = 0
    private var elapsedMs = 0L
    private var error: String? = null
    private var built: Built? = null
    private var settings: ChunkSettingsDto? = null
    private val phaseLog = ArrayList<PhaseDto>()

    /**
     * Что страница знает до нажатия кнопки: корпус, настройки по умолчанию и эталонный вопрос.
     *
     * Корпус может быть пустым: своего набора у страницы нет, документ приносит человек, и до
     * первой загрузки индексировать нечего. Пустой корпус — это состояние страницы, а не ошибка,
     * и у него есть своё имя ([EMPTY_TITLE]), иначе заголовок пришлось бы показывать пустым.
     */
    fun setup(): SetupDto {
        val current = documents
        val files = current.flatMap { document -> document.files.map { fileDto(document, it) } }
        val pages = current.flatMap { document -> document.files }
            .flatMap { file -> file.pages.map { it.page } }
            .distinct()
        return SetupDto(
            title = current.firstOrNull()?.let { it.title ?: it.id } ?: EMPTY_TITLE,
            documents = current.size,
            files = files,
            chars = files.sumOf { it.chars },
            tokens = Tokens.count(current),
            pages = pages.size,
            defaults = ChunkSettingsDto(),
            provider = providerDto(),
            referenceQuestion = DayReference.QUESTION,
            defaultTopK = DEFAULT_TOP_K,
            maxTopK = MAX_TOP_K
        )
    }

    /**
     * Провайдер для страницы: имя, модель, размерность и причина выбора.
     *
     * Размерность берётся у самого провайдера, а не из настройки: у модели её объявляет модель,
     * и число, вписанное рядом, разошлось бы с векторами в индексе.
     */
    private fun providerDto() = ProviderDto(
        kind = embedding.kind,
        name = embedding.name,
        model = embedding.model,
        dimension = embedding.provider.dimension,
        note = embedding.note,
        featuresNamed = embedding.features != null
    )

    /** Файл корпуса для страницы: имя, документ, размеры в символах и токенах, страницы. */
    private fun fileDto(document: Document, file: DocumentFile) = CorpusFileDto(
        name = file.name,
        document = document.title ?: document.id,
        chars = file.content.length,
        tokens = Tokens.count(file),
        pageLabel = PageMarkers.format(file.pages.map { it.page }),
        // Документ дня — тот, что собран из встроенного PDF: страница называет его ресурсом дня,
        // и человек видит, откуда взялся корпус, который он не загружал.
        builtIn = file.name == builtInFileName
    )

    /** Текущее состояние индексации. */
    fun status(): StatusDto = synchronized(lock) {
        StatusDto(
            state = state,
            phase = phase,
            phaseIndex = phaseIndex,
            phaseCount = PHASES,
            done = done,
            total = total,
            percent = percent(),
            chunks = (built?.fixed?.chunks?.size ?: 0) + (built?.structural?.chunks?.size ?: 0),
            chunksByStrategy = built?.let {
                mapOf(
                    ChunkingStrategyType.FIXED_SIZE.name to it.fixed.chunks.size,
                    ChunkingStrategyType.STRUCTURAL.name to it.structural.chunks.size
                )
            } ?: emptyMap(),
            tokensByStrategy = built?.let {
                mapOf(
                    ChunkingStrategyType.FIXED_SIZE.name to chunkTokens(it.fixed),
                    ChunkingStrategyType.STRUCTURAL.name to chunkTokens(it.structural)
                )
            } ?: emptyMap(),
            phaseLog = phaseLog.toList(),
            settings = settings,
            elapsedMs = elapsedMs,
            error = error
        )
    }

    /**
     * Запускает индексацию с настройками [requested].
     *
     * Повторное нажатие ничего не меняет: пока идёт работа, вернётся её же состояние, а не второй
     * прогон поверх первого — два индексатора писали бы один файл. Настройки, отвергнутые
     * чанкерами, не запускают ничего: индекс остаётся тем же, что был, и страница показывает
     * сообщение конструктора.
     */
    fun start(requested: ChunkSettingsDto): StartOutcome {
        val chunkers = chunkersOrNull(requested)
            ?: return StartOutcome.Rejected(settingsError(requested))
        return synchronized(lock) {
            // Индексировать пустой набор нельзя: прогон завёл бы два пустых индекса, а страница
            // показала бы нули вместо ответа на незаданный вопрос. Отказ говорит, что делать.
            if (documents.isEmpty()) return@synchronized StartOutcome.Rejected(EMPTY_CORPUS_MESSAGE)
            startLocked(requested, chunkers)
        }
    }

    /** Добавляет документ по ссылке. */
    suspend fun addDocumentByUrl(url: String): AddOutcome = add { pdfSource.load(url) }

    /**
     * Добавляет локальный файл PDF и сразу перестраивает индекс.
     *
     * [name] — имя файла из формы, [bytes] — его содержимое. Отдельный вход, а не «ссылка на
     * file://»: локальный файл не нужно доставать по сети, и браузер, и сервер рядом, а имя файла
     * приходит таким, каким его видит человек.
     */
    suspend fun addDocumentFromFile(name: String, bytes: ByteArray): AddOutcome =
        add { pdfSource.parse(bytes, name) }

    /**
     * Общий путь добавления: получить документ, записать его в корпус и объявить индекс устаревшим.
     *
     * Индексация отсюда не запускается: порядок шагов задаёт человек — сначала документ, потом
     * индексация, — и настройки нарезки он выбирает между этими шагами. Запусти прогон в момент
     * загрузки, поля настроек оказались бы позади уже начатой работы.
     *
     * Чтение документа (сеть или диск) идёт до блокировки — сеть под общим замком остановила бы
     * опрос состояния, — а запись в корпус выполняется под блокировкой: два документа, добавленных
     * разом, получили бы одно имя файла.
     */
    private suspend fun add(read: suspend () -> LoadedPdf): AddOutcome {
        val loaded = try {
            read()
        } catch (cause: PdfLoadException) {
            return AddOutcome.Rejected(cause.message ?: "Документ не загрузился")
        }

        return synchronized(lock) {
            if (state == "running") {
                return@synchronized AddOutcome.Rejected(
                    "Идёт индексация: дождитесь её окончания и повторите — документ не потеряется"
                )
            }
            val file = attach(loaded)
            invalidate()
            AddOutcome.Added(file, setup(), status())
        }
    }

    /**
     * Корпус изменился: построенный индекс описывает прежний набор, и искать по нему нельзя.
     *
     * Прогон не обнуляется молча — страница после этого показывает «индекс не построен», а поиск
     * остаётся заблокированным до новой индексации. Второй вход — тот же: старый индекс отвечал бы
     * на вопрос по документам, которых в корпусе уже нет.
     */
    private fun invalidate() {
        built = null
        settings = null
        state = "idle"
        phase = null
        phaseIndex = 0
        done = 0
        total = 0
        elapsedMs = 0
        error = null
        phaseLog.clear()
    }

    /** Запуск под уже взятой блокировкой: состояние меняется целиком, вторым прогоном поверх первого. */
    private fun startLocked(requested: ChunkSettingsDto, chunkers: Chunkers): StartOutcome {
        if (state == "running") return StartOutcome.Accepted(status())
        state = "running"
        phase = null
        phaseIndex = 0
        done = 0
        total = 0
        elapsedMs = 0
        error = null
        built = null
        settings = requested
        phaseLog.clear()
        scope.launch { runIndexing(requested, chunkers) }
        return StartOutcome.Accepted(status())
    }

    /** Записывает документ файлом корпуса и добавляет его в набор прогона. */
    private fun attach(loaded: LoadedPdf): CorpusFileDto {
        Files.createDirectories(documentsDir)
        val name = freeFileName(loaded.fileName)
        Files.writeString(documentsDir.resolve(name), loaded.content)

        // Маркеры страниц снимаются тем же кодом, что и у корпуса книги: страницы чанка считаются
        // по смещениям, а не хранятся в самом тексте.
        val paged = PageMarkers.stripAndIndex(loaded.content)
        val document = Document(
            id = name.removeSuffix(CORPUS_SUFFIX),
            title = null,
            files = listOf(DocumentFile(name, paged.content, paged.pages))
        )
        documents = documents + document
        return fileDto(document, document.files.single())
    }

    /** Имя файла, не занятое прежними загрузками: второй документ с тем же именем не перезапишет первый. */
    private fun freeFileName(wanted: String): String {
        val taken = documents.flatMap { it.files }.map { it.name }.toSet()
        if (wanted !in taken) return wanted
        val base = wanted.removeSuffix(CORPUS_SUFFIX)
        var index = 2
        while ("$base-$index$CORPUS_SUFFIX" in taken) index++
        return "$base-$index$CORPUS_SUFFIX"
    }

    /**
     * Корпус на старте: документ дня из ресурса, затем документы прежних загрузок.
     *
     * Разбор ресурса идёт синхронно (`runBlocking`): корпус нужен первому же запросу страницы, а
     * сервер до конца инициализации запросов не принимает — фоновый разбор означал бы, что первый
     * заход может увидеть корпус до того, как его собрали.
     */
    private fun loadCorpus(): List<Document> {
        materialiseBuiltIn()
        return loadDownloaded()
    }

    /**
     * Кладёт PDF дня в рабочий каталог файлом корпуса — тем же путём, что и загрузки.
     *
     * Один путь на все документы: тот же разбор, та же запись, та же нумерация страниц. Второй
     * способ «взять документ из ресурса» жил бы рядом с первым и расходился бы с ним в мелочах —
     * а мелочи здесь и есть всё: от них зависят страницы чанков и эталон.
     *
     * Файл пишется один раз: при следующих запусках он уже лежит в каталоге, и разбирать 12 МБ PDF
     * заново незачем. Если встроенного PDF рядом с кодом нет (в репозиторий он не попадает),
     * корпус соберётся только из загруженных документов, и страница скажет об этом при запуске.
     */
    private fun materialiseBuiltIn() {
        val bytes = DayCorpus.bytes()
        if (bytes == null) {
            println(MISSING_BUILT_IN_MESSAGE)
            return
        }
        Files.createDirectories(documentsDir)
        val target = documentsDir.resolve(DayCorpus.FILE_NAME)
        if (Files.exists(target)) {
            builtInFileName = DayCorpus.FILE_NAME
            return
        }
        val loaded = try {
            runBlocking { pdfSource.parse(bytes, DayCorpus.RESOURCE, DayCorpus.PAGES) }
        } catch (cause: PdfLoadException) {
            println("Встроенный корпус дня не разобрался: ${cause.message}")
            return
        }
        Files.writeString(target, loaded.content)
        builtInFileName = target.fileName.toString()
    }

    /** Документы, загруженные в прошлые разы: файлы корпуса из рабочего каталога. */
    private fun loadDownloaded(): List<Document> {
        if (!Files.isDirectory(documentsDir)) return emptyList()
        val names = Files.list(documentsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(CORPUS_SUFFIX) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        return names.map { name ->
            val paged = PageMarkers.stripAndIndex(Files.readString(documentsDir.resolve(name)))
            Document(
                id = name.removeSuffix(CORPUS_SUFFIX),
                title = null,
                files = listOf(DocumentFile(name, paged.content, paged.pages))
            )
        }
    }

    /**
     * Ищет вопрос дня по обоим индексам; `null` — индекс ещё не построен, и странице нечего искать.
     *
     * Вопрос не приходит из запроса: он задан заранее ([DayReference.QUESTION]), и вместе с ним
     * заранее известны и ответ ([DayReference.ANSWER]), и место, где этот ответ лежит в документе
     * ([DayReference.pages]). Человек выбирает только `top_k` — сколько результатов показать, —
     * поэтому ответ нельзя «подогнать» формулировкой.
     *
     * Обе стратегии получают один и тот же запрос, посчитанный одним и тем же embedding, поэтому
     * расхождение в выдаче — след нарезки, а не поиска.
     */
    suspend fun search(topK: Int): SearchDto? {
        val current = synchronized(lock) { built } ?: return null
        val question = DayReference.QUESTION
        val referenceAnswer = Pages.text(synchronized(lock) { documents }, DayReference.pages)
        val referencePages = DayReference.pages.toList()
        val fixedQuery = current.fixedSearch.searchWithQuery(question, topK)
        val structuralQuery = current.structuralSearch.searchWithQuery(question, topK)
        // Вектор вопроса берётся у поиска, а не считается второй раз: показанный вектор обязан быть
        // тем самым, которым посчитана близость, иначе сверка на странице ничего не проверяет.
        val queryLabels = featuresByDimension(question)

        return SearchDto(
            question = question,
            queryEmbedding = embeddingDto(fixedQuery.queryVector, queryLabels),
            topK = topK,
            settings = current.settings,
            reference = ReferenceDto(
                question = DayReference.QUESTION,
                pages = referencePages,
                pageLabel = PageMarkers.format(referencePages),
                // Текст страниц берётся из документа дня, и его может не оказаться: тогда страница
                // скажет, что эталон к этому корпусу не относится, вместо чужого текста.
                found = referenceAnswer.isNotEmpty(),
                // Абзацы ищутся по словам ответа задания, а не вопроса: показать нужно то, что
                // отвечает на вопрос, а не то, что похоже на его формулировку.
                fragments = if (referenceAnswer.isEmpty()) {
                    emptyList()
                } else {
                    AnswerFragment.matches(referenceAnswer, DayReference.ANSWER)
                },
                taskAnswer = DayReference.ANSWER,
                answer = referenceAnswer
            ),
            strategies = listOf(
                strategyResults(
                    type = ChunkingStrategyType.FIXED_SIZE,
                    label = "Fixed Size (окно ${current.settings.chunkSize} / перекрытие ${current.settings.overlap})",
                    indexed = current.fixed,
                    results = fixedQuery.results,
                    store = current.fixedStore,
                    queryVector = fixedQuery.queryVector,
                    queryLabels = queryLabels,
                    question = question
                ),
                strategyResults(
                    type = ChunkingStrategyType.STRUCTURAL,
                    label = "Structural (предел чанка ${current.settings.maxChunkSize})",
                    indexed = current.structural,
                    results = structuralQuery.results,
                    store = current.structuralStore,
                    queryVector = structuralQuery.queryVector,
                    queryLabels = queryLabels,
                    question = question
                )
            )
        )
    }

    private suspend fun strategyResults(
        type: ChunkingStrategyType,
        label: String,
        indexed: IndexingResult,
        results: List<SearchResult>,
        store: JsonVectorStore,
        queryVector: List<Float>,
        queryLabels: Map<Int, List<Feature>>,
        question: String
    ): StrategyResultsDto {
        val dtos = results.mapIndexed { index, result ->
            resultDto(index + 1, result, store, queryVector, queryLabels, question)
        }
        return StrategyResultsDto(
            strategy = type.name,
            label = label,
            chunks = indexed.chunks.size,
            tokens = chunkTokens(indexed),
            results = dtos,
            referenceRank = dtos.firstOrNull { it.reference }?.rank
        )
    }

    private suspend fun resultDto(
        rank: Int,
        result: SearchResult,
        store: JsonVectorStore,
        queryVector: List<Float>,
        queryLabels: Map<Int, List<Feature>>,
        question: String
    ): ResultDto {
        val metadata = result.chunk.metadata
        // Результат пришёл из этого же хранилища, поэтому вектор чанка в нём есть всегда; если его
        // нет — сломан инвариант «выдача собрана из индекса», и это должно быть видно, а не заглушено.
        val chunkVector = requireNotNull(store.get(result.chunk.id)) {
            "Чанка ${result.chunk.id} нет в индексе, из которого он найден"
        }.embedding
        return ResultDto(
            rank = rank,
            similarity = result.similarity,
            source = metadata.source,
            section = metadata.section,
            pages = metadata.pages,
            pageLabel = PageMarkers.format(metadata.pages),
            fragment = AnswerFragment.best(result.chunk.content, question),
            chunkIndex = metadata.chunkIndex,
            chars = result.chunk.content.length,
            tokens = Tokens.count(result.chunk.content),
            embedding = embeddingDto(chunkVector, featuresByDimension(result.chunk.content)),
            breakdown = breakdownDto(queryVector, queryLabels, chunkVector, result.chunk.content),
            reference = isReference(metadata.pages)
        )
    }

    /**
     * Подписи признаков по измерениям вектора.
     *
     * Показ вектора без этого был бы списком безымянных чисел: измерение хешированного вектора
     * не несёт собственного смысла, и прочитать его можно только по признакам, которые в него попали.
     */
    private fun featuresByDimension(text: String): Map<Int, List<Feature>> =
        embedding.features?.invoke(text)?.groupBy { it.dimension }.orEmpty()

    /** Сводка вектора: старшие компоненты и признаки, объясняющие каждую из них. */
    private fun embeddingDto(vector: List<Float>, labels: Map<Int, List<Feature>>): EmbeddingDto {
        val summary = CosineBreakdown.summarize(vector, TOP_COMPONENTS)
        return EmbeddingDto(
            dimension = summary.dimension,
            nonZero = summary.nonZero,
            norm = summary.norm,
            top = summary.top.map { component ->
                val found = labels[component.dimension].orEmpty().sortedByDescending { abs(it.weight) }
                val shown = found.take(LABELS_PER_COMPONENT)
                VectorComponentDto(
                    dimension = component.dimension,
                    weight = component.weight,
                    features = shown.map { FeatureLabelDto(it.text, it.kind.name) },
                    hiddenFeatures = found.size - shown.size
                )
            }
        )
    }

    /** Разбор близости: слагаемые по общим измерениям с признаками обеих сторон. */
    private fun breakdownDto(
        queryVector: List<Float>,
        queryLabels: Map<Int, List<Feature>>,
        chunkVector: List<Float>,
        chunkText: String
    ): BreakdownDto {
        val breakdown = CosineBreakdown.of(queryVector, chunkVector, TOP_TERMS)
        val chunkLabels = featuresByDimension(chunkText)
        return BreakdownDto(
            shared = breakdown.shared,
            terms = breakdown.terms.map { term ->
                TermDto(
                    dimension = term.dimension,
                    queryWeight = term.left,
                    chunkWeight = term.right,
                    contribution = term.contribution,
                    queryFeatures = featureLabels(queryLabels[term.dimension]),
                    chunkFeatures = featureLabels(chunkLabels[term.dimension])
                )
            },
            shownSum = breakdown.shownSum,
            dot = breakdown.dot,
            cosine = breakdown.similarity
        )
    }

    private fun featureLabels(features: List<Feature>?): List<FeatureLabelDto> =
        features.orEmpty()
            .sortedByDescending { abs(it.weight) }
            .take(LABELS_PER_TERM)
            .map { FeatureLabelDto(it.text, it.kind.name) }

    /**
     * Попадание в эталон: чанк лежит на страницах эталона.
     *
     * Признак — страницы, а не `section` и не текст: страницы есть у метаданных обеих стратегий
     * (их считает карта страниц файла по смещениям), поэтому обе сравниваются по одному правилу,
     * и фиксированная нарезка не проигрывает из-за того, что разделов не размечает. Текст для
     * этого не годится: эталон занимает две страницы, и доля его слов в чанке зависит от размера
     * чанка, а не от того, попал ли он на ответ.
     */
    private fun isReference(pages: List<Int>): Boolean = pages.any { it in DayReference.pages }

    private suspend fun runIndexing(requested: ChunkSettingsDto, chunkers: Chunkers) {
        val startedAt = System.nanoTime()
        // Набор документов фиксируется на время прогона: добавленный в это время документ попадёт
        // в следующий прогон, а не в середину текущего.
        val snapshot = synchronized(lock) { documents }
        try {
            val fixed = indexPhase(0, ChunkingStrategyType.FIXED_SIZE, chunkers.fixed, snapshot)
            val structural = indexPhase(1, ChunkingStrategyType.STRUCTURAL, chunkers.structural, snapshot)
            val fixedStore = JsonVectorStore.open(workDir.resolve(FIXED_INDEX))
            val structuralStore = JsonVectorStore.open(workDir.resolve(STRUCTURAL_INDEX))

            synchronized(lock) {
                built = Built(
                    settings = requested,
                    fixed = fixed,
                    structural = structural,
                    fixedSearch = SemanticSearch(embedding.provider, fixedStore),
                    structuralSearch = SemanticSearch(embedding.provider, structuralStore),
                    fixedStore = fixedStore,
                    structuralStore = structuralStore
                )
                state = "ready"
                phase = null
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            }
        } catch (cause: Throwable) {
            synchronized(lock) {
                state = "failed"
                phase = null
                error = cause.message ?: cause::class.simpleName
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            }
        }
    }

    private suspend fun indexPhase(
        phaseIndex: Int,
        type: ChunkingStrategyType,
        chunker: ChunkingStrategy,
        documents: List<Document>
    ): IndexingResult {
        synchronized(lock) {
            this.phaseIndex = phaseIndex
            phase = type.name
            done = 0
            total = 0
        }
        // Индекс фазы стирается перед записью: чанки прошлого прогона иначе остались бы в файле
        // и в поиске — страница показывала бы смесь двух нарезок, в том числе с другими настройками.
        val store = JsonVectorStore.create(workDir.resolve(indexFile(type)))
        val startedAt = System.nanoTime()
        val result = DocumentIndexer(chunker, embedding.provider, store).index(documents) { done, total ->
            synchronized(lock) {
                this.done = done
                this.total = total
            }
        }
        synchronized(lock) {
            phaseLog += PhaseDto(
                strategy = type.name,
                chunks = result.chunks.size,
                tokens = chunkTokens(result),
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            )
        }
        return result
    }

    /** Чанкеры по настройкам: проверку делают их конструкторы, здесь только сборка. */
    private fun chunkers(requested: ChunkSettingsDto) = Chunkers(
        fixed = FixedSizeChunker(requested.chunkSize, requested.overlap),
        structural = StructuralChunker(requested.maxChunkSize)
    )

    /** Чанкеры или `null`, если настройки не прошли проверку конструкторов. */
    private fun chunkersOrNull(requested: ChunkSettingsDto): Chunkers? = try {
        chunkers(requested)
    } catch (cause: IllegalArgumentException) {
        null
    }

    /** Почему настройки не подошли: сообщение берётся у чанкера, который их отверг. */
    private fun settingsError(requested: ChunkSettingsDto): String = try {
        chunkers(requested)
        "Настройки нарезки не подходят"
    } catch (cause: IllegalArgumentException) {
        cause.message ?: "Настройки нарезки не подходят"
    }

    private fun chunkTokens(result: IndexingResult): Int = result.chunks.sumOf { Tokens.count(it.content) }

    /** Процент по фазам: целые фазы плюс доля текущей. */
    private fun percent(): Int {
        val fraction = if (total > 0) done.toDouble() / total else 0.0
        val value = (phaseIndex + fraction) / PHASES * 100
        return when {
            state == "ready" -> 100
            else -> value.toInt().coerceIn(0, 100)
        }
    }

    private fun indexFile(type: ChunkingStrategyType) = when (type) {
        ChunkingStrategyType.FIXED_SIZE -> FIXED_INDEX
        ChunkingStrategyType.STRUCTURAL -> STRUCTURAL_INDEX
    }

    /** Обе стратегии с их настройками: собраны вместе, потому что запускаются всегда вместе. */
    private data class Chunkers(val fixed: ChunkingStrategy, val structural: ChunkingStrategy)

    companion object {

        /** Имя набора, когда в нём нет ни одного документа: так бывает, если нет и PDF дня. */
        const val EMPTY_TITLE = "Корпус пуст"

        /** Что отвечает запуск индексации, пока не загружен ни один PDF. */
        const val EMPTY_CORPUS_MESSAGE =
            "Корпус пуст: загрузите PDF по ссылке или файлом — индексировать пока нечего"

        /**
         * Что печатает запуск, если встроенного PDF дня нет рядом с кодом.
         *
         * Сообщение для того, кто запускает страницу, а не для человека в браузере: файл под
         * авторским правом в репозиторий не попадает, и на новой машине его нужно положить самому.
         */
        const val MISSING_BUILT_IN_MESSAGE =
            "Встроенный PDF дня не найден: положите файл в indexing-ui/src/main/resources" +
                DayCorpus.RESOURCE + " — без него корпус соберётся только из загруженных документов"

        /** Сколько фаз у индексации: по одной на стратегию. */
        const val PHASES = 2

        /** Порт по умолчанию: страница локальная, порт не пересекается с сервером приложения. */
        const val DEFAULT_PORT = 8099

        /** Сколько результатов показывать по умолчанию. */
        const val DEFAULT_TOP_K = 3

        /** Сколько старших компонент вектора показывать: хвост разреженного вектора не читается. */
        private const val TOP_COMPONENTS = 8

        /** Сколько признаков подписывать у одной компоненты. */
        private const val LABELS_PER_COMPONENT = 3

        /** Сколько слагаемых близости показывать: сумма считается по всем общим измерениям. */
        private const val TOP_TERMS = 8

        /** Сколько признаков подписывать у одной стороны слагаемого. */
        private const val LABELS_PER_TERM = 2

        /** Предел `top_k`: на странице нужен обзор выдачи, а не выгрузка всего индекса. */
        const val MAX_TOP_K = 20

        private const val FIXED_INDEX = "index-fixed-size.json"
        private const val STRUCTURAL_INDEX = "index-structural.json"

        /** Подкаталог рабочего каталога с документами, загруженными по ссылке. */
        private const val DOCUMENTS_DIR = "documents"

        /** Суффикс файлов корпуса: в них лежит текст, снятый с PDF. */
        private const val CORPUS_SUFFIX = ".md"

        /** Рабочий каталог страницы: индексы и загруженные документы; создаётся при старте. */
        fun workDirectory(path: Path): Path = Files.createDirectories(path)
    }
}
