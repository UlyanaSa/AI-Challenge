package com.osvin.aichallenge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Инструкция для формата «нумерованные пункты, каждый пункт — одно предложение».
 */
private const val BULLET_INSTRUCTION =
    "Формат ответа: опиши породу собаки пронумерованным списком; каждый пункт — " +
        "одно предложение на отдельной строке и начинается с номера (1., 2., 3., …). " +
        "Не используй JSON."

/**
 * Инструкция для строгого JSON-формата. Ключи заданы явно в тексте инструкции.
 */
private const val JSON_INSTRUCTION =
    "Формат ответа: ответь ровно одним JSON-объектом, без текста до или после и " +
        "без Markdown-разметки. Ключи объекта: \"breed\" (порода), " +
        "\"lifespan\" (средняя продолжительность жизни в годах), \"color\" (окраска), " +
        "\"origin\" (страна, в которой появилась порода), \"temperament\" (характер). " +
        "Значения всех полей пиши на русском языке."

/**
 * Поддерживаемые форматы ответа: как формат влияет на запрос к модели.
 *
 * @param key Канонический ключ, который присылает клиент.
 * @param instruction Системная инструкция о формате; null — без дополнительных требований.
 * @param jsonMode Требовать от DeepSeek ответ ровно одним JSON-объектом
 *                 (response_format = json_object).
 */
enum class GenerationFormat(
    val key: String,
    val instruction: String?,
    val jsonMode: Boolean
) {
    /** Свободная форма: любая структура ответа допустима. */
    FREE_FORM(
        key = "FREE_FORM",
        instruction = null,
        jsonMode = false
    ),

    /** Ответ по пунктам, каждый пункт — одно предложение. */
    BULLET_SENTENCE(
        key = "BULLET_SENTENCE",
        instruction = BULLET_INSTRUCTION,
        jsonMode = false
    ),

    /** Строгий JSON с заданным набором полей о породе. */
    STRICT_JSON(
        key = "STRICT_JSON",
        instruction = JSON_INSTRUCTION,
        jsonMode = true
    );

    companion object {
        /** Режим DeepSeek: строго один JSON-объект в ответе. */
        val STRICT_JSON_MODE: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("json_object")))

        /** Поиск формата по каноническому ключу; неизвестный ключ -> свободная форма. */
        fun fromKey(key: String?): GenerationFormat =
            entries.find { it.key == key } ?: FREE_FORM
    }
}
