package com.osvin.aichallenge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Состояние задачи в чате: на каком этапе работа, какой шаг идёт и что ждут от человека.
 *
 * Это копия серверной модели, как и остальные модели дня ([MemoryLayers], [UserProfile]):
 * этап, шаг и ожидаемое действие считает сервер, а клиент их только показывает и не
 * выводит сам. Пустые значения означают «поле ещё не пришло» или «задачи нет».
 *
 * Подписей этапов здесь нет намеренно, хотя значения на проводе английские: каталог
 * этапов приходит вместе со снимком ([TaskStageInfo]), и он — единственный источник
 * названий и пояснений. Своя таблица на клиенте разошлась бы с серверной и пережила бы
 * добавление нового этапа молча.
 *
 * @param stage Этап работы на проводе: `planning`, `execution`, `validation`, `done`.
 * @param step Текущий шаг: что делают прямо сейчас.
 * @param expectedAction Ожидаемое действие человека: чего задача ждёт от него.
 * @param paused true — задача на паузе: состояние заморожено и ждёт продолжения.
 */
@Serializable
data class TaskState(
    @SerialName("stage") val stage: String = "",
    @SerialName("step") val step: String = "",
    @SerialName("expected_action") val expectedAction: String = "",
    @SerialName("paused") val paused: Boolean = false
)

/**
 * Этап из каталога сервера: [stage] — значение на проводе, [title] и [hint] — подпись
 * и пояснение этапа. Каталог — единственное место, где клиент берёт название этапа,
 * поэтому у полосы задачи нет своей таблицы названий.
 */
@Serializable
data class TaskStageInfo(
    @SerialName("stage") val stage: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("hint") val hint: String = ""
)

/**
 * Разрешённый переход задачи: из какого этапа куда можно и почему. Таблица приходит
 * с сервера вместе со снимком, поэтому полоса задачи показывает «куда дальше можно»
 * по серверному списку, а не по своей копии правил: своя копия молча разошлась бы
 * с той, которой ограничен ассистент.
 *
 * @param from Этап, из которого переходят: значение на проводе.
 * @param to Этап, в который переходят: значение на проводе.
 * @param why Почему такой переход разрешён.
 */
@Serializable
data class TaskTransition(
    @SerialName("from") val from: String = "",
    @SerialName("to") val to: String = "",
    @SerialName("why") val why: String = ""
)

/**
 * Снимок задачи чата: состояние работы, каталог этапов и разрешённые переходы. Тем же
 * ответом сервер отвечает и на чтение, и на взятие задачи в работу, и на паузу, и на
 * забвение, поэтому состояние полосы всегда берётся из ответа сервера, а не собирается
 * на клиенте: иначе нажатие кнопки показывало бы не то, что лежит на сервере.
 *
 * @param task Задача чата; null — задачи нет: её не заводили, она забыта или сервер
 *        о ней не знает.
 * @param stages Каталог этапов: подписи полосы в том порядке, в котором их задумал сервер.
 * @param transitions Разрешённые переходы: по ним полоса называет следующий возможный этап.
 */
@Serializable
data class TaskSnapshot(
    @SerialName("task") val task: TaskState? = null,
    @SerialName("stages") val stages: List<TaskStageInfo> = emptyList(),
    @SerialName("transitions") val transitions: List<TaskTransition> = emptyList()
)

/**
 * Взятие задачи в работу: сессию называет поле [sessionId]. Сессия здесь обязательна,
 * потому что состояние задачи, в отличие от памяти, принадлежит диалогу: этап и шаг
 * описывают работу в конкретном чате, и без сессии у запроса нет адреса.
 *
 * @param sessionId Идентификатор диалога, в котором заводится задача.
 */
@Serializable
data class TaskStartRequest(
    val sessionId: String
)

/**
 * Пауза и продолжение задачи: сессию называет поле [sessionId], действие — [paused].
 * Одно поле на оба действия, потому что разница между ними только в нём: на паузе
 * состояние остаётся на месте, и после продолжения работа идёт с того же шага.
 *
 * @param sessionId Идентификатор диалога, в котором ведётся задача.
 * @param paused true — поставить на паузу, false — продолжить с того же шага.
 */
@Serializable
data class TaskPauseRequest(
    val sessionId: String,
    val paused: Boolean
)
