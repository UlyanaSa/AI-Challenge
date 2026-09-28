package com.osvin.aichallenge.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Объявление инструмента в запросе к API: из него модель узнаёт, что ей доступно.
 *
 * Устроено так, как этого требует API: `type` всегда `function`, а внутри — имя, назначение
 * и схема аргументов. Схема приходит от владельца инструмента
 * ([com.osvin.aichallenge.agent.AgentTool]) и уезжает как есть: агент не придумывает
 * аргументы инструмента, а передаёт объявленное.
 *
 * `type` помечен [EncodeDefault]: значение совпадает с умолчанием, но поле обязательное,
 * и пропуск его в теле запроса — это отказ провайдера, а не экономия байтов.
 */
@Serializable
data class ToolDeclaration(
    @EncodeDefault val type: String = "function",
    val function: ToolFunction
)

/** Описание функции: имя для вызова, назначение словами и схема аргументов. */
@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject = JsonObject(emptyMap())
)

/**
 * Вызов инструмента, который просит модель: что вызвать и с чем.
 *
 * `arguments` — строка JSON, а не объект: так их отдаёт API. Разбирает их агент
 * ([com.osvin.aichallenge.agent.LlmAgent]), и строкой же вызов повторяется в следующем
 * запросе — в переписке он должен быть ровно таким, каким пришёл.
 *
 * @param id Идентификатор вызова: по нему ответ инструмента находит свой вызов.
 * @param type Тип вызова; у функций всегда `function`. Поле помечено [EncodeDefault]:
 *        провайдер ждёт его в переписке всегда, умолчание тут — не повод пропустить поле.
 * @param function Имя инструмента и аргументы.
 */
@Serializable
data class ToolCall(
    val id: String,
    @EncodeDefault val type: String = "function",
    val function: ToolCallFunction
)

/** Имя вызываемого инструмента и его аргументы строкой JSON. */
@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String
)
