// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Liest Termine aus dem Kalender-Provider des Geräts (alle sichtbaren Kalender, auch Konten-Kalender).
 * Es wird nur gelesen; es findet kein Netzwerkzugriff statt.
 */
class CalendarRepository(context: Context) {
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Alle Vorkommen von Terminen, die sich mit [firstDay] bis [lastDay] (einschließlich) überschneiden.
     * Der Abfragebereich ist um einen Tag erweitert, weil ganztägige Termine im Provider nach UTC gespeichert sind;
     * die genaue Zuordnung zu Tagen übernimmt [CalendarMath]. Ohne Berechtigung oder bei einem Fehler des
     * Providers ist das Ergebnis leer.
     */
    suspend fun load(firstDay: LocalDate, lastDay: LocalDate, zone: ZoneId): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val from = firstDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = lastDay.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return withContext(Dispatchers.IO) { query(from, to) }
    }

    private suspend fun query(from: Long, to: Long): List<CalendarEvent> {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)

        // Die blockierende Datenbankabfrage wird mit der Coroutine abgebrochen.
        val signal = CancellationSignal()
        val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) signal.cancel()
        }

        val events = ArrayList<CalendarEvent>()
        try {
            appContext.contentResolver.query(
                builder.build(),
                PROJECTION,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
                signal,
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                    val begin = cursor.getLong(COLUMN_BEGIN)
                    val eventColor = cursor.getInt(COLUMN_EVENT_COLOR)
                    events += CalendarEvent(
                        id = cursor.getLong(COLUMN_EVENT_ID),
                        title = cursor.getString(COLUMN_TITLE).orEmpty(),
                        begin = begin,
                        end = cursor.getLong(COLUMN_END),
                        allDay = cursor.getInt(COLUMN_ALL_DAY) != 0,
                        color = if (eventColor != 0) eventColor else cursor.getInt(COLUMN_CALENDAR_COLOR),
                        location = cursor.getString(COLUMN_LOCATION)?.takeIf { it.isNotBlank() },
                        calendarName = cursor.getString(COLUMN_CALENDAR_NAME)?.takeIf { it.isNotBlank() },
                        description = cursor.getString(COLUMN_DESCRIPTION)?.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION_CHARS),
                    )
                }
            }
        } catch (ignored: SecurityException) {
            events.clear()
        } catch (ignored: IllegalArgumentException) {
            events.clear()
        } finally {
            handle.dispose()
        }
        return events
    }

    private companion object {
        /** Obergrenze, damit ein sehr voller Kalender die Oberfläche nicht lahmlegt. */
        const val MAX_EVENTS = 3000

        /** Beschreibungen werden gekürzt, damit viele Termine mit langen Texten nicht viel Speicher belegen. */
        const val MAX_DESCRIPTION_CHARS = 2000

        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
        )
        const val COLUMN_EVENT_ID = 0
        const val COLUMN_TITLE = 1
        const val COLUMN_BEGIN = 2
        const val COLUMN_END = 3
        const val COLUMN_ALL_DAY = 4
        const val COLUMN_EVENT_COLOR = 5
        const val COLUMN_CALENDAR_COLOR = 6
        const val COLUMN_LOCATION = 7
        const val COLUMN_CALENDAR_NAME = 8
        const val COLUMN_DESCRIPTION = 9
    }
}