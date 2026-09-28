package com.osvin.aichallenge.mcp

import com.osvin.aichallenge.agent.AgentTool
import com.osvin.aichallenge.agent.ToolOutcome
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject

/**
 * Инструменты MCP-сервера глазами агента: адаптер протокола в нейтральный контракт агента.
 *
 * Здесь встречаются обе стороны, поэтому адаптер и живёт рядом с клиентом протокола: агент
 * про MCP не знает — он получает имя, описание, схему аргументов и способ вызова, — а
 * протокол не знает про агента. Замена транспорта (stdio на HTTP) или подключение другого
 * сервера не трогает агент вовсе.
 *
 * Схема аргументов уезжает как есть: её составил сервер, и переписанная на нашей стороне
 * она разошлась бы с тем, что сервер принимает.
 *
 * Список инструментов спрашивается у сервера при каждом подключении, а не кэшируется:
 * сервер объявляет состав инструментов сам, и копия на нашей стороне обновилась бы
 * не тогда, когда он изменился.
 */
suspend fun McpSession.agentTools(): List<AgentTool> = listTools().map { tool ->
    AgentTool(
        name = tool.name,
        description = tool.description.orEmpty(),
        parameters = tool.inputSchema,
        call = { arguments -> callTool(tool.name, arguments) }
    )
}

/**
 * Один вызов инструмента по протоколу: ответ сервера — текст для модели.
 *
 * Результат передаётся текстом: MCP разрешает в ответе и картинки, и ссылки на ресурсы,
 * но инструменты, которые мы подключаем, отвечают данными словами, и выдумывать для
 * остальных видов содержимого своё представление значило бы придумывать формат, которого
 * от нас никто не просил.
 *
 * Отказ сервера (`isError`) не превращается в исключение: причину видит модель, и это её
 * дело — ответить без данных или позвать инструмент иначе. А ошибка самого соединения
 * (сервер умер, кадр не разобрался) исключением остаётся: результата нет вовсе, и
 * притворяться, что инструмент ответил, значило бы отдать модели выдумку вместо данных.
 */
private suspend fun McpSession.callTool(name: String, arguments: JsonObject): ToolOutcome {
    val result = client.callTool(
        CallToolRequest(params = CallToolRequestParams(name = name, arguments = arguments))
    )
    val text = result.content
        .filterIsInstance<TextContent>()
        .joinToString("\n") { it.text }
        .ifEmpty { "инструмент $name ответил без текста" }
    return ToolOutcome(text = text, isError = result.isError == true)
}
