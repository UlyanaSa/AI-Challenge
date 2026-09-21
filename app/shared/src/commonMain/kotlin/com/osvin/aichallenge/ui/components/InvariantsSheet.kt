package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.Invariant
import com.osvin.aichallenge.data.InvariantKindInfo
import com.osvin.aichallenge.data.InvariantSnapshot

/**
 * Шторка инвариантов проекта: правила, которые ассистент нарушать не имеет права,
 * и форма явного добавления правила.
 *
 * Это не память и не задача: правила не извлекаются из диалога и не описывают работу —
 * их объявляет человек, они лежат на сервере в своём хранилище и уходят в модель
 * системным сообщением при любой стратегии. Поэтому здесь нет ни слоёв, ни отчёта
 * последнего ответа: шторка показывает действующий свод правил и даёт его дополнить
 * или сократить. Отказ на правку приходит текстом: правило проверяет сервер, и его
 * причина важнее того, что набрано на экране.
 *
 * Разделы идут в порядке каталога ([InvariantSnapshot.kinds]) и подписаны его же
 * названиями: своей таблицы видов у клиента нет, поэтому каталог — единственный
 * источник и выбора вида, и его подписи. Пустой список правил — это именно «правил
 * нет»: умолчания проекта подставляет сервер, когда человек их ещё не задавал,
 * и на клиенте второй их копии нет.
 *
 * Своего состояния у шторки нет: набранный текст и выбранный вид живут на экране
 * чата, иначе они терялись бы при перерисовке списка сообщений.
 *
 * @param snapshot Снимок инвариантов профиля: действующие правила и каталог видов.
 * @param error Отказ сервера на чтение или правку правил; null — отказа не было.
 * @param selectedKind Выбранный вид правила; null — каталог не пришёл или в нём нет видов.
 * @param valueText Формулировка правила, которую вводят сейчас.
 * @param onKindSelected Выбор вида из каталога.
 * @param onValueChange Ввод формулировки правила.
 * @param onRemember Добавление правила: вид и формулировка.
 * @param onForget Удаление правила: вид и формулировка.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvariantsSheet(
    snapshot: InvariantSnapshot,
    error: String?,
    selectedKind: String?,
    valueText: String,
    onKindSelected: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemember: () -> Unit,
    onForget: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Инварианты проекта",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))
        invariantText(
            "Правила, которые ассистент нарушать не имеет права. Они лежат на сервере " +
                "отдельно от диалога и уходят в модель с каждым запросом — при любой стратегии."
        )
        Spacer(Modifier.height(4.dp))
        invariantText(
            "Если запрос противоречит правилу, ассистент откажется его выполнять, назовёт " +
                "нарушенный инвариант и предложит решение в его рамках."
        )

        snapshot.kinds.forEach { kind ->
            InvariantKindSection(
                kind = kind,
                invariants = snapshot.invariants.filter { it.kind == kind.kind },
                onForget = onForget
            )
        }

        // Пустой свод — не «снимок не пришёл»: правила подставляет сервер, когда человек
        // их ещё не задавал, поэтому пусто бывает только после того, как их убрали все.
        // Тогда и проверки нет — об этом надо сказать, иначе шторка выглядит сломанной.
        // Каталог при этом приходит всегда, поэтому по нему и видно, что снимок есть
        if (snapshot.kinds.isNotEmpty() && snapshot.invariants.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            invariantText("Инвариантов нет: запросы не проверяются на конфликт с правилами")
        }

        Spacer(Modifier.height(16.dp))
        invariantText("Новое правило")
        // Пустой каталог — единственная причина, по которой добавление недоступно не
        // из-за текста: без каталога нечего выбрать, и кнопка выглядела бы сломанной
        if (snapshot.kinds.isEmpty()) {
            invariantText("Каталог видов не пришёл с сервера: добавить правило некуда, пока нет снимка")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.kinds.forEach { kind ->
                FilterChip(
                    selected = kind.kind == selectedKind,
                    onClick = { onKindSelected(kind.kind) },
                    label = { Text(kind.label()) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = valueText,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Инвариант") },
            supportingText = { Text("Одно правило, записанное как требование проекта") },
            maxLines = 3
        )
        // Добавить можно только правило каталога и не пустое: остальное сервер
        // отвергнет, поэтому кнопка такое просто не отправляет
        Button(
            onClick = onRemember,
            enabled = snapshot.kinds.any { it.kind == selectedKind } && valueText.isNotBlank()
        ) {
            Text("Добавить")
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Раздел вида: подпись и пояснение из каталога, правила вида и «✕» у каждого. */
@Composable
private fun InvariantKindSection(
    kind: InvariantKindInfo,
    invariants: List<Invariant>,
    onForget: (String, String) -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = kind.label(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (invariants.isEmpty()) {
        invariantText("пока пусто")
        return
    }
    invariants.forEach { invariant ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            invariantText(invariant.value, Modifier.weight(1f))
            TextButton(
                onClick = { onForget(invariant.kind, invariant.value) },
                modifier = Modifier.semantics {
                    contentDescription = "Убрать инвариант: ${invariant.value}"
                }
            ) {
                Text("✕", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Строка шторки инвариантов: тот же стиль, что у остальных подписей блока. */
@Composable
private fun invariantText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Подпись вида: название из каталога вместе с его пояснением из того же каталога. */
private fun InvariantKindInfo.label(): String = "${title.replaceFirstChar { it.uppercase() }} ($hint)"
