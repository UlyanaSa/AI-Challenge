package com.osvin.aichallenge.tuning

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.rag.Check
import com.osvin.aichallenge.rag.ControlQuestion
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Один вопрос одного профиля: ответ модели, его оценка и его цена.
 *
 * Ответ, оценка и расход лежат вместе, потому что по отдельности не значат ничего: оценка без
 * времени и токенов не отличает «модель знает» от «модель думала минуту», а время без оценки
 * не отличает быстрый ответ от быстрого отказа. Замеры времени и токенов — измеренные, а не
 * пересчитанные: их дают часы прогона и `usage` ответа демона.
 *
 * @param id Номер вопроса набора (`q01`…`q10`).
 * @param question Текст вопроса: повторяется в отчёте, чтобы строку можно было прочитать,
 *        не заглядывая в набор.
 * @param answer Ответ модели как есть; пустой, если ответа не было.
 * @param score Оценка по шкале задания из [Check.evaluate]: 0 — ответа нет или он неверен,
 *        1 — часть фактов, 2 — все факты. Считается той же функцией, что у дня 22, и второй
 *        формулы в проекте нет.
 * @param matched Засчитанных фактов ответа.
 * @param total Фактов в ожидании вопроса.
 * @param millis Время одного обращения к модели: от отправки запроса до ответа или отказа.
 * @param promptTokens Токены запроса из `usage` ответа; нули, если ответа не было.
 * @param completionTokens Токены ответа из `usage` ответа; нули, если ответа не было.
 * @param failed Причина отказа словами; `null` — модель ответила. Отказом считается и отказ
 *        транспорта, и пустой ответ: в обоих случаях ответа нет, и отчитываться о нём нечем.
 */
data class TuningAnswer(
    val id: String,
    val question: String,
    val answer: String,
    val score: Int,
    val matched: Int,
    val total: Int,
    val millis: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val failed: String?
)

/**
 * Итог одного профиля: ответы на все вопросы и сводка по ним.
 *
 * Все числа сводки считаются из ответов ([TuningAnswer]), а не хранятся рядом с ними: одна
 * копия числа не может разойтись с ответами, из которых отчёт его печатает. Средняя оценка
 * считается по всем вопросам набора, включая отказы, — отказ это 0, а не пропуск: если бы
 * отказ выпадал из среднего, профиль, отказавший на девяти вопросах из десяти и ответивший
 * на один, показал бы по этому одному «отлично».
 *
 * @param tuning Профиль, которым получены ответы: без него числа не объяснить.
 * @param answers Ответы по вопросам в порядке набора.
 * @param millis Время всего прогона профиля: снимки ресурсов и обработка входят в него, поэтому
 *        оно больше суммы времён ответов и не подменяет её.
 */
data class TuningResult(val tuning: Tuning, val answers: List<TuningAnswer>, val millis: Long) {

    /** Средняя оценка по набору; отказ считается нулём, и в наборе он потому и остаётся. */
    val averageScore: Double
        get() = if (answers.isEmpty()) 0.0 else answers.sumOf { it.score }.toDouble() / answers.size

    /** Ответов, засчитанных полностью: все факты ожидания на месте. */
    val full: Int get() = answers.count { it.score == FULL_SCORE }

    /** Ответов, засчитанных частично: часть фактов, но не все. */
    val partial: Int get() = answers.count { it.score == PARTIAL_SCORE }

    /**
     * Ответов с нулём баллов: неверных и отказов вместе.
     *
     * Отказы считаются здесь же, а не отдельной строкой мимо суммы: `full + partial + wrong`
     * обязано равняться числу вопросов, иначе в отчёте появились бы вопросы, которых нет ни
     * в одной колонке. Сколько из них — отказы, видно по [failed].
     */
    val wrong: Int get() = answers.count { it.score == 0 }

    /** Ответов, которых нет вовсе: транспорт отказал или модель вернула пустое. */
    val failed: Int get() = answers.count { it.failed != null }

    /** Засчитанных фактов по всем ответам. */
    val matchedFacts: Int get() = answers.sumOf { it.matched }

    /** Ожидавшихся фактов по всем вопросам набора. */
    val totalFacts: Int get() = answers.sumOf { it.total }

    /** Токены запросов: у отказавших вопросов их нет, и они считаются нулём. */
    val promptTokens: Int get() = answers.sumOf { it.promptTokens }

    /** Токены ответов: то же. */
    val completionTokens: Int get() = answers.sumOf { it.completionTokens }

    /**
     * Среднее время одного вопроса.
     *
     * Считается по временам ответов, а не по времени прогона: в прогон входит ещё и снимок
     * ресурсов демона, и «среднее время вопроса», включающее замер памяти, говорило бы о чужом.
     */
    val averageMillis: Long
        get() = if (answers.isEmpty()) 0L else answers.sumOf { it.millis } / answers.size

    /**
     * Скорость генерации: токены ответов, делённые на время генерации.
     *
     * Время — то же, по которому считается [averageMillis], и оно меньше времени прогона:
     * снимки ресурсов и отказавшие запросы скорости модели не измеряют. Ноль в знаменателе —
     * не ошибка, а вырожденный прогон (все вопросы мгновенно отказали): скорость тогда 0.0,
     * а не деление на ноль.
     */
    val tokensPerSecond: Double
        get() {
            val millis = answers.sumOf { it.millis }
            return if (millis == 0L) 0.0 else completionTokens * MILLIS_PER_SECOND / millis
        }

    private companion object {
        const val FULL_SCORE = 2
        const val PARTIAL_SCORE = 1
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Прогон одного профиля по набору вопросов.
 *
 * Клиент приходит в конструктор, а не собирается внутри: в прогоне это транспорт к демону,
 * в тестах — подставной, и от того, чем отвечает модель, зависит всё сравнение. Обращение
 * к модели — ровно одно на вопрос: повторные попытки исказили бы и время, и расход, а отказ
 * обязан быть виден числом, а не скрыт второй попыткой.
 *
 * Запрос собирается здесь, а не вызовом агента (`LlmAgent`), по двум причинам. Первая: агент
 * отвечает за диалог с историей, памятью и стратегиями контекста, а прогону нужна одна реплика
 * на один вопрос — иначе вопросы влияли бы друг на друга через историю, и «профиль» мерил бы
 * ещё и то, что накопилось в переписке. Вторая: вид запроса базового профиля обязан повторить
 * поведение чата дня 27 на первом ходу, и это поведение задано правилом агента «системным
 * промптом становится первое сообщение диалога, а на первом ходу — текущий запрос» —
 * правило повторено строкой ниже, а не пересказано по памяти (см. [messagesOf]).
 *
 * @param client Транспорт к модели: единственное, чем прогон отличается в тестах от живого запуска.
 */
class TuningRunner(private val client: LlmClient) {

    /**
     * Прогоняет профиль по вопросам и возвращает ответы с итогом.
     *
     * Порядок вопросов сохраняется: обращения к модели идут по очереди, и разный порядок для
     * разных профилей сделал бы числа несравнимыми. Отказ на одном вопросе прогон не роняет
     * ([ask]): остальные вопросы обязаны быть заданы — иначе «профиль ответил хуже» нельзя было бы
     * отличить от «профиль сломался на середине».
     *
     * @param onAnswer Что печатать по ходу: сколько вопросов пройдено и какой именно. Прогон идёт
     *        секундами на вопрос, и без этой печати консоль молчит минуты, а человек не знает,
     *        работает прогон или завис.
     */
    suspend fun run(
        tuning: Tuning,
        questions: List<ControlQuestion>,
        onAnswer: (done: Int, total: Int, id: String) -> Unit = { _, _, _ -> }
    ): TuningResult {
        val started = TimeSource.Monotonic.markNow()
        val answers = questions.mapIndexed { index, question ->
            val answer = ask(tuning, question)
            onAnswer(index + 1, questions.size, question.id)
            answer
        }
        return TuningResult(tuning, answers, started.elapsedNow().inWholeMilliseconds)
    }

    /** Один вопрос: запрос, ответ и его сверка; отказ транспорта превращается в причину, а не в исключение. */
    private suspend fun ask(tuning: Tuning, question: ControlQuestion): TuningAnswer {
        val started = TimeSource.Monotonic.markNow()
        return try {
            val response = client.complete(request(tuning, question))
            val answer = response.choices.firstOrNull()?.message?.content.orEmpty()
            val check = Check.evaluate(question, answer, emptyList())
            TuningAnswer(
                id = question.id,
                question = question.question,
                answer = answer,
                score = check.score,
                matched = check.matched,
                total = check.total,
                millis = started.elapsedNow().inWholeMilliseconds,
                promptTokens = response.usage?.promptTokens ?: 0,
                completionTokens = response.usage?.completionTokens ?: 0,
                // Пустой ответ — тоже отсутствие ответа: демон может ответить успешно и не сказать
                // ничего (бюджет ушёл в рассуждение), и в отчёте это обязано быть видно как отказ,
                // а не как ответ с нулём баллов, который читался бы как «модель не знает».
                failed = if (answer.isBlank()) EMPTY_REPLY else null
            )
        } catch (cancelled: CancellationException) {
            // Отмена прогона — не отказ модели: пойманная здесь, она остановила бы и отмену.
            throw cancelled
        } catch (error: Throwable) {
            TuningAnswer(
                id = question.id,
                question = question.question,
                answer = "",
                score = 0,
                matched = 0,
                // Факты ожидания остаются в знаменателе: вопрос был задан и не получил ответа,
                // и отказ обязан снижать среднюю оценку профиля, а не исчезать из неё.
                total = question.facts.size,
                millis = started.elapsedNow().inWholeMilliseconds,
                promptTokens = 0,
                completionTokens = 0,
                failed = reason(error)
            )
        }
    }

    /**
     * Запрос профиля на один вопрос.
     *
     * Температура здесь не задаётся (`null`) — её назначает транспорт из конфигурации профиля.
     * Это не пропуск: у локального провайдера температура транспорта перекрывает температуру
     * запроса, и запрос со своим значением обещал бы настройку, которую транспорт молча заменит.
     * Имя модели в запросе — из конфигурации профиля: локальный транспорт берёт модель у себя,
     * но запрос обязан называть ту же модель, иначе разбор запроса в отчёте назвал бы чужую.
     */
    private fun request(tuning: Tuning, question: ControlQuestion): DeepSeekRequest = DeepSeekRequest(
        model = tuning.config.ollamaModel,
        messages = messagesOf(tuning, question),
        maxTokens = tuning.maxTokens,
        temperature = null
    )

    /**
     * Сообщения запроса: правило чата дня 27 на первом ходу.
     *
     * Правило агента короткое — «свой system prompt важнее; если его нет, роль системного играет
     * первое сообщение диалога, а на первом ходу — текущий запрос», — и прогон повторяет его
     * дословно: профиль без инструкции отправляет вопрос и системным сообщением, и репликой
     * пользователя, ровно как чат дня 27. Профиль с инструкцией кладёт её системным сообщением,
     * и вопрос уходит только репликой. Сообщений ровно два и на первом ходу: истории у вопроса
     * кейса нет — вопросы сравниваются поодиночке, а не в диалоге.
     */
    private fun messagesOf(tuning: Tuning, question: ControlQuestion): List<ChatMessage> {
        val system = tuning.systemPrompt?.takeIf { it.isNotBlank() } ?: question.question
        return listOf(
            ChatMessage(SYSTEM_ROLE, system),
            ChatMessage(USER_ROLE, question.question)
        )
    }

    /** Причина отказа словами: сообщение исключения, а если его нет — имя типа. */
    private fun reason(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: UNKNOWN_REASON

    private companion object {
        /** Роли сообщений запроса: те же строки, которыми их называет агент. */
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"

        /** Причина отказа, когда модель ответила успешно и пусто: бюджет ушёл в рассуждение. */
        const val EMPTY_REPLY = "модель вернула пустой ответ"

        /** Причина отказа, когда у исключения нет ни сообщения, ни имени типа. */
        const val UNKNOWN_REASON = "отказ без объяснения"
    }
}
