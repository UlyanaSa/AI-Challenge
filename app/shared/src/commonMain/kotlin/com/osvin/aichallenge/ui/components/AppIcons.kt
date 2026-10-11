package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Значки интерфейса, нарисованные на холсте.
 *
 * Раньше их место занимали символы шрифта («←», «＋», «➤», «✕»), и в браузере они
 * отображались неверно: стрелка, полный плюс и треугольник отправки нарисованы редкими
 * глифами (U+FF0B, U+27A4), которых в сборочном шрифте WebAssembly-сборки нет — вместо
 * значка выходил пустой прямоугольник. Здесь те же фигуры строятся линиями и заливкой,
 * поэтому рисуются одинаково всеми платформами и не зависят от набора глифов.
 *
 * Размер и цвет задаёт вызывающий: значок идёт внутрь кнопки Compose, а её размеры
 * и цвет содержимого — её собственные ([androidx.compose.material3.IconButton],
 * [androidx.compose.material3.Button]).
 *
 * @param modifier Модификатор значка; размер берётся из [dimension].
 * @param dimension Сторона квадрата значка.
 * @param color Цвет линий и заливки; по умолчанию — цвет содержимого окружения.
 */
@Composable
fun PlusIcon(
    modifier: Modifier = Modifier,
    dimension: Dp = 20.dp,
    color: Color = LocalContentColor.current
) {
    Canvas(modifier.size(dimension)) {
        val thickness = 2.dp.toPx()
        // Половина длины луча: линии не доходят до краёв, иначе знак выглядит крупнее
        // соседней стрелки при той же рамке
        val from = size.width * 0.15f
        val to = size.width * 0.85f
        drawLine(
            color = color,
            start = Offset(from, size.height / 2),
            end = Offset(to, size.height / 2),
            strokeWidth = thickness,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2, from),
            end = Offset(size.width / 2, to),
            strokeWidth = thickness,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Значок отправки: залитый треугольник, смотрящий вправо.
 *
 * @param modifier Модификатор значка; размер берётся из [dimension].
 * @param dimension Сторона квадрата значка.
 * @param color Цвет заливки; по умолчанию — цвет содержимого окружения.
 */
@Composable
fun SendIcon(
    modifier: Modifier = Modifier,
    dimension: Dp = 20.dp,
    color: Color = LocalContentColor.current
) {
    Canvas(modifier.size(dimension)) {
        val plane = Path().apply {
            // Вершины заданы долями стороны: значок остаётся тем же при любом размере
            moveTo(size.width * 0.10f, size.height * 0.12f)
            lineTo(size.width * 0.92f, size.height * 0.50f)
            lineTo(size.width * 0.10f, size.height * 0.88f)
            close()
        }
        drawPath(path = plane, color = color)
    }
}

/**
 * Значок закрытия: два диагональных штриха.
 *
 * @param modifier Модификатор значка; размер берётся из [dimension].
 * @param dimension Сторона квадрата значка.
 * @param color Цвет линий; по умолчанию — цвет содержимого окружения.
 */
@Composable
fun CloseIcon(
    modifier: Modifier = Modifier,
    dimension: Dp = 18.dp,
    color: Color = LocalContentColor.current
) {
    Canvas(modifier.size(dimension)) {
        val thickness = 2.dp.toPx()
        val from = size.width * 0.18f
        val to = size.width * 0.82f
        drawLine(color, Offset(from, from), Offset(to, to), thickness, StrokeCap.Round)
        drawLine(color, Offset(to, from), Offset(from, to), thickness, StrokeCap.Round)
    }
}

/**
 * Значок возврата: стрела, смотрящая влево.
 *
 * @param modifier Модификатор значка; размер берётся из [dimension].
 * @param dimension Сторона квадрата значка.
 * @param color Цвет линий; по умолчанию — цвет содержимого окружения.
 */
@Composable
fun BackIcon(
    modifier: Modifier = Modifier,
    dimension: Dp = 20.dp,
    color: Color = LocalContentColor.current
) {
    Canvas(modifier.size(dimension)) {
        val thickness = 2.dp.toPx()
        val middle = size.height / 2
        drawLine(color, Offset(size.width * 0.85f, middle), Offset(size.width * 0.18f, middle), thickness, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.45f, size.height * 0.18f), Offset(size.width * 0.15f, middle), thickness, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.45f, size.height * 0.82f), Offset(size.width * 0.15f, middle), thickness, StrokeCap.Round)
    }
}
