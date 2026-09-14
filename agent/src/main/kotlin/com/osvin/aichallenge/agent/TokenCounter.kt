package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import kotlin.math.ceil

/**
 * Счётчик токенов текста.
 *
 * API отдаёт только общее число токенов запроса (`prompt_tokens`) и ответа
 * (`completion_tokens`) — без разбивки на system prompt, историю и текущий запрос.
 * Поэтому разбивку и проверку до отправки считает локальный счётчик, а точные
 * итоговые числа агент берёт из `usage` ответа API.
 */
interface TokenCounter {
    /** Токены текста без служебной обвязки сообщения. */
    fun count(text: String): Int

    /** Токены всего запроса: обвязка чата + обвязка каждого сообщения + их текст. */
    fun countPrompt(messages: List<ChatMessage>): Int
}

/**
 * Оценка токенов по составу текста: кириллица в BPE дороже латиницы.
 *
 * Коэффициенты подобраны по замерам живого API DeepSeek (день 8): латиница —
 * 4.0 символа на токен, кириллица — 2.5. На естественных текстах оценка
 * расходится с фактом на единицы процентов (замеры в README, день 8),
 * поэтому для итоговых чисел берётся `usage`, а оценка используется для
 * разбивки по частям и проверки контекста до отправки запроса.
 */
object EstimatingTokenCounter : TokenCounter {
    /** Обвязка чата: шаблон запроса и роль ассистента. */
    const val TEMPLATE_TOKENS = 27

    /** Обвязка одного сообщения: роль, разделители. */
    const val MESSAGE_TOKENS = 3

    /** Символов на токен для кириллицы. */
    const val CYRILLIC_CHARS_PER_TOKEN = 2.5

    /** Символов на токен для латиницы, цифр и знаков. */
    const val LATIN_CHARS_PER_TOKEN = 4.0

    override fun count(text: String): Int {
        var cyrillic = 0
        var rest = 0
        for (symbol in text) {
            if (symbol in '\u0400'..'\u04FF') cyrillic++ else rest++
        }
        return ceil(cyrillic / CYRILLIC_CHARS_PER_TOKEN + rest / LATIN_CHARS_PER_TOKEN).toInt()
    }

    override fun countPrompt(messages: List<ChatMessage>): Int =
        TEMPLATE_TOKENS + messages.size * MESSAGE_TOKENS + messages.sumOf { count(it.content) }
}
