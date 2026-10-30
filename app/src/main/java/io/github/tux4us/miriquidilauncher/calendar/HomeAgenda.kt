// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Die Termine, die die Card auf der Startseite zeigt.
 *
 * @property day Tag, dessen Termine angezeigt werden
 * @property isToday `true`, wenn [day] der heutige Tag ist (sonst der morgige)
 */
data class AgendaView(val day: LocalDate, val isToday: Boolean, val events: List<CalendarEvent>)

/**
 * Auswahl für die Termin-Card der Startseite. Bis zur Umschaltzeit zeigt sie die noch ausstehenden Termine von
 * heute, ab der Umschaltzeit (20 Uhr) die Termine von morgen. Frei von Android-Klassen und JVM-getestet.
 */
object HomeAgenda {
    const val SWITCH_HOUR = 20

    /** Heute vor [switchHour] Uhr (Ortszeit), danach morgen. Um Mitternacht beginnt wieder der heutige Tag. */
    fun targetDay(now: ZonedDateTime, switchHour: Int = SWITCH_HOUR): LocalDate =
        if (now.hour >= switchHour) now.toLocalDate().plusDays(1) else now.toLocalDate()

    /**
     * Wählt aus [events] (beliebig viele, auch von anderen Tagen) die Termine des Zieltags aus: ganztägige zuerst,
     * dann nach Beginn. Für den heutigen Tag entfallen Termine, die schon zu Ende sind.
     */
    fun select(events: List<CalendarEvent>, now: ZonedDateTime, switchHour: Int = SWITCH_HOUR): AgendaView {
        val day = targetDay(now, switchHour)
        val isToday = day == now.toLocalDate()
        val nowMillis = now.toInstant().toEpochMilli()
        val selected = CalendarMath.eventsOn(events, day, now.zone)
            .filter { !isToday || it.allDay || it.end > nowMillis }
        return AgendaView(day, isToday, selected)
    }
}
