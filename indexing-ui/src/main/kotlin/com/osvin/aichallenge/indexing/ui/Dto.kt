package com.osvin.aichallenge.indexing.ui

import com.osvin.aichallenge.indexing.chunking.StrategyDefaults
import kotlinx.serialization.Serializable

/**
 * Данные страницы: то, что сервер отдаёт браузеру, и ничего больше.
 *
 * Отдельные DTO, а не модели конвейера, потому что у страницы и у конвейера разные контракты:
 * странице нужны подписи для человека (номер страницы уже отформатирован, similarity округлена),
 * а конвейеру — сырые значения. Если бы страница получала `ChunkMetadata` напрямую, любое
 * изменение метаданных ломало бы разметку, а форматирование приходилось бы делать в JavaScript.
 */
@Serializable
data class CorpusFileDto(
    val name: String,
    /** Документ, которому принадлежит файл: у книги дня это один документ, и у загрузок тоже один. */
    val document: String,
    val chars: Int,
    val tokens: Int,
    val pageLabel: String?,
    /**
     * Файл собран из встроенного PDF дня ([DayCorpus]), а не загружен человеком.
     *
     * Страница показывает это словом, а не догадкой: корпус с первого захода не пуст, и человек
     * должен видеть, откуда в нём документ, — иначе он решит, что страница подсунула свой текст.
     */
    val builtIn: Boolean
)

/**
 * Настройки нарезки, которые страница может менять перед индексацией.
 *
 * Значения по умолчанию — числа задания ([StrategyDefaults]): те же, что в прогоне сравнения,
 * чтобы первый прогон на странице был тем же экспериментом, который описан в отчёте. Ограничения
 * на них не выдуманы здесь: их проверяют конструкторы чанкеров (`overlap < chunkSize` и прочее),
 * и страница получает от них же сообщение об ошибке.
 */
@Serializable
data class ChunkSettingsDto(
    val chunkSize: Int = StrategyDefaults.FIXED_CHUNK_SIZE,
    val overlap: Int = StrategyDefaults.FIXED_OVERLAP,
    val maxChunkSize: Int = StrategyDefaults.STRUCTURAL_MAX_CHUNK_SIZE
)

/**
 * Что страница знает до нажатия кнопки: корпус, настройки стратегий и эталонный вопрос.
 *
 * [chars] и [tokens] — размер документа целиком: токенов меньше, чем символов, и по ним видно
 * объём работы embedding; по файлам то же самое разложено в [files].
 */
@Serializable
data class SetupDto(
    val title: String,
    /** Сколько документов в наборе: корпус дня и всё, что загружено по ссылке. */
    val documents: Int,
    val files: List<CorpusFileDto>,
    val chars: Int,
    val tokens: Int,
    val pages: Int,
    val defaults: ChunkSettingsDto,
    /** Чем считаются векторы: без этого числа близости на странице не прочитать. */
    val provider: ProviderDto,
    /** Эталонный вопрос дня: страница подставляет его в поле ввода, пока индекс ещё не построен. */
    val referenceQuestion: String,
    val defaultTopK: Int,
    val maxTopK: Int
)

/** Отработавшая фаза индексации: стратегия, её чанки, их токены и время. */
@Serializable
data class PhaseDto(val strategy: String, val chunks: Int, val tokens: Int, val elapsedMs: Long)

/**
 * Чем на этой странице считаются векторы.
 *
 * Числа близости читаются вместе с этим: у хешированных признаков и у модели своя шкала, и одна
 * и та же similarity означает у них разное. [note] говорит, почему выбран именно этот провайдер, —
 * при `auto` здесь же стоит причина, по которой страница осталась без модели.
 *
 * [featuresNamed] — знает ли провайдер имена признаков: хешированный вектор объясняется словами
 * и триграммами, вектор модели — нет, и подписывать его компоненты было бы выдумкой.
 */
@Serializable
data class ProviderDto(
    val kind: String,
    val name: String,
    val model: String?,
    val dimension: Int,
    val note: String,
    val featuresNamed: Boolean
)

/**
 * Состояние индексации для страницы.
 *
 * [phase] и [phaseIndex] описывают, какая из двух стратегий индексируется сейчас, а [percent]
 * считается по этапам: `(phaseIndex + done / total) / phaseCount`. Так прогресс честен при любом
 * соотношении числа чанков стратегий — ждать заранее неизвестного общего числа чанков не нужно.
 *
 * [phaseLog] — что уже отработало. Он нужен не вместо прогресса, а вместе с ним: корпус дня
 * индексируется за доли секунды, полоса успевает мигнуть, и по журналу видно, что именно
 * произошло — сколько чанков дала каждая стратегия, сколько в них токенов и сколько это заняло.
 *
 * [settings] — настройки, с которыми построен (или строится) индекс: страница сравнивает их
 * с тем, что стоит в полях, и подсказывает, что после правки настроек индекс нужно перестроить.
 */
@Serializable
data class StatusDto(
    val state: String,
    val phase: String?,
    val phaseIndex: Int,
    val phaseCount: Int,
    val done: Int,
    val total: Int,
    val percent: Int,
    val chunks: Int,
    val chunksByStrategy: Map<String, Int>,
    val tokensByStrategy: Map<String, Int>,
    val phaseLog: List<PhaseDto>,
    val settings: ChunkSettingsDto?,
    val elapsedMs: Long,
    val error: String?
)

/** Что дало добавление документа по ссылке: сам файл, корпус после него и запущенная индексация. */
@Serializable
data class AddedDocumentDto(
    val file: CorpusFileDto,
    val setup: SetupDto,
    val status: StatusDto
)

/**
 * Эталонный ответ: вопрос, страницы, на которых он лежит, и фрагмент с ответом.
 *
 * Файла и раздела здесь нет: корпус — это загруженный PDF, у которого и имя, и разметка свои,
 * а страницы — его собственное свойство. [found] отличает «ответ прочитан из страниц» от «в этом
 * PDF таких страниц нет»: во втором случае текст пуст, и страница обязана сказать об этом словами,
 * а не показать пустоту.
 */
@Serializable
data class ReferenceDto(
    val question: String,
    val pages: List<Int>,
    val pageLabel: String?,
    val found: Boolean,
    /**
     * Абзацы документа, которые отвечают на вопрос: ищутся по словам [taskAnswer], а не вопроса, —
     * показать нужно то, что читает человек, а не то, что похоже на формулировку.
     *
     * Их несколько, потому что ответ бывает перечислением: пять принципов названы пятью абзацами,
     * и первый из них, показанный в одиночку, выглядел бы ответом целиком.
     */
    val fragments: List<String>,
    /** Ответ задания ([DayReference.ANSWER]): с ним сверяется и выдача, и текст страниц. */
    val taskAnswer: String,
    /** Текст страниц эталона из документа дня: то, что о том же самом говорит корпус. */
    val answer: String
)

/** Подпись признака: имя и вид — слово целиком или триграмма внутри слова. */
@Serializable
data class FeatureLabelDto(val text: String, val kind: String)

/**
 * Компонента вектора: измерение, вес и признаки, которые в это измерение попали.
 *
 * [hiddenFeatures] — сколько признаков измерения не поместилось в [features]: измерение хешированного
 * вектора собирает все признаки с таким хешем, и в одном измерении их может быть много.
 */
@Serializable
data class VectorComponentDto(
    val dimension: Int,
    val weight: Double,
    val features: List<FeatureLabelDto>,
    val hiddenFeatures: Int
)

/** Сводка вектора: размерность, ненулевых компонент, норма и старшие компоненты. */
@Serializable
data class EmbeddingDto(
    val dimension: Int,
    val nonZero: Int,
    val norm: Double,
    val top: List<VectorComponentDto>
)

/**
 * Слагаемое близости: одно измерение, веса вопроса и чанка по нему и вклад в сумму.
 *
 * Признаки обеих сторон нужны вместе с числами: измерение хешированного вектора безымянно, и
 * «почему это измерение общее» видно только по признакам — например, слово вопроса и триграмма
 * чанка, попавшие в один и тот же бакет.
 */
@Serializable
data class TermDto(
    val dimension: Int,
    val queryWeight: Double,
    val chunkWeight: Double,
    val contribution: Double,
    val queryFeatures: List<FeatureLabelDto>,
    val chunkFeatures: List<FeatureLabelDto>
)

/**
 * Разбор близости: сколько измерений общих, вклад показанных слагаемых и сверка с косинусом.
 *
 * [dot] — сумма всех слагаемых (скалярное произведение), [shownSum] — только показанных;
 * [cosine] посчитан той же мерой, что и поиск, и на нормированных векторах обязан совпасть
 * с similarity из выдачи — это и есть проверяемость числа на странице.
 */
@Serializable
data class BreakdownDto(
    val shared: Int,
    val terms: List<TermDto>,
    val shownSum: Double,
    val dot: Double,
    val cosine: Double
)

/** Один найденный чанк в том виде, в каком его читает человек. */
@Serializable
data class ResultDto(
    val rank: Int,
    val similarity: Double,
    val source: String,
    val section: String?,
    val pages: List<Int>,
    val pageLabel: String?,
    val fragment: String,
    val chunkIndex: Int,
    val chars: Int,
    val tokens: Int,
    /** Эмбеддинг этого чанка из индекса: размерность, ненулевые и старшие компоненты. */
    val embedding: EmbeddingDto,
    /** Из чего сложилась близость с вопросом: слагаемые по общим измерениям. */
    val breakdown: BreakdownDto,
    /** Совпадает ли чанк с эталонным разделом по содержанию — той же мерой, что и в сравнении стратегий. */
    val reference: Boolean
)

/** Результаты одной стратегии: `referenceRank` — ранг эталона в её выдаче, `null` — не попал. */
@Serializable
data class StrategyResultsDto(
    val strategy: String,
    val label: String,
    val chunks: Int,
    val tokens: Int,
    val results: List<ResultDto>,
    val referenceRank: Int?
)

/**
 * Ответ на поисковый запрос: вопрос, его эмбеддинг, запрошенное число результатов, настройки,
 * эталон и стратегии.
 *
 * Вектор вопроса лежит здесь, а не в каждом результате: он один и тот же для всей выдачи, и
 * хранить его копию рядом с каждым чанком значило бы отправлять все его измерения столько раз,
 * сколько результатов показано (их бывает и 1024 — столько у модели дня).
 */
@Serializable
data class SearchDto(
    val question: String,
    val queryEmbedding: EmbeddingDto,
    val topK: Int,
    val settings: ChunkSettingsDto,
    val reference: ReferenceDto,
    val strategies: List<StrategyResultsDto>
)

/** Сообщение об ошибке: страница показывает его человеку, а не разбирает по коду. */
@Serializable
data class ErrorDto(val message: String)
