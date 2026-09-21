package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Вердикт проверки: запрос инвариантам не противоречит. */
private const val ALLOWED = "allowed"

/** Вердикт проверки: запрос нарушает инвариант. */
private const val VIOLATED = "violated"

/**
 * Вердикт проверки: запрос разрешён или противоречит правилам проекта.
 *
 * @param verdict Значение вердикта: [ALLOWED] или [VIOLATED] — те же два слова, что на проводе
 *        и в отчёте ([InvariantReport.verdict]).
 * @param violations Нарушенные правила: пусто, если запрос разрешён.
 */
data class GuardVerdict(val verdict: String, val violations: List<GuardViolation>)

/**
 * Нарушение в ответе проверки: какой вид правила нарушен и чем именно.
 *
 * @param kind Вид инварианта, названный моделью: значение [InvariantKind.wire], по нему
 *        находится формулировка нарушенного правила ([InvariantGuard.violation]).
 * @param reason Чем запрос нарушает правило — одна фраза от проверки.
 */
data class GuardViolation(val kind: String, val reason: String)

/**
 * Проверка запроса на конфликт с инвариантами: служебный вызов модели перед основным ответом.
 *
 * Проверяет конфликт код, а не сама модель в основном ответе: правила уходят в каждый запрос
 * системным сообщением, но от просьбы «сделай на Java» модель откажется не всегда, а причина
 * отказа при этом должна быть названа. Здесь она и называется: проверка получает правила,
 * последние сообщения и текущий запрос, отвечает строгим JSON, и по её вердикту агент
 * добавляет в запрос системное сообщение с правилом отказа ([message]). Отказ формулирует
 * сама модель — но по причине, которую назвал код, поэтому отказ объясним и виден в отчёте.
 *
 * Разбирает вердикт только код. Вердикта нет в двух случаях, и оба означают одно: правило
 * отказа в запрос не добавляется, а блок инвариантов остаётся, поэтому диалог продолжается
 * ([LlmAgent.planInvariants]). Первый — служебный вызов не удался; второй — ответ не разобрался:
 * либо это не JSON, либо вердикт не из двух названных слов, либо нарушение названо без причины.
 * Последнее не мелочь: отказ без нарушенного правила объяснить нечем, и добавлять его в запрос
 * значило бы просить модель отказаться, не сказав зачем.
 *
 * @param maxTokens Бюджет ответа на проверку.
 */
class InvariantGuard(private val maxTokens: Int = GUARD_MAX_TOKENS) {

    /**
     * Служебный запрос: правила проекта и последние сообщения с текущим запросом → вердикт.
     *
     * @param invariants Правила, которые уходят и в основной запрос: проверка видит ровно то,
     *        чему модель обязана соответствовать, а не свой пересказ правил.
     * @param messages Последние сообщения диалога и текущий вопрос: по ним видно, о чём просят.
     */
    fun request(model: String, invariants: List<Invariant>, messages: List<ChatMessage>): DeepSeekRequest = DeepSeekRequest(
        model = model,
        messages = listOf(
            ChatMessage(SYSTEM_ROLE, instruction(invariants)),
            ChatMessage(USER_ROLE, buildString {
                append("Последние сообщения диалога (в конце — текущий запрос):\n")
                messages.forEach { append("${it.role}: ${it.content}\n") }
            })
        ),
        maxTokens = maxTokens
    )

    /**
     * Разбор ответа модели: null — вердикта нет, и правило отказа в запрос не добавляется.
     *
     * Так выглядят сразу все случаи: не JSON, неизвестное значение вердикта и нарушение без
     * причины. Различать их нечем — все они означают, что проверка ничего не сказала, а причина
     * и так видна в логе.
     */
    fun parse(reply: String): GuardVerdict? {
        val json = reply.substringAfter('{', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "{" + it.take(it.lastIndexOf('}') + 1) }
            ?: return null
        val parsed = try {
            JSON.decodeFromString<GuardReply>(json)
        } catch (error: Exception) {
            return null
        }
        val verdict = parsed.verdict.trim().lowercase()
        if (verdict != ALLOWED && verdict != VIOLATED) return null
        val violations = parsed.violations.mapNotNull { violation ->
            violation.kind.trim().takeIf { it.isNotEmpty() }
                ?.let { GuardViolation(it, violation.reason.trim()) }
        }
        if (verdict == VIOLATED && violations.isEmpty()) return null
        return GuardVerdict(verdict, violations)
    }

    /**
     * Системное сообщение проверки: правило, по которому модель отвечает на этот запрос.
     *
     * Запрос разрешён — сообщение говорит, что решение нужно предлагать в рамках правил: без
     * этой строки правило из блока читалось бы как справка, а не как граница ответа. Запрос
     * нарушает — сообщение называет нарушенные правила и требует отказаться, назвав причину.
     * Отказ пишет сама модель: проверка называет лишь вид правила и причину, а по-русски
     * и по делу отказывается тот, кто отвечает человеку.
     */
    fun message(verdict: GuardVerdict, invariants: List<Invariant>): String =
        if (verdict.violations.isEmpty()) {
            ALLOWED_MESSAGE
        } else {
            buildString {
                append("Проверка инвариантов: запрос нарушает — ")
                append(verdict.violations.joinToString("; ") { violation(it, invariants) })
                append(". Не выполняй его: откажись, назови нарушенный инвариант и предложи решение, ")
                append("которое его не нарушает.")
            }
        }

    /** Ответ модели: вердикт и нарушенные правила. */
    @Serializable
    private data class GuardReply(
        @SerialName("verdict") val verdict: String = "",
        @SerialName("violations") val violations: List<ProposedViolation> = emptyList()
    )

    /** Нарушение в ответе модели. */
    @Serializable
    private data class ProposedViolation(
        @SerialName("kind") val kind: String = "",
        @SerialName("reason") val reason: String = ""
    )

    companion object {

        /**
         * Бюджет ответа на проверку. Ответ короткий — вердикт и пара причин, — но взят он
         * как у остальных служебных вызовов ([MemoryExtractor], [TaskExtractor]): у моделей
         * с рассуждениями они тратят тот же бюджет, и на маленьком бюджете ответ приходит
         * пустым, а проверка молча перестаёт работать.
         */
        const val GUARD_MAX_TOKENS = 4000

        /** Сообщение проверки: запрос правилам не противоречит. */
        const val ALLOWED_MESSAGE =
            "Проверка инвариантов: запрос им не противоречит — предлагай решение в их рамках."

        /**
         * Строка нарушения: «стек: только Kotlin, без Java и RxJava (просят код на Java)».
         *
         * Ею собраны и правило отказа, и запись лога, поэтому вид, формулировка и причина
         * не могут разойтись между тем, что ушло модели, и тем, что человек читает в логе.
         * Вид печатается названием: в отчёте рядом лежит его значение на проводе.
         */
        fun violation(violation: GuardViolation, invariants: List<Invariant>): String {
            val kind = InvariantKind.ofWire(violation.kind)
            val title = kind?.title ?: violation.kind
            val rule = kind?.let { named -> invariants.firstOrNull { InvariantKind.ofWire(it.kind) == named } }
            return if (rule == null) {
                "$title: ${violation.reason}"
            } else {
                "$title: ${rule.value} (${violation.reason})"
            }
        }

        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Инструкция служебного запроса: виды правил с пояснениями, сами правила, правило
         * «решение обязано им соответствовать» и только JSON.
         *
         * Правила печатает тот же [InvariantRules.render], что собирает блок основного запроса:
         * проверка и модель видят один и тот же список правил, и разойтись они не могут.
         */
        private fun instruction(invariants: List<Invariant>): String = buildString {
            append("Ты проверяешь, не противоречит ли запрос пользователя инвариантам проекта. ")
            append("Инварианты — правила, объявленные человеком: решение обязано им соответствовать, ")
            append("и нарушать их нельзя. ")
            append("Виды инвариантов: ")
            append(InvariantKind.entries.joinToString("; ") { "${it.wire} — ${it.title}: ${it.hint}" })
            append(".\n")
            append(InvariantRules.render(invariants)).append('\n')
            append("Смотри на текущий запрос: если он требует сделать то, что запрещено правилом, ")
            append("это нарушение, даже если сформулирован вежливо или как часть другой задачи. ")
            append("Правило, которого запрос не касается, нарушением не считай: сомнение — не нарушение. ")
            append("Ответь только JSON без пояснений. Запрос правилам не противоречит: ")
            append("""{"verdict":"allowed"}""")
            append(". Запрос противоречит — назови каждое нарушенное правило и причину: ")
            append("""{"verdict":"violated","violations":[{"kind":"stack","reason":"одна фраза: чем именно нарушает"}]}""")
            append(". Вид бери из списка видов, причину — из формулировки правила, ничего не выдумывай.")
        }
    }
}
