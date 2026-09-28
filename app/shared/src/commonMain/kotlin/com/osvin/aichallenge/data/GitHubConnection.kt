package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Снимок работы с GitHub: подключён ли сервер инструментов, кто он и что умеет.
 *
 * Клиент сам GitHub не знает и знать не должен: инструменты живут на сервере в отдельном
 * MCP-процессе, а клиенту достаётся только этот снимок — кнопка подключения, список
 * инструментов и их вызов. Второй копии состояния подключения на клиенте нет намеренно:
 * подключение переживает перезапуск приложения и может быть отозвано с другого устройства,
 * поэтому «правда» приходит только ответом сервера, а не выводится из локальных флагов.
 *
 * Токен в снимке не появляется ни в каком виде — сервер не возвращает то, что ему прислали
 * ([GitHubConnectRequest]): иначе он попал бы в хранилище устройства и в логи экрана.
 *
 * @param connected Сервер инструментов готов к работе: подключение выполнено и не оборвалось.
 * @param server Имя MCP-сервера из рукопожатия; null — ещё не подключено, и имени нет.
 * @param version Версия MCP-сервера из рукопожатия; null — ещё не подключено.
 * @param tools Инструменты, которые сервер объявил при подключении. Пустой список —
 *        подключения нет либо сервер не объявил ни одного инструмента: разделять эти
 *        случаи клиенту нечем, кроме [connected].
 */
@Serializable
data class GitHubConnection(
    @SerialName("connected") val connected: Boolean = false,
    @SerialName("server") val server: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("tools") val tools: List<GitHubTool> = emptyList()
)

/**
 * Инструмент GitHub, объявленный MCP-сервером, — то, что показывается списком в шторке.
 *
 * Форма описания своя, а не MCP-протокольная: снимок читает и человек, и клиент, и чужая
 * схема с вложенными `inputSchema` протекла бы в интерфейс. Преобразование объявления
 * в эту форму делает сервер — здесь она только копия провода.
 *
 * @param name Имя инструмента для вызова ([GitHubCallRequest.name]).
 * @param description Пояснение для человека; null — сервер его не дал.
 * @param arguments Аргументы вызова по порядку: подписи полей и их допустимые значения.
 */
@Serializable
data class GitHubTool(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("arguments") val arguments: List<GitHubToolArgument> = emptyList()
)

/**
 * Аргумент инструмента: что за поле и что в него можно положить.
 *
 * Значения перечисляются списком, а не одним типом: у аргумента вида «какие репозитории
 * вернуть» важно не только то, что это строка, но и то, что допустимых значений всего
 * три, — иначе человек набирал бы значение наугад и получал отказ сервера. Своей таблицы
 * аргументов у клиента нет: она приходит из объявления инструмента, и вторая копия на
 * клиенте разошлась бы с сервером на первом же новом аргументе.
 *
 * @param name Имя поля в аргументах вызова (ключ JSON-объекта).
 * @param description Пояснение: что за аргумент и как его заполнять; null — сервер его не дал.
 * @param type Тип значения словами JSON-схемы (`string`, `integer`, …); null — сервер
 *        типа не назвал, и ограничиться можно только [values].
 * @param required Аргумент обязателен: без него сервер откажет в вызове.
 * @param values Допустимые значения, если инструмент ограничился перечнем; пустой список —
 *        ограничения нет, поле свободное.
 */
@Serializable
data class GitHubToolArgument(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("required") val required: Boolean = false,
    @SerialName("values") val values: List<String> = emptyList()
)

/**
 * Запрос на подключение к серверу инструментов GitHub.
 *
 * @param token Токен GitHub или null, если брать его из окружения сервера (`GITHUB_TOKEN`).
 *        Пустая строка означает то же, что null: в поле ввода её легко оставить, а «пустой
 *        токен» уехал бы в GitHub заголовком `Bearer ` и вернулся невнятным отказом.
 *        Клиент токен не хранит — он уходит телом этого запроса и больше нигде на клиенте
 *        не оседает: ни в снимке ([GitHubConnection]), ни в логах, ни в хранилище.
 */
@Serializable
data class GitHubConnectRequest(
    @SerialName("token") val token: String? = null
)

/**
 * Запрос на вызов инструмента GitHub.
 *
 * Аргументы приходят от человека строкой JSON и разбираются клиентом, а не сервером:
 * набор полей у каждого инструмента свой ([GitHubToolArgument]), и типизировать его
 * общим классом на клиенте нельзя — аргументы меняются вместе с инструментом на сервере.
 * Поэтому здесь свободный JSON-объект, а не конкретные поля.
 *
 * @param name Имя инструмента из снимка ([GitHubTool.name]).
 * @param arguments Аргументы вызова; пустой объект — вызвать инструмент с его умолчаниями.
 */
@Serializable
data class GitHubCallRequest(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: JsonObject
)

/**
 * Ответ инструмента GitHub: чем кончился вызов и что он вернул.
 *
 * Тот же результат, что уезжает модели, — поэтому он же ложится в ленту чата записью
 * ([MessageRole.TOOL]): человек видит команду и её результат, а не догадывается, откуда
 * взялись данные в ответе ассистента.
 *
 * @param name Имя вызванного инструмента — им подписан результат в ленте.
 * @param result Ответ инструмента текстом: данные или причина отказа.
 * @param failed Инструмент ответил отказом: данных нет, и модель отвечала без них.
 */
@Serializable
data class GitHubCallResponse(
    @SerialName("name") val name: String,
    @SerialName("result") val result: String,
    @SerialName("failed") val failed: Boolean = false
)
