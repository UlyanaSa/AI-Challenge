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
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.DialogBranches
import com.osvin.aichallenge.data.MessageRole

/**
 * Пузырек сообщения в списке чата.
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
