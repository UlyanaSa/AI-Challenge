package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.EstimatingTokenCounter
import com.osvin.aichallenge.agent.TokenCounter
import com.osvin.aichallenge.models.ChatMessage

/**
 * Что стало с историей при сборке запроса.
 *
 * @param FITS История влезла в окно целиком.
 * @param TRIMMED Часть старых сообщений отброшена, чтобы влезла.
 * @param OVERFLOW Не влезает даже без истории: системная инструкция и текущий вопрос
 *        вместе занимают больше окна, чем под них есть. Это отказ, а не усечение:
 *        молча выкинуть текущий вопрос или инструкцию сервис не может.
 */
enum class ContextOutcome { FITS, TRIMMED, OVERFLOW }

/**
 * Собранный запрос к модели и отчёт о том, как он собран.
 *
 * @param outcome Что сделали с историей.
 * @param messages Сообщения для транспорта: системная инструкция, оставленная история
 *        в прежнем порядке, текущий вопрос.
 * @param dropped Сколько сообщений истории не ушло в запрос.
 * @param estimatedTokens Оценка размера запроса локальным счётчиком.
 * @param budgetTokens Сколько токенов доступно под запрос: окно минус резерв под ответ.
 */
data class ContextPlan(
    val outcome: ContextOutcome,
    val messages: List<ChatMessage>,
    val dropped: Int,
    val estimatedTokens: Int,
    val budgetTokens: Int
)

/**
 * Сборка контекста запроса из истории сессии.
 *
 * Отдельный тип, а не код в маршруте: правило усечения — единственное место, где история
 * теряется, и потеря обязана быть видимой. Здесь считается, что именно уйдёт модели,
 * сколько сообщений не уйдёт и влезает ли это в окно вообще; маршрут только отвечает
 * отказом, если не влезает.
 *
 * Усечение — удаление самых старых сообщений (первая из стратегий задания). Сжатие истории
 * в проекте есть ([com.osvin.aichallenge.agent.HistoryCompressor]), но оно само стоит вызова
 * модели: на вопросе, который и так отвечается за секунды, платить за сводку временем и
 * токенами дороже, чем отбросить два старых сообщения. Сжатие остаётся у агента, где
 * диалог длинный, а у сервиса потолок окна — жёсткое условие.
 *
 * Системная инструкция сервиса и системные сообщения самой истории не отбрасываются никогда:
 * это условие ответа, а не часть диалога, и ответ без него — ответ на другой вопрос. Текущее
 * сообщение человека — тоже: потерять его значит ответить не на то, что спросили, и клиент
 * об этом не узнает.
 */
object ContextBudget {

    /** Роль системного сообщения: по ней история делится на отбрасываемую и обязательную. */
    const val SYSTEM_ROLE = "system"

    /** Роль человека: текущий вопрос помечается ею, а не приходит от клиента. */
    const val USER_ROLE = "user"

    /**
     * Собирает запрос к модели и отчёт об усечении.
     *
     * @param history История сессии в порядке записи; может быть пустой.
     * @param current Текущее сообщение человека.
     * @param system Обязательная системная инструкция сервиса.
     * @param maxContextTokens Потолок окна контекста: клиентское значение больше него
     *        не принимается, поэтому сюда приходит уже обрезанное сервером.
     * @param maxGenerationTokens Бюджет ответа: он резервируется заранее, иначе модель
     *        упрётся в окно на середине ответа, а не остановится сама.
     * @param counter Счётчик токенов: в проде — оценка по составу текста, в тестах —
     *        своя, чтобы проверка не зависела от коэффициентов [EstimatingTokenCounter].
     */
    fun plan(
        history: List<StoredMessage>,
        current: String,
        system: String,
        maxContextTokens: Int,
        maxGenerationTokens: Int,
        counter: TokenCounter = EstimatingTokenCounter
    ): ContextPlan {
        val budget = maxContextTokens - maxGenerationTokens
        val systemMessage = ChatMessage(role = SYSTEM_ROLE, content = system)
        val currentMessage = ChatMessage(role = USER_ROLE, content = current)

        // Обязательная часть: инструкция, системные сообщения истории и текущий вопрос.
        val required = buildList {
            add(systemMessage)
            history.forEach { if (it.role == SYSTEM_ROLE) add(ChatMessage(it.role, it.content)) }
            add(currentMessage)
        }
        val requiredTokens = counter.countPrompt(required)

        // Резерва нет вовсе или обязательная часть больше окна: усекать нечего, отказ.
        // Проверка стоит до усечения, потому что отбросить историю здесь всё равно
        // не поможет — вопрос и инструкция не влезают сами по себе.
        if (budget <= 0 || requiredTokens > budget) {
            return ContextPlan(
                outcome = ContextOutcome.OVERFLOW,
                messages = required,
                dropped = history.count { it.role != SYSTEM_ROLE },
                estimatedTokens = requiredTokens,
                budgetTokens = budget
            )
        }

        // Отбрасываемые сообщения — всё, кроме системных; идём от самых старых к новым,
        // то есть убираем с начала списка, а не с конца: свежая история и есть контекст.
        val droppable = history.indices.filter { history[it].role != SYSTEM_ROLE }
        var keep = droppable.size
        var messages = collect(history, systemMessage, currentMessage, droppable.takeLast(keep).toSet())

        // Пересчёт на каждом шаге, а не вычитание токенов отброшенного сообщения: вычитание
        // знало бы, как счётчик считает обвязку сообщения, то есть повторяло бы его
        // устройство. История сессии — десятки сообщений, и полный пересчёт дешевле
        // одной ошибки в арифметике.
        while (keep > 0 && counter.countPrompt(messages) > budget) {
            keep--
            messages = collect(history, systemMessage, currentMessage, droppable.takeLast(keep).toSet())
        }

        val estimated = counter.countPrompt(messages)
        return ContextPlan(
            outcome = if (keep < droppable.size) ContextOutcome.TRIMMED else ContextOutcome.FITS,
            messages = messages,
            dropped = droppable.size - keep,
            estimatedTokens = estimated,
            budgetTokens = budget
        )
    }

    /** Собирает сообщения запроса: инструкция, оставленная история в прежнем порядке, вопрос. */
    private fun collect(
        history: List<StoredMessage>,
        system: ChatMessage,
        current: ChatMessage,
        keptDroppable: Set<Int>
    ): List<ChatMessage> = buildList {
        add(system)
        history.forEachIndexed { index, message ->
            if (message.role == SYSTEM_ROLE || index in keptDroppable) {
                add(ChatMessage(role = message.role, content = message.content))
            }
        }
        add(current)
    }
}
