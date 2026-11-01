// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.DogForecast
import io.github.tux4us.miriquidilauncher.weather.DogLevel
import io.github.tux4us.miriquidilauncher.weather.DogReason
import io.github.tux4us.miriquidilauncher.weather.DogWeather
import io.github.tux4us.miriquidilauncher.weather.WeatherReport
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Karte „Hunde-Wetter“: Pfoten-/Hitzewarnung, beste Zeiten für den Spaziergang, Zecken. Alles sind Schätzungen. */
@Composable
internal fun DogWeatherCard(report: WeatherReport, now: LocalDateTime) {
    val levels = stringArrayResource(R.array.weather_dog_levels)
    val reasons = stringArrayResource(R.array.weather_dog_reasons)
    val forecast: DogForecast = remember(report, now.hour, now.toLocalDate()) {
        DogWeather.forecast(
            report.forecast.hours, report.forecast.days,
            report.place.latitude, report.place.longitude, report.forecast.utcOffsetSeconds, now,
        )
    }
    SectionCard(stringResource(R.string.weather_section_dog)) {
        val current = forecast.now
        if (current == null) {
            Text(stringResource(R.string.weather_dog_none))
        } else {
            val worst = DogWeather.worst(forecast.hours.filter { it.daylight })
            LevelDot(dogColor(current.level), levels[current.level.ordinal]) {
                Text(
                    stringResource(R.string.weather_dog_now, levels[current.level.ordinal]) +
                        reasonText(current.reasons, reasons),
                )
            }
            val cool = if (current.daylight) current.surfaceC else null
            if (cool != null) {
                Text(
                    stringResource(R.string.weather_dog_surface, current.surfaceC.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            forecast.peakSurface?.let { peak ->
                Text(
                    stringResource(R.string.weather_dog_peak, peak.surfaceC.roundToInt(), peak.time.format(TimeFormat)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (forecast.windows.isEmpty()) {
                Text(stringResource(R.string.weather_dog_no_window))
            } else {
                val title = if (forecast.windowsLevel == DogLevel.GOOD) R.string.weather_dog_windows_good else R.string.weather_dog_windows_caution
                Text(stringResource(title))
                forecast.windows.forEach { w ->
                    Text(
                        stringResource(R.string.weather_dog_window_line, w.start.format(TimeFormat), w.end.format(TimeFormat)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (worst == DogLevel.AVOID) Text(stringResource(R.string.weather_dog_avoid_hint))
        }
        forecast.tick?.let { tick ->
            Text(
                stringResource(
                    if (tick.active) R.string.weather_dog_tick_active else R.string.weather_dog_tick_inactive,
                    tick.maxTempC.roundToInt(),
                ),
            )
        }
        Text(
            stringResource(R.string.weather_dog_note),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

private fun reasonText(reasons: Set<DogReason>, names: Array<String>): String =
    if (reasons.isEmpty()) "" else " (" + reasons.joinToString(", ") { names[it.ordinal] } + ")"

private fun dogColor(level: DogLevel): Color = when (level) {
    DogLevel.GOOD -> Color(0xFF66BB6A)
    DogLevel.CAUTION -> Color(0xFFFFB300)
    DogLevel.AVOID -> Color(0xFFE53935)
}
