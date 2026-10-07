package com.osvin.aichallenge.rag.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Терпимое чтение ответов модели: объект вырезается из текста, поле ищется по имени.
 *
 * Модель отвечает JSON-объектом, но просьба — не гарантия: живой прогон дня 23 показал ответы
 * «не тем форматом, который просили». Здесь повторяется приём служебных вызовов `:agent`
 * (`MemoryExtractor.parse`): от ответа берётся всё от первой `{` до последней `}`, а непонятное
 * поле считается отсутствующим. Разбор не бросает исключений по той же причине, что и там:
 * сломанный формат одного служебного ответа — это «данных нет», а не падение диалога.
 *
 * Типы ответов разбирает вызывающий: у памяти задачи свой [MemoryKeeper], у ответа по книге —
 * проверенный разбор дня 24 ([com.osvin.aichallenge.rag.Citations]), и подменять его здесь нельзя:
 * утверждения с цитатами разбираются ровно один раз и в одном месте.
 */
internal object ChatJson {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Тело объекта из ответа модели; `null`, если объекта нет.
     *
     * Если `{` есть, а закрывающей скобки нет, берётся остаток строки: обрезанный ответ модели —
     * тоже ответ, и часть полей в нём может быть цела. Именно поэтому разбор терпимый: строгий
     * разбор объявил бы такой ответ пустым целиком.
     */
    fun body(raw: String?): String? {
        val text = raw ?: return null
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        return if (end > start) text.substring(start, end + 1) else text.substring(start)
    }

    /** Разобранный объект верхнего уровня; `null` — ответ не разобрался. */
    fun objectOf(raw: String?): JsonObject? {
        val body = body(raw) ?: return null
        return try {
            JSON.decodeFromString<JsonObject>(body)
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Строковое поле верхнего уровня; `null`, если поля нет или оно не строка.
     *
     * Не строка — не ошибка: модель может назвать поле числом или объектом, и решать, что это
     * значит, должен вызывающий, а не разбор. Отсутствие значения и непонятное значение в этом
     * коде означают одно и то же — «модель этого не сказала».
     */
    fun field(raw: String?, name: String): String? =
        (objectOf(raw)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
