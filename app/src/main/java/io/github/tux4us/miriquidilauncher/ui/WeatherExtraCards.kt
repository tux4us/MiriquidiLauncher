// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.DayPoint
import io.github.tux4us.miriquidilauncher.weather.HourPoint
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import io.github.tux4us.miriquidilauncher.weather.SunMath
import io.github.tux4us.miriquidilauncher.weather.WeatherMath
import io.github.tux4us.miriquidilauncher.weather.WeatherReport

private val DialogColor = Color(0xFF141A22)

// region Dialoge -------------------------------------------------------------------------------------

@Composable
internal fun DetailDialog(content: DetailContent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(content.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content.lines.forEach { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_close))
            }
        },
    )
}

@Composable
internal fun ShadowHeightDialog(
    current: ShadowHeights,
    onSave: (Double, Double) -> Boolean,
    onDismiss: () -> Unit,
) {
    var person by remember { mutableStateOf(formatInput(current.personM)) }
    var obj by remember { mutableStateOf(formatInput(current.objectM)) }
    var invalid by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = WeatherText,
        unfocusedTextColor = WeatherText,
        cursorColor = WeatherText,
        focusedLabelColor = WeatherText,
        unfocusedLabelColor = WeatherTextMuted,
        focusedBorderColor = WeatherText,
        unfocusedBorderColor = Color(0x99FFFFFF),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.weather_shadow_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = person, onValueChange = { person = it; invalid = false }, singleLine = true,
                    label = { Text(stringResource(R.string.weather_shadow_height_person)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = obj, onValueChange = { obj = it; invalid = false }, singleLine = true,
                    label = { Text(stringResource(R.string.weather_shadow_height_object)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors,
                )
                if (invalid) Text(stringResource(R.string.weather_shadow_invalid), color = Color(0xFFFF9E9E))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = parseInput(person)
                    val o = parseInput(obj)
                    if (p != null && o != null && onSave(p, o)) onDismiss() else invalid = true
                },
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.weather_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_cancel))
            }
        },
    )
}

private fun formatInput(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)

private fun parseInput(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

internal fun hourDetail(context: Context, h: HourPoint): DetailContent {
    val codes = context.resources.getStringArray(R.array.weather_codes)
    val dirs = context.resources.getStringArray(R.array.weather_wind_dirs)
    val uvLevels = context.resources.getStringArray(R.array.weather_uv_levels)
    val bft = WeatherMath.beaufort(h.windSpeed)
    val description = WeatherMath.weatherCodeIndex(h.weatherCode)?.let { codes[it] } ?: "–"
    val lines = mutableListOf(
        context.getString(R.string.weather_detail_temp, h.temperature.roundToInt().toString(), h.feelsLike.roundToInt().toString()),
        context.getString(R.string.weather_detail_precip, oneDecimal(h.precipitation), h.precipitationProbability),
        context.getString(
            R.string.weather_detail_wind, h.windSpeed.roundToInt().toString(),
            dirs[WeatherMath.compassIndex(h.windDirection)], h.windGusts.roundToInt().toString(), bft,
        ),
        context.getString(R.string.weather_detail_uv, oneDecimal(h.uvIndex), uvLevels[WeatherMath.uvLevel(h.uvIndex)]),
    )
    h.pressure?.let { lines += context.getString(R.string.weather_detail_pressure, it.roundToInt().toString()) }
    if (h.cape > 0) lines += context.getString(R.string.weather_detail_cape, h.cape.roundToInt().toString())
    return DetailContent(context.getString(R.string.weather_detail_hour_title, h.time.format(TimeFormat), description), lines)
}

internal fun dayDetail(context: Context, d: DayPoint, label: String): DetailContent {
    val codes = context.resources.getStringArray(R.array.weather_codes)
    val uvLevels = context.resources.getStringArray(R.array.weather_uv_levels)
    val description = WeatherMath.weatherCodeIndex(d.weatherCode)?.let { codes[it] } ?: "–"
    val lines = mutableListOf(
        context.getString(R.string.weather_detail_minmax, d.temperatureMin.roundToInt().toString(), d.temperatureMax.roundToInt().toString()),
        context.getString(R.string.weather_precip_probability, d.precipitationProbabilityMax),
        context.getString(R.string.weather_detail_wind_max, d.windSpeedMax.roundToInt().toString(), d.windGustsMax.roundToInt().toString()),
        context.getString(R.string.weather_detail_uv, oneDecimal(d.uvIndexMax), uvLevels[WeatherMath.uvLevel(d.uvIndexMax)]),
        context.getString(R.string.weather_detail_sunshine, oneDecimal(d.sunshineMinutes / 60.0)),
    )
    if (d.sunrise != null && d.sunset != null) {
        lines += context.getString(R.string.weather_detail_sun, d.sunrise.format(TimeFormat), d.sunset.format(TimeFormat))
    }
    return DetailContent(context.getString(R.string.weather_detail_hour_title, label, description), lines)
}

// endregion

// region Wind ----------------------------------------------------------------------------------------

@Composable
internal fun WindCard(hours: List<HourPoint>) {
    val dirs = stringArrayResource(R.array.weather_wind_dirs)
    val beaufort = stringArrayResource(R.array.weather_beaufort)
    SectionCard(stringResource(R.string.weather_section_wind)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEach { h ->
                val bft = WeatherMath.beaufort(h.windSpeed)
                val direction = dirs[WeatherMath.compassIndex(h.windDirection)]
                Column(
                    modifier = Modifier
                        .width(64.dp)
                        .padding(vertical = 4.dp)
                        .androidSemantics("${h.time.format(TimeFormat)}: ${beaufort[bft]}, $direction, ${h.windSpeed.roundToInt()} km/h"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(h.time.format(TimeFormat), style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                    // Der Pfeil zeigt in die Richtung, in die der Wind weht (Windrichtung ist die Herkunftsrichtung).
                    Text("↑", modifier = Modifier.rotate(h.windDirection.toFloat() + 180f), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.weather_wind_hour_speed, h.windSpeed.roundToInt().toString()), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.weather_wind_hour_gust, h.windGusts.roundToInt().toString()),
                        style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted,
                    )
                    Text("Bft $bft", style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                }
            }
        }
    }
}

// endregion

// region Sonne ---------------------------------------------------------------------------------------

@Composable
internal fun SunCard(
    report: WeatherReport,
    now: LocalDateTime,
    heights: ShadowHeights,
    onDetail: (DetailContent) -> Unit,
    onEditShadow: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val compass = stringArrayResource(R.array.weather_compass16)
    val elevationLevels = stringArrayResource(R.array.weather_sun_elevation_levels)
    val uvLevels = stringArrayResource(R.array.weather_uv_levels)
    val uvAdvice = stringArrayResource(R.array.weather_uv_advice)
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    val place = report.place
    val forecast = report.forecast
    val today = forecast.days.firstOrNull { it.date == now.toLocalDate() }
    val angles = SunMath.solarAngles(place.latitude, place.longitude, now, forecast.utcOffsetSeconds)

    SectionCard(stringResource(R.string.weather_section_sun)) {
        if (today?.sunrise != null && today.sunset != null) {
            val riseAz = SunMath.sunriseAzimuth(place.latitude, today.date)
            val setAz = 360.0 - riseAz
            Text(stringResource(R.string.weather_sunrise_azimuth, today.sunrise.format(TimeFormat), riseAz.roundToInt().toString(), compass[SunMath.compass16Index(riseAz)]))
            Text(stringResource(R.string.weather_sunset_azimuth, today.sunset.format(TimeFormat), setAz.roundToInt().toString(), compass[SunMath.compass16Index(setAz)]))
        }
        Text(
            stringResource(
                R.string.weather_sun_position,
                oneDecimal(angles.elevationDeg), elevationLevels[SunMath.elevationLevel(angles.elevationDeg)],
                angles.azimuthDeg.roundToInt().toString(), compass[SunMath.compass16Index(angles.azimuthDeg)],
            ),
        )

        Text(stringResource(R.string.weather_shadow_title), style = MaterialTheme.typography.titleSmall)
        val person = SunMath.shadowLength(heights.personM, angles.elevationDeg)
        val obj = SunMath.shadowLength(heights.objectM, angles.elevationDeg)
        if (person == null || obj == null) {
            Text(stringResource(R.string.weather_shadow_none))
        } else {
            Text(stringResource(R.string.weather_shadow_person, oneDecimal2(heights.personM), oneDecimal2(person)))
            Text(stringResource(R.string.weather_shadow_object, oneDecimal2(heights.objectM), oneDecimal2(obj)))
        }
        TextButton(onClick = onEditShadow, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
            Text(stringResource(R.string.weather_shadow_edit))
        }

        if (today != null) {
            Text(stringResource(R.string.weather_sunshine, oneDecimal(today.sunshineMinutes / 60.0)))
        }
        val week = forecast.days.filter { !it.date.isBefore(now.toLocalDate()) }.take(7)
        if (week.size >= 2) {
            Text(stringResource(R.string.weather_sunshine_week), style = MaterialTheme.typography.labelMedium)
            SunshineChart(
                hours = week.map { it.sunshineMinutes / 60.0 },
                dayLabels = week.map { it.date.format(dayFormat) },
                description = stringResource(R.string.weather_sunshine_week),
            )
        }

        // UV-Verlauf des Tages
        Text(stringResource(R.string.weather_uv_hourly_title), style = MaterialTheme.typography.titleSmall)
        val uvHours = forecast.hours.filter { it.time.toLocalDate() == now.toLocalDate() && it.uvIndex >= 0.5 }
        if (uvHours.isEmpty()) {
            Text(stringResource(R.string.weather_uv_none_today))
        } else {
            val peak = uvHours.maxByOrNull { it.uvIndex }!!
            Text(stringResource(R.string.weather_uv_peak, oneDecimal(peak.uvIndex), peak.time.format(TimeFormat)))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                uvHours.forEach { h ->
                    val level = WeatherMath.uvLevel(h.uvIndex)
                    Column(
                        modifier = Modifier
                            .width(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDetail(
                                    DetailContent(
                                        context.getString(R.string.weather_uv_dialog_title, h.time.format(TimeFormat), oneDecimal(h.uvIndex), uvLevels[level]),
                                        listOf(uvAdvice[level], context.getString(R.string.weather_bio_disclaimer)),
                                    ),
                                )
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(h.time.format(TimeFormat), style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                        Text(oneDecimal(h.uvIndex), fontWeight = FontWeight.Medium)
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(levelColor(level, 5)),
                        )
                    }
                }
            }
            if (WeatherMath.uvLevel(peak.uvIndex) >= 2) {
                Text(uvAdvice[WeatherMath.uvLevel(peak.uvIndex)], style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
            }
        }
    }
}

@Composable
internal fun DaylightCard(days: List<DayPoint>, now: LocalDateTime) {
    val today = days.firstOrNull { it.date == now.toLocalDate() }
    val sunrise = today?.sunrise
    val sunset = today?.sunset
    SectionCard(stringResource(R.string.weather_section_daylight)) {
        if (sunrise == null || sunset == null) {
            Text("–")
            return@SectionCard
        }
        val yesterday = days.firstOrNull { it.date == now.toLocalDate().minusDays(1) }
        val previous = if (yesterday?.sunrise != null && yesterday.sunset != null) yesterday.sunrise to yesterday.sunset else null
        val d = SunMath.daylight(sunrise, sunset, previous)
        Text(stringResource(R.string.weather_daylight_duration, d.duration.toHours().toInt(), (d.duration.toMinutes() % 60).toInt()))
        Text(stringResource(R.string.weather_dawn, d.dawn.format(TimeFormat)))
        Text(stringResource(R.string.weather_dusk, d.dusk.format(TimeFormat)))
        d.trendMinutes?.let { t ->
            Text(
                when {
                    t > 0 -> stringResource(R.string.weather_daylight_longer, t.toInt())
                    t < 0 -> stringResource(R.string.weather_daylight_shorter, (-t).toInt())
                    else -> stringResource(R.string.weather_daylight_same)
                },
            )
        }
        val untilSunrise = Duration.between(now, sunrise).toMinutes()
        val untilSunset = Duration.between(now, sunset).toMinutes()
        when {
            untilSunrise > 60 -> Text(stringResource(R.string.weather_light_dark_morning))
            untilSunrise > 0 -> Text(stringResource(R.string.weather_light_sunrise_soon, untilSunrise.toInt()))
        }
        when {
            untilSunset in 1..119 -> Text(stringResource(R.string.weather_light_sunset_soon, untilSunset.toInt()))
            untilSunset <= 0 -> Text(stringResource(R.string.weather_light_dark_evening))
        }
        Text(
            stringResource(
                R.string.weather_golden_hour,
                d.goldenMorningStart.format(TimeFormat), d.goldenMorningEnd.format(TimeFormat),
                d.goldenEveningStart.format(TimeFormat), d.goldenEveningEnd.format(TimeFormat),
            ),
        )
    }
}

// endregion

// region Mond ----------------------------------------------------------------------------------------

@Composable
internal fun MoonCard() {
    val phases = stringArrayResource(R.array.weather_moon_phases)
    val astronomy = stringArrayResource(R.array.weather_moon_astronomy)
    val signs = stringArrayResource(R.array.weather_zodiac_signs)
    val moon = remember { WeatherMath.moon(Instant.now()) }
    val zodiac = remember { WeatherMath.moonZodiacIndex(Instant.now()) }
    val fullLabel = stringResource(R.string.weather_moon_full_label)
    val newLabel = stringResource(R.string.weather_moon_new_label)
    SectionCard(stringResource(R.string.weather_section_moon)) {
        Text(phases[moon.phaseIndex], style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.weather_moon_illumination, moon.illuminationPercent.roundToInt().toString()))
        Text(stringResource(R.string.weather_moon_age, oneDecimal(moon.ageDays)))
        Text(moonEvent(fullLabel, moon.daysToFullMoon))
        Text(moonEvent(newLabel, moon.daysToNewMoon))
        Text(stringResource(R.string.weather_moon_zodiac, signs[zodiac]))
        Text(astronomy[moon.phaseIndex], style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

@Composable
private fun moonEvent(label: String, days: Double): String = when {
    days < 0.5 -> stringResource(R.string.weather_moon_event_today, label)
    days < 1.5 -> stringResource(R.string.weather_moon_event_tomorrow, label)
    else -> stringResource(R.string.weather_moon_event_in_days, label, days.roundToInt())
}

// endregion

private fun oneDecimal2(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)

private fun Modifier.androidSemantics(description: String): Modifier =
    this.then(Modifier.semantics(mergeDescendants = true) { contentDescription = description })
