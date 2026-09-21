package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Состояние задачи: по новому сообщению модель предлагает, на каком этапе задача и что
 * делается следующим шагом, а код проверяет переход по таблице ([TaskRules]).
 *
 * Задачу заводит человек явным действием ([TaskWriter]), поэтому вызов идёт только тогда,
 * когда состояние уже есть: пока задачи нет, спрашивать модель не о чем, и лишних обращений
 * к API не будет. Прежнее состояние уходит в служебный запрос целиком, поэтому модель видит,
 * где работа остановилась, и не начинает её заново — ни в следующем сообщении, ни после
 * паузы, когда работа продолжается с того же шага.
 *
 * Этапы описаны теми же словами, что в задании дня, а таблица переходов в инструкции
 * собрана из [TaskRules.allowed]: инструкция и проверка не могут разойтись.
 *
 * Ответ — строгий JSON; если он не разобрался, состояние остаётся прежним, и диалог
 * продолжается.
 *
 * @param maxTokens Бюджет ответа на обновление состояния задачи.
 */
class TaskExtractor(private val maxTokens: Int = TASK_MAX_TOKENS) {

    /**
     * Служебный запрос: прежнее состояние задачи и новые сообщения → предложение перехода.
     *
     * @param previous Состояние задачи до этого сообщения: модель меняет его, а не собирает
     *        заново, поэтому и в запросе оно есть целиком.
     * @param messages Последние сообщения диалога и текущий вопрос: по ним видно, что
     *        изменилось в работе.
     */
    fun request(model: String, previous: TaskState, messages: List<ChatMessage>): DeepSeekRequest = DeepSeekRequest(
        model = model,
        messages = listOf(
            ChatMessage(SYSTEM_ROLE, instruction()),
            ChatMessage(USER_ROLE, buildString {
                append("Прежнее состояние задачи:\n")
                append(JSON.encodeToString(previous)).append('\n')
                append("Последние сообщения диалога (в конце — текущий вопрос):\n")
                messages.forEach { append("${it.role}: ${it.content}\n") }
            })
        ),
        maxTokens = maxTokens
    )

    /**
     * Разбор ответа модели: null — предложения нет, и состояние задачи остаётся прежним.
     *
     * Так выглядят оба случая сразу: и неразобранный ответ, и прямой ответ модели «задачи
     * нет» (`{"task":null}`). Различать их нечем — оба означают, что переходить некуда,
     * а причина отказа и так видна в логе.
     */
    fun parse(reply: String): TaskProposal? {
        val json = reply.substringAfter('{', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "{" + it.take(it.lastIndexOf('}') + 1) }
            ?: return null
        val parsed = try {
            JSON.decodeFromString<TaskReply>(json)
        } catch (error: Exception) {
            return null
        }
        val task = parsed.task ?: return null
        return TaskProposal(stage = task.stage, step = task.step, expectedAction = task.expectedAction)
    }

    /** Ответ модели: задача или её отсутствие — задача null, если переходить некуда. */
    @Serializable
    private data class TaskReply(
        @SerialName("task") val task: ProposedTask? = null
    )

    /** Предложение перехода в ответе модели. */
    @Serializable
    private data class ProposedTask(
        @SerialName("stage") val stage: String = "",
        @SerialName("step") val step: String = "",
        @SerialName("expected_action") val expectedAction: String = ""
    )

    companion object {

        /**
         * Бюджет ответа на обновление состояния задачи. Ответ короткий — этап, шаг
         * и ожидаемое действие, — но взят он как у обновления памяти ([MemoryExtractor]):
         * у моделей с рассуждениями они тратят тот же бюджет, и на маленьком бюджете
         * ответ приходит пустым, а состояние молча перестаёт обновляться.
         */
        const val TASK_MAX_TOKENS = 4000

        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Инструкция служебного запроса: этапы, таблица разрешённых переходов, правило
         * про пустые поля, только JSON и ничего выдуманного.
         *
         * Таблица переходов берётся из [TaskRules.allowed] словами: инструкция и проверка
         * тогда не могут разойтись — модель нечего обещать то, чего код не примет.
         */
        private fun instruction(): String = buildString {
            append("Ты ведёшь состояние задачи: этап, текущий шаг и ожидаемое действие. ")
            append("По ним агент продолжает работу с того места, где остановился, а не начинает её заново. ")
            append("Этапы задачи: ")
            append(TaskStage.entries.joinToString("; ") { "${it.wire} — ${it.title}: ${it.hint}" })
            append(". Переходить можно только так: ")
            append(
                TaskRules.allowed.entries.joinToString("; ") { (from, to) ->
                    "${from.title} → ${to.joinToString(", ") { it.title }}"
                }
            )
            append(". Прежнее состояние задачи — то, что уже установлено. ")
            append("Верни состояние после последнего сообщения: этап, текущий шаг и ожидаемое действие. ")
            append("Поле, которое не изменилось, оставь пустым — прежнее значение сохранится. ")
            append(
                "Но если шага или действия ещё нет (задача только заведена в работу), назови их: " +
                    "по этим двум полям работа и продолжается, поэтому пустыми они оставляют её без следующего хода. "
            )
            append("Этап меняй только по таблице переходов: запрещённый переход не примут. ")
            append("Если задачи в диалоге нет и работа по ней не продолжается, ответь без предложения. ")
            append("Ответь только JSON без пояснений: ")
            append("""{"task":{"stage":"execution","step":"одна фраза","expected_action":"одна фраза"}}""")
        }
    }
}
