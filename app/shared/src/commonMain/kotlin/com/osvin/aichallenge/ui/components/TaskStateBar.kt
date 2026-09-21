package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.TaskSnapshot
import com.osvin.aichallenge.data.TaskState

/**
 * Полоса задачи над полем ввода: на каком этапе работа, какой шаг идёт и что ждут
 * от человека.
 *
 * Задача — не часть диалога, а отдельный слой работы, поэтому в ленте сообщений её нет:
 * как память и профиль, она живёт вне переписки. Задача принадлежит чату (её состояние
 * адресуется сессией диалога), поэтому и полоса у каждого чата своя.
 *
 * Своего состояния у полосы нет: и состояние работы, и каталог этапов приходят с сервера
 * ([TaskSnapshot]), а клиент их только показывает — этап, шаг и ожидаемое действие не
 * выводятся из переписки. Подписи и пояснения этапа берутся тоже только из каталога:
 * своей таблицы этапов у клиента нет, иначе она пережила бы новый этап на сервере молча,
 * а пояснение разошлось бы с серверным.
 *
 * @param task Снимок задачи чата; null — снимка ещё не было или он не пришёл, тогда
 *        задача считается не заведённой.
 * @param error Отказ сервера на чтение задачи или на действие с ней; null — отказа не было.
 * @param onStart Взятие задачи в работу.
 * @param onTogglePause Пауза или продолжение — что именно, видно по состоянию задачи.
 * @param onForget Забвение задачи: её состояние стирается на сервере.
 */
@Composable
fun TaskStateBar(
    task: TaskSnapshot?,
    error: String?,
    onStart: () -> Unit,
    onTogglePause: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = task?.task
    // Поле ввода под полосой выше на тон, поэтому полоса читается как отдельная строка,
    // а не как часть панели ввода
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (task == null || state == null) {
                // Задачу не заводили или она забыта — это нормальное состояние, а не
                // пустое место: видно, что работа не идёт, и есть чем её начать
                Row(verticalAlignment = Alignment.CenterVertically) {
                    taskText("Задача не в работе", Modifier.weight(1f))
                    TextButton(onClick = onStart) {
                        Text("Взять в работу")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Этап — подписью из каталога, а не значением с провода: значения
                        // английские, и человеку они ничего не говорят
                        Text(
                            text = task.stageTitle(state),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val hint = task.stageHint(state)
                        if (hint != null) {
                            taskText(hint)
                        }
                    }
                    TextButton(onClick = onTogglePause) {
                        Text(if (state.paused) "Продолжить" else "Пауза")
                    }
                    TextButton(
                        onClick = onForget,
                        modifier = Modifier.semantics { contentDescription = "Забыть задачу" }
                    ) {
                        Text("✕", style = MaterialTheme.typography.labelMedium)
                    }
                }
                // Пометка паузы отдельной строкой: без неё остановленная задача
                // выглядела бы как пропавшая — состояние-то на месте, но работа стоит
                if (state.paused) {
                    taskText("Задача на паузе: работа ждёт продолжения")
                }
                // Пустые шаг и действие не печатаем: строка-подпись без значения
                // читалась бы как потерянное состояние
                if (state.step.isNotBlank()) {
                    taskText("текущий шаг: ${state.step}")
                }
                if (state.expectedAction.isNotBlank()) {
                    taskText("ожидаемое действие: ${state.expectedAction}")
                }
            }

            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Название этапа из каталога снимка. Каталог — единственный источник названий, поэтому
 * незнакомый этап печатается значением с провода: пустая строка на его месте выглядела бы
 * как сломанное состояние, а выдуманное название разошлось бы с серверным.
 */
private fun TaskSnapshot.stageTitle(state: TaskState): String =
    stages.firstOrNull { it.stage == state.stage }?.title?.takeIf { it.isNotBlank() } ?: state.stage

/** Пояснение этапа из того же каталога; null — каталог этого этапа не знает. */
private fun TaskSnapshot.stageHint(state: TaskState): String? =
    stages.firstOrNull { it.stage == state.stage }?.hint?.takeIf { it.isNotBlank() }

/** Строка полосы задачи: тот же стиль, что у подписей в шторках памяти и профиля. */
@Composable
private fun taskText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
