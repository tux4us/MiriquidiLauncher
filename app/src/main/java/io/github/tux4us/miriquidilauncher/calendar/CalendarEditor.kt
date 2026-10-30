// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Schreibt Termine in den Kalender-Provider des Geräts. Das funktioniert für lokale Kalender und für Konten-Kalender,
 * die eine Sync-App (z. B. DAVx5 für Nextcloud) in den Provider einspielt; der Abgleich mit dem Server ist deren Sache.
 * Es findet kein Netzwerkzugriff statt.
 */
class CalendarEditor(context: Context) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean = granted(Manifest.permission.WRITE_CALENDAR) && granted(Manifest.permission.READ_CALENDAR)

    /** Kalender mit Schreibrecht (mindestens „Mitwirkender“). */
    suspend fun writableCalendars(): List<WritableCalendar> = withContext(Dispatchers.IO) {
        val out = ArrayList<WritableCalendar>()
        try {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                ),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(WritableCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4)))
                }
            }
        } catch (e: RuntimeException) {
            out.clear()
        }
        out
    }

    /** Der gespeicherte Termin (bei Serien der Serieneintrag) samt erster Erinnerung; `null`, wenn nicht lesbar. */
    suspend fun details(eventId: Long): EventDetails? = withContext(Dispatchers.IO) {
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val base = resolver.query(
                uri,
                arrayOf(
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.DURATION,
                    CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.RRULE,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                EventDetails(
                    id = eventId,
                    calendarId = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    location = c.getString(2).orEmpty(),
                    description = c.getString(3).orEmpty(),
                    dtStart = c.getLong(4),
                    dtEnd = if (c.isNull(5)) null else c.getLong(5),
                    duration = c.getString(6),
                    timeZone = c.getString(7),
                    allDay = c.getInt(8) != 0,
                    rrule = c.getString(9),
                    reminderMinutes = null,
                )
            } ?: return@withContext null
            base.copy(reminderMinutes = firstReminder(eventId))
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun firstReminder(eventId: Long): Int? =
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            var found: Int? = null
            while (c.moveToNext() && found == null) {
                val minutes = c.getInt(0)
                // „Standard“ des Kalenders (-1) lässt sich hier nicht darstellen und wird als keine Erinnerung angezeigt.
                if (minutes >= 0) found = minutes
            }
            found
        }

    /** Legt den Termin an oder ändert ihn. Liefert die Termin-Id oder `null` bei einem Fehler. */
    suspend fun save(draft: EventDraft): Long? = withContext(Dispatchers.IO) {
        try {
            val v = CalendarEdit.toValues(draft)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, draft.title.trim().take(CalendarEdit.MAX_TITLE))
                put(CalendarContract.Events.EVENT_LOCATION, draft.location.trim().take(CalendarEdit.MAX_TEXT))
                put(CalendarContract.Events.DESCRIPTION, draft.description.take(CalendarEdit.MAX_TEXT))
                put(CalendarContract.Events.DTSTART, v.dtStart)
                put(CalendarContract.Events.EVENT_TIMEZONE, v.timeZone)
                put(CalendarContract.Events.ALL_DAY, if (v.allDay) 1 else 0)
                if (v.rrule != null) {
                    put(CalendarContract.Events.RRULE, v.rrule)
                    put(CalendarContract.Events.DURATION, v.duration)
                    putNull(CalendarContract.Events.DTEND)
                } else {
                    put(CalendarContract.Events.DTEND, v.dtEnd)
                    putNull(CalendarContract.Events.RRULE)
                    putNull(CalendarContract.Events.DURATION)
                }
            }
            val id: Long
            if (draft.eventId == null) {
                values.put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@withContext null
                id = ContentUris.parseId(uri)
            } else {
                id = draft.eventId
                val rows = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null)
                if (rows < 1) return@withContext null
            }
            resolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
            draft.reminderMinutes?.let { minutes ->
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
            }
            id
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Löscht den Termin; bei Serien die gesamte Serie. */
    suspend fun delete(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Legt einen lokalen Kalender an (ohne Konto, nur auf diesem Gerät). Liefert die Kalender-Id oder `null`. */
    suspend fun createLocalCalendar(name: String): Long? = withContext(Dispatchers.IO) {
        try {
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, name)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                put(CalendarContract.Calendars.CALENDAR_COLOR, LOCAL_COLOR)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            }
            resolver.insert(uri, values)?.let { ContentUris.parseId(it) }
        } catch (e: RuntimeException) {
            null
        }
    }

    private companion object {
        const val ACCOUNT_NAME = "MiriquidiLauncher"
        const val LOCAL_COLOR = 0xFF5E9BFF.toInt()
    }
}
