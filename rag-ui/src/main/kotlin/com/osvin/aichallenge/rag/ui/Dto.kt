package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.rag.Answer
import com.osvin.aichallenge.rag.AnswerCheck
import com.osvin.aichallenge.rag.Candidate
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ConfidenceCheck
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.GroundedAnswer
import com.osvin.aichallenge.rag.GroundedCheck
import com.osvin.aichallenge.rag.GroundingSummary
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.QuoteCheck
import com.osvin.aichallenge.rag.SourceRef
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
    /**
     * Порог достаточности grounded-режима дня 24: то же число, что у фильтра (§8).
     *
     * Отдельным полем, хотя значение совпадает с [threshold]: задание дня 24 требует называть это
     * условие своим именем, и два разных порога — «прошло фильтр» и «хватает на ответ» — разошлись бы
     * при первой правке, а объяснить отказ разными порогами было бы нечем. Считать его на странице
     * значило бы завести второе место, где условие прогона превращается в число.
     */
    val groundingThreshold: Double,
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
    /**
     * Метрики дня 24 (§17): подтверждение ответов источниками и цитатами, охваты и отказы.
     *
     * Отдельным набором от [stages], а не полями в нём: у дня 24 другие знаменатели и другие
     * вопросы — «сколько ответов подтверждено цитатами» не выводится из попаданий этапов.
     */
    val grounding: GroundingDto?,
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
    /**
     * Grounded-режим дня 24 на той же выдаче, что [improved]: ответ, источники, цитаты и их проверка.
     *
     * `null` означает «этап не выполнялся», а не «ничего не нашлось»: у отказа или сбоя этапа
     * на этом месте стоит заполненный [GroundedDto] со своим состоянием и причиной.
     */
    val grounded: GroundedDto?,
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

/**
 * Источник grounded-ответа дня 24: запись, собранная системой из метаданных чанка.
 *
 * Здесь нет ни одного поля из текста модели, и это не совпадение: задание дня требует, чтобы
 * источник формировался из найденного чанка (§4). Модель называет только номер фрагмента, и по нему
 * система достаёт готовую запись — придумать страницу, раздел или `chunk_id` она не может, потому
 * что этих полей в её ответе просто нет.
 */
@Serializable
data class GroundedSourceDto(
    /** Номер фрагмента в контексте: им цитата связывается с источником. */
    val fragment: Int,
    /** Место в финальном Top-K: с ним источник пришёл в контекст. */
    val rank: Int,
    val source: String,
    val section: String?,
    /** Идентификатор чанка в индексе — то, что задание называет `chunk_id`. */
    val chunkId: String,
    val chunkIndex: Int,
    val pages: List<Int>,
    val similarity: Double,
    /** Оценка второго этапа; `null` — этапа не было или кандидат оценён не был. */
    val rerankScore: Double?
)

/**
 * Цитата ответа вместе с результатом программной проверки.
 *
 * Цитата и утверждение идут парой, а не по отдельности: день 24 держится на том, что подтверждение
 * проверяемо, а оторванная от утверждения цитата не подтверждает ничего. Здесь же и отброшенные
 * цитаты — одним списком со всеми проверенными: без них выдуманная моделью ссылка исчезла бы
 * из отчёта вместе с уликой, а задание просит её ловить и показывать (§11).
 */
@Serializable
data class QuoteDto(
    /** Номер фрагмента, на который сослалась модель. */
    val fragment: Int,
    val claim: String,
    val quote: String,
    /** Чанк, с которым сверяли цитату; `null` — ссылка вне контекста, сверять не с чем. */
    val chunkId: String?,
    /** Нашлась ли цитата в тексте чанка: `false` — пересказ, выданный за цитату. */
    val found: Boolean,
    /** Почему цитата не подтвердилась: причину читает и отчёт, и страница. */
    val reason: String?
)

/**
 * Решение о достаточности контекста вместе с числами, по которым оно принято.
 *
 * [decision] и [code] — одно решение в двух видах: словом для читателя и кодом для разметки
 * (`SUFFICIENT`, `NO_CHUNKS`, `ALL_FILTERED`, `BELOW_THRESHOLD`). Так же устроен код потери этапов
 * дня 23, и по той же причине: странице нужны и фраза, и признак для класса; собирать фразу
 * из кода значило бы завести словарь на второй стороне.
 *
 * Пустые [decision] и [code] означают, что решения не было: этап дня 24 отказал раньше проверки,
 * и это видно по [GroundedDto.state]. Ноль в [considered] там — тоже не измерение, а его отсутствие.
 *
 * [bestSimilarity] берётся у лучшего фрагмента **контекста**, а не выдачи: уверенность считается
 * по тому, что увидит модель, — фрагмент, отсечённый порогом, до неё не дошёл.
 */
@Serializable
data class ConfidenceDto(
    val decision: String,
    val code: String,
    /** Сколько фрагментов дошло до контекста. */
    val considered: Int,
    /** Близость лучшего фрагмента контекста; `null` — сравнивать было не с чем. */
    val bestSimilarity: Double?,
    val threshold: Double,
    val sufficient: Boolean
)

/**
 * Grounded-ответ одного вопроса: то, что задание дня 24 требует показать пользователю.
 *
 * Исходов три, и [state] с [error] отличают их друг от друга. Обычный ответ и отказ — оба исходы
 * работы: у них заполнены [confidence], [sources] и [quotes], а у отказа ещё и [refusal] с текстом
 * из [GroundedAnswer.REFUSAL_TEXT]. Сбой **этапа** (поиск не выполнился, сверять не с чем) — это
 * уже не решение системы, и у него `state = failed` с причиной в [error]: заполненная достаточность
 * на его месте выдавала бы сбой за работу проверки.
 *
 * Проверочные признаки ([correct], [grounded], [validAbstention] и остальные) приходят посчитанными
 * в `:rag` теми же правилами, что и отчёт. Страница их только называет словами: второй набор формул
 * в браузере разошёлся бы с отчётом, и сверять прогон со страницей стало бы нечем — тот же довод,
 * что у [StagesDto].
 */
@Serializable
data class GroundedDto(
    val id: String,
    val question: String,
    /** Ответа в базе нет: правильным исходом считается отказ (§18). */
    val absent: Boolean,
    /** `done` или `failed`: у отказавшего этапа проверочные поля пусты, а не нулевые. */
    val state: String,
    val error: String?,
    /** Текст ответа модели; `null` у отказа и у сбоя этапа. */
    val answer: String?,
    /** Текст отказа, который видит пользователь (§8): у отказа заполнен, у ответа пуст. */
    val refusal: String?,
    /** Код отказа (`NO_CONTEXT` или `NO_EVIDENCE`): по нему страница выбирает разметку. */
    val refusalCode: String?,
    /** Тот же отказ словами — то, что читает человек. */
    val refusalTitle: String?,
    /** Замечание этапа: причина отказа, отброшенные цитаты, решение достаточности. */
    val note: String?,
    val confidence: ConfidenceDto,
    val sources: List<GroundedSourceDto>,
    /** Все проверенные цитаты, включая отброшенные: проверка — часть ответа, а не украшение. */
    val quotes: List<QuoteDto>,
    /** Ответ предыдущего режима верен по мерке дня 22: без него «grounded верен» ничего не значит. */
    val previousCorrect: Boolean,
    val correct: Boolean,
    val abstained: Boolean,
    val abstainNeeded: Boolean,
    val validAbstention: Boolean,
    val wrongAbstention: Boolean,
    val hasSource: Boolean,
    val hasQuote: Boolean,
    /** Ни одной отброшенной цитаты: ответ подтверждён полностью, а не наполовину. */
    val supported: Boolean,
    /** Названные ответом факты подтверждены цитатами, а не просто присутствуют в тексте. */
    val factsCovered: Boolean,
    val grounded: Boolean,
    /** Ссылки на фрагменты, которых модель не получала: выдуманные источники (§4). */
    val fabricatedSources: Int,
    /** Цитаты, которых нет в тексте процитированного чанка (§11). */
    val invalidQuotes: Int,
    /** Сырой ответ модели: по нему видно, отказалась модель сама или система отбросила её цитаты. */
    val raw: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val elapsedMillis: Long
) {

    companion object {

        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/**
 * Метрики дня 24 по всему прогону (§17): те же числа, что в отчёте [GroundingSummary].
 *
 * Считаются целые числа, а не доли: доля — это деление, и её видно по числителю со знаменателем.
 * Знаменатели ([answered], [abstainNeeded], [factsTotal]) едут вместе с числителями намеренно:
 * «охват источников 0,8» без знаменателя пришлось бы восстанавливать обратным счётом, и на странице
 * появилось бы второе место, где из чисел выводится число.
 */
@Serializable
data class GroundingDto(
    val questions: Int,
    val previousCorrect: Int,
    val correct: Int,
    /** Верные ответы и уместные отказы — числитель Answer Accuracy (§18). */
    val rightAnswers: Int,
    /** Обычные ответы (не отказы) — знаменатель охватов источника и цитаты. */
    val answered: Int,
    val withSource: Int,
    val withQuote: Int,
    val grounded: Int,
    val abstained: Int,
    /** Вопросы, у которых верного ответа в контексте не было: знаменатель правильных отказов. */
    val abstainNeeded: Int,
    val validAbstentions: Int,
    val wrongAbstentions: Int,
    val fabricatedSources: Int,
    val invalidQuotes: Int,
    val factsPrevious: Int,
    val factsGrounded: Int,
    val factsTotal: Int
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

/**
 * Источник уходит странице без [SourceRef.title]: подпись к фрагменту она собирает из источника,
 * раздела и `chunk_id`, а название файла базы здесь одно на весь прогон и лежит в шапке страницы.
 */
internal fun SourceRef.toDto(): GroundedSourceDto = GroundedSourceDto(
    fragment = fragment,
    rank = rank,
    source = source,
    section = section,
    chunkId = chunkId,
    chunkIndex = chunkIndex,
    pages = pages,
    similarity = similarity,
    rerankScore = rerankScore
)

/**
 * Проверка разворачивается вместе со своим утверждением: цитата, утверждение и номер фрагмента —
 * одно неделимое, и держать их двумя списками значило бы позволить им разойтись при первой правке.
 */
internal fun QuoteCheck.toDto(): QuoteDto = QuoteDto(
    fragment = claim.fragment,
    claim = claim.text,
    quote = claim.quote,
    chunkId = chunkId,
    found = found,
    reason = reason
)

/**
 * Решение о достаточности переводится в два вида: словом ([Confidence.title]) и кодом
 * ([Confidence.name]). Странице нужны оба — по коду выбирается разметка, по слову читается решение;
 * собирать слово из кода значило бы завести второй словарь решений на стороне браузера.
 */
internal fun ConfidenceCheck.toDto(): ConfidenceDto = ConfidenceDto(
    decision = decision.title,
    code = decision.name,
    considered = considered,
    bestSimilarity = bestSimilarity,
    threshold = threshold,
    sufficient = sufficient
)

/**
 * Grounded-ответ в том виде, в каком его показывает страница.
 *
 * Сверка приходит сюда готовой ([GroundedCheck]): признаки «верен», «подтверждён цитатами» и «отказ
 * уместен» считает `:rag`, а страница их только называет словами. Тот же довод, что у метрик этапов
 * дня 23: второй набор правил на стороне браузера разошёлся бы с отчётом при первой правке, и
 * сверять прогон стало бы нечем.
 *
 * Текст отказа подставляется из [GroundedAnswer.REFUSAL_TEXT], а не из ответа модели: у отказа
 * ответа нет по построению (§10) — он был бы ответом по памяти, — а показать пользователю нужно
 * именно ту формулировку, которую задание называет правильным поведением. Код и заголовок отказа
 * идут рядом: код выбирает разметку, заголовок объясняет исход словами.
 *
 * Цитатами уходят **все** проверенные ([GroundedAnswer.checks]), а не только подтверждённые: список
 * проверок — это и есть результат дня 24, и отброшенная цитата с причиной говорит о качестве ответа
 * больше, чем её отсутствие. Подтверждённые страница отделяет по [QuoteDto.found] — это выбор вида,
 * а не пересчёт.
 */
internal fun GroundedAnswer.toDto(check: GroundedCheck, id: String): GroundedDto = GroundedDto(
    id = id,
    question = question,
    absent = check.control.absent,
    state = GroundedDto.DONE,
    error = null,
    answer = answer,
    refusal = if (refused) GroundedAnswer.REFUSAL_TEXT else null,
    refusalCode = refusal?.name,
    refusalTitle = refusal?.title,
    note = note,
    confidence = confidence.toDto(),
    sources = sources.map { it.toDto() },
    quotes = checks.map { it.toDto() },
    previousCorrect = check.previousCorrect,
    correct = check.correct,
    abstained = check.abstained,
    abstainNeeded = check.abstainNeeded,
    validAbstention = check.validAbstention,
    wrongAbstention = check.wrongAbstention,
    hasSource = check.hasSource,
    hasQuote = check.hasQuote,
    supported = check.supported,
    factsCovered = check.factsCovered,
    grounded = check.grounded,
    fabricatedSources = check.fabricatedSources,
    invalidQuotes = check.invalidQuotes,
    raw = raw,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    elapsedMillis = millis
)

/**
 * Сбой grounded-этапа: ответа и сверки нет, а причина уходит странице строкой.
 *
 * Отдельным построением, а не веткой [toDto]: у сбоя нет ни ответа, ни решения о достаточности,
 * ни проверки цитат, и собирать его из пустых значений готового ответа значило бы притвориться,
 * что этап доработал до проверок. Признаки отказа здесь `false` не потому, что система решила
 * «не подтверждено», а потому что решения не было вовсе — и отличает одно от другого
 * [GroundedDto.state]: страница показывает по нему сбой, а не нулевые метрики.
 *
 * [threshold] сохраняется: это условие прогона, и оно известно даже тогда, когда проверка не дошла
 * до конца. Пустые код и слово в достаточности означают «решения не было» — того же порядка вещь,
 * что пустой `hit` у режима без базы.
 */
internal fun failedGrounded(
    id: String,
    control: ControlQuestion,
    threshold: Double,
    error: String
): GroundedDto = GroundedDto(
    id = id,
    question = control.question,
    absent = control.absent,
    state = GroundedDto.FAILED,
    error = error,
    answer = null,
    refusal = null,
    refusalCode = null,
    refusalTitle = null,
    note = null,
    confidence = ConfidenceDto(
        decision = "",
        code = "",
        considered = 0,
        bestSimilarity = null,
        threshold = threshold,
        sufficient = false
    ),
    sources = emptyList(),
    quotes = emptyList(),
    previousCorrect = false,
    correct = false,
    abstained = false,
    abstainNeeded = false,
    validAbstention = false,
    wrongAbstention = false,
    hasSource = false,
    hasQuote = false,
    supported = false,
    factsCovered = false,
    grounded = false,
    fabricatedSources = 0,
    invalidQuotes = 0,
    raw = null,
    promptTokens = null,
    completionTokens = null,
    elapsedMillis = 0L
)

/**
 * Метрики дня 24 переводятся поле в поле, без пересчёта долей: страница показывает те же числа,
 * что и отчёт, — второй набор формул разошёлся бы с первым, и сверять прогон стало бы нечем.
 */
internal fun GroundingSummary.toDto(): GroundingDto = GroundingDto(
    questions = questions,
    previousCorrect = previousCorrect,
    correct = correct,
    rightAnswers = rightAnswers,
    answered = answered,
    withSource = withSource,
    withQuote = withQuote,
    grounded = grounded,
    abstained = abstained,
    abstainNeeded = abstainNeeded,
    validAbstentions = validAbstentions,
    wrongAbstentions = wrongAbstentions,
    fabricatedSources = fabricatedSources,
    invalidQuotes = invalidQuotes,
    factsPrevious = factsPrevious,
    factsGrounded = factsGrounded,
    factsTotal = factsTotal
)
