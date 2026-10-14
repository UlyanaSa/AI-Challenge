package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.ChatMessage
import com.osvin.aichallenge.data.CompareFeed
import com.osvin.aichallenge.data.CompareTurn
import com.osvin.aichallenge.data.MessageRole
import com.osvin.aichallenge.data.Pane
import com.osvin.aichallenge.data.PaneAnswer
import com.osvin.aichallenge.data.PaneInfo

/**
 * Две сборки модели рядом, на одном вопросе.
 *
 * Окна показываются вместо обычной ленты чата, а не вместе с ней: сравнивают ответы
 * на один вопрос, и вторая лента рядом с первой читалась бы как продолжение разговора.
 *
 * Вопрос печатается в обоих окнах, хотя задан один раз: окна читаются порознь — слева
 * «как сейчас», справа оптимизированная сборка, — и вопрос в каждом окне нужен, чтобы
 * видеть, на что отвечает модель. Тот же вопрос, напечатанный один раз над обоими окнами,
 * заставил бы глаз возвращаться к нему от каждого ответа.
 *
 * Ожидание показывается в каждом окне своим индикатором ([TypingIndicator]): сборки
 * отвечают одновременно, но не синхронно, и по индикатору видно, какая ещё пишет.
 * Отказ сборки ([PaneAnswer.failed]) стоит в её окне вместо ответа: одна модель не
 * ответила — сравнение от этого не отменяется.
 *
 * Под каждым ответом печатается его цена — время и токены ([metricsLine]): сравнивают
 * здесь сборки, а не тексты, и сборки отличаются ровно тем, что видно в этой строке, —
 * окно и бюджет меняют время ответа, инструкция меняет объём ответа.
 *
 * @param feed Лента сравнения: ходы, описание окон и состояние запроса.
 */
@Composable
fun ComparePanes(
    feed: CompareFeed,
    modifier: Modifier = Modifier
) {
    // Прокрутка у каждого окна своя: окна сравнивают построчно, и общая прокрутка
    // уводила бы ответ одного окна, когда смотрят другое
    val leftState = rememberLazyListState()
    val rightState = rememberLazyListState()

    // Прокрутка идёт за новым ответом: без неё сравнение заканчивается за нижней границей
    // окна, и кажется, что ответа не было
    LaunchedEffect(feed.turns.size, feed.pendingQuestion, feed.loading) {
        val last = feed.turns.size + if (feed.pendingQuestion != null) 1 else 0
        if (last > 0) {
            leftState.animateScrollToItem(last - 1)
            rightState.animateScrollToItem(last - 1)
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        ComparePane(
            pane = Pane.BASELINE,
            feed = feed,
            listState = leftState,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        VerticalDivider()
        ComparePane(
            pane = Pane.OPTIMIZED,
            feed = feed,
            listState = rightState,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

/**
 * Одно окно сравнения: подпись сборки и её разговор.
 *
 * @param pane Метка панели: по ней из ходов берутся ответы именно этого окна.
 * @param feed Лента сравнения целиком: окно показывает свою часть.
 * @param listState Состояние прокрутки окна.
 */
@Composable
private fun ComparePane(
    pane: String,
    feed: CompareFeed,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PaneHeader(pane = pane, info = feed.pane(pane))

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (feed.turns.isEmpty() && !feed.loading) {
                    item {
                        Text(
                            text = "Задайте вопрос — обе сборки ответят на него одновременно.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp)
                        )
                    }
                }

                itemsIndexed(feed.turns) { _, turn ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = PANE_MAX_WIDTH),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChatBubble(message = ChatMessage(MessageRole.USER, turn.question))
                        TurnAnswer(pane = pane, answer = turn.answer(pane), waiting = feed.loading)
                    }
                }

                // Заданный вопрос стоит в окне до ответа: окна пишут одновременно, и вопрос
                // в каждом из них показывает, что запрос ушёл, а индикатор — что идёт ответ
                feed.pendingQuestion?.let { question ->
                    item(key = "ожидание") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = PANE_MAX_WIDTH),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ChatBubble(message = ChatMessage(MessageRole.USER, question))
                            TurnAnswer(pane = pane, answer = null, waiting = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Заголовок окна: какая это сборка и чем она отличается от соседней.
 *
 * Настройки печатаются словами, а не числами без подписи: «окно 8192» читается,
 * «8192» — нет, а сравнивают здесь именно настройки — модель, окно контекста, бюджет
 * ответа и наличие своей инструкции.
 */
@Composable
private fun PaneHeader(pane: String, info: PaneInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = info?.title ?: defaultTitle(pane),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        info?.let {
            Text(
                text = settingsLine(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Ответ окна на последний вопрос: текст, его цена, ожидание или причина отказа.
 *
 * Цена ([metricsLine]) стоит под текстом, а не над ним: она относится к ответу и читается
 * после него — подписью под ответом, а не заголовком над ним.
 */
@Composable
private fun TurnAnswer(pane: String, answer: PaneAnswer?, waiting: Boolean) {
    when {
        answer?.failed == true -> ErrorMessage(answer.error.orEmpty())
        answer != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChatBubble(message = ChatMessage(MessageRole.ASSISTANT, answer.reply))
            metricsLine(answer)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Окно ждёт ответа только пока идёт запрос: пустой ответ без запроса — это
        // не «сборка думает», а расхождение, о котором честнее сказать словами
        waiting -> TypingIndicator()
        else -> Text(
            text = "Ответа нет",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Цена ответа одной строкой: «18,4 с · 1 320 ток. (ответ 1 267, запрос 53)».
 *
 * Числа приходят с сервера и не считаются на клиенте: время измеряет тот, кто ждал модель
 * ([PaneAnswer.elapsedMs]), токены — отчёт агента из самого ответа. Часы и счётчик клиента
 * дали бы другие числа и разошлись бы с отчётом прогона, ради сверки с которым сравнение
 * и включают.
 *
 * Половины строки печатаются порознь, потому что пустыми они бывают порознь: у ответа
 * с подставленным транспортом времени нет, а сборка, вернувшая пустой ответ, до токенов
 * не доходит. Ноль на месте отсутствующего числа читался бы как измерение («0 с»), поэтому
 * не названное не печатается, а не превращается в ноль.
 *
 * @return Строка цены; null — ни времени, ни расхода нет, и называть нечего.
 */
private fun metricsLine(answer: PaneAnswer): String? {
    // Отчёт агента идёт впереди карты `usage`: он есть у обоих транспортов и разложен
    // по частям запроса (запрос, ответ, рассуждения), а карта — сырой ответ провайдера
    val prompt = answer.tokens?.promptTokens?.takeIf { it > 0 } ?: answer.usage?.get("prompt_tokens")
    val reply = answer.tokens?.replyTokens?.takeIf { it > 0 } ?: answer.usage?.get("completion_tokens")
    val total = listOfNotNull(prompt, reply).sum().takeIf { it > 0 }

    val parts = buildList {
        if (answer.elapsedMs > 0) add(secondsText(answer.elapsedMs))
        if (total != null) {
            add("${grouped(total)} ток. (ответ ${grouped(reply ?: 0)}, запрос ${grouped(prompt ?: 0)})")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Время ответа: секунды с одним знаком, а от минуты — минуты и секунды. */
private fun secondsText(millis: Long): String {
    // Округление до десятых, а не отбрасывание: отчёт прогона печатает это же время
    // в целых миллисекундах, и «18,4 с» здесь — то же число, что 18 359 мс там
    val tenths = (millis + 50) / 100
    if (tenths < 600) return "${tenths / 10},${tenths % 10} с"
    val seconds = tenths / 10
    return "${seconds / 60} мин ${seconds % 60} с"
}

/** Число с разрядами через пробел: «13 569» — как в отчёте прогона. */
private fun grouped(value: Int): String =
    value.toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()

/** Заголовок окна, пока описание панелей не прочиталось: он же придёт с первым ответом. */
private fun defaultTitle(pane: String): String =
    if (pane == Pane.OPTIMIZED) "оптимизированная" else "неоптимизированная"

/**
 * Строка настроек сборки: модель, окно контекста, бюджет ответа, инструкция, температура
 * и рассуждение — всё, чем одна сборка отличается от соседней.
 */
private fun settingsLine(info: PaneInfo): String = buildList {
    add(info.model.ifEmpty { "модель не названа" })
    add("окно ${info.numCtx} ток.")
    add(info.maxTokens?.let { "бюджет $it ток." } ?: "без бюджета")
    add(if (info.promptTemplate) "своя инструкция" else "без инструкции")
    add("температура ${info.temperature}")
    // Обе половины называются словами: «без рассуждения» — такая же настройка сборки, как
    // «с рассуждением», и молчание о ней читалось бы как умолчание, а не как выбор
    add(if (info.think) "с рассуждением" else "без рассуждения")
}.joinToString(" · ")

/**
 * Предел ширины строки внутри окна.
 *
 * Окно занимает половину ширины чата, и на широком экране строка ответа растянулась бы так,
 * что глаз терял бы её начало: 520 dp — та же читаемая строка, что и в обычной ленте.
 */
private val PANE_MAX_WIDTH = 520.dp
