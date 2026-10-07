package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.rag.GroundedAnswer
import java.time.Instant
import com.osvin.aichallenge.rag.Claim
import com.osvin.aichallenge.rag.ConfidenceCheck
import com.osvin.aichallenge.rag.QuoteCheck
import com.osvin.aichallenge.rag.Refusal
import com.osvin.aichallenge.rag.Retrieval
import com.osvin.aichallenge.rag.SourceRef

/**
 * Откуда взят ответ: из найденных фрагментов книги или из памяти задачи.
 *
 * Различие введено потому, что не всякий вопрос в чате — вопрос о книге. «А что мы вообще разбираем
 * и какие правила ты держишь?» адресован разговору, а не роману: ответ на него лежит в памяти задачи,
 * и требовать под него цитату из главы бессмысленно — подходящего фрагмента может не быть вовсе,
 * и честный отказ «в источниках этого нет» был бы ответом не на заданный вопрос. При этом граница
 * жёсткая: `DIALOGUE` отвечает только про сам разговор (цель, правила, термины, что уже выяснили),
 * и любой вопрос о романе идёт прежним путём `BASE` — с цитатами и проверкой.
 */
enum class ChatAnswerKind(val title: String) {
    /** Ответ по найденным фрагментам: утверждения и цитаты, источники — из метаданных чанков. */
    BASE("по базе"),

    /** Ответ о самом разговоре: опирается на память задачи, цитат из книги не несёт. */
    DIALOGUE("по памяти задачи")
}

/**
 * Один ход диалога целиком: вопрос, что искали, что нашлось, что ответила модель и что осталось
 * в памяти задачи.
 *
 * Ход хранит не только ответ, но и весь путь к нему — по той же причине, что и день 24: разбирать
 * прогон нужно, не повторяя его. Здесь к этому добавляется память: [memoryBefore] и [memory] стоят
 * рядом, и видно, что именно реплика человека изменила в памяти задачи (или не изменила ничего),
 * а [query] рядом с [question] показывает, что ушло в поиск, — при раскрытии местоимения по истории
 * это разные строки, и без второй «нашлось не то» неотличимо от «спросили не то».
 *
 * Пути запросов к модели ([answerMessages], [memoryMessages], [rewriteMessages]) хранятся раздельно:
 * их три, они решают разные задачи, и в логе каждый нужен на своём месте — иначе непонятно, чей
 * именно промпт привёл к странному ответу.
 */
data class ChatTurn(
    /** Номер хода в диалоге, с единицы: по нему считается окно истории и нумеруется отчёт. */
    val index: Int,
    /** Вопрос человека так, как он его задал. */
    val question: String,
    /** Запрос, которым искали: обычно равен вопросу, у неполного вопроса — раскрытый по истории. */
    val query: String,
    /** Что сделал переписыватель: `null`, если запрос не переписывали или переписывать было нечего. */
    val queryNote: String?,
    /** Вся выдача поиска с этапами: по ней видно и достаточность, и откуда взялись источники. */
    val found: Retrieval,
    val confidence: ConfidenceCheck,
    /** Откуда ответ; `null` у отказа — отказ не «из базы» и не «из памяти», а отсутствие ответа. */
    val kind: ChatAnswerKind?,
    val answer: String?,
    /** Источники подтверждённых утверждений — из метаданных чанков, как в дне 24. */
    val sources: List<SourceRef>,
    val claims: List<Claim>,
    val checks: List<QuoteCheck>,
    val refusal: Refusal?,
    /** Причина отказа словами: она же уходит человеку рядом с пустым блоком источников. */
    val refusalReason: String?,
    /**
     * Сбой обращения к модели, если он был; `null` — вызов дошёл до ответа.
     *
     * Отдельно от отказа, потому что это разные вещи: отказ — решение системы («в источниках этого
     * нет»), а ошибка — недошедший запрос (лимит, сеть, ошибка API). Обе строки честные, но лечатся
     * по-разному: отказ означает, что вопрос надо задать иначе, ошибка — что ход надо повторить.
     * Смешать их значило бы записать сбой сети в качество поиска.
     */
    val error: String?,
    val memoryBefore: TaskMemory,
    val memory: TaskMemory,
    /** Что сделала память на этом ходу: обновлена, не изменилась или не разобрана (причина). */
    val memoryNote: String?,
    val answerMessages: List<ChatMessage>,
    val memoryMessages: List<ChatMessage>,
    val rewriteMessages: List<ChatMessage>,
    /** Сырой ответ модели по существу вопроса. */
    val raw: String?,
    /** Сырой ответ модели на извлечение памяти — отдельно: формат памяти ломается независимо. */
    val memoryRaw: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    /** Время ответа на вопрос. */
    val millis: Long,
    /** Время извлечения памяти; у него своя цена, и в отчёте она видна отдельно. */
    val memoryMillis: Long,
    val rewriteMillis: Long,
    /**
     * Когда человек задал вопрос — время обеих реплик хода ([messages]).
     *
     * Ноль означает «время не проставлено»: так строятся ходы в проверках, где часов нет. Живой ход
     * всегда со временем ([ChatSession.ask]), и это не украшение отчёта: по нему видно, когда
     * разговор начался, а по паузам между ходами — что сценарий прогонялся человеком со страницы,
     * а не одной пачкой.
     */
    val at: Instant = Instant.EPOCH
) {

    /** Ответ опирается на память задачи, а не на фрагменты книги. */
    val fromMemory: Boolean get() = kind == ChatAnswerKind.DIALOGUE

    /** Состоялся ли ответ: отказ ответом не считается, у него своя строка в отчёте. */
    val answered: Boolean get() = answer != null

    /** Есть ли у хода источник: фрагменты базы или память задачи. Отказ источника не имеет. */
    val hasSource: Boolean get() = sources.isNotEmpty() || fromMemory

    /** Обновилась ли память задачи на этом ходу. */
    val memoryChanged: Boolean get() = memory != memoryBefore

    /** Полное время хода: ответ, извлечение памяти и переписывание запроса. */
    val totalMillis: Long get() = millis + memoryMillis + rewriteMillis

    /**
     * Что услышал человек в ответ на вопрос: ответ, причина отказа или причина сбоя.
     *
     * Это не то же, что [answer]: ответа у отказа нет по построению, но человеку что-то сказали —
     * и в истории разговора, которая уходит в промпт следующего хода, должно стоять именно сказанное.
     * Иначе модель в окне истории видела бы нейтральную заглушку вместо причины, которую уже знает
     * человек, и следующий вопрос она толковала бы, не зная, что предыдущий закончился отказом.
     */
    val reply: String get() = answer ?: refusalReason ?: error ?: GroundedAnswer.REFUSAL_TEXT

    /**
     * Реплики хода — история разговора в форме задания: роль, текст, время.
     *
     * Их две, и обе с одним временем: вопрос человека и ответ ассистента. Собирается из хода,
     * а не хранится рядом с ним, по той же причине, что и сводка ([ChatReport]): второй список
     * тех же реплик рано или поздно разошёлся бы с первым.
     */
    fun messages(): List<DialogueMessage> = listOf(
        DialogueMessage(DialogueRole.USER, question, at),
        DialogueMessage(DialogueRole.ASSISTANT, reply, at)
    )

    /**
     * Блок источников хода одной строкой — то, что печатается и в отчёте, и на странице.
     *
     * Отказ здесь не пустой, а объяснённый: «источников нет» без причины неотличимо от забытого
     * блока, а требование дня — источники **всегда**, и у отказа источником служит сама причина.
     * Ответ по памяти задачи тоже называет свой источник — память, — иначе он выглядел бы ответом
     * без подтверждения.
     */
    val sourcesLine: String
        get() = when {
            sources.isNotEmpty() -> sources.joinToString("; ") { source ->
                "[${source.fragment}] ${source.source}" +
                    (source.section?.let { ", $it" } ?: "") +
                    (if (source.pages.isEmpty()) "" else ", стр. ${source.pages.joinToString("–")}") +
                    ", чанк ${source.chunkIndex}, близость ${"%.2f".format(source.similarity)}"
            }
            fromMemory -> "память задачи — " + MEMORY_SOURCE_LINES.filter { memoryLine(it) }
                .joinToString(", ")
            error != null -> "нет — $error"
            else -> "нет — ${refusalReason ?: "причина не записана"}"
        }

    private fun memoryLine(label: String): Boolean = when (label) {
        TaskMemory.GOAL_LABEL -> !memory.goal.isNullOrBlank()
        TaskMemory.CLARIFIED_LABEL -> memory.clarifications.isNotEmpty()
        TaskMemory.CONSTRAINTS_LABEL -> memory.constraints.isNotEmpty()
        else -> memory.terms.isNotEmpty()
    }

    private companion object {
        /** Что именно в памяти послужило источником: перечисляются только заполненные разделы. */
        val MEMORY_SOURCE_LINES = listOf(
            TaskMemory.GOAL_LABEL,
            TaskMemory.CLARIFIED_LABEL,
            TaskMemory.CONSTRAINTS_LABEL,
            TaskMemory.TERMS_LABEL
        )
    }
}
