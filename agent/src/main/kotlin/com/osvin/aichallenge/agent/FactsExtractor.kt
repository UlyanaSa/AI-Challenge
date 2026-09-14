package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Память фактов: агент просит модель обновить список «ключ — значение» после
 * каждого сообщения пользователя. Прежние факты уходят в служебный запрос входом,
 * поэтому память накапливается, а не строится заново.
 *
 * Ответ модели — JSON; если он не разобрался, память остаётся прежней
 * (агент не теряет то, что уже знает).
 *
 * @param maxTokens Бюджет ответа на обновление памяти.
 */
class FactsExtractor(private val maxTokens: Int = FACTS_MAX_TOKENS) {

    /** Служебный запрос: прежние факты и новые сообщения → обновлённый список фактов. */
    fun request(model: String, previous: Facts, messages: List<ChatMessage>): DeepSeekRequest =
        DeepSeekRequest(
            model = model,
            messages = listOf(
                ChatMessage(SYSTEM_ROLE, INSTRUCTION),
                ChatMessage(USER_ROLE, buildString {
                    if (previous.isEmpty) {
                        append("Фактов пока нет.\n\n")
                    } else {
                        append("Прежние факты:\n")
                        previous.items.forEach { append("- ${it.key}: ${it.value}\n") }
                        append('\n')
                    }
                    append("Новые сообщения диалога:\n")
                    messages.forEach { append("${it.role}: ${it.content}\n") }
                })
            ),
            maxTokens = maxTokens
        )

    /** Блок фактов для запроса: системное сообщение перед историей диалога. */
    fun factsMessage(facts: Facts): ChatMessage? =
        facts.block()?.let { ChatMessage(SYSTEM_ROLE, "Память диалога (факты):\n$it") }

    /** Разбор ответа модели: null — ответ не разобрался и память надо оставить прежней. */
    fun parse(reply: String): Facts? {
        val json = reply.substringAfter('{', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "{" + it.take(it.lastIndexOf('}') + 1) }
            ?: return null
        val parsed = try {
            JSON.decodeFromString<FactsReply>(json)
        } catch (error: Exception) {
            return null
        }
        return Facts.of(parsed.facts)
    }

    @Serializable
    private data class FactsReply(val facts: List<Fact> = emptyList())

    companion object {
        /**
         * Бюджет ответа на обновление памяти. У модели с рассуждениями они тратят тот же
         * бюджет, что и текст, поэтому он взят с запасом: маленький бюджет даёт пустой
         * ответ, и память молча перестаёт обновляться.
         */
        const val FACTS_MAX_TOKENS = 4000

        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Инструкция служебного запроса: только важное, только JSON, ничего не выдумывать. */
        private const val INSTRUCTION =
            "Ты ведёшь память диалога — короткий список фактов «ключ — значение». " +
                "Запоминай только важное: цель, ограничения, предпочтения, решения, договорённости, " +
                "названные числа и сроки. Ключ — короткое повторяемое слово (цель, ограничение, " +
                "предпочтение, решение, договорённость, бюджет, срок). Значение — одна фраза. " +
                "Обнови изменившееся, добавь новое, убери отменённое, повторы объедини. " +
                "Ничего не выдумывай: если в сообщениях нового важного нет, верни прежние факты. " +
                "Ответь только JSON без пояснений: {\"facts\":[{\"key\":\"цель\",\"value\":\"...\"}]}"
    }
}
