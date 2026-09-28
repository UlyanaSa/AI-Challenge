package com.osvin.aichallenge.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * Число с провода как текст: курсы и их изменения читаются из JSON строкой,
 * а не числом.
 *
 * Сервер присылает эти значения числами JSON (`95.8709`), и разобрать их в `Double`
 * клиент не может: `Double` — двоичная дробь, поэтому десятичные деньги он вернул бы
 * другим числом (`95.8709` превратилось бы в `95.87090000000001`), а показать в ленте
 * нужно ровно те цифры, что послал сервер. `BigDecimal` здесь тоже нет: он живёт
 * в JVM, а общий код приложения собирается ещё и под iOS, Web и wasm. Поэтому модель
 * объявляет такие поля строкой, а этот сериализатор берёт у числа его исходный текст
 * ([JsonPrimitive.content]) — без арифметики, то есть без единого потерянного разряда.
 *
 * Значение приходит строкой и остаётся строкой: в ленте его достаточно переставить
 * в русский вид (запятая вместо точки), а в арифметике эти числа не участвуют —
 * сложение делает сервер и присылает посчитанным.
 *
 * Обратная запись тоже возможна и идёт без кавычек ([JsonUnquotedLiteral]): так число
 * вернулось бы на провод числом, а не строкой. Пишет эти модели только тест, как и
 * остальные модели ответов: на сервер они не уезжают.
 */
object DecimalText : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.osvin.aichallenge.data.DecimalText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("DecimalText читается только из JSON")
        val element = input.decodeJsonElement()
        // JsonNull тоже JsonPrimitive, но значения у него нет: «null» вместо числа
        // просочилось бы в ленту строкой, поэтому это отдельный отказ, а не текст
        if (element !is JsonPrimitive || element is JsonNull) {
            throw SerializationException("DecimalText: ожидалось число, пришло $element")
        }
        return element.content
    }

    override fun serialize(encoder: Encoder, value: String) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("DecimalText пишется только в JSON")
        output.encodeJsonElement(JsonUnquotedLiteral(value))
    }
}
