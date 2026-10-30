// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.calendar.AgendaView
import io.github.tux4us.miriquidilauncher.calendar.CalendarEvent
import io.github.tux4us.miriquidilauncher.feed.FeedText
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Höchstens so viele Termine stehen in der Card; weitere werden als "+n weitere" gezählt. */
private const val MAX_LISTED = 4

private val CardText = Color.White
private val CardTextMuted = Color.White.copy(alpha = 0.72f)
private val DefaultDot = Color(0xFF8AB4F8)

private val TimeColumnWidth = 72.dp

/** Einrückung der Detailzeilen: Punkt (8 dp) und zwei Abstände (je 10 dp) plus Uhrzeitspalte. */
private val DetailIndent = 8.dp + 10.dp + TimeColumnWidth + 10.dp

/**
 * Halbtransparente Glas-Card mit den Terminen von heute (bis 20 Uhr) bzw. morgen (ab 20 Uhr). Tippen öffnet
 * die Tagesansicht der Kalenderseite. Solange die Card im Bild ist, wird [onRefresh] einmal pro Minute
 * aufgerufen, damit sie zur Umschaltzeit und um Mitternacht wechselt.
 */
@Composable
fun AgendaCard(view: AgendaView, onRefresh: () -> Unit, onOpen: (AgendaView) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val allDayLabel = stringResource(R.string.calendar_all_day)
    val ongoingLabel = stringResource(R.string.agenda_ongoing)
    val untitled = stringResource(R.string.calendar_untitled)

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            onRefresh()
        }
    }

    val shape = RoundedCornerShape(24.dp)
    val listed = view.events.take(MAX_LISTED)
    val dayStart = view.day.atStartOfDay(zone).toInstant().toEpochMilli()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape)
            .clickable { onOpen(view) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (view.isToday) R.string.agenda_today else R.string.agenda_tomorrow),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = CardText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dateFormat.format(view.day),
                style = MaterialTheme.typography.labelMedium,
                color = CardTextMuted,
            )
        }
        if (listed.isEmpty()) {
            Text(stringResource(R.string.agenda_none), style = MaterialTheme.typography.bodyMedium, color = CardTextMuted)
        }
        listed.forEach { event ->
            AgendaEntry(
                event = event,
                timeLabel = when {
                    event.allDay -> allDayLabel
                    event.begin < dayStart -> ongoingLabel
                    else -> timeFormat.format(Date(event.begin))
                },
                title = event.title.ifBlank { untitled },
            )
        }
        if (view.events.size > MAX_LISTED) {
            Text(
                text = stringResource(R.string.agenda_more, view.events.size - MAX_LISTED),
                style = MaterialTheme.typography.labelMedium,
                color = CardTextMuted,
            )
        }
    }
}

/** Ein Termin: Zeile mit Farbpunkt, Uhrzeit und Titel, darunter Ort und Beschreibung (falls vorhanden). */
@Composable
private fun AgendaEntry(event: CalendarEvent, timeLabel: String, title: String) {
    // Beschreibungen können HTML enthalten (z. B. aus Google-Kalendern); angezeigt wird nur der Text.
    val description = remember(event.key) { event.description?.let(FeedText::plainText)?.takeIf { it.isNotBlank() } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EventDot(event)
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = CardTextMuted,
                maxLines = 1,
                modifier = Modifier.width(TimeColumnWidth),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Ort und Beschreibung stehen unter dem Titel (eingerückt um Punkt und Uhrzeitspalte).
        Column(Modifier.padding(start = DetailIndent), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            event.location?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = CardText.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = CardTextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Farbpunkt des Termins (Farbe des Termins bzw. seines Kalenders). */
@Composable
private fun EventDot(event: CalendarEvent) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (event.color != 0) Color(event.color or 0xFF000000.toInt()) else DefaultDot),
    )
}