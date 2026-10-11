package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.ToolCall
import com.osvin.aichallenge.models.ToolCallFunction
import com.osvin.aichallenge.models.ToolDeclaration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Провод локального демона Ollama: запрос к `POST /api/chat` и его ответ.
 *
 * Формат Ollama не совпадает с форматом облачного API, поэтому у локального
 * провайдера своя пара DTO, а не подмена полей облачных. Отличий три, и все они
 * значимы: сообщения называются так же (`role`/`content`), но вызов инструмента
 * несёт аргументы объектом, а не строкой JSON; ответ не разложен по `choices`,
 * а лежит одним `message` с причиной остановки `done_reason`; расход токенов
 * приходит двумя числами (`prompt_eval_count`, `eval_count`) вместо блока `usage`.
 * Отсюда и два преобразования — [DeepSeekRequest.toOllama] и
 * [OllamaChatResponse.toDeepSeek] — на границе провайдера: внутри приложения
 * по-прежнему ходят [DeepSeekRequest] и [DeepSeekResponse], и агент о Ollama
 * не знает.
 *
 * Поля объявлены внутренними (`internal`): это деталь транспорта, а не часть
 * контракта [LlmClient], и тесты транспорта видят их в своём модуле.
 *
 * @param stream `false` — ответ приходит одним JSON, без потока: дополнительное задание
 *        дня (SSE) в эту реализацию не входит.
 * @param think Рассуждать ли перед ответом. Поле помечено [EncodeDefault], то есть уходит
 *        явно в обе стороны: умолчание Ollama зависит от модели, и у thinking-модели оно
 *        `true` — qwen3 без явного отказа потратила бы бюджет ответа на рассуждения, а без
 *        явного согласия не решила бы задачу, где рассуждение обязательно (см.
 *        [LocalLlmClient.DEFAULT_THINK]).
 * @param format Требование к формату: строка `json` — ответ одним объектом JSON.
 *        Приходит из [DeepSeekRequest.responseFormat] и нужен служебным вызовам
 *        (проверка инвариантов, извлечение памяти), которые разбирают ответ кодом.
 * @param options Настройки генерации Ollama: температура, бюджет ответа и стоп-слова.
 */
@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    @EncodeDefault val stream: Boolean = false,
    @EncodeDefault val think: Boolean = false,
    val tools: List<ToolDeclaration>? = null,
    val format: String? = null,
    val options: OllamaOptions? = null
)

/**
 * Настройки генерации Ollama: имена полей отличаются от облачных
 * (`num_predict` вместо `max_tokens`), поэтому у них свой тип.
 *
 * @param temperature Температура генерации.
 * @param numPredict Бюджет ответа в токенах.
 * @param stop Стоп-последовательности: пустой список не пишется, иначе поле обещало бы остановку.
 */
@Serializable
internal data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    val stop: List<String>? = null
)

/**
 * Сообщение в формате Ollama.
 *
 * @param toolName Имя инструмента, на который отвечает сообщение роли `tool`:
 *        Ollama адресует результат именем, а не идентификатором вызова, как облачный API.
 */
@Serializable
internal data class OllamaMessage(
    val role: String,
    @EncodeDefault val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null
)

/** Вызов инструмента в переписке Ollama: имя и аргументы объектом. */
@Serializable
internal data class OllamaToolCall(val function: OllamaToolCallFunction)

/** Имя вызываемого инструмента и его аргументы объектом JSON. */
@Serializable
internal data class OllamaToolCallFunction(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap())
)

/**
 * Ответ `POST /api/chat`: одно сообщение модели и расход токенов.
 *
 * @param message Ответ модели; null — демон вернул ошибку, и её несёт [error].
 * @param doneReason Причина остановки: `stop` — модель договорила, `length` — кончился бюджет.
 * @param promptEvalCount Токены запроса: то же, что `prompt_tokens` облачного API.
 * @param evalCount Токены ответа: то же, что `completion_tokens`.
 * @param error Причина отказа демона, как её называет Ollama (модель не найдена и т. п.).
 */
@Serializable
internal data class OllamaChatResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = true,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    val error: String? = null
)

/** Роль помощника в переписке: модель отвечает этой ролью в обоих форматах. */
private const val ASSISTANT_ROLE = "assistant"

/** Роль результата инструмента: её несёт ответ на вызов. */
private const val TOOL_ROLE = "tool"

/** Разбор аргументов вызова: они приходят строкой JSON и уходят объектом. */
private val ollamaJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Запрос агента в формате Ollama.
 *
 * Модель передаётся параметром, а не берётся из запроса: транспорт знает, к какому
 * демону и к какой модели он подключён, а [DeepSeekRequest.model] — это выбор
 * облачного провайдера, который к Ollama не относится.
 *
 * @param model Имя модели демона: своё, не из запроса.
 * @param think Рассуждать ли перед ответом. Уходит явно в обе стороны: thinking-модель
 *        без отказа тратит бюджет на рассуждения, а рассуждения нужны не всякому запросу.
 * @param temperatureOverride Температура транспорта; null — температура запроса. Задана
 *        транспортом, потому что качество ответа маленькой модели зависит от неё сильнее,
 *        чем от выбора клиента: см. [LocalLlmClient].
 */
internal fun DeepSeekRequest.toOllama(
    model: String,
    think: Boolean = false,
    temperatureOverride: Double? = null
): OllamaChatRequest = OllamaChatRequest(
    model = model,
    messages = messages.withToolNames(),
    stream = false,
    think = think,
    tools = tools,
    format = if (responseFormat == null) null else OLLAMA_JSON_FORMAT,
    options = OllamaOptions(
        temperature = temperatureOverride ?: temperature,
        numPredict = maxTokens,
        stop = stop?.takeIf { it.isNotEmpty() }
    )
)

/** Значение поля `format`, которым Ollama обязывает модель ответить объектом JSON. */
private const val OLLAMA_JSON_FORMAT = "json"

/**
 * Переписка в формате Ollama.
 *
 * Результат инструмента адресуется именем, а не идентификатором вызова, поэтому имени
 * предшествует проход по переписке: идентификатор вызова лежит в сообщении ассистента,
 * а результат ссылается на него один. Без этого прохода Ollama не поняла бы, на какой
 * вызов отвечает сообщение `tool`.
 */
private fun List<ChatMessage>.withToolNames(): List<OllamaMessage> {
    val names = mutableMapOf<String, String>()
    forEach { message ->
        message.toolCalls?.forEach { call -> names[call.id] = call.function.name }
    }
    return map { message ->
        when (message.role) {
            TOOL_ROLE -> OllamaMessage(
                role = message.role,
                content = message.content,
                toolName = message.toolCallId?.let(names::get)
            )

            else -> OllamaMessage(
                role = message.role,
                content = message.content,
                toolCalls = message.toolCalls?.takeIf { it.isNotEmpty() }?.map { it.toOllama() }
            )
        }
    }
}

/** Вызов в формате Ollama: аргументы-строка разбираются в объект. */
private fun ToolCall.toOllama(): OllamaToolCall = OllamaToolCall(
    function = OllamaToolCallFunction(
        name = function.name,
        arguments = ollamaJson.parseToJsonElement(function.arguments)
            .let { element -> element as? JsonObject ?: JsonObject(emptyMap()) }
    )
)

/**
 * Ответ Ollama в модели приложения.
 *
 * Ответа без `message` не бывает успешным: если демон вернул ошибку в теле при
 * успешном статусе, её несёт `error`, и вызывающий получает отказ, а не пустую реплику.
 */
internal fun OllamaChatResponse.toDeepSeek(): DeepSeekResponse {
    val reply = message ?: throw LlmApiException(
        status = 200,
        message = "Ollama вернул ответ без сообщения: ${error ?: "причина не названа"}"
    )
    val prompt = promptEvalCount
    val completion = evalCount
    val usage = if (prompt == null && completion == null) {
        null
    } else {
        DeepSeekResponse.Usage(
            promptTokens = prompt ?: 0,
            completionTokens = completion ?: 0,
            totalTokens = (prompt ?: 0) + (completion ?: 0)
        )
    }
    return DeepSeekResponse(
        choices = listOf(
            DeepSeekResponse.Choice(
                message = ChatMessage(
                    role = ASSISTANT_ROLE,
                    content = reply.content,
                    toolCalls = reply.toolCalls?.mapIndexed { index, call ->
                        ToolCall(
                            id = syntheticToolCallId(index),
                            function = ToolCallFunction(
                                name = call.function.name,
                                arguments = call.function.arguments.toString()
                            )
                        )
                    }?.takeIf { it.isNotEmpty() }
                ),
                finishReason = doneReason
            )
        ),
        usage = usage
    )
}

/**
 * Идентификатор вызова для ответа локальной модели: Ollama его не присылает,
 * а агенту он нужен, чтобы связать вызов с результатом инструмента. Идентификатор
 * синтетический и живёт только внутри одного ответа — наружу он не уходит.
 */
private fun syntheticToolCallId(index: Int): String = "ollama-call-$index"

/** Разбор тела ошибки Ollama: `{"error":"..."}` — сообщение для лога и клиента. */
internal fun ollamaError(text: String): String = runCatching {
    ollamaJson.decodeFromString<OllamaChatResponse>(text).error
}.getOrNull() ?: text
