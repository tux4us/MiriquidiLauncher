// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Liniendiagramm der Temperatur. [labels] enthält pro Wert eine Beschriftung der x-Achse (leer = keine);
 * [description] ist der Text für Screenreader.
 */
@Composable
fun TemperatureChart(
    values: List<Double>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val measurer = rememberTextMeasurer()
    val line = WeatherText
    val grid = Color(0x33FFFFFF)
    val textColor = WeatherTextMuted
    val labelStyle = TextStyle(color = textColor, fontSize = 11.sp)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics { contentDescription = description },
    ) {
        val left = 30.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val min = values.min()
        val max = values.max()
        val span = (max - min).coerceAtLeast(1.0)
        fun x(i: Int) = left + (right - left) * i / (values.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - min) / span)).toFloat()

        drawLine(grid, Offset(left, top), Offset(right, top), 1.dp.toPx())
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        val maxText = measurer.measure("${max.roundToInt()}°", labelStyle)
        val minText = measurer.measure("${min.roundToInt()}°", labelStyle)
        drawText(maxText, topLeft = Offset(0f, top - maxText.size.height / 2f))
        drawText(minText, topLeft = Offset(0f, bottom - minText.size.height / 2f))

        val path = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        labels.forEachIndexed { i, text ->
            if (text.isNotEmpty() && i < values.size) {
                val m = measurer.measure(text, labelStyle)
                val px = (x(i) - m.size.width / 2f).coerceIn(left - 10.dp.toPx(), size.width - m.size.width)
                drawText(m, topLeft = Offset(px, bottom + 3.dp.toPx()))
            }
        }
    }
}

/** Säulendiagramm für Niederschlag in mm je Stunde; die Skala reicht mindestens bis 1 mm. */
@Composable
fun PrecipitationChart(
    values: List<Double>,
    description: String,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xCCFFFFFF),
) {
    if (values.isEmpty()) return
    val grid = Color(0x33FFFFFF)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = description },
    ) {
        val left = 30.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        val top = 2.dp.toPx()
        val max = values.max().coerceAtLeast(1.0)
        val slot = (right - left) / values.size
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v > 0.0) {
                val h = ((bottom - top) * (v / max)).toFloat().coerceAtLeast(1.5.dp.toPx())
                drawRect(
                    color = barColor,
                    topLeft = Offset(left + slot * i + slot * 0.15f, bottom - h),
                    size = Size(slot * 0.7f, h),
                )
            }
        }
    }
}

/** Säulen für die Sonnenscheindauer je Tag in Stunden (Skala bis 16 h), Beschriftung mit Tag und Stundenzahl. */
@Composable
fun SunshineChart(
    hours: List<Double>,
    dayLabels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (hours.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = WeatherTextMuted, fontSize = 11.sp)
    val valueStyle = TextStyle(color = WeatherText, fontSize = 11.sp)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .semantics { contentDescription = description },
    ) {
        val labelH = 16.dp.toPx()
        val valueH = 16.dp.toPx()
        val barArea = size.height - labelH - valueH
        val slot = size.width / hours.size
        hours.forEachIndexed { i, h ->
            val frac = (h / 16.0).coerceIn(0.0, 1.0).toFloat()
            val barH = (barArea * frac).coerceAtLeast(2.dp.toPx())
            val barW = (slot * 0.5f).coerceAtMost(28.dp.toPx())
            val cx = slot * i + slot / 2f
            drawRect(
                color = Color(0xCCFFFFFF),
                topLeft = Offset(cx - barW / 2f, valueH + barArea - barH),
                size = Size(barW, barH),
            )
            val value = measurer.measure("${h.roundToInt()}h", valueStyle)
            drawText(value, topLeft = Offset(cx - value.size.width / 2f, valueH + barArea - barH - value.size.height - 1.dp.toPx()))
            val label = measurer.measure(dayLabels.getOrElse(i) { "" }, labelStyle)
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, valueH + barArea + 2.dp.toPx()))
        }
    }
}
