package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.DialogBranches
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.ToolCallRecord

/**
 * Пузырек сообщения в списке чата.
 *
 * Вызовы инструментов печатаются карточками рядом с речью, а не строкой в пузыре: у ответа
 * инструмента текста нет вовсе ([MessageRole.TOOL] приходит с пустым `content`), и пузырь
 * на его месте был бы пустым. Заодно видно, откуда взяты данные ответа ассистента.
 *
 * @param message Данные сообщения.
 * @param choice Варианты продолжения после этого сообщения: null или один вариант —
 *        переключать нечего.
 * @param onSelectOption Переход к варианту: null — основная линия или родительская ветка.
 * @param onBranchFrom Действие «ветка от этого сообщения»; null — ветвить нечего
 *        (сообщение вне активного пути).
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    choice: DialogBranches.BranchChoice? = null,
    onSelectOption: (String?) -> Unit = {},
    onBranchFrom: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    // Разные углы для пользователя и ИИ
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Ответ на вызов инструмента — не речь участника, а след работы: вместо пузыря
        // с пустым текстом печатается карточка вызова. Ветвить такой ответ нечем,
        // поэтому дальше ветка не выполняется вовсе
        if (message.role == MessageRole.TOOL) {
            message.tools.forEach { call ->
                ToolCallCard(call)
            }
            return@Column
        }

        Surface(
            color = bubbleColor,
            shape = shape,
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }

        // Вызовы модели идут под её ответом: по ним видно, откуда взяты данные, и что
        // инструмент на них ответил — это часть ответа, а не служебная запись в логе
        message.tools.forEach { call ->
            Spacer(Modifier.height(4.dp))
            ToolCallCard(call)
        }

        // Варианты продолжения и точка ветвления: «‹ 2/3 ›» и «ветка от этого сообщения»
        if ((choice != null && choice.size > 1) || onBranchFrom != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (choice != null && choice.size > 1) {
                    TextButton(
                        onClick = { onSelectOption(choice.neighbour(-1)) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(text = "‹", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        text = "${choice.current + 1}/${choice.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { onSelectOption(choice.neighbour(1)) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(text = "›", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (onBranchFrom != null) {
                    TextButton(
                        onClick = onBranchFrom,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Ветка от этого сообщения",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Карточка вызова инструмента: команда, ответ и пометка отказа.
 *
 * Одна на оба места — под ответом ассистента и вместо пузыря ответа инструмента: это один
 * и тот же след работы, и вторая отрисовка разошлась бы с первой на первой же правке
 * формата. Команда печатается моноширинно, потому что это имя и аргументы вызова, а не
 * речь: так её видно как команду, а не как часть ответа.
 *
 * @param call Вызов инструмента из сообщения.
 */
@Composable
private fun ToolCallCard(call: ToolCallRecord, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = call.command(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Ответа может не быть: инструмент мог не дойти до данных, и тогда пустой
            // строки на его месте достаточно, чтобы это не выглядело потерянным ответом
            val result = call.result
            if (result != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (call.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            // Пометка обязательна: отказ инструмента — это ответ модели, которая дальше
            // отвечала без данных, и по одному тексту этого не отличить от данных
            if (call.failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "инструмент ответил отказом: данных нет",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
