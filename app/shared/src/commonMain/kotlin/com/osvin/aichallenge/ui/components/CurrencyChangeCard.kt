package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osvin.aichallenge.data.CurrencyChangeFeed
import com.osvin.aichallenge.data.line

/**
 * Шапка закреплённого чата курсов: как курсы изменились за сутки.
 *
 * Карточка стоит первой строкой ленты, потому что отвечает на вопрос, ради которого
 * в ленту смотрят после паузы: «что стало с курсами». Сама лента показывает наблюдения
 * по минутам, и по ней этот вывод пришлось бы считать в голове, а клиент этого и не может —
 * вычитать и поделить деньги ему нечем ([com.osvin.aichallenge.data.DecimalText] объясняет
 * почему). Поэтому все числа — изменение, проценты, крайние значения за окно — приходят
 * посчитанными с сервера, а карточка только печатает их ([line]).
 *
 * Ограничение сервера показывается честно, а не прячется: [CurrencyChangeFeed.hoursCovered]
 * говорит, сколько часов окна сервер действительно наблюдал. Пока наблюдений меньше суток,
 * карточка так и пишет — иначе неполная картина читалась бы как суточная, а на только что
 * установленном приложении она именно неполная.
 *
 * Своей копии снимка у карточки нет: пока ответа нет, видно, что он идёт, а при отказе —
 * причина словами, а не пустая шапка. Причина не стирает уже показанный снимок: показать
 * «изменений нет» там, где они прочитаны, значило бы соврать.
 *
 * @param feed Изменение курсов с сервера; null — ответа ещё нет или он не пришёл.
 * @param error Отказ сервера на чтение изменения; null — отказа не было.
 */
@Composable
fun CurrencyChangeCard(
    feed: CurrencyChangeFeed?,
    error: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "Изменение за сутки",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            when {
                // Снимок показывается и рядом с причиной отказа: он прочитан, а значит правдив
                feed != null -> {
                    if (feed.currencies.isEmpty()) {
                        note("Сервер не прислал ни одной валюты")
                    } else {
                        feed.currencies.forEach { item ->
                            Text(
                                text = item.line(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (feed.hoursCovered < feed.hours) {
                            Spacer(Modifier.height(4.dp))
                            note("за сутки данных не хватило: сохранено ${feed.hoursCovered} ч из ${feed.hours}")
                        }
                    }
                    // Отказ печатается и рядом со снимком: числа на экране остались от прошлого
                    // удачного чтения, и без причины их легко принять за свежие.
                    if (error != null) {
                        Spacer(Modifier.height(4.dp))
                        note(error, isFailure = true)
                    }
                }

                error != null -> note(error, isFailure = true)
                else -> note("Изменение курсов загружается…")
            }
        }
    }
}

/**
 * Строка состояния карточки: пояснение, чего ждать или что случилось.
 *
 * Отдельная функция, чтобы четыре такие строки не разъезжались по стилю; отказ печатается
 * цветом ошибки, а не тем же серым, — иначе «сервер недоступен» было бы не отличить
 * от «данных пока нет».
 *
 * @param text Что сказать человеку.
 * @param isFailure Показать текст как отказ.
 */
@Composable
private fun note(text: String, isFailure: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isFailure) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}
