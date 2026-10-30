// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** Ansicht der Kalenderseite. */
enum class CalendarMode { Day, Week, Month }

/**
 * Ein Termin (genauer: ein Vorkommen eines Termins; Serientermine ergeben mehrere Einträge).
 *
 * @property begin Beginn in Millisekunden seit 1970 (UTC)
 * @property end Ende (exklusiv) in Millisekunden. Bei ganztägigen Terminen markieren [begin] und [end]
 *   volle UTC-Tage, unabhängig von der Zeitzone des Geräts (so speichert sie der Kalender-Provider).
 * @property color ARGB-Farbe des Termins bzw. seines Kalenders; 0, wenn unbekannt
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int = 0,
    val location: String? = null,
    val calendarName: String? = null,
    /** Beschreibung des Termins; kann HTML oder Zeilenumbrüche enthalten, auf 2000 Zeichen gekürzt. */
    val description: String? = null,
) {
    /** Eindeutig auch bei Serienterminen, die dieselbe [id] mehrfach haben. */
    val key: String get() = "$id@$begin"
}

/** Ein zeitlicher Termin, in Spalten angeordnet, damit sich überlappende Termine nebeneinander liegen. */
data class PlacedEvent(
    val event: CalendarEvent,
    /** Minuten ab Tagesbeginn (0..1440), nie kleiner als [CalendarMath.MIN_DISPLAY_MINUTES] hoch. */
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

/** Datums- und Terminrechnung der Kalenderseite. Frei von Android-Klassen, damit sie auf der JVM testbar bleibt. */
object CalendarMath {
    const val MINUTES_PER_DAY = 24 * 60

    /** Kürzeste dargestellte Länge eines zeitlichen Termins, damit kurze Termine antippbar bleiben. */
    const val MIN_DISPLAY_MINUTES = 30

    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(firstDay))

    /** Alle Wochen des Monats als Zeilen zu je 7 Tagen, beginnend mit [firstDay]; 4 bis 6 Zeilen. */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate>> {
        val first = month.atDay(1)
        val start = weekStart(first, firstDay)
        val last = month.atEndOfMonth()
        val days = (java.time.temporal.ChronoUnit.DAYS.between(start, last) + 1).toInt()
        val rows = (days + 6) / 7
        return List(rows) { row -> List(7) { column -> start.plusDays((row * 7 + column).toLong()) } }
    }

    /** Die sichtbaren Tage der Ansicht [mode] um [date]. Der Monat zeigt das ganze Raster inklusive Nachbartage. */
    fun visibleDays(date: LocalDate, mode: CalendarMode, firstDay: DayOfWeek): List<LocalDate> = when (mode) {
        CalendarMode.Day -> listOf(date)
        CalendarMode.Week -> weekStart(date, firstDay).let { start -> List(7) { start.plusDays(it.toLong()) } }
        CalendarMode.Month -> monthGrid(YearMonth.from(date), firstDay).flatten()
    }

    /** Ein Schritt vor ([delta] = 1) oder zurück ([delta] = -1) in der Ansicht [mode]. */
    fun shift(date: LocalDate, mode: CalendarMode, delta: Int): LocalDate = when (mode) {
        CalendarMode.Day -> date.plusDays(delta.toLong())
        CalendarMode.Week -> date.plusWeeks(delta.toLong())
        CalendarMode.Month -> date.plusMonths(delta.toLong())
    }

    /** Erster Tag des Termins. Ganztägige Termine zählen nach UTC, zeitliche nach [zone]. */
    fun firstDay(event: CalendarEvent, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(event.begin).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** Letzter Tag des Termins (einschließlich). Ein Termin, der genau um Mitternacht endet, gehört nicht zum Folgetag. */
    fun lastDay(event: CalendarEvent, zone: ZoneId): LocalDate {
        val first = firstDay(event, zone)
        if (event.end <= event.begin) return first
        val last = if (event.allDay) {
            Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        } else {
            Instant.ofEpochMilli(event.end - 1).atZone(zone).toLocalDate()
        }
        return if (last.isBefore(first)) first else last
    }

    fun occursOn(event: CalendarEvent, date: LocalDate, zone: ZoneId): Boolean =
        !date.isBefore(firstDay(event, zone)) && !date.isAfter(lastDay(event, zone))

    /** Termine des Tages: erst die ganztägigen, dann nach Beginn und Titel. */
    fun eventsOn(events: List<CalendarEvent>, date: LocalDate, zone: ZoneId): List<CalendarEvent> =
        events.filter { occursOn(it, date, zone) }
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.begin }.thenBy { it.title })

    /**
     * Ordnet die zeitlichen Termine von [date] in Spalten an. Termine, die sich überschneiden, teilen sich die
     * Breite; ein Termin über Mitternacht erscheint an beiden Tagen mit dem jeweiligen Ausschnitt.
     */
    fun layoutDay(events: List<CalendarEvent>, date: LocalDate, zone: ZoneId): List<PlacedEvent> {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val segments = events
            .filter { !it.allDay && occursOn(it, date, zone) }
            .map { event ->
                val start = minutesFrom(dayStart, event.begin)
                var end = minutesFrom(dayStart, event.end)
                var from = start
                if (end - from < MIN_DISPLAY_MINUTES) end = from + MIN_DISPLAY_MINUTES
                if (end > MINUTES_PER_DAY) {
                    end = MINUTES_PER_DAY
                    from = minOf(from, MINUTES_PER_DAY - MIN_DISPLAY_MINUTES)
                }
                Triple(event, from, end)
            }
            .sortedWith(compareBy<Triple<CalendarEvent, Int, Int>> { it.second }.thenByDescending { it.third })

        val result = ArrayList<PlacedEvent>(segments.size)
        var cluster = ArrayList<Triple<CalendarEvent, Int, Int>>()
        var columnEnds = ArrayList<Int>()
        var clusterEnd = Int.MIN_VALUE
        val columnOf = HashMap<String, Int>()

        fun finishCluster() {
            val columns = columnEnds.size
            for ((event, start, end) in cluster) {
                result += PlacedEvent(event, start, end, columnOf.getValue(event.key), columns)
            }
            cluster = ArrayList()
            columnEnds = ArrayList()
            clusterEnd = Int.MIN_VALUE
        }

        for (segment in segments) {
            val (event, start, end) = segment
            if (cluster.isNotEmpty() && start >= clusterEnd) finishCluster()
            var column = columnEnds.indexOfFirst { it <= start }
            if (column < 0) {
                column = columnEnds.size
                columnEnds.add(end)
            } else {
                columnEnds[column] = end
            }
            columnOf[event.key] = column
            cluster.add(segment)
            clusterEnd = maxOf(clusterEnd, end)
        }
        if (cluster.isNotEmpty()) finishCluster()
        return result
    }

    private fun minutesFrom(dayStart: Instant, epochMillis: Long): Int =
        Duration.between(dayStart, Instant.ofEpochMilli(epochMillis)).toMinutes()
            .coerceIn(0L, MINUTES_PER_DAY.toLong()).toInt()
}
