package com.osvin.aichallenge

import kotlin.test.*

/**
 * Юнит-тесты сверки формата ответа и округления лимита токенов.
 * Чистые функции — без сети и окружения.
 */
class GenerationFormatTest {

    // --- Поиск формата по ключу ---

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

    // --- Свободная форма: сверка всегда успешна ---

    @Test
    fun `свободная форма принимает любой ответ`() {
        assertNull(GenerationFormat.FREE_FORM.verify("Абсолютно любой текст, хоть JSON, хоть список."))
    }

    // --- Формат «по пунктам, каждый пункт — одно предложение» ---

    @Test
    fun `нумерованные пункты принимаются`() {
        val reply = "1. Золотистый ретривер — крупная порода.\n" +
            "2. Живёт 10–12 лет.\n" +
            "3. Окрас золотистый, от светлого до насыщенного.\n" +
            "4. Порода появилась в Великобритании."
        assertNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `нумерация со скобкой принимается`() {
        val reply = "1) Золотистый ретривер — крупная порода.\n" +
            "2) Живёт 10–12 лет."
        assertNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `нумерованный пункт из двух предложений отклоняется`() {
        val reply = "1. Золотистый ретривер — крупная порода.\n" +
            "2. Живёт 10–12 лет. Окрас золотистый."
        assertNotNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `один нумерованный пункт отклоняется`() {
        val reply = "1. Золотистый ретривер — крупная порода."
        assertNotNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `пункты с дефисами без номеров отклоняются`() {
        val reply = "- Золотистый ретривер — крупная порода.\n" +
            "- Живёт 10–12 лет."
        assertNotNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `предложения без номеров по одному на строку отклоняются`() {
        val reply = "Золотистый ретривер — крупная порода.\n" +
            "Живут они около 10–12 лет.\n" +
            "Окрас золотистый."
        assertNotNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    @Test
    fun `сплошной текст без пунктов отклоняется`() {
        val reply = "Золотистый ретривер — это крупная порода собак. " +
            "Живут они около 10–12 лет, окрас золотистый, появилась порода в Великобритании."
        assertNotNull(GenerationFormat.BULLET_SENTENCE.verify(reply))
    }

    // --- Строгий JSON-формат ---

    @Test
    fun `JSON со всеми ключами принимается`() {
        val reply = """{"breed":"Золотистый ретривер","lifespan":"10-12 лет","color":"золотистый","origin":"Великобритания","temperament":"дружелюбный"}"""
        assertNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `JSON в Markdown-ограждении принимается`() {
        val reply = "```json\n" +
            "{\"breed\":\"Такса\",\"lifespan\":\"12-15 лет\"," +
            "\"color\":\"чёрно-подпалый\",\"origin\":\"Германия\"," +
            "\"temperament\":\"смелая, упрямая\"}\n```"
        assertNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `JSON без обязательного ключа отклоняется`() {
        val reply = """{"breed":"Такса","color":"чёрно-подпалый","origin":"Германия","temperament":"смелая"}"""
        assertNotNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `JSON со старыми русскими ключами отклоняется`() {
        val reply = """{"название_породы":"Такса","средняя_продолжительность_жизни":"12-15 лет","окраска":"чёрно-подпалый","страна_появления":"Германия","характер":"смелая"}"""
        assertNotNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `числовой lifespan и русские значения принимаются`() {
        val reply = """{"breed":"Такса","lifespan":14,"color":"чёрно-подпалый","origin":"Германия","temperament":"смелая, упрямая"}"""
        assertNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `значения полей на английском отклоняются`() {
        val reply = """{"breed":"Siberian Husky","lifespan":12,"color":"gray","origin":"Russia","temperament":"friendly"}"""
        assertNotNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `частично английские значения отклоняются`() {
        val reply = """{"breed":"Сибирский хаски","lifespan":12,"color":"серый","origin":"Russia","temperament":"дружелюбный"}"""
        assertNotNull(GenerationFormat.STRICT_JSON.verify(reply))
    }

    @Test
    fun `текст вместо JSON отклоняется`() {
        assertNotNull(GenerationFormat.STRICT_JSON.verify("Такса — охотничья порода из Германии."))
    }

    @Test
    fun `JSON-массив вместо объекта отклоняется`() {
        assertNotNull(GenerationFormat.STRICT_JSON.verify("""[{"название_породы":"Такса"}]"""))
    }

    // --- Округление лимита токенов ---

    @Test
    fun `округление вверх до шага 100`() {
        assertEquals(1000, roundUpTokens(1000))
        assertEquals(1200, roundUpTokens(1101))
        assertEquals(2100, roundUpTokens(2001))
    }

    @Test
    fun `округление после умножения лимита на полтора`() {
        // 2000 * 1.5 = 3000 -> 3000; 3000 * 1.5 = 4500 -> 4500
        assertEquals(3000, roundUpTokens(2000 * 3 / 2))
        assertEquals(4500, roundUpTokens(3000 * 3 / 2))
        // 1000 * 1.5 = 1500 -> 1500; затем 1500 * 1.5 = 2250 -> 2300
        assertEquals(2300, roundUpTokens(1500 * 3 / 2))
    }

    @Test
    fun `округление требует положительные аргументы`() {
        assertFailsWith<IllegalArgumentException> { roundUpTokens(0) }
        assertFailsWith<IllegalArgumentException> { roundUpTokens(100, step = 0) }
    }
}
