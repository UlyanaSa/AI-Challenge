package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Память агента: после сообщения пользователя модель обновляет записи тех типов,
 * которые ведёт выбранная стратегия ([ContextStrategy.memory]).
 *
 * Тип каждой записи называет модель, а код проверяет, что тип известен и что стратегия
 * его ведёт ([MemoryLayer], [MemoryRules.normalize]). В инструкции типы описаны теми же
 * словами, что в задании дня: рабочая память — данные текущей задачи, долговременная —
 * профиль, решения и знания. Краткосрочную память модель не трогает: это сообщения диалога.
 *
 * Прежние записи уходят в служебный запрос входом, поэтому память накапливается,
 * а не строится заново.
 *
 * Ответ — JSON; если он не разобрался, память остаётся прежней и диалог продолжается.
 *
 * @param maxTokens Бюджет ответа на обновление памяти.
 */
class MemoryExtractor(private val maxTokens: Int = MEMORY_MAX_TOKENS) {

    /** Разобранный ответ: принятые записи и сколько отклонено (неизвестный тип или пустой текст). */
    data class Parsed(val records: List<MemoryRecord>, val rejected: Int)

    /**
     * Служебный запрос: прежние записи слоёв и новые сообщения → обновлённые записи.
     *
     * @param layers Слои, которые ведёт стратегия: про остальные модель не спрашивают,
     *        поэтому и записей для них не будет.
     */
    fun request(
        model: String,
        layers: Set<MemoryLayer>,
        working: List<MemoryRecord>,
        longTerm: List<MemoryRecord>,
        messages: List<ChatMessage>
    ): DeepSeekRequest = DeepSeekRequest(
        model = model,
        messages = listOf(
            ChatMessage(SYSTEM_ROLE, instruction(layers)),
            ChatMessage(USER_ROLE, buildString {
                if (MemoryLayer.LONG_TERM in layers) {
                    append(previousBlock(MemoryLayer.LONG_TERM, longTerm, "Долговременная память пока пуста"))
                }
                if (MemoryLayer.WORKING in layers) {
                    append(previousBlock(MemoryLayer.WORKING, working, "Рабочая память пока пуста"))
                }
                append("Новые сообщения диалога:\n")
                messages.forEach { append("${it.role}: ${it.content}\n") }
            })
        ),
        maxTokens = maxTokens
    )

    /** Блок слоя для запроса к модели: системное сообщение перед историей; пустой слой — null. */
    fun message(layer: MemoryLayer, records: List<MemoryRecord>): ChatMessage? =
        records.takeIf { it.isNotEmpty() }?.let {
            ChatMessage(SYSTEM_ROLE, "${title(layer)}:\n" + it.joinToString("\n") { record -> line(record) })
        }

    /** Разбор ответа модели: null — ответ не разобрался, и память надо оставить прежней. */
    fun parse(reply: String): Parsed? {
        val json = reply.substringAfter('{', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "{" + it.take(it.lastIndexOf('}') + 1) }
            ?: return null
        val parsed = try {
            JSON.decodeFromString<MemoryReply>(json)
        } catch (error: Exception) {
            return null
        }
        val normalized = parsed.memory.mapNotNull { MemoryRules.normalize(it) }
        return Parsed(normalized, parsed.memory.size - normalized.size)
    }

    @Serializable
    private data class MemoryReply(val memory: List<MemoryRecord> = emptyList())

    companion object {

        /**
         * Бюджет ответа на обновление памяти. У модели с рассуждениями они тратят тот же
         * бюджет, что и текст, поэтому он взят с запасом: маленький бюджет даёт пустой
         * ответ, и память молча перестаёт обновляться.
         */
        const val MEMORY_MAX_TOKENS = 4000

        /** Название типа в промпте: по нему блоки памяти видно в логе и в тестах. */
        fun title(layer: MemoryLayer): String = when (layer) {
            MemoryLayer.WORKING -> "Рабочая память задачи (данные текущей задачи)"
            MemoryLayer.LONG_TERM -> "Долговременная память (профиль, решения, знания)"
            MemoryLayer.SHORT_TERM -> "Краткосрочная память (текущий диалог)"
        }

        /** Строка записи: текст как есть — так запись видно и в промпте, и в логе. */
        fun line(record: MemoryRecord): String = "- ${record.value}"

        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Прежние записи слоя в служебном запросе: пустой слой так и говорится. */
        private fun previousBlock(layer: MemoryLayer, records: List<MemoryRecord>, empty: String): String =
            buildString {
                append("Прежняя память — ${title(layer).substringBefore(" (")}:\n")
                if (records.isEmpty()) {
                    append("$empty\n")
                } else {
                    records.forEach { append(line(it)).append('\n') }
                }
                append('\n')
            }

        /**
         * Инструкция служебного запроса: какие типы памяти в игре, что в них попадает,
         * только JSON и ничего выдуманного.
         */
        private fun instruction(layers: Set<MemoryLayer>): String {
            // Типы, в которые вообще можно писать: краткосрочная память — это диалог.
            val writable = layers.filter { it.writable }
            val example = (writable.firstOrNull() ?: MemoryLayer.WORKING).wire
            return buildString {
                append("Ты ведёшь память агента: короткие записи о том, что важно помнить дальше. ")
                append("Типы памяти и что в них попадает: ")
                if (MemoryLayer.WORKING in layers) {
                    append("${MemoryLayer.WORKING.caption} — цель, ограничения, числа, сроки, ")
                    append("договорённости этой задачи; ")
                }
                if (MemoryLayer.LONG_TERM in layers) {
                    append("${MemoryLayer.LONG_TERM.caption} — кто пользователь: имя, роль, язык, стиль, стек; ")
                    append("принятые решения и устойчивые знания о проекте; ")
                }
                append("${MemoryLayer.SHORT_TERM.caption} не трогай — это сообщения диалога. ")
                append("Запись — одна фраза без разметки; тип называй сам: ")
                append(writable.joinToString(", ") { it.wire })
                append(". Обнови изменившееся, добавь новое, убери отменённое, повторы объедини. ")
                append("Ничего не выдумывай: если в сообщениях нового важного нет, верни пустой список. ")
                append("Ответь только JSON без пояснений: ")
                append("""{"memory":[{"layer":"$example","value":"одна фраза"}]}""")
            }
        }
    }
}
