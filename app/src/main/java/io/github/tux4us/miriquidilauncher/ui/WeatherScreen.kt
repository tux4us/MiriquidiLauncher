// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.AirHour
import io.github.tux4us.miriquidilauncher.weather.BioIndicator
import io.github.tux4us.miriquidilauncher.weather.BioKind
import io.github.tux4us.miriquidilauncher.weather.BioLevel
import io.github.tux4us.miriquidilauncher.weather.Biowetter
import io.github.tux4us.miriquidilauncher.weather.DayPoint
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.HourPoint
import io.github.tux4us.miriquidilauncher.weather.PollenType
import io.github.tux4us.miriquidilauncher.weather.ThunderLevel
import io.github.tux4us.miriquidilauncher.weather.Thunderstorm
import io.github.tux4us.miriquidilauncher.weather.WeatherError
import io.github.tux4us.miriquidilauncher.weather.WeatherMath
import io.github.tux4us.miriquidilauncher.weather.WeatherReport
import io.github.tux4us.miriquidilauncher.weather.WeatherUiState
import io.github.tux4us.miriquidilauncher.weather.WeatherViewModel
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import io.github.tux4us.miriquidilauncher.weather.SunMath

private val CardShape = RoundedCornerShape(20.dp)

// Feste Farben: Die Seite liegt über beliebigen Hintergrundbildern und muss immer gut lesbar sein.
internal val WeatherText = Color.White
internal val WeatherTextMuted = Color(0xFFD0D5DB)
internal val WeatherCard = Color(0xE6141A22)
private val WeatherPageScrim = Color(0x66000000)
private val WeatherErrorColor = Color(0xFFFF9E9E)
internal val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Wetterseite rechts neben dem Launcher. [active] ist `true`, solange die Seite sichtbar ist; dann werden
 * veraltete Daten aktualisiert.
 */
@Composable
fun WeatherScreen(viewModel: WeatherViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(active) {
        if (active) viewModel.onScreenShown()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.useCurrentLocation() else viewModel.onLocationPermissionDenied()
    }
    val requestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    var detail by remember { mutableStateOf<DetailContent?>(null) }
    var editShadow by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    if (editServer) WeatherServerDialog(viewModel, onDismiss = { editServer = false })
    detail?.let { content -> DetailDialog(content, onDismiss = { detail = null }) }
    if (editShadow) {
        ShadowHeightDialog(
            current = state.shadowHeights,
            onSave = viewModel::setShadowHeights,
            onDismiss = { editShadow = false },
        )
    }

    CompositionLocalProvider(LocalContentColor provides WeatherText) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(WeatherPageScrim),
        contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.size(4.dp)) }
        item {
            WeatherHeader(
                state = state,
                onQueryChange = viewModel::setSearchQuery,
                onClearQuery = viewModel::clearSearch,
                onRefresh = viewModel::refresh,
                onUseLocation = requestLocation,
                onServer = { editServer = true },
                onToggleFavorite = { state.place?.let(viewModel::toggleFavorite) },
            )
        }
        if (state.searchQuery.isNotBlank()) {
            item {
                SearchResults(state = state, onSelect = viewModel::selectPlace)
            }
        } else {
            if (state.favorites.isNotEmpty()) {
                item { FavoriteChips(state.favorites, onSelect = viewModel::selectPlace) }
            }
            item { StatusMessage(state, onRequestLocation = requestLocation) }
            val report = state.report
            if (report != null) {
                weatherSections(report, state.shadowHeights, onDetail = { detail = it }, onEditShadow = { editShadow = true })
            }
        }
        item { Spacer(Modifier.size(8.dp)) }
    }
    }
}

// region Kopfbereich ---------------------------------------------------------------------------

@Composable
private fun WeatherHeader(
    state: WeatherUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRefresh: () -> Unit,
    onUseLocation: () -> Unit,
    onServer: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.place?.name ?: stringResource(R.string.weather_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (state.place != null) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        painter = painterResource(if (state.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border),
                        contentDescription = stringResource(
                            if (state.isFavorite) R.string.weather_favorite_remove else R.string.weather_favorite_add,
                        ),
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = WeatherText)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.weather_refresh))
                    }
                }
            }
            IconButton(onClick = onUseLocation) {
                Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.weather_use_location))
            }
            IconButton(onClick = onServer) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.weather_server_title))
            }
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.weather_search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    TextButton(onClick = onClearQuery, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.weather_search_clear)) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = WeatherText,
                unfocusedTextColor = WeatherText,
                cursorColor = WeatherText,
                focusedPlaceholderColor = WeatherTextMuted,
                unfocusedPlaceholderColor = WeatherTextMuted,
                focusedLeadingIconColor = WeatherText,
                unfocusedLeadingIconColor = WeatherText,
                // Fläche und Randlinie zeichnet der Glas-Stil.
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SearchResults(state: WeatherUiState, onSelect: (GeoPlace) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        when {
            state.searching -> Text(stringResource(R.string.weather_searching), style = MaterialTheme.typography.bodyMedium)
            state.searchFailed -> Text(stringResource(R.string.weather_search_failed), color = WeatherErrorColor)
            state.searchResults.isEmpty() && state.searchQuery.trim().length >= 2 ->
                Text(stringResource(R.string.weather_search_no_results), style = MaterialTheme.typography.bodyMedium)
            else -> state.searchResults.forEach { place ->
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(place) }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun FavoriteChips(favorites: List<GeoPlace>, onSelect: (GeoPlace) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        favorites.forEach { place ->
            AssistChip(
                onClick = { onSelect(place) },
                label = { Text(place.name.substringBefore(',')) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_star), null, Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = WeatherCard,
                    labelColor = WeatherText,
                    leadingIconContentColor = WeatherText,
                ),
                border = BorderStroke(1.dp, Color(0x99FFFFFF)),
            )
        }
    }
}

@Composable
private fun StatusMessage(state: WeatherUiState, onRequestLocation: () -> Unit) {
    val padding = Modifier.padding(horizontal = 16.dp)
    Column(padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            state.place == null && state.error == null && !state.loading ->
                Text(stringResource(R.string.weather_no_place), style = MaterialTheme.typography.bodyLarge)
            state.loading && state.report == null ->
                Text(stringResource(R.string.weather_loading), style = MaterialTheme.typography.bodyLarge)
        }
        val error = state.error
        if (error != null) {
            val text = when (error) {
                WeatherError.NETWORK -> R.string.weather_error_network
                WeatherError.DATA -> R.string.weather_error_data
                WeatherError.LOCATION_UNAVAILABLE -> R.string.weather_error_location
                WeatherError.LOCATION_PERMISSION -> R.string.weather_error_permission
            }
            Text(stringResource(text), color = WeatherErrorColor, style = MaterialTheme.typography.bodyMedium)
            if (error == WeatherError.LOCATION_PERMISSION) {
                TextButton(onClick = onRequestLocation, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.weather_permission_location_button)) }
            }
            if (state.showsStaleData) {
                val time = state.report?.let { formatMillis(it.fetchedAtMillis, it.forecast.utcOffsetSeconds) }.orEmpty()
                Text(stringResource(R.string.weather_stale_notice, time), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// endregion

// region Abschnitte ------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.weatherSections(
    report: WeatherReport,
    heights: ShadowHeights,
    onDetail: (DetailContent) -> Unit,
    onEditShadow: () -> Unit,
) {
    val now = report.forecast.localNow()
    val hourStart = now.withMinute(0).withSecond(0).withNano(0)
    val upcoming = report.forecast.hours.filter { !it.time.isBefore(hourStart) }.take(24)

    item { CurrentCard(report) }
    if (upcoming.size >= 2) item { HourlyCard(upcoming, onDetail) }
    if (upcoming.size >= 2) item { WindCard(upcoming) }
    if (report.forecast.days.isNotEmpty()) item { DailyCard(report.forecast.days, now, onDetail) }
    item { ThunderCard(report.forecast.hours, now) }
    item { AirCard(report.air, hourStart) }
    item { BioCard(report, hourStart) }
    item { SunCard(report, now, heights, onDetail, onEditShadow) }
    item { DaylightCard(report.forecast.days, now) }
    item { MoonCard() }
    item { Footer() }
}

/** Inhalt eines Detaildialogs; Titel und Zeilen sind bereits übersetzt. */
internal data class DetailContent(val title: String, val lines: List<String>)

@Composable
internal fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .glass(CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides WeatherText) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CurrentCard(report: WeatherReport) {
    val c = report.forecast.current
    val codes = stringArrayResource(R.array.weather_codes)
    val dirs = stringArrayResource(R.array.weather_wind_dirs)
    val beaufortNames = stringArrayResource(R.array.weather_beaufort)
    val uvLevels = stringArrayResource(R.array.weather_uv_levels)
    val description = WeatherMath.weatherCodeIndex(c.weatherCode)?.let { codes[it] } ?: "–"
    val bft = WeatherMath.beaufort(c.windSpeed)
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WeatherMath.weatherEmoji(c.weatherCode, c.isDay), fontSize = 56.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${c.temperature.roundToInt()}°", fontSize = 56.sp, fontWeight = FontWeight.Light)
                Text(description, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(stringResource(R.string.weather_feels_like, c.feelsLike.roundToInt().toString()))
        Text(
            stringResource(
                R.string.weather_wind_value,
                beaufortNames[bft], dirs[WeatherMath.compassIndex(c.windDirection)],
                c.windSpeed.roundToInt().toString(), bft,
            ),
        )
        Text(stringResource(R.string.weather_gusts_value, c.windGusts.roundToInt().toString()))
        Text(stringResource(R.string.weather_humidity_value, c.humidity))
        Text(stringResource(R.string.weather_pressure_value, c.pressure.roundToInt().toString()))
        Text(stringResource(R.string.weather_precip_value, oneDecimal(c.precipitation)))
        Text(stringResource(R.string.weather_uv_value, oneDecimal(c.uvIndex), uvLevels[WeatherMath.uvLevel(c.uvIndex)]))
        Text(
            stringResource(R.string.weather_updated, formatMillis(report.fetchedAtMillis, report.forecast.utcOffsetSeconds)),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

@Composable
private fun HourlyCard(hours: List<HourPoint>, onDetail: (DetailContent) -> Unit) {
    val context = LocalContext.current
    val temps = hours.map { it.temperature }
    val labels = hours.mapIndexed { i, h -> if (i % 6 == 0) h.time.format(TimeFormat) else "" }
    SectionCard(stringResource(R.string.weather_section_hourly)) {
        Text(stringResource(R.string.weather_chart_temperature), style = MaterialTheme.typography.labelMedium)
        TemperatureChart(
            values = temps,
            labels = labels,
            description = stringResource(R.string.weather_chart_temperature) + ": " +
                temps.min().roundToInt() + "° – " + temps.max().roundToInt() + "°",
        )
        Text(stringResource(R.string.weather_chart_precipitation), style = MaterialTheme.typography.labelMedium)
        val precip = hours.map { it.precipitation }
        PrecipitationChart(
            values = precip,
            description = stringResource(R.string.weather_chart_precipitation) + ": " + oneDecimal(precip.sum()) + " mm",
        )
        Text(stringResource(R.string.weather_section_hours), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEach { h ->
                Column(
                    modifier = Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDetail(hourDetail(context, h)) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(h.time.format(TimeFormat), style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                    Text(WeatherMath.weatherEmoji(h.weatherCode, h.time.hour in 6..20), fontSize = 22.sp)
                    Text("${h.temperature.roundToInt()}°", fontWeight = FontWeight.Medium)
                    Text("${h.precipitationProbability}%", style = MaterialTheme.typography.labelSmall, color = WeatherTextMuted)
                }
            }
        }
    }
}

@Composable
private fun DailyCard(days: List<DayPoint>, now: LocalDateTime, onDetail: (DetailContent) -> Unit) {
    val context = LocalContext.current
    val codes = stringArrayResource(R.array.weather_codes)
    val format = remember { DateTimeFormatter.ofPattern("EEE d.M.", Locale.getDefault()) }
    val today = stringResource(R.string.weather_today)
    SectionCard(stringResource(R.string.weather_section_daily)) {
        days.filter { !it.date.isBefore(now.toLocalDate()) }.take(7).forEach { day ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onDetail(dayDetail(context, day, if (day.date == now.toLocalDate()) today else day.date.format(format))) }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    if (day.date == now.toLocalDate()) today else day.date.format(format),
                    modifier = Modifier.width(84.dp),
                )
                Text(WeatherMath.weatherEmoji(day.weatherCode), fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(WeatherMath.weatherCodeIndex(day.weatherCode)?.let { codes[it] } ?: "–", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        stringResource(R.string.weather_precip_probability, day.precipitationProbabilityMax),
                        style = MaterialTheme.typography.bodySmall,
                        color = WeatherTextMuted,
                    )
                }
                Text("${day.temperatureMin.roundToInt()}° / ${day.temperatureMax.roundToInt()}°", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ThunderCard(hours: List<HourPoint>, now: LocalDateTime) {
    val levels = stringArrayResource(R.array.weather_thunder_levels)
    val forecast = remember(hours, now.hour, now.toLocalDate()) { Thunderstorm.forecast(hours, now, 12) }
    SectionCard(stringResource(R.string.weather_section_thunder)) {
        val peak = forecast.peak
        if (peak == null) {
            Text(stringResource(R.string.weather_thunder_none))
        } else {
            LevelDot(thunderColor(forecast.maxLevel), levels[forecast.maxLevel.ordinal]) {
                Text(
                    stringResource(
                        R.string.weather_thunder_summary,
                        levels[forecast.maxLevel.ordinal], peak.hour.time.format(TimeFormat),
                    ),
                )
            }
            forecast.entries.forEach { e ->
                Text(
                    stringResource(
                        R.string.weather_thunder_line,
                        e.hour.time.format(TimeFormat), levels[e.level.ordinal],
                        e.hour.cape.roundToInt().toString(), e.hour.precipitationProbability,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            stringResource(R.string.weather_thunder_note),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

@Composable
private fun AirCard(air: List<AirHour>, hourStart: LocalDateTime) {
    val aqiLevels = stringArrayResource(R.array.weather_aqi_levels)
    val pollenLevels = stringArrayResource(R.array.weather_pollen_levels)
    val pollenNames = stringArrayResource(R.array.weather_pollen_types)
    val hour = air.firstOrNull { !it.time.isBefore(hourStart) } ?: air.lastOrNull()
    SectionCard(stringResource(R.string.weather_section_air)) {
        if (hour == null) {
            Text(stringResource(R.string.weather_air_unavailable))
            return@SectionCard
        }
        val aqi = WeatherMath.aqiLevel(hour.pm25)
        LevelDot(levelColor(aqi, 5), aqiLevels[aqi]) {
            Text(aqiLevels[aqi], fontWeight = FontWeight.Medium)
        }
        Text(stringResource(R.string.weather_air_pm25, oneDecimal(hour.pm25)))
        Text(stringResource(R.string.weather_air_pm10, oneDecimal(hour.pm10)))
        Text(stringResource(R.string.weather_air_no2, oneDecimal(hour.no2)))
        Text(stringResource(R.string.weather_air_ozone, oneDecimal(hour.ozone)))
        Text(stringResource(R.string.weather_pollen_title), style = MaterialTheme.typography.titleSmall)
        val active = PollenType.values().mapNotNull { type ->
            val grains = hour.pollen[type] ?: return@mapNotNull null
            val level = WeatherMath.pollenLevel(grains)
            if (level == 0) null else Triple(type, level, grains)
        }
        if (active.isEmpty()) {
            Text(stringResource(R.string.weather_pollen_none), style = MaterialTheme.typography.bodySmall)
        } else {
            active.forEach { (type, level, grains) ->
                LevelDot(levelColor(level, 4), pollenLevels[level]) {
                    Text("${pollenNames[type.ordinal]}: ${pollenLevels[level]} (${grains.roundToInt()}/m³)")
                }
            }
        }
    }
}

@Composable
private fun BioCard(report: WeatherReport, hourStart: LocalDateTime) {
    val f = report.forecast
    val result = remember(report) {
        val pressures = f.hours.filter { !it.time.isAfter(hourStart) }.mapNotNull { it.pressure }.takeLast(4)
        val current = f.hours.firstOrNull { it.time == hourStart }
        Biowetter.evaluate(
            pressureHourly = pressures,
            tempC = f.current.temperature,
            relHumidityPct = f.current.humidity,
            windSpeedKmh = f.current.windSpeed,
            cape = current?.cape ?: -1.0,
            weatherCode = f.current.weatherCode,
            precipitationProbability = current?.precipitationProbability ?: 0,
        )
    }
    val levels = stringArrayResource(R.array.weather_bio_levels)
    SectionCard(stringResource(R.string.weather_section_bio)) {
        val overallText = if (result.overall <= BioLevel.LOW) {
            stringResource(R.string.weather_bio_summary_none)
        } else {
            stringResource(R.string.weather_bio_summary, levels[result.overall.ordinal])
        }
        LevelDot(levelColor(result.overall.ordinal, 4), levels[result.overall.ordinal]) {
            Text(overallText, fontWeight = FontWeight.Medium)
        }
        result.indicators.forEach { BioRow(it, levels) }
        Text(
            stringResource(R.string.weather_bio_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

@Composable
private fun BioRow(indicator: BioIndicator, levels: Array<String>) {
    val ordinal = indicator.level.ordinal
    val name = stringResource(
        when (indicator.kind) {
            BioKind.PRESSURE -> R.string.weather_bio_pressure
            BioKind.HUMIDITY -> R.string.weather_bio_humidity
            BioKind.HEAT -> R.string.weather_bio_heat
            BioKind.COLD -> R.string.weather_bio_cold
            BioKind.THERMAL_NEUTRAL -> R.string.weather_bio_thermal_neutral
            BioKind.CAPE -> R.string.weather_bio_cape
        },
    )
    val v = indicator.value
    val valueText = when (indicator.kind) {
        BioKind.PRESSURE -> if (v == null) stringResource(R.string.weather_bio_pressure_unknown)
        else stringResource(R.string.weather_bio_pressure_value, signed(v))
        BioKind.HUMIDITY -> stringResource(R.string.weather_bio_humidity_value, oneDecimal(v ?: 0.0))
        BioKind.HEAT -> stringResource(R.string.weather_bio_heat_value, oneDecimal(v ?: 0.0))
        BioKind.COLD -> stringResource(R.string.weather_bio_cold_value, oneDecimal(v ?: 0.0))
        BioKind.THERMAL_NEUTRAL -> stringResource(R.string.weather_bio_neutral_value)
        BioKind.CAPE -> stringResource(R.string.weather_bio_cape_value, (v ?: 0.0).roundToInt().toString())
    }
    val text = when (indicator.kind) {
        BioKind.PRESSURE -> stringArrayResource(R.array.weather_bio_pressure_text)[ordinal]
        BioKind.HUMIDITY -> stringArrayResource(R.array.weather_bio_humidity_text)[ordinal]
        BioKind.HEAT -> stringArrayResource(R.array.weather_bio_heat_text)[ordinal]
        BioKind.COLD -> stringArrayResource(R.array.weather_bio_cold_text)[ordinal]
        BioKind.THERMAL_NEUTRAL -> stringResource(R.string.weather_bio_neutral_text)
        BioKind.CAPE -> stringArrayResource(R.array.weather_bio_cape_text)[
            when (indicator.thunderLevel) {
                ThunderLevel.HIGH -> 2
                ThunderLevel.MODERATE -> 1
                else -> 0
            },
        ]
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LevelDot(levelColor(ordinal, 4), levels[ordinal]) {
            Text("$name · $valueText", fontWeight = FontWeight.Medium)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

@Composable
private fun Footer() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AttributionLink(R.string.weather_attr_open_meteo, "https://open-meteo.com/") { openWebLink(context, it) }
        AttributionLink(R.string.weather_attr_cams, "https://atmosphere.copernicus.eu/") { openWebLink(context, it) }
        AttributionLink(R.string.weather_attr_osm, "https://www.openstreetmap.org/copyright") { openWebLink(context, it) }
        Text(stringResource(R.string.weather_privacy_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

// endregion

// region Hilfen ------------------------------------------------------------------------------------

/** Farbpunkt plus Text: Die Stufe ist nie nur an der Farbe erkennbar. */
@Composable
internal fun LevelDot(color: Color, contentDescription: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        content()
    }
}

internal fun levelColor(level: Int, max: Int): Color {
    val ratio = if (max == 0) 0f else level.toFloat() / max
    return when {
        ratio <= 0.0f -> Color(0xFF66BB6A)
        ratio <= 0.25f -> Color(0xFFC0CA33)
        ratio <= 0.5f -> Color(0xFFFFB300)
        ratio <= 0.75f -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }
}

private fun thunderColor(level: ThunderLevel): Color = when (level) {
    ThunderLevel.LOW -> Color(0xFF66BB6A)
    ThunderLevel.MODERATE -> Color(0xFFFFB300)
    ThunderLevel.HIGH -> Color(0xFFE53935)
}

internal fun oneDecimal(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

private fun signed(v: Double): String = String.format(Locale.getDefault(), "%+.1f", v)

private fun formatMillis(millis: Long, utcOffsetSeconds: Int): String =
    Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).format(TimeFormat)

// endregion
