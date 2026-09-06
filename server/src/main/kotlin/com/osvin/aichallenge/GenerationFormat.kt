package com.osvin.aichallenge

import com.osvin.aichallenge.models.config.AppConfig
import kotlinx.serialization.json.Json
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
 * Инструкция для строгого JSON-формата. Ключи обязаны совпадать с [GenerationFormat.JSON_KEYS].
 */
private const val JSON_INSTRUCTION =
    "Формат ответа: ответь ровно одним JSON-объектом, без текста до или после и " +
        "без Markdown-разметки. Ключи объекта: \"breed\" (порода), " +
        "\"lifespan\" (средняя продолжительность жизни в годах), \"color\" (окраска), " +
        "\"origin\" (страна, в которой появилась порода), \"temperament\" (характер). " +
        "Значения всех полей пиши на русском языке."

/**
 * Поддерживаемые форматы ответа: инструкция для модели и правило сверки.
 *
 * @param key Канонический ключ, который присылает клиент.
 * @param label Человекочитаемое имя для шапки ответа.
 * @param instruction Системная инструкция о формате; null — без дополнительных требований.
 * @param jsonMode Требовать от DeepSeek ответ ровно одним JSON-объектом
 *                 (response_format = json_object).
 * @param validator Проверка соответствия ответа формату; null — формат совпал,
 *                  иначе текст причины.
 */
enum class GenerationFormat(
    val key: String,
    val label: String,
    val instruction: String?,
    val jsonMode: Boolean,
    private val validator: (String) -> String?
) {
    /** Свободная форма: любая структура ответа допустима, сверка всегда успешна. */
    FREE_FORM(
        key = "FREE_FORM",
        label = "Свободная форма",
        instruction = null,
        jsonMode = false,
        validator = { null }
    ),

    /** Ответ по пунктам, каждый пункт — одно предложение. */
    BULLET_SENTENCE(
        key = "BULLET_SENTENCE",
        label = "Нумерованные пункты, каждый пункт — одно предложение",
        instruction = BULLET_INSTRUCTION,
        jsonMode = false,
        validator = ::verifyNumberedList
    ),

    /** Строгий JSON с заданным набором полей о породе. */
    STRICT_JSON(
        key = "STRICT_JSON",
        label = "Чёткий JSON (breed, lifespan, color, origin, temperament)",
        instruction = JSON_INSTRUCTION,
        jsonMode = true,
        validator = ::verifyJson
    );

    /** Проверка ответа: null — формат совпал, иначе причина рассогласования. */
    fun verify(reply: String): String? = validator(reply)

    companion object {
        /** Обязательные ключи JSON-формата (см. [JSON_INSTRUCTION]). */
        val JSON_KEYS = listOf(
            "breed",
            "lifespan",
            "color",
            "origin",
            "temperament"
        )

        /** Режим DeepSeek: строго один JSON-объект в ответе. */
        val STRICT_JSON_MODE: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("json_object")))

        /** Поиск формата по каноническому ключу; неизвестный ключ -> свободная форма. */
        fun fromKey(key: String?): GenerationFormat =
            entries.find { it.key == key } ?: FREE_FORM
    }
}

/** Номер пункта: «1.», «12)» и т.п. в начале строки. */
private val NUMBERED_MARKER = Regex("""^\s*\d{1,2}[.)]\s*""")

/** Граница предложения: точка, восклицательный/вопросительный знак, многоточие. */
private val SENTENCE_END = Regex("""[.!?…]+""")

/**
 * Сверка формата «нумерованные пункты, каждый пункт — одно предложение».
 * Каждый пункт — отдельная строка, начинающаяся с номера («1. …», «2. …»),
 * внутри пункта не больше одного предложения. Требуется минимум два пункта.
 */
internal fun verifyNumberedList(text: String): String? {
    val items = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            if (NUMBERED_MARKER.containsMatchIn(line)) {
                line.replaceFirst(NUMBERED_MARKER, "")
            } else {
                null
            }
        }
        .toList()

    if (items.size < 2) {
        return "Нужен пронумерованный список (минимум два пункта вида «1. …»)"
    }

    items.forEachIndexed { index, item ->
        if (sentenceCount(item) > 1) {
            return "Пункт ${index + 1} содержит больше одного предложения: «${item.take(60)}»"
        }
    }
    return null
}

/**
 * Количество предложений в тексте: число границ предложения
 * (точка, «!», «?», многоточие).
 */
private fun sentenceCount(text: String): Int = SENTENCE_END.findAll(text).count()

/**
 * Сверка JSON-формата: ответ должен быть одним JSON-объектом со всеми
 * обязательными ключами [GenerationFormat.JSON_KEYS], ключи — английские,
 * текстовые значения полей — на русском языке.
 */
internal fun verifyJson(text: String): String? {
    val obj = runCatching {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(stripCodeFence(text))
    }.getOrNull() as? JsonObject
        ?: return "Ответ не является одним JSON-объектом"

    val missing = GenerationFormat.JSON_KEYS.filter { it !in obj }
    if (missing.isNotEmpty()) {
        return "В JSON отсутствуют ключи: ${missing.joinToString(", ")}"
    }

    // Текстовые значения должны быть на русском (числовые, например lifespan, пропускаются)
    val notRussian = GenerationFormat.JSON_KEYS.filter { key ->
        val value = obj[key] as? JsonPrimitive ?: return@filter false
        value.isString && !CYRILLIC.containsMatchIn(value.content)
    }
    if (notRussian.isNotEmpty()) {
        return "Значения полей не на русском языке: ${notRussian.joinToString(", ")}"
    }
    return null
}

/** Кириллические символы (русский алфавит). */
private val CYRILLIC = Regex("""[\u0400-\u04FF]""")

/**
 * Удаление Markdown-ограждения ```json ... ``` вокруг ответа модели.
 */
private fun stripCodeFence(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed

    val firstLine = trimmed.lineSequence().first()
    return trimmed
        .removePrefix(firstLine)
        .removePrefix("\n")
        .removeSuffix("```")
        .trim()
}

/**
 * Округление количества токенов вверх до шага [step].
 * Используется при увеличении лимита на повторной попытке после обрыва ответа
 * (finish_reason = length): 2000 -> 3000 -> 4500 -> 6800 -> 8192.
 */
internal fun roundUpTokens(tokens: Int, step: Int = AppConfig.TOKEN_ROUND_STEP): Int {
    require(tokens > 0) { "tokens должно быть положительным" }
    require(step > 0) { "step должен быть положительным" }
    return ((tokens + step - 1) / step) * step
}
