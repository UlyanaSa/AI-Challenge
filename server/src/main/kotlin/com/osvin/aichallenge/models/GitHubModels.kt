@file:OptIn(ExperimentalSerializationApi::class)

package com.osvin.aichallenge.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Состояние подключения к MCP-серверу GitHub — то, что клиент читает, чтобы нарисовать
 * кнопку подключения, состояние доступа и список инструментов.
 *
 * Отдаётся и до подключения: `connected=false` — это полноценный ответ, а не ошибка, потому
 * что «ещё не подключались» и «подключение оборвалось» для интерфейса неразличимы, и обоим
 * соответствует одна и та же картинка. Сервер и его версия необязательны: до рукопожатия
 * назвать их некому, и выдумывать пустую строку значило бы соврать, что сервер себя назвал.
 *
 * Подключение и доступ — разные состояния, и в снимке они разведены: `connected=true` при
 * `authorized=false` значит «инструменты поднялись, но ходить в GitHub нечем» — это не сбой
 * соединения, а его законный итог, и человеку по нему нужна не ошибка, а [hint]. Отсюда и
 * `login`/`source`: они говорят, откуда доступ взят, но самого доступа (токена) в снимке нет
 * и быть не может — его не знает и сервер приложения.
 *
 * Поля со значениями по умолчанию помечены `@EncodeDefault`: общий JSON сервера умолчания
 * не пишет, поэтому пустой список инструментов и null пропали бы из ответа вовсе, а клиент
 * читает контракт по именам полей — отсутствие поля для него не то же, что пустое значение.
 *
 * @param connected Держится ли сейчас живая сессия инструментов.
 * @param authorized Нашёлся ли доступ к GitHub: false — искать нечего или доступ отвергнут.
 * @param login Кто именно доступен; null — доступ не найден.
 * @param scopes Права доступного токена; пусто — их не сообщили.
 * @param scopesReported Прислал ли GitHub заголовок с правами: fine-grained токены его не отдают.
 * @param source Пометка, откуда взят доступ; null — доступа нет.
 * @param hint Что сделать, чтобы доступ появился; null — доступ есть.
 * @param server Имя сервера из рукопожатия; null — соединения нет.
 * @param version Версия сервера из рукопожатия; null — соединения нет.
 * @param tools Инструменты сервера: пусто — их не у кого спрашивать.
 */
@Serializable
data class GitHubConnection(
    val connected: Boolean,
    @EncodeDefault
    val authorized: Boolean = false,
    @EncodeDefault
    val login: String? = null,
    @EncodeDefault
    val scopes: List<String> = emptyList(),
    @EncodeDefault
    val scopesReported: Boolean = false,
    @EncodeDefault
    val source: String? = null,
    @EncodeDefault
    val hint: String? = null,
    @EncodeDefault
    val server: String? = null,
    @EncodeDefault
    val version: String? = null,
    @EncodeDefault
    val tools: List<GitHubTool> = emptyList()
)

/**
 * Инструмент MCP-сервера GitHub в том виде, в каком его показывает человеку интерфейс.
 *
 * Не то же, что [com.osvin.aichallenge.agent.AgentTool]: агент получает схему аргументов как
 * есть, а человеку нужен разбор по полям — по ним рисуется форма ручного вызова. Поэтому у
 * контракта своя форма, а список строится из того же ответа сервера ([com.osvin.aichallenge.mcp.McpTool]).
 *
 * @param name Имя для вызова: оно же уезжает в `POST /v1/github/call`.
 * @param description Что инструмент делает, словами сервера (их не переписываем).
 * @param arguments Аргументы вызова: имя, тип, обязательность и допустимые значения.
 */
@Serializable
data class GitHubTool(
    val name: String,
    @EncodeDefault
    val description: String? = null,
    @EncodeDefault
    val arguments: List<GitHubToolArgument> = emptyList()
)

/**
 * Аргумент инструмента для формы ручного вызова.
 *
 * Поля необязательны там, где необязателен сам ответ сервера: схема может не называть тип
 * или пояснение, и подставлять вместо этого пустую строку значило бы показывать человеку
 * «описания нет» как описание. Обязательность, наоборот, всегда известна — сервер либо
 * перечислил аргумент в `required`, либо нет, — поэтому у неё нет значения по умолчанию.
 *
 * @param name Имя аргумента — то, что человек пишет в JSON.
 * @param description Пояснение сервера: что подставлять.
 * @param type Тип значения по схеме; null — сервер его не назвал.
 * @param required Аргумент обязателен: без него сервер отвергнет вызов.
 * @param values Допустимые значения, если сервер объявил их списком; пусто — не ограничены.
 */
@Serializable
data class GitHubToolArgument(
    val name: String,
    @EncodeDefault
    val description: String? = null,
    @EncodeDefault
    val type: String? = null,
    val required: Boolean,
    @EncodeDefault
    val values: List<String> = emptyList()
)

/**
 * Ответ на прямой вызов инструмента из чата.
 *
 * Отказ инструмента — это тоже ответ (`failed=true` и причина в `result`), а не ошибка
 * транспорта: инструмент отработал и словами сказал, почему данных нет, — их нужно показать
 * человеку как результат вызова, а не как сбой сервера. Ошибкой запроса остаётся другое:
 * соединения нет или инструмента с таким именем сервер не объявлял.
 *
 * @param name Имя вызванного инструмента.
 * @param result Текст ответа инструмента; при `failed=true` — причина отказа.
 * @param failed Инструмент отказал: данных нет, но вызов дошёл и ответил.
 */
@Serializable
data class GitHubCallResponse(
    val name: String,
    val result: String,
    val failed: Boolean
)

/**
 * Отчёт о доступе, как его отдаёт инструмент MCP-сервера `github_access`.
 *
 * Внутренняя форма, а не часть контракта с клиентом: это ровно то, что `github_access` пишет
 * в ответ, и разбирается она тем же кодом, что зовёт инструмент, — сервер приложения только
 * переносит поля в снимок [GitHubConnection]. Отдельная форма нужна потому, что у снимка есть
 * и свои поля (соединение, имя сервера, инструменты), которых в отчёте нет; разбирать ответ
 * инструмента прямо в снимок значило бы приписать снимку поля, которых инструмент не шлёт.
 *
 * Токена здесь нет и быть не может: инструмент сообщает только пометку источника ([source]),
 * по которой видно, откуда доступ взят, но не сам доступ.
 *
 * Умолчания — только ради разбора: форма живёт от ответа инструмента к снимку и обратно не
 * едет, поэтому `@EncodeDefault` ей не нужен, а пропущенное поле читается как «про это не
 * сказано»: падать из-за него значило бы терять доступ из-за формы отчёта, а не из-за сути.
 *
 * @param authorized Доступ найден и принят GitHub; false — доступ искать негде или он отвергнут.
 * @param login Владелец доступа; null — GitHub его не назвал (доступа нет).
 * @param scopes Права токена из заголовка `X-OAuth-Scopes`; пусто — их не сообщили.
 * @param scopesReported Прислал ли GitHub заголовок с правами: fine-grained токены его не отдают.
 * @param source Пометка, откуда взят доступ; null — доступа нет и брать неоткуда.
 * @param hint Что делать человеку; null — доступ есть, подсказывать нечего.
 */
@Serializable
internal data class GitHubAccessReport(
    val authorized: Boolean,
    val login: String? = null,
    val scopes: List<String> = emptyList(),
    val scopesReported: Boolean = false,
    val source: String? = null,
    val hint: String? = null
)

/**
 * Тело запроса на прямой вызов инструмента: имя и аргументы, собранные человеком.
 *
 * Аргументы — уже готовый JSON-объект, а не строка: их собирает интерфейс по форме вызова,
 * и сервер не разбирает человеческий текст повторно, потому что форма и так знает поля.
 * Пустой объект — вызов без аргументов, поэтому у поля есть умолчание: у инструментов
 * проекта аргументы необязательны.
 *
 * @param name Имя инструмента из подключённого сервера.
 * @param arguments Аргументы вызова как есть: что собрал интерфейс, то и уходит серверу.
 */
@Serializable
data class GitHubCallRequest(
    val name: String,
    @EncodeDefault
    val arguments: JsonObject = JsonObject(emptyMap())
)
