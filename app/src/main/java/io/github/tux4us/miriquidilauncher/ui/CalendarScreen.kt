// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.calendar.CalendarEvent
import io.github.tux4us.miriquidilauncher.calendar.CalendarNotice
import io.github.tux4us.miriquidilauncher.calendar.CalendarMath
import io.github.tux4us.miriquidilauncher.calendar.CalendarMode
import io.github.tux4us.miriquidilauncher.calendar.CalendarUiState
import io.github.tux4us.miriquidilauncher.calendar.CalendarViewModel
import io.github.tux4us.miriquidilauncher.feed.FeedText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

// Feste Farben wie auf der Wetterseite: Die Seite liegt über beliebigen Hintergrundbildern.
private val PageScrim = Color(0x66000000)
private val GridLine = Color(0x33FFFFFF)
private val NowLine = Color(0xFFFF5252)
private val DefaultEventColor = Color(0xFF4A90D9)

private val HourHeight = 52.dp
private val GutterWidth = 48.dp
private const val HOURS = 24

/** Platz unten, damit der Home-Button keine Bedienelemente überdeckt. */
private val HomeButtonClearance = 72.dp

/**
 * Kalenderseite mit Tages-, Wochen- und Monatsansicht. [active] ist `true`, solange die Seite sichtbar ist;
 * dann werden die Termine neu gelesen. Ohne Berechtigung `READ_CALENDAR` zeigt die Seite stattdessen einen Hinweis.
 */
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val zone = viewModel.zone
    val firstDay = viewModel.firstDayOfWeek
    val use24h = DateFormat.is24HourFormat(context)
    val timeFormat = remember(use24h, locale) {
        DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
    }
    var selected by remember { mutableStateOf<CalendarEvent?>(null) }
    var deleting by remember { mutableStateOf<CalendarEvent?>(null) }
    var pendingWrite by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(active) {
        if (active) viewModel.refresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refresh()
        val action = pendingWrite
        pendingWrite = null
        if (granted) action?.invoke()
    }
    // Schreibaktionen brauchen WRITE_CALENDAR; er wird erst auf Wunsch angefragt.
    val withWrite: (() -> Unit) -> Unit = { action ->
        if (state.writeGranted) {
            action()
        } else {
            pendingWrite = action
            writeLauncher.launch(Manifest.permission.WRITE_CALENDAR)
        }
    }
    val noticeText = state.notice?.let { stringResource(noticeRes(it)) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            Toast.makeText(context, noticeText, Toast.LENGTH_SHORT).show()
            viewModel.dismissNotice()
        }
    }

    selected?.let { event ->
        EventDialog(
            event = event,
            zone = zone,
            locale = locale,
            timeFormat = timeFormat,
            onDismiss = { selected = null },
            onEdit = { selected = null; withWrite { viewModel.startEdit(event) } },
            onDelete = { selected = null; deleting = event },
        )
    }
    deleting?.let { event ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Color(0xFF141A22),
            titleContentColor = WeatherText,
            textContentColor = WeatherTextMuted,
            title = { Text(stringResource(R.string.calendar_delete_title)) },
            text = { Text(stringResource(R.string.calendar_delete_text, event.title.ifBlank { stringResource(R.string.calendar_untitled) })) },
            confirmButton = {
                TextButton(
                    onClick = { deleting = null; withWrite { viewModel.delete(event.id) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                ) { Text(stringResource(R.string.calendar_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, colors = ButtonDefaults.textButtonColors(contentColor = WeatherTextMuted)) {
                    Text(stringResource(R.string.calendar_cancel))
                }
            },
        )
    }
    state.editor?.let { CalendarEditorDialog(it, viewModel) }

    CompositionLocalProvider(LocalContentColor provides WeatherText) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(PageScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = HomeButtonClearance),
        ) {
            val days = remember(state.date, state.mode, firstDay) {
                CalendarMath.visibleDays(state.date, state.mode, firstDay)
            }
            CalendarHeader(
                title = periodTitle(state, days, locale),
                mode = state.mode,
                loading = state.loading,
                onMode = viewModel::setMode,
                onPrevious = { viewModel.step(-1) },
                onNext = { viewModel.step(1) },
                onToday = viewModel::goToToday,
                onNew = if (state.permissionGranted) ({ withWrite { viewModel.startNew() } }) else null,
            )
            if (!state.permissionGranted) {
                PermissionNotice(
                    onRequest = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                    onOpenSettings = { openAppSettings(context) },
                )
            } else if (state.mode == CalendarMode.Month) {
                MonthView(
                    state = state,
                    firstDay = firstDay,
                    zone = zone,
                    locale = locale,
                    onDay = viewModel::openDay,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TimeGridView(
                    days = days,
                    events = state.events,
                    zone = zone,
                    locale = locale,
                    timeFormat = timeFormat,
                    onEvent = { selected = it },
                    onDayClick = if (state.mode == CalendarMode.Week) viewModel::openDay else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun periodTitle(state: CalendarUiState, days: List<LocalDate>, locale: Locale): String {
    val month = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val full = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val medium = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    return when (state.mode) {
        CalendarMode.Month -> month.format(YearMonth.from(state.date))
        CalendarMode.Week -> "${medium.format(days.first())} – ${medium.format(days.last())}"
        CalendarMode.Day -> full.format(state.date)
    }
}

// region Kopfbereich ---------------------------------------------------------------------------

@Composable
private fun CalendarHeader(
    title: String,
    mode: CalendarMode,
    loading: Boolean,
    onMode: (CalendarMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onNew: (() -> Unit)?,
) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.calendar_previous))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = WeatherText)
            }
            IconButton(onClick = onNext) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.calendar_next))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarMode.entries.forEach { entry ->
                FilterChip(
                    selected = entry == mode,
                    onClick = { onMode(entry) },
                    label = {
                        Text(
                            stringResource(
                                when (entry) {
                                    CalendarMode.Day -> R.string.calendar_mode_day
                                    CalendarMode.Week -> R.string.calendar_mode_week
                                    CalendarMode.Month -> R.string.calendar_mode_month
                                },
                            ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = WeatherCard,
                        labelColor = WeatherText,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onToday,
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.calendar_today)) }
            if (onNew != null) {
                TextButton(
                    onClick = onNew,
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                ) { Text(stringResource(R.string.calendar_new)) }
            }
        }
    }
}

@Composable
private fun PermissionNotice(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.calendar_permission_text),
            style = MaterialTheme.typography.bodyMedium,
            color = WeatherTextMuted,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.calendar_permission_action)) }
        // Nach zweimaliger Ablehnung zeigt das System den Dialog nicht mehr; dann hilft nur die App-Info.
        TextButton(
            onClick = onOpenSettings,
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherTextMuted),
        ) { Text(stringResource(R.string.calendar_permission_settings)) }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent)
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
    }
}

// endregion

// region Monatsansicht -------------------------------------------------------------------------

@Composable
private fun MonthView(
    state: CalendarUiState,
    firstDay: java.time.DayOfWeek,
    zone: ZoneId,
    locale: Locale,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = remember(state.date, firstDay) { CalendarMath.monthGrid(YearMonth.from(state.date), firstDay) }
    val today = LocalDate.now()
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            grid.first().forEach { day ->
                Text(
                    text = day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = WeatherTextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
        }
        grid.forEach { week ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                week.forEach { day ->
                    val events = remember(state.events, day, zone) { CalendarMath.eventsOn(state.events, day, zone) }
                    MonthCell(
                        day = day,
                        inMonth = day.month == state.date.month,
                        isToday = day == today,
                        events = events,
                        onClick = { onDay(day) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    events: List<CalendarEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (inMonth) WeatherCard else WeatherCard.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        // Platz für die Tageszahl (22 dp), der Rest in Zeilen zu je 14 dp.
        val capacity = ((maxHeight - 22.dp) / 14.dp).toInt().coerceAtLeast(0)
        val overflow = events.size > capacity
        val shown = if (overflow) (capacity - 1).coerceAtLeast(0) else events.size
        Column {
            DayNumber(day, isToday)
            events.take(shown).forEach { EventChip(it) }
            if (overflow) {
                Text(
                    text = "+${events.size - shown}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = WeatherTextMuted,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun DayNumber(day: LocalDate, isToday: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (isToday) MaterialTheme.colorScheme.onPrimary else WeatherText,
        )
    }
}

// endregion

// region Tages- und Wochenansicht --------------------------------------------------------------

@Composable
private fun TimeGridView(
    days: List<LocalDate>,
    events: List<CalendarEvent>,
    zone: ZoneId,
    locale: Locale,
    timeFormat: DateTimeFormatter,
    onEvent: (CalendarEvent) -> Unit,
    onDayClick: ((LocalDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val today = LocalDate.now()
    // Beim ersten Anzeigen etwa zur aktuellen Uhrzeit (heute) bzw. 7 Uhr scrollen; danach bleibt die Stellung.
    val initialScroll = remember {
        val startHour = if (days.contains(today)) (LocalTime.now(zone).hour - 1).coerceAtLeast(0) else 7
        with(density) { (HourHeight * startHour).roundToPx() }
    }
    val scroll = rememberScrollState(initialScroll)
    val allDay = remember(events, days, zone) {
        days.map { day -> CalendarMath.eventsOn(events, day, zone).filter { it.allDay } }
    }
    val noEvents = remember(events, days, zone) {
        days.none { day -> CalendarMath.eventsOn(events, day, zone).isNotEmpty() }
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = GutterWidth)) {
            days.forEach { day ->
                DayHeader(
                    day = day,
                    isToday = day == today,
                    locale = locale,
                    onClick = onDayClick?.let { handler -> { handler(day) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(start = GutterWidth, bottom = 4.dp)) {
                allDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        list.take(MAX_ALL_DAY_CHIPS).forEach { EventChip(it, onClick = { onEvent(it) }) }
                        if (list.size > MAX_ALL_DAY_CHIPS) {
                            Text(
                                text = "+${list.size - MAX_ALL_DAY_CHIPS}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = WeatherTextMuted,
                                modifier = Modifier.padding(start = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        if (noEvents) {
            Text(
                text = stringResource(R.string.calendar_no_events),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
                modifier = Modifier.padding(start = GutterWidth, top = 2.dp, bottom = 4.dp),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            Column(Modifier.width(GutterWidth)) {
                for (hour in 0 until HOURS) {
                    Box(Modifier.height(HourHeight)) {
                        if (hour > 0) {
                            Text(
                                text = timeFormat.format(LocalTime.of(hour, 0)),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = WeatherTextMuted,
                                maxLines = 1,
                                modifier = Modifier
                                    .offset(y = (-7).dp)
                                    .padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
            days.forEach { day ->
                DayColumn(
                    day = day,
                    isToday = day == today,
                    events = events,
                    zone = zone,
                    timeFormat = timeFormat,
                    showTimes = days.size == 1,
                    onEvent = onEvent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val MAX_ALL_DAY_CHIPS = 3

@Composable
private fun DayHeader(
    day: LocalDate,
    isToday: Boolean,
    locale: Locale,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
            style = MaterialTheme.typography.labelSmall,
            color = WeatherTextMuted,
            maxLines = 1,
        )
        DayNumber(day, isToday)
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    isToday: Boolean,
    events: List<CalendarEvent>,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
    showTimes: Boolean,
    onEvent: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placed = remember(events, day, zone) { CalendarMath.layoutDay(events, day, zone) }
    BoxWithConstraints(
        modifier = modifier
            .height(HourHeight * HOURS)
            .background(if (isToday) Color(0x14FFFFFF) else Color.Transparent),
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val hourPx = HourHeight.toPx()
            for (hour in 0..HOURS) {
                drawLine(GridLine, Offset(0f, hour * hourPx), Offset(size.width, hour * hourPx), strokeWidth = 1f)
            }
            drawLine(GridLine, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
        }
        placed.forEach { item ->
            val columnWidth = width / item.columns
            val height = HourHeight * ((item.endMinute - item.startMinute) / 60f)
            val color = eventColor(item.event)
            val textColor = contentColorFor(color)
            Column(
                modifier = Modifier
                    .offset(x = columnWidth * item.column, y = HourHeight * (item.startMinute / 60f))
                    .width(columnWidth - 1.dp)
                    .height(height - 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
                    .clickable { onEvent(item.event) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                Text(
                    text = item.event.title.ifBlank { stringResource(R.string.calendar_untitled) },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = if (height >= 44.dp) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showTimes && height >= 44.dp) {
                    val from = Instant.ofEpochMilli(item.event.begin).atZone(zone).toLocalTime()
                    val to = Instant.ofEpochMilli(item.event.end).atZone(zone).toLocalTime()
                    Text(
                        text = "${timeFormat.format(from)} – ${timeFormat.format(to)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = textColor.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (isToday) {
            val now = LocalTime.now(zone)
            Box(
                Modifier
                    .offset(y = HourHeight * ((now.hour * 60 + now.minute) / 60f))
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(NowLine),
            )
        }
    }
}

// endregion

// region Gemeinsame Bausteine ------------------------------------------------------------------

private fun eventColor(event: CalendarEvent): Color =
    if (event.color == 0) DefaultEventColor else Color(event.color or 0xFF000000.toInt())

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.6f) Color.Black else Color.White

/** Einzeiliger farbiger Streifen mit dem Titel eines Termins. Ohne [onClick] ist er nicht antippbar. */
@Composable
private fun EventChip(event: CalendarEvent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val background = eventColor(event)
    Text(
        text = event.title.ifBlank { stringResource(R.string.calendar_untitled) },
        color = contentColorFor(background),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 3.dp),
    )
}

@Composable
private fun EventDialog(
    event: CalendarEvent,
    zone: ZoneId,
    locale: Locale,
    timeFormat: DateTimeFormatter,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val launchFailed = stringResource(R.string.launch_failed)
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val allDayLabel = stringResource(R.string.calendar_all_day)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(event.title.ifBlank { stringResource(R.string.calendar_untitled) }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(describeTime(event, zone, dateFormat, timeFormat, allDayLabel))
                event.location?.let { Text(it, color = WeatherTextMuted) }
                val description = remember(event.key) { event.description?.let(FeedText::plainText)?.takeIf { it.isNotBlank() } }
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                event.calendarName?.let { Text(it, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                        Text(stringResource(R.string.calendar_edit))
                    }
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                        Text(stringResource(R.string.calendar_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!openInCalendar(context, event)) {
                        Toast.makeText(context, launchFailed, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.calendar_open)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherTextMuted),
            ) { Text(stringResource(R.string.calendar_close)) }
        },
    )
}

/** Zeitangabe eines Termins als Text, z. B. "5. Okt. 2026 · 09:00 – 10:30" oder "Ganztägig". */
private fun describeTime(
    event: CalendarEvent,
    zone: ZoneId,
    dateFormat: DateTimeFormatter,
    timeFormat: DateTimeFormatter,
    allDayLabel: String,
): String {
    val first = CalendarMath.firstDay(event, zone)
    val last = CalendarMath.lastDay(event, zone)
    if (event.allDay) {
        val range = if (first == last) dateFormat.format(first) else "${dateFormat.format(first)} – ${dateFormat.format(last)}"
        return "$range · $allDayLabel"
    }
    val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
    val end = Instant.ofEpochMilli(event.end).atZone(zone)
    return if (first == last) {
        "${dateFormat.format(first)} · ${timeFormat.format(begin)} – ${timeFormat.format(end)}"
    } else {
        "${dateFormat.format(begin)} ${timeFormat.format(begin)} – ${dateFormat.format(end)} ${timeFormat.format(end)}"
    }
}

/** Öffnet den Termin in der Kalender-App des Geräts. `false`, wenn keine App den Termin anzeigen kann. */
private fun openInCalendar(context: Context, event: CalendarEvent): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
    return try {
        context.startActivity(intent)
        true
    } catch (ignored: ActivityNotFoundException) {
        false
    } catch (ignored: SecurityException) {
        false
    }
}

// endregion
private fun noticeRes(notice: CalendarNotice): Int = when (notice) {
    CalendarNotice.SAVED -> R.string.calendar_notice_saved
    CalendarNotice.DELETED -> R.string.calendar_notice_deleted
    CalendarNotice.SAVE_FAILED -> R.string.calendar_notice_save_failed
    CalendarNotice.DELETE_FAILED -> R.string.calendar_notice_delete_failed
    CalendarNotice.LOAD_FAILED -> R.string.calendar_notice_load_failed
    CalendarNotice.CALENDAR_CREATED -> R.string.calendar_notice_calendar_created
    CalendarNotice.CALENDAR_CREATE_FAILED -> R.string.calendar_notice_calendar_failed
}
