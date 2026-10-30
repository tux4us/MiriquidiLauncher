// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Liniendiagramm des Kursverlaufs. [values] sind die Kurse in zeitlicher Reihenfolge, [maxLabel] und [minLabel]
 * beschriften den höchsten und niedrigsten Wert, [startLabel] und [endLabel] den Anfang und das Ende des Zeitraums.
 * [description] ist der Text für Screenreader. Mit weniger als zwei Werten wird nichts gezeichnet.
 */
@Composable
fun PriceChart(
    values: List<Double>,
    maxLabel: String,
    minLabel: String,
    startLabel: String,
    endLabel: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val measurer = rememberTextMeasurer()
    val line = WeatherText
    val grid = Color(0x33FFFFFF)
    val labelStyle = TextStyle(color = WeatherTextMuted, fontSize = 11.sp)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .semantics { contentDescription = description },
    ) {
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 36.dp.toPx()
        val min = values.min()
        val max = values.max()
        val span = (max - min).coerceAtLeast(maxOf(max * 1e-6, 1e-9))
        fun x(i: Int): Float = left + (right - left) * i / (values.size - 1)
        fun y(v: Double): Float = (bottom - (bottom - top) * ((v - min) / span)).toFloat()

        drawLine(grid, Offset(left, top), Offset(right, top), 1.dp.toPx())
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        val maxText = measurer.measure(maxLabel, labelStyle)
        drawText(maxText, topLeft = Offset(left, top - maxText.size.height - 1.dp.toPx()))
        val minText = measurer.measure(minLabel, labelStyle)
        drawText(minText, topLeft = Offset(left, bottom + 2.dp.toPx()))

        val rowY = size.height - 16.dp.toPx()
        val startText = measurer.measure(startLabel, labelStyle)
        drawText(startText, topLeft = Offset(left, rowY))
        val endText = measurer.measure(endLabel, labelStyle)
        drawText(endText, topLeft = Offset(right - endText.size.width, rowY))

        val path = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(x(values.lastIndex), y(values.last())))
    }
}
