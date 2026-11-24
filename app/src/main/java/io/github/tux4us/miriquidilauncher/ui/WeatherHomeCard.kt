// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.DayPoint
import io.github.tux4us.miriquidilauncher.weather.WeatherMath
import io.github.tux4us.miriquidilauncher.weather.WeatherReport
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CardText = Color.White
private val CardTextMuted = Color.White.copy(alpha = 0.85f)

/**
 * Card der Startseite mit dem aktuellen Wetter des gewählten Ortes: Temperatur, Lage, gefühlte Temperatur, Tageshöchst-
 * und -tiefstwert, Wind und Regenwahrscheinlichkeit. Hinter den Angaben läuft derselbe animierte Himmel wie auf der
 * Wetterseite ([animated], siehe Einstellung der Wetterseite); die Animation läuft nur, solange [visible] gilt und die
 * App im Vordergrund ist. Tippen öffnet die Wetterseite. [onRefresh] ruft alle fünf Minuten die Aktualisierung bei
 * veralteten Daten auf.
 */
@Composable
fun WeatherHomeCard(
    report: WeatherReport?,
    animated: Boolean,
    visible: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(5 * 60_000L)
        }
    }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .glass(shape)
            .clickable(onClick = onOpen),
    ) {
        if (report == null) {
            Text(
                text = stringResource(R.string.home_weather_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = CardTextMuted,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }
        val current = report.forecast.current
        if (animated) {
            WeatherBackground(current, report.place.latitude, visible, Modifier.fillMaxSize())
        }
        // Verlauf von unten, damit der Text auch vor hellem Himmel lesbar bleibt.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
        )
        val codes = stringArrayResource(R.array.weather_codes)
        val description = WeatherMath.weatherCodeIndex(current.weatherCode)?.let { codes[it] } ?: "–"
        val today: DayPoint? = remember(report) {
            val date = report.forecast.localNow().toLocalDate()
            report.forecast.days.firstOrNull { it.date == date } ?: report.forecast.days.firstOrNull()
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = report.place.name,
                style = MaterialTheme.typography.labelLarge,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeatherMath.weatherEmoji(current.weatherCode, current.isDay), fontSize = 44.sp)
                Spacer(Modifier.width(10.dp))
                Text("${current.temperature.roundToInt()}°", fontSize = 44.sp, fontWeight = FontWeight.Light, color = CardText)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(description, style = MaterialTheme.typography.titleMedium, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.weather_feels_like, current.feelsLike.roundToInt().toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = CardTextMuted,
                    )
                }
            }
            val parts = buildList {
                today?.let { add(stringResource(R.string.home_weather_range, it.temperatureMin.roundToInt(), it.temperatureMax.roundToInt())) }
                add(stringResource(R.string.home_weather_wind, current.windSpeed.roundToInt()))
                today?.let { add(stringResource(R.string.home_weather_rain, it.precipitationProbabilityMax)) }
            }
            Text(
                text = parts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CARD_HEIGHT = 168.dp
