package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.rag.Answer
import com.osvin.aichallenge.rag.AnswerCheck
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import com.osvin.aichallenge.rag.Mode
import com.osvin.aichallenge.rag.Summary
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
    val topK: Int,
    val maxTopK: Int,
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
    val summary: Summary?,
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
 * Результаты одного вопроса в обоих режимах.
 *
 * Пустой [without] или [with] означает, что режим ещё не выполнялся: страница показывает такие
 * вопросы как «не прогонялся», а не как нулевую оценку.
 */
@Serializable
data class ResultDto(
    val id: String,
    val question: String,
    val absent: Boolean,
    val without: ModeDto?,
    val with: ModeDto?
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

    fun toDto(): ModeDto {
        val answer = answer
        val check = check
        return ModeDto(
            mode = if (mode == Mode.WITH_RAG) "with" else "without",
            title = mode.title,
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
