// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HomeAgendaTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): ZonedDateTime =
        LocalDateTime.of(y, mo, d, h, mi).atZone(berlin)

    private fun timed(id: Long, from: ZonedDateTime, to: ZonedDateTime) = CalendarEvent(
        id = id, title = "T$id", begin = from.toInstant().toEpochMilli(), end = to.toInstant().toEpochMilli(), allDay = false,
    )

    private fun allDay(id: Long, from: LocalDate, toExclusive: LocalDate) = CalendarEvent(
        id = id, title = "A$id",
        begin = from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        end = toExclusive.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        allDay = true,
    )

    @Test
    fun switchesToTomorrowAtTwentyOClock() {
        assertEquals(LocalDate.of(2026, 10, 5), HomeAgenda.targetDay(at(2026, 10, 5, 0, 0)))
        assertEquals(LocalDate.of(2026, 10, 5), HomeAgenda.targetDay(at(2026, 10, 5, 19, 59)))
        assertEquals(LocalDate.of(2026, 10, 6), HomeAgenda.targetDay(at(2026, 10, 5, 20, 0)))
        assertEquals(LocalDate.of(2026, 10, 6), HomeAgenda.targetDay(at(2026, 10, 5, 23, 59)))
        // Um Mitternacht beginnt der neue Tag wieder mit "heute".
        assertEquals(LocalDate.of(2026, 10, 6), HomeAgenda.targetDay(at(2026, 10, 6, 0, 0)))
    }

    @Test
    fun switchesAcrossMonthAndYearEnd() {
        assertEquals(LocalDate.of(2027, 1, 1), HomeAgenda.targetDay(at(2026, 12, 31, 21)))
    }

    @Test
    fun beforeSwitchShowsOnlyEventsNotYetOver() {
        val now = at(2026, 10, 5, 14, 0)
        val over = timed(1, at(2026, 10, 5, 9), at(2026, 10, 5, 10))
        val running = timed(2, at(2026, 10, 5, 13), at(2026, 10, 5, 15))
        val later = timed(3, at(2026, 10, 5, 18), at(2026, 10, 5, 19))
        val tomorrow = timed(4, at(2026, 10, 6, 9), at(2026, 10, 6, 10))
        val view = HomeAgenda.select(listOf(over, running, later, tomorrow), now)
        assertTrue(view.isToday)
        assertEquals(listOf(2L, 3L), view.events.map { it.id })
    }

    @Test
    fun afterSwitchShowsAllEventsOfTomorrow() {
        val now = at(2026, 10, 5, 20, 30)
        val today = timed(1, at(2026, 10, 5, 21), at(2026, 10, 5, 22))
        val early = timed(2, at(2026, 10, 6, 8), at(2026, 10, 6, 9))
        val late = timed(3, at(2026, 10, 6, 17), at(2026, 10, 6, 18))
        val day = allDay(4, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7))
        val view = HomeAgenda.select(listOf(late, today, early, day), now)
        assertFalse(view.isToday)
        assertEquals(LocalDate.of(2026, 10, 6), view.day)
        // Ganztägig zuerst, dann nach Beginn.
        assertEquals(listOf(4L, 2L, 3L), view.events.map { it.id })
    }

    @Test
    fun allDayEventsOfTodayStayAfterTheyStarted() {
        val now = at(2026, 10, 5, 15)
        val day = allDay(1, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))
        assertEquals(listOf(1L), HomeAgenda.select(listOf(day), now).events.map { it.id })
    }

    @Test
    fun multiDayEventsCountOnEveryDay() {
        val trip = allDay(1, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8))
        assertEquals(listOf(1L), HomeAgenda.select(listOf(trip), at(2026, 10, 6, 21)).events.map { it.id })
        assertTrue(HomeAgenda.select(listOf(trip), at(2026, 10, 7, 21)).events.isEmpty())
    }

    @Test
    fun eventEndingAtMidnightDoesNotSpillIntoTomorrow() {
        val evening = timed(1, at(2026, 10, 5, 22), at(2026, 10, 6, 0))
        assertTrue(HomeAgenda.select(listOf(evening), at(2026, 10, 5, 20, 30)).events.isEmpty())
    }

    @Test
    fun dayWithoutEventsGivesEmptyList() {
        val view = HomeAgenda.select(emptyList(), at(2026, 10, 5, 20))
        assertTrue(view.events.isEmpty())
        assertEquals(LocalDate.of(2026, 10, 6), view.day)
    }
}
