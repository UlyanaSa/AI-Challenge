package com.osvin.aichallenge.agent

/** Чем закончилось действие человека над задачей: снимком или отказом с причиной. */
sealed interface TaskWrite {

    /** Действие прошло; [snapshot] — состояние задачи после него. */
    data class Written(val snapshot: TaskSnapshot) : TaskWrite

    /** Задачи в диалоге нет: причина — в [reason]. */
    data class Rejected(val reason: String) : TaskWrite
}

/**
 * Задача как явное действие человека: взять в работу, поставить на паузу, забыть.
 *
 * Задачу заводит человек, а не модель, — ровно так же, как в память пишет человек
 * ([MemoryWriter]). Пока задачи нет, агент её не ведёт вовсе: служебных вызовов к модели
 * нет, и поведение прежних дней не меняется. Дальше состояние ведёт агент сам, а сюда
 * приходят только решения о самой работе: начать, остановить, закрыть. Этап он не меняет:
 * этап — это работа, а не распоряжение ([TaskRules]).
 *
 * Код проверяет то, что проверить может: задача в диалоге есть (иначе пауза не имеет
 * смысла) и пауза ставится и снимается только у неё. Причина отказа называет, чего
 * не хватает, — «в этом диалоге задачи нет» вместо пустого снимка, по которому нельзя
 * понять, задача на паузе или её не заводили.
 */
class TaskWriter(private val store: TaskStateStore) {

    /** Снимок задачи: состояние диалога, каталог этапов и разрешённые переходы. */
    fun snapshot(sessionId: String): TaskSnapshot =
        TaskSnapshot(
            task = store.get(sessionId),
            stages = TaskStage.info,
            transitions = TaskRules.transitions
        )

    /**
     * Берёт задачу в работу: заводит её в этапе «планирование» с пустым шагом — что делать,
     * назовёт модель первым служебным вызовом.
     *
     * Идемпотентно: повторное нажатие не ошибка и уже пройденный путь не сбрасывает —
     * кнопка может нажаться дважды, а возврат к началу стёр бы и этап, и шаг.
     */
    fun start(sessionId: String): TaskSnapshot {
        if (store.get(sessionId) == null) store.put(sessionId, TaskStage.start())
        return snapshot(sessionId)
    }

    /**
     * Ставит задачу на паузу и снимает её.
     *
     * Пауза — не переход: этап, шаг и ожидаемое действие остаются на месте, а служебного
     * вызова на паузе нет вовсе. Поэтому продолжение идёт с того же шага — и по состоянию
     * видно, что задача не двигалась, а стояла.
     */
    fun setPaused(sessionId: String, paused: Boolean): TaskWrite {
        val state = store.get(sessionId) ?: return TaskWrite.Rejected(NO_TASK)
        val pausedState = state.copy(paused = paused)
        store.put(sessionId, pausedState)
        return TaskWrite.Written(
            TaskSnapshot(
                task = pausedState,
                stages = TaskStage.info,
                transitions = TaskRules.transitions
            )
        )
    }

    /**
     * Забывает задачу диалога: работа в нём закрыта.
     *
     * Идемпотентно: забывать нечего — снимок вернётся пустым, потому что повторное
     * удаление не ошибка.
     */
    fun forget(sessionId: String): TaskSnapshot {
        store.clear(sessionId)
        return snapshot(sessionId)
    }

    companion object {

        /** Причина отказа: задачи в этом диалоге нет — ставить на паузу нечего. */
        const val NO_TASK = "у этого диалога задачи нет: возьмите задачу в работу"
    }
}
