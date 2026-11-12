// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.HourPoint
import io.github.tux4us.miriquidilauncher.weather.WeatherInsights
import io.github.tux4us.miriquidilauncher.weather.WeatherMath
import io.github.tux4us.miriquidilauncher.weather.WeatherReport
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Karten und Hilfen für die Auswertungen aus WeatherInsights (Zusammenfassung, Luftdruck, UV).

/** Uhrzeit; liegt sie nicht am Tag [today], steht der Wochentag davor. */
internal fun timeLabel(time: LocalDateTime, today: LocalDate): String =
    if (time.toLocalDate() == today) {
        time.format(TimeFormat)
    } else {
        time.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()))
    }

/** Zusammenfassung der nächsten 24 Stunden in wenigen Sätzen: Niederschlag, Temperatur, Böen, Wind, UV. */
@Composable
internal fun SummaryCard(report: WeatherReport, hourStart: LocalDateTime) {
    val hours = report.forecast.hours
    val today = hourStart.toLocalDate()
    val dirs = stringArrayResource(R.array.weather_wind_dirs)
    val upcoming = remember(report, hourStart) { hours.filter { !it.time.isBefore(hourStart) }.take(24) }
    val rain = remember(report, hourStart) { WeatherInsights.rainOutlook(hours, hourStart) }
    val extremes = remember(upcoming) { WeatherInsights.extremes(upcoming) }
    val wind = remember(upcoming) { WeatherInsights.windOutlook(upcoming) }
    val uv = remember(report, hourStart) { WeatherInsights.uvWindow(hours, hourStart) }
    SectionCard(stringResource(R.string.weather_section_summary)) {
        val first = rain.first
        if (first == null) {
            Text(stringResource(R.string.weather_sum_dry))
        } else if (rain.state == WeatherInsights.RainState.RAINING_NOW) {
            if (rain.endsWithinHorizon) {
                Text(stringResource(R.string.weather_sum_rain_now_until, timeLabel(first.end, today), oneDecimal(first.totalMm)))
            } else {
                Text(stringResource(R.string.weather_sum_rain_now_ongoing, oneDecimal(first.totalMm)))
            }
        } else {
            if (rain.endsWithinHorizon) {
                Text(
                    stringResource(
                        R.string.weather_sum_rain_later,
                        timeLabel(first.start, today), timeLabel(first.end, today), oneDecimal(first.totalMm),
                    ),
                )
            } else {
                Text(stringResource(R.string.weather_sum_rain_later_ongoing, timeLabel(first.start, today), oneDecimal(first.totalMm)))
            }
        }
        rain.next?.let { Text(stringResource(R.string.weather_sum_rain_next, timeLabel(it.start, today))) }
        extremes?.let { e ->
            Text(
                stringResource(
                    R.string.weather_sum_temp,
                    e.max.temperature.roundToInt().toString(), timeLabel(e.max.time, today),
                    e.min.temperature.roundToInt().toString(), timeLabel(e.min.time, today),
                ),
            )
        }
        wind?.let { w ->
            if (w.strongGust) {
                Text(
                    stringResource(
                        R.string.weather_sum_gust,
                        w.peakGust.windGusts.roundToInt().toString(), timeLabel(w.peakGust.time, today),
                        WeatherMath.beaufort(w.peakGust.windGusts).toString(),
                    ),
                )
            }
            if (w.directionShift) {
                Text(
                    stringResource(
                        R.string.weather_sum_wind_shift,
                        dirs[WeatherMath.compassIndex(w.fromDegrees)], dirs[WeatherMath.compassIndex(w.toDegrees)],
                    ),
                )
            }
        }
        uv?.let { u ->
            Text(
                stringResource(
                    R.string.weather_sum_uv,
                    timeLabel(u.start, today), timeLabel(u.end, today), oneDecimal(u.peakUv), timeLabel(u.peakTime, today),
                ),
            )
        }
        Text(stringResource(R.string.weather_sum_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

/** Luftdruckverlauf der nächsten 24 Stunden mit Tendenz. */
@Composable
internal fun PressureCard(outlook: WeatherInsights.PressureOutlook) {
    val labels = outlook.times.mapIndexed { i, t -> if (i % 6 == 0) t.format(TimeFormat) else "" }
    val title = stringResource(R.string.weather_pressure_chart)
    SectionCard(stringResource(R.string.weather_section_pressure)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        TemperatureChart(
            values = outlook.values,
            labels = labels,
            description = title + ": " + outlook.values.min().roundToInt() + " – " + outlook.values.max().roundToInt(),
            unit = "",
        )
        val change = signed(outlook.changeAhead)
        Text(
            stringResource(
                when (outlook.trend) {
                    WeatherInsights.PressureTrend.RISING -> R.string.weather_pressure_trend_rising
                    WeatherInsights.PressureTrend.STEADY -> R.string.weather_pressure_trend_steady
                    WeatherInsights.PressureTrend.FALLING -> R.string.weather_pressure_trend_falling
                },
                change,
            ),
            fontWeight = FontWeight.Medium,
        )
        outlook.change3hPast?.let { Text(stringResource(R.string.weather_pressure_past, signed(it))) }
        Text(stringResource(R.string.weather_pressure_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

/** UV-Fenster mit Sonnenschutz-Empfehlung und stündlichen Werten ab UV 1. */
@Composable
internal fun UvCard(window: WeatherInsights.UvWindow, hours: List<HourPoint>, today: LocalDate) {
    SectionCard(stringResource(R.string.weather_section_uv)) {
        Text(
            stringResource(
                R.string.weather_uv_window,
                timeLabel(window.start, today), timeLabel(window.end, today),
                oneDecimal(window.peakUv), timeLabel(window.peakTime, today),
            ),
            fontWeight = FontWeight.Medium,
        )
        val relevant = hours.filter { it.uvIndex >= 1.0 }
        if (relevant.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                relevant.forEach { h ->
                    Column(
                        modifier = Modifier.width(52.dp).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(h.time.format(TimeFormat), style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                        Text(oneDecimal(h.uvIndex), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Text(stringResource(R.string.weather_uv_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}
