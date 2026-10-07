package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.rag.Answer
import com.osvin.aichallenge.rag.AnswerCheck
import com.osvin.aichallenge.rag.Candidate
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.StageCheck
import com.osvin.aichallenge.rag.StageConfig
import com.osvin.aichallenge.rag.StageSummary
import com.osvin.aichallenge.rag.Summary
import com.osvin.aichallenge.rag.Trace
import com.osvin.aichallenge.indexing.pipeline.Tokens
import kotlinx.serialization.Serializable

/**
 * Что страница знает до нажатия кнопки: база, индекс, провайдер векторов и набор вопросов.
 *
 * Набор вопросов уходит на страницу целиком, с ожиданиями и местом в книге, а не по одному на
 * запрос: это условие сравнения, а не справочная информация. Человек, который видит вопросы
 * и эталоны рядом с прогоном, может судить о числах; тот, кто видит только ответы, вынужден
 * верить оценке.
 */
@Serializable
data class SetupDto(
    /** Рабочий каталог прогона: корпус, индекс и отчёты. */
    val dir: String,
    val base: BaseDto,
    val index: IndexDto,
    val provider: ProviderDto,
    val model: String,
    /** Top-K базового режима дня 22: столько фрагментов берёт поиск по близости. */
    val topK: Int,
    val maxTopK: Int,
    /**
     * Настройки этапов улучшенного конвейера — те же значения по умолчанию, что у прогона в консоли.
     *
     * Уходят странице не подсказкой, а начальным значением полей: прогон со страницы и прогон
     * в консоли должны начинаться с одного условия, иначе числа двух отчётов не сравнить.
     */
    val retrievalTopK: Int,
    val finalTopK: Int,
    val threshold: Double,
    /** Вариант переписывания (`none` или `llm`) и второго этапа (`none`, `heuristic` или `llm`). */
    val rewrite: String,
    val rerank: String,
    /**
     * Чего не хватает для живого прогона.
     *
     * Отдельной строкой, а не ошибкой на кнопке: без ключа страница показывает поиск и индексацию,
     * а ответов модели не будет — и это состояние запуска, а не сбой одного запроса.
     */
    val keyNote: String?,
    val questions: List<QuestionDto>
)

/** База прогона: где она лежит и сколько в ней текста. */
@Serializable
data class BaseDto(val file: String, val chars: Int, val tokens: Int, val pages: Int)

/** Индекс: какой файл, чем нарезан, собран ли и сколько в нём чанков. */
@Serializable
data class IndexDto(
    val file: String,
    val strategy: String,
    val chunks: Int,
    /** Сколько чанков должно получиться: видно только во время сборки, поэтому и отдельно. */
    val total: Int,
    val state: String
) {

    companion object {

        /** Индекс не собран: поиск невозможен, пока его не построят. */
        const val IDLE = "idle"

        /** Идёт сборка: страница показывает прогресс. */
        const val BUILDING = "building"

        /** Индекс готов: поиск идёт по нему, пересборки при запросах нет. */
        const val READY = "ready"
    }
}

/** Провайдер векторов: без него числа близости не читаются. */
@Serializable
data class ProviderDto(
    val kind: String,
    val name: String,
    /** Модель векторов: `null` у хеширования, где модели нет. */
    val model: String?,
    val dimension: Int,
    val note: String
)

/**
 * Контрольный вопрос на странице: он же — строка таблицы сравнения.
 *
 * [facts] уходят списком строк, а не правилами проверки: страница показывает, чего от ответа ждут,
 * и не пересказывает [Check] — иначе правила сверки существовали бы в двух видах.
 */
@Serializable
data class QuestionDto(
    val id: String,
    val question: String,
    val expected: String,
    val facts: List<String>,
    val pages: List<Int>,
    val section: String,
    val note: String,
    /** Ответа в базе нет: у вопроса не будет ни страниц, ни попадания источника. */
    val absent: Boolean
)

/**
 * Состояние прогона на странице.
 *
 * Ответы и шаги лежат вместе, потому что страница опрашивает одно состояние целиком: собирать
 * их из разных запросов значило бы показывать таблицу и конвейер из разных моментов времени.
 */
@Serializable
data class StateDto(
    val state: String,
    /** Что именно идёт: «индекс» или номер вопроса — по нему видно, где прогон остановился. */
    val stage: String?,
    val stageTitle: String?,
    val done: Int,
    val total: Int,
    val index: IndexDto,
    val error: String?,
    val results: List<ResultDto>,
    /** Метрики дня 22 на паре «без базы — базовый RAG»: считаются тем же кодом, что и в дне 22. */
    val summary: Summary?,
    /** Метрики этапов дня 23: попадания по шагам, цена фильтра и потери по видам. */
    val stages: StagesDto?,
    /** Настройки последнего прогона: без них числа и трейсы не объяснить. */
    val config: StageConfigDto?,
    /** Файлы, записанные после прогона: сравнение и лог запросов. */
    val reports: List<ReportDto>,
    val elapsedMs: Long
) {

    companion object {

        const val IDLE = "idle"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"

        /** Имена шагов прогона: по ним страница пишет, что делается прямо сейчас. */
        const val INDEX_STAGE = "index"
    }
}

/** Записанный файл прогона: имя для человека и путь на диске. */
@Serializable
data class ReportDto(val name: String, val file: String)

/**
 * Результаты одного вопроса во всех трёх режимах.
 *
 * Пустой [without], [baseline] или [improved] означает, что режим ещё не выполнялся: страница
 * показывает такие вопросы как «не прогонялся», а не как нулевую оценку.
 *
 * [trace] и [stageCheck] относятся только к улучшенному режиму: это путь запроса по этапам конвейера,
 * и у базового поиска дня 22 этапов не было — описывать в нём нечего.
 */
@Serializable
data class ResultDto(
    val id: String,
    val question: String,
    val absent: Boolean,
    val without: ModeDto?,
    val baseline: ModeDto?,
    val improved: ModeDto?,
    val trace: TraceDto?,
    val stageCheck: StageCheckDto?
)

/**
 * Конвейер одного режима одного вопроса — то, ради чего страница существует.
 *
 * Поля идут в порядке пути запроса: вектор → выдача → контекст → ответ → сверка. Так их читает
 * и страница, и человек; из готового ответа этот порядок не восстановить, потому что в нём
 * остались бы только текст и число оценки.
 *
 * [state] различает «режим выполнился» и «режим отказал»: у отказанного ответа нет ни текста,
 * ни оценки, и показывать на его месте нули значило бы выдавать сбой за результат сравнения.
 */
@Serializable
data class ModeDto(
    /** Какой это режим: `without`, `baseline` или `improved` — по нему страница выбирает разметку. */
    val mode: String,
    val title: String,
    val state: String,
    val error: String?,
    val text: String,
    val score: Int,
    val total: Int,
    /**
     * Чем кончился режим: верно, ошибка поиска или ошибка генерации.
     *
     * Пусто у режима без базы: ошибки поиска там быть не может — поиска не было, а разделение
     * ошибок задание требует для режима с базой (оно отделяет «поиск не нашёл» от «модель
     * не справилась»). Показать здесь «ошибку поиска» значило бы обвинить поиск, который
     * не вызывался.
     */
    val outcome: String,
    val facts: List<FactDto>,
    /**
     * Попал ли ожидаемый текст в выдачу поиска.
     *
     * `null` в двух случаях, и оба означают «считать нечего»: режим без базы поиска не выполнял,
     * а у вопроса без ответа в базе искать нечего. Ноль на их месте выглядел бы как измерение.
     */
    val hit: Boolean?,
    val sources: List<SourceDto>,
    /** Сколько чисел в векторе вопроса: `null` в режиме без RAG, где вектора нет. */
    val queryDimension: Int?,
    val retrievalMillis: Long,
    val messages: List<MessageDto>,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val finishReason: String?,
    val elapsedMillis: Long,
    val citedFragments: List<Int>,
    val citedPages: List<Int>
) {

    companion object {

        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** Факт ожидания и то, нашёлся ли он в ответе. */
@Serializable
data class FactDto(val text: String, val matched: Boolean)

/** Найденный фрагмент: место в выдаче, место в книге, близость и текст. */
@Serializable
data class SourceDto(
    val rank: Int,
    val id: String,
    val source: String,
    val title: String?,
    val section: String?,
    val pages: List<Int>,
    val similarity: Double,
    val chars: Int,
    val tokens: Int,
    val text: String
)

/** Сообщение запроса: системное правило или вопрос с контекстом. */
@Serializable
data class MessageDto(val role: String, val content: String)

/** Результат одного режима вместе с вопросом, по которому он получен. */
internal data class ModeResult(
    val control: ControlQuestion,
    val mode: Mode,
    val answer: Answer?,
    val check: AnswerCheck?,
    val error: String?
) {

    /** Режим отказал: ответа нет, и оценка к нему не относится. */
    val failed: Boolean get() = error != null

    /**
     * Отдаёт режим странице под именем и заголовком столбца.
     *
     * Имя и заголовок приходят снаружи, потому что режимов с базой два — базовый и улучшенный, —
     * а [Mode] различает только «с поиском» и «без поиска»: показывать обоим «с RAG» значило бы
     * не отличать колонки сравнения друг от друга.
     */
    fun toDto(kind: String, title: String): ModeDto {
        val answer = answer
        val check = check
        return ModeDto(
            mode = kind,
            title = title,
            state = if (failed) ModeDto.FAILED else ModeDto.DONE,
            error = error,
            text = answer?.text.orEmpty(),
            score = check?.score ?: 0,
            total = check?.total ?: control.facts.size,
            outcome = if (check == null || mode == Mode.WITHOUT_RAG) "" else Check.outcome(control, check).title,
            facts = control.facts.mapIndexed { index, fact ->
                FactDto(fact.text, check?.facts?.getOrNull(index) ?: false)
            },
            hit = if (control.absent || check == null || mode == Mode.WITHOUT_RAG) {
                null
            } else {
                check.retrievedFacts.any { it }
            },
            sources = answer?.sources.orEmpty().map { source ->
                SourceDto(
                    rank = source.rank,
                    id = source.id,
                    source = source.source,
                    title = source.title,
                    section = source.section,
                    pages = source.pages,
                    similarity = source.similarity,
                    chars = source.text.length,
                    tokens = Tokens.count(source.text),
                    text = source.text
                )
            },
            queryDimension = answer?.retrieval?.queryVector?.size?.takeIf { it > 0 },
            retrievalMillis = answer?.retrieval?.millis ?: 0L,
            messages = answer?.messages.orEmpty().map { MessageDto(it.role, it.content) },
            promptTokens = answer?.promptTokens,
            completionTokens = answer?.completionTokens,
            finishReason = answer?.finishReason,
            elapsedMillis = answer?.elapsedMillis ?: 0L,
            // Ссылки на фрагменты и страницы разбираются той же проверкой, что считает набор:
            // второе правило разбора в UI разошлось бы с эталоном.
            citedFragments = if (answer == null) emptyList() else Check.citedFragments(answer.text, answer.sources),
            citedPages = if (answer == null) emptyList() else Check.citedPages(answer.text)
        )
    }
}

/**
 * Путь запроса по этапам улучшенного конвейера.
 *
 * Отдаётся странице целиком, потому что собрать его заново после прогона нечем: кандидаты, оценки
 * и позиции живут в памяти процесса, а в готовом ответе остались только финальные фрагменты.
 * Поля идут в порядке пути: запрос → выдача → фильтр → второй этап → контекст.
 */
@Serializable
data class TraceDto(
    val original: String,
    val rewritten: String?,
    val retrievalTopK: Int,
    val threshold: Double,
    val accepted: Int,
    val removed: Int,
    val passed: Int,
    /** Порог отсеял всех: контекста нет вовсе, и это отдельное состояние конвейера. */
    val empty: Boolean,
    val retrievalMillis: Long,
    val rewriteMillis: Long,
    val rerankMillis: Long,
    /** Сколько кандидатов оценил второй этап: остальным хватило близости. */
    val rerankScoredByModel: Int,
    /** Что пошло не так на этапах: сбой формата ответа, неизменившееся переписывание. */
    val note: String?,
    val candidates: List<CandidateDto>
)

/**
 * Кандидат и всё, что с ним сделали этапы.
 *
 * [rerankScore] пуст у отсечённого порогом: второй этап его не видел, и ноль читался бы как
 * «оценён низко» — то есть как суждение, которого не было. [rerankRank] и [finalRank] пусты
 * по той же причине: место есть только у того, кого до этого места донесли предыдущие этапы.
 */
@Serializable
data class CandidateDto(
    /** Место в векторной выдаче: с ним кандидат пришёл на фильтр. */
    val rank: Int,
    val id: String,
    /** Подпись фрагмента для человека: файл, страницы, раздел и номер чанка. */
    val label: String,
    val similarity: Double,
    /** Прошёл ли кандидат порог близости. */
    val accepted: Boolean,
    val rerankScore: Double?,
    val rerankRank: Int?,
    val finalRank: Int?
)

/**
 * Где на этапах потерялся правильный фрагмент — то, что задание требует называть точно.
 *
 * Три булева признака и [loss] читаются вместе: признаки говорят, что было на каждом шаге, а [loss]
 * называет первый шаг, после которого фрагмента не стало. Ошибка поиска, слишком высокий порог
 * и проигрыш на втором этапе лечатся по-разному, и по одному «не нашлось» их не различить.
 */
@Serializable
data class StageCheckDto(
    val inRetrieval: Boolean,
    val inFiltered: Boolean,
    val inFinal: Boolean,
    val candidates: Int,
    val accepted: Int,
    val passed: Int,
    val removed: Int,
    val wronglyFiltered: Int,
    /** Второй этап изменил порядок кандидатов: без этого числа «реранкер работает» — не факт. */
    val reordered: Boolean,
    /** Первый этап, после которого правильного фрагмента не стало, словами. */
    val loss: String,
    /** Код той же потери: `NONE`, `RETRIEVAL`, `FILTER` или `RERANK`. */
    val lossCode: String,
    /** Вопрос с ответом в базе: только по таким считается попадание этапов. */
    val scored: Boolean
)

/**
 * Метрики этапов по всему набору: попадания по шагам, цена фильтра и потери по видам.
 *
 * Числа те же, что в отчёте ([StageSummary]), но отдельным типом: [StageSummary] не сериализуем,
 * а страница показывает ровно те же числа, а не свой пересчёт — второй набор формул разошёлся бы
 * с отчётом при первой правке.
 */
@Serializable
data class StagesDto(
    val questions: Int,
    val scored: Int,
    val noBaseScore: Double,
    val baselineScore: Double,
    val improvedScore: Double,
    val baselineFacts: Int,
    val improvedFacts: Int,
    val factsTotal: Int,
    val baselineHits: Int,
    val improvedHits: Int,
    val retrievalHits: Int,
    val filterHits: Int,
    val finalHits: Int,
    val removed: Int,
    val wronglyFiltered: Int,
    val emptyContext: Int,
    val reordered: Int,
    val lostInRetrieval: Int,
    val lostInFilter: Int,
    val lostInRerank: Int,
    val rewriteChanged: Int,
    val better: Int,
    val same: Int,
    val worse: Int
)

/**
 * Настройки этапов последнего прогона.
 *
 * [rewrite] и [rerank] — имена реализаций (например, «модель deepseek-v4-flash»): у этапа может быть
 * несколько вариантов, и отчёт обязан говорить, который работал. Пустое имя означает, что этапа
 * в прогоне нет вовсе.
 */
@Serializable
data class StageConfigDto(
    val baselineTopK: Int,
    val retrievalTopK: Int,
    val finalTopK: Int,
    val threshold: Double,
    val rewrite: String?,
    val rerank: String?
)

/** Преобразования типов `:rag` в то, что уходит в браузер: один вид числа — одно место перевода. */
internal fun Trace.toDto(): TraceDto = TraceDto(
    original = original,
    rewritten = rewritten,
    retrievalTopK = retrievalTopK,
    threshold = threshold,
    accepted = accepted,
    removed = removed,
    passed = passed,
    empty = empty,
    retrievalMillis = retrievalMillis,
    rewriteMillis = rewriteMillis,
    rerankMillis = rerankMillis,
    rerankScoredByModel = rerankScoredByModel,
    note = note,
    candidates = candidates.map { it.toDto() }
)

internal fun Candidate.toDto(): CandidateDto = CandidateDto(
    rank = source.rank,
    id = source.id,
    label = source.label,
    similarity = similarity,
    accepted = accepted,
    rerankScore = rerankScore,
    rerankRank = rerankRank,
    finalRank = finalRank
)

internal fun StageCheck.toDto(): StageCheckDto = StageCheckDto(
    inRetrieval = inRetrieval,
    inFiltered = inFiltered,
    inFinal = inFinal,
    candidates = candidates,
    accepted = accepted,
    passed = passed,
    removed = removed,
    wronglyFiltered = wronglyFiltered,
    reordered = reordered,
    loss = loss.title,
    lossCode = loss.name,
    scored = scored
)

internal fun StageSummary.toDto(): StagesDto = StagesDto(
    questions = questions,
    scored = scored,
    noBaseScore = noBaseScore,
    baselineScore = baselineScore,
    improvedScore = improvedScore,
    baselineFacts = baselineFacts,
    improvedFacts = improvedFacts,
    factsTotal = factsTotal,
    baselineHits = baselineHits,
    improvedHits = improvedHits,
    retrievalHits = retrievalHits,
    filterHits = filterHits,
    finalHits = finalHits,
    removed = removed,
    wronglyFiltered = wronglyFiltered,
    emptyContext = emptyContext,
    reordered = reordered,
    lostInRetrieval = lostInRetrieval,
    lostInFilter = lostInFilter,
    lostInRerank = lostInRerank,
    rewriteChanged = rewriteChanged,
    better = better,
    same = same,
    worse = worse
)

internal fun StageConfig.toDto(): StageConfigDto = StageConfigDto(
    baselineTopK = baselineTopK,
    retrievalTopK = retrievalTopK,
    finalTopK = finalTopK,
    threshold = threshold,
    rewrite = rewrite,
    rerank = rerank
)
