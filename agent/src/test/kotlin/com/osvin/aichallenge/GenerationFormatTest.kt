package com.osvin.aichallenge

import kotlin.test.*

/**
 * Юнит-тесты выбора формата ответа по ключу.
 * Чистая функция — без сети и окружения.
 */
class GenerationFormatTest {

    @Test
    fun `неизвестный ключ формата отдаёт свободную форму`() {
        assertEquals(GenerationFormat.FREE_FORM, GenerationFormat.fromKey("BOGUS"))
        assertEquals(GenerationFormat.FREE_FORM, GenerationFormat.fromKey(null))
    }

    @Test
    fun `известные ключи форматов находятся`() {
        assertEquals(GenerationFormat.BULLET_SENTENCE, GenerationFormat.fromKey("BULLET_SENTENCE"))
        assertEquals(GenerationFormat.STRICT_JSON, GenerationFormat.fromKey("STRICT_JSON"))
    }
}
