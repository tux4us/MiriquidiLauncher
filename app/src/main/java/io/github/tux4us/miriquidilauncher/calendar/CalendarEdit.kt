// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Wiederholung beim Bearbeiten. [KEEP] belässt eine vorhandene Regel, die die App nicht darstellen kann. */
enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, KEEP }

enum class DraftError { END_BEFORE_START, NO_CALENDAR }

/** Eingabestand des Termin-Editors. Zeiten sind Ortszeit in [zoneId]; bei ganztägigen Terminen zählen nur die Tage. */
data class EventDraft(
    val eventId: Long? = null,
    val calendarId: Long = -1L,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val zoneId: String,
    val repeat: Repeat = Repeat.NONE,
    /** Ursprüngliche Regel, solange [repeat] `KEEP` ist. */
    val keepRule: String? = null,
    /** Erinnerung in Minuten vor dem Beginn; `null` = keine. */
    val reminderMinutes: Int? = null,
)

/** Werte für den Kalender-Provider. Serientermine tragen [duration] statt [dtEnd]. */
data class EventValues(
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val timeZone: String,
    val allDay: Boolean,
    val rrule: String?,
)

/** Ein gespeicherter Termin aus der Tabelle `Events` (nicht ein einzelnes Vorkommen). */
data class EventDetails(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    val description: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val timeZone: String?,
    val allDay: Boolean,
    val rrule: String?,
    val reminderMinutes: Int?,
)

/** Ein Kalender, in den die App Termine schreiben darf. */
data class WritableCalendar(val id: Long, val name: String, val account: String, val accountType: String, val color: Int)

/** Reine Umrechnungen für das Anlegen und Bearbeiten von Terminen. Ohne Android-Klassen, auf der JVM testbar. */
object CalendarEdit {
    /** Auswahl der Erinnerungen in Minuten vor dem Beginn; `null` = keine. */
    val REMINDER_CHOICES: List<Int?> = listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440)

    const val MAX_TITLE = 300
    const val MAX_TEXT = 4000

    fun newDraft(date: LocalDate, now: LocalTime, zone: ZoneId, calendarId: Long): EventDraft {
        val hour = minOf(now.hour + 1, 22)
        val start = LocalTime.of(hour, 0)
        return EventDraft(
            calendarId = calendarId,
            startDate = date,
            startTime = start,
            endDate = date,
            endTime = start.plusHours(1),
            zoneId = zone.id,
        )
    }

    private fun zoneOf(id: String?): ZoneId = try {
        if (id.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(id)
    } catch (e: DateTimeException) {
        ZoneId.systemDefault()
    }

    private fun startMillis(d: EventDraft): Long =
        LocalDateTime.of(d.startDate, d.startTime).atZone(zoneOf(d.zoneId)).toInstant().toEpochMilli()

    private fun endMillis(d: EventDraft): Long =
        LocalDateTime.of(d.endDate, d.endTime).atZone(zoneOf(d.zoneId)).toInstant().toEpochMilli()

    fun validate(d: EventDraft): DraftError? = when {
        d.calendarId < 0 -> DraftError.NO_CALENDAR
        d.allDay && d.endDate.isBefore(d.startDate) -> DraftError.END_BEFORE_START
        !d.allDay && endMillis(d) <= startMillis(d) -> DraftError.END_BEFORE_START
        else -> null
    }

    fun rruleOf(repeat: Repeat, keepRule: String?): String? = when (repeat) {
        Repeat.NONE -> null
        Repeat.DAILY -> "FREQ=DAILY"
        Repeat.WEEKLY -> "FREQ=WEEKLY"
        Repeat.MONTHLY -> "FREQ=MONTHLY"
        Repeat.YEARLY -> "FREQ=YEARLY"
        Repeat.KEEP -> keepRule
    }

    /** Erkennt einfache Regeln (`FREQ=…` ohne weitere Teile); alles andere bleibt unverändert ([Repeat.KEEP]). */
    fun repeatOf(rrule: String?): Repeat {
        val rule = rrule?.trim()?.removePrefix("RRULE:").orEmpty()
        return when (rule) {
            "" -> Repeat.NONE
            "FREQ=DAILY" -> Repeat.DAILY
            "FREQ=WEEKLY" -> Repeat.WEEKLY
            "FREQ=MONTHLY" -> Repeat.MONTHLY
            "FREQ=YEARLY" -> Repeat.YEARLY
            else -> Repeat.KEEP
        }
    }

    fun toValues(d: EventDraft): EventValues {
        val rrule = rruleOf(d.repeat, d.keepRule)?.takeIf { it.isNotBlank() }
        if (d.allDay) {
            val start = d.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = d.endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val days = ChronoUnit.DAYS.between(d.startDate, d.endDate) + 1
            return if (rrule != null) {
                EventValues(start, null, "P${days}D", "UTC", true, rrule)
            } else {
                EventValues(start, end, null, "UTC", true, null)
            }
        }
        val start = startMillis(d)
        val end = endMillis(d)
        val zone = zoneOf(d.zoneId).id
        return if (rrule != null) {
            EventValues(start, null, "P${(end - start) / 1000}S", zone, false, rrule)
        } else {
            EventValues(start, end, null, zone, false, null)
        }
    }

    /** Dauer in Millisekunden aus `P1D`, `PT1H30M`, `P1W`, `P3600S` oder `null` bei unbekanntem Format. */
    fun parseDuration(text: String?): Long? {
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:(\\d+)S)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(text?.trim().orEmpty()) ?: return null
        if (text!!.trim() == "P") return null
        val (w, d, s0, h, min, s) = m.destructured
        fun n(v: String) = v.toLongOrNull() ?: 0L
        val seconds = n(w) * 604800 + n(d) * 86400 + n(s0) + n(h) * 3600 + n(min) * 60 + n(s)
        return seconds * 1000
    }

    /** Eingabestand aus einem gespeicherten Termin. */
    fun draftFrom(e: EventDetails): EventDraft {
        val repeat = repeatOf(e.rrule)
        val keep = if (repeat == Repeat.KEEP) e.rrule?.trim()?.removePrefix("RRULE:") else null
        val lengthMillis = e.dtEnd?.let { it - e.dtStart } ?: parseDuration(e.duration)
        val base = EventDraft(
            eventId = e.id,
            calendarId = e.calendarId,
            title = e.title,
            location = e.location,
            description = e.description,
            allDay = e.allDay,
            startDate = LocalDate.now(),
            startTime = LocalTime.of(9, 0),
            endDate = LocalDate.now(),
            endTime = LocalTime.of(10, 0),
            zoneId = zoneOf(e.timeZone.takeIf { !e.allDay }).id,
            repeat = repeat,
            keepRule = keep,
            reminderMinutes = e.reminderMinutes,
        )
        if (e.allDay) {
            val start = Instant.ofEpochMilli(e.dtStart).atZone(ZoneOffset.UTC).toLocalDate()
            val endExclusive = Instant.ofEpochMilli(e.dtStart + (lengthMillis ?: 86_400_000L)).atZone(ZoneOffset.UTC).toLocalDate()
            val end = endExclusive.minusDays(1).let { if (it.isBefore(start)) start else it }
            return base.copy(startDate = start, endDate = end, zoneId = ZoneId.systemDefault().id)
        }
        val zone = zoneOf(base.zoneId)
        val start = Instant.ofEpochMilli(e.dtStart).atZone(zone)
        val end = Instant.ofEpochMilli(e.dtStart + (lengthMillis ?: 3_600_000L)).atZone(zone)
        return base.copy(startDate = start.toLocalDate(), startTime = start.toLocalTime().truncatedTo(ChronoUnit.MINUTES), endDate = end.toLocalDate(), endTime = end.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
    }

    /** Wechsel zwischen ganztägig und zeitlich; die Enddaten bleiben gültig. */
    fun withEndFixed(d: EventDraft): EventDraft {
        if (d.allDay) return if (d.endDate.isBefore(d.startDate)) d.copy(endDate = d.startDate) else d
        val start = LocalDateTime.of(d.startDate, d.startTime)
        val end = LocalDateTime.of(d.endDate, d.endTime)
        return if (end.isAfter(start)) d else {
            val fixed = start.plusHours(1)
            d.copy(endDate = fixed.toLocalDate(), endTime = fixed.toLocalTime())
        }
    }

    /** Neuer Beginn mit unveränderter Länge: Das Ende wandert mit. */
    fun moveStart(d: EventDraft, newDate: LocalDate, newTime: LocalTime): EventDraft {
        if (d.allDay) {
            val days = ChronoUnit.DAYS.between(d.startDate, d.endDate).coerceAtLeast(0)
            return d.copy(startDate = newDate, endDate = newDate.plusDays(days))
        }
        val length = java.time.Duration.between(LocalDateTime.of(d.startDate, d.startTime), LocalDateTime.of(d.endDate, d.endTime))
            .let { if (it.isNegative || it.isZero) java.time.Duration.ofHours(1) else it }
        val end = LocalDateTime.of(newDate, newTime).plus(length)
        return d.copy(startDate = newDate, startTime = newTime, endDate = end.toLocalDate(), endTime = end.toLocalTime())
    }
}
