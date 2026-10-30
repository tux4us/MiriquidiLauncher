// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarMathTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    private fun timed(id: Long, from: LocalDateTime, to: LocalDateTime, zone: ZoneId = berlin) = CalendarEvent(
        id = id,
        title = "T$id",
        begin = from.atZone(zone).toInstant().toEpochMilli(),
        end = to.atZone(zone).toInstant().toEpochMilli(),
        allDay = false,
    )

    private fun allDay(id: Long, from: LocalDate, toExclusive: LocalDate) = CalendarEvent(
        id = id,
        title = "A$id",
        begin = from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        end = toExclusive.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        allDay = true,
    )

    @Test
    fun weekStartDependsOnFirstDay() {
        // 2026-10-08 ist ein Donnerstag.
        assertEquals(date(2026, 10, 5), CalendarMath.weekStart(date(2026, 10, 8), DayOfWeek.MONDAY))
        assertEquals(date(2026, 10, 4), CalendarMath.weekStart(date(2026, 10, 8), DayOfWeek.SUNDAY))
        assertEquals(date(2026, 10, 5), CalendarMath.weekStart(date(2026, 10, 5), DayOfWeek.MONDAY))
    }

    @Test
    fun monthGridStartsAtFirstDayAndCoversMonth() {
        val grid = CalendarMath.monthGrid(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(5, grid.size)
        assertTrue(grid.all { it.size == 7 })
        assertEquals(date(2026, 9, 28), grid.first().first())
        assertEquals(date(2026, 11, 1), grid.last().last())
    }

    @Test
    fun monthGridWithSundayStart() {
        val grid = CalendarMath.monthGrid(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        assertEquals(5, grid.size)
        assertEquals(date(2026, 9, 27), grid.first().first())
        assertEquals(date(2026, 10, 31), grid.last().last())
    }

    @Test
    fun monthGridHasFourRowsWhenMonthFitsExactly() {
        // Februar 2027 beginnt an einem Montag und hat 28 Tage.
        val grid = CalendarMath.monthGrid(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(4, grid.size)
        assertEquals(date(2027, 2, 1), grid.first().first())
        assertEquals(date(2027, 2, 28), grid.last().last())
    }

    @Test
    fun monthGridHasSixRowsWhenNeeded() {
        // August 2026 beginnt an einem Samstag: 5 Tage Vorlauf + 31 Tage = 36 Tage -> 6 Zeilen.
        val grid = CalendarMath.monthGrid(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(6, grid.size)
    }

    @Test
    fun visibleDaysPerMode() {
        val day = date(2026, 10, 8)
        assertEquals(listOf(day), CalendarMath.visibleDays(day, CalendarMode.Day, DayOfWeek.MONDAY))
        val week = CalendarMath.visibleDays(day, CalendarMode.Week, DayOfWeek.MONDAY)
        assertEquals(7, week.size)
        assertEquals(date(2026, 10, 5), week.first())
        assertEquals(date(2026, 10, 11), week.last())
        assertEquals(35, CalendarMath.visibleDays(day, CalendarMode.Month, DayOfWeek.MONDAY).size)
    }

    @Test
    fun shiftMovesByModeAndClampsMonthEnd() {
        assertEquals(date(2026, 10, 9), CalendarMath.shift(date(2026, 10, 8), CalendarMode.Day, 1))
        assertEquals(date(2026, 10, 1), CalendarMath.shift(date(2026, 10, 8), CalendarMode.Week, -1))
        assertEquals(date(2027, 2, 28), CalendarMath.shift(date(2027, 1, 31), CalendarMode.Month, 1))
        assertEquals(date(2025, 12, 15), CalendarMath.shift(date(2026, 1, 15), CalendarMode.Month, -1))
    }

    @Test
    fun allDayEventCountsByUtcDateInAnyZone() {
        val event = allDay(1, date(2026, 10, 10), date(2026, 10, 11))
        for (zone in listOf(berlin, losAngeles, ZoneId.of("Pacific/Auckland"))) {
            assertEquals(date(2026, 10, 10), CalendarMath.firstDay(event, zone))
            assertEquals(date(2026, 10, 10), CalendarMath.lastDay(event, zone))
            assertTrue(CalendarMath.occursOn(event, date(2026, 10, 10), zone))
            assertFalse(CalendarMath.occursOn(event, date(2026, 10, 9), zone))
            assertFalse(CalendarMath.occursOn(event, date(2026, 10, 11), zone))
        }
    }

    @Test
    fun multiDayAllDayEventEndsOnLastDay() {
        val event = allDay(1, date(2026, 10, 10), date(2026, 10, 13))
        assertEquals(date(2026, 10, 12), CalendarMath.lastDay(event, berlin))
        assertTrue(CalendarMath.occursOn(event, date(2026, 10, 11), berlin))
        assertFalse(CalendarMath.occursOn(event, date(2026, 10, 13), berlin))
    }

    @Test
    fun timedEventAcrossMidnightBelongsToBothDays() {
        val event = timed(1, LocalDateTime.of(2026, 10, 10, 23, 0), LocalDateTime.of(2026, 10, 11, 1, 0))
        assertTrue(CalendarMath.occursOn(event, date(2026, 10, 10), berlin))
        assertTrue(CalendarMath.occursOn(event, date(2026, 10, 11), berlin))
        val first = CalendarMath.layoutDay(listOf(event), date(2026, 10, 10), berlin).single()
        assertEquals(23 * 60, first.startMinute)
        assertEquals(24 * 60, first.endMinute)
        val second = CalendarMath.layoutDay(listOf(event), date(2026, 10, 11), berlin).single()
        assertEquals(0, second.startMinute)
        assertEquals(60, second.endMinute)
    }

    @Test
    fun eventEndingAtMidnightDoesNotSpillOver() {
        val event = timed(1, LocalDateTime.of(2026, 10, 10, 20, 0), LocalDateTime.of(2026, 10, 11, 0, 0))
        assertTrue(CalendarMath.occursOn(event, date(2026, 10, 10), berlin))
        assertFalse(CalendarMath.occursOn(event, date(2026, 10, 11), berlin))
    }

    @Test
    fun zeroLengthEventOccursOnItsDay() {
        val at = LocalDateTime.of(2026, 10, 10, 9, 0)
        val event = timed(1, at, at)
        assertTrue(CalendarMath.occursOn(event, date(2026, 10, 10), berlin))
        assertFalse(CalendarMath.occursOn(event, date(2026, 10, 11), berlin))
    }

    @Test
    fun eventsOnListAllDayFirstThenByStart() {
        val late = timed(1, LocalDateTime.of(2026, 10, 10, 15, 0), LocalDateTime.of(2026, 10, 10, 16, 0))
        val early = timed(2, LocalDateTime.of(2026, 10, 10, 8, 0), LocalDateTime.of(2026, 10, 10, 9, 0))
        val whole = allDay(3, date(2026, 10, 10), date(2026, 10, 11))
        val other = timed(4, LocalDateTime.of(2026, 10, 11, 8, 0), LocalDateTime.of(2026, 10, 11, 9, 0))
        val result = CalendarMath.eventsOn(listOf(late, other, early, whole), date(2026, 10, 10), berlin)
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun overlappingEventsShareColumns() {
        val a = timed(1, LocalDateTime.of(2026, 10, 10, 9, 0), LocalDateTime.of(2026, 10, 10, 10, 0))
        val b = timed(2, LocalDateTime.of(2026, 10, 10, 9, 30), LocalDateTime.of(2026, 10, 10, 10, 30))
        val c = timed(3, LocalDateTime.of(2026, 10, 10, 10, 0), LocalDateTime.of(2026, 10, 10, 11, 0))
        val d = timed(4, LocalDateTime.of(2026, 10, 10, 12, 0), LocalDateTime.of(2026, 10, 10, 13, 0))
        val placed = CalendarMath.layoutDay(listOf(d, c, b, a), date(2026, 10, 10), berlin).associateBy { it.event.id }
        assertEquals(0, placed.getValue(1).column)
        assertEquals(1, placed.getValue(2).column)
        assertEquals(0, placed.getValue(3).column)
        assertEquals(2, placed.getValue(1).columns)
        assertEquals(2, placed.getValue(2).columns)
        assertEquals(2, placed.getValue(3).columns)
        assertEquals(0, placed.getValue(4).column)
        assertEquals(1, placed.getValue(4).columns)
    }

    @Test
    fun touchingEventsDoNotOverlap() {
        val a = timed(1, LocalDateTime.of(2026, 10, 10, 9, 0), LocalDateTime.of(2026, 10, 10, 10, 0))
        val b = timed(2, LocalDateTime.of(2026, 10, 10, 10, 0), LocalDateTime.of(2026, 10, 10, 11, 0))
        val placed = CalendarMath.layoutDay(listOf(a, b), date(2026, 10, 10), berlin)
        assertTrue(placed.all { it.columns == 1 && it.column == 0 })
    }

    @Test
    fun shortEventsGetMinimumDisplayHeight() {
        val e = timed(1, LocalDateTime.of(2026, 10, 10, 9, 0), LocalDateTime.of(2026, 10, 10, 9, 10))
        val p = CalendarMath.layoutDay(listOf(e), date(2026, 10, 10), berlin).single()
        assertEquals(9 * 60, p.startMinute)
        assertEquals(9 * 60 + CalendarMath.MIN_DISPLAY_MINUTES, p.endMinute)
    }

    @Test
    fun shortEventAtEndOfDayStaysInsideTheDay() {
        val e = timed(1, LocalDateTime.of(2026, 10, 10, 23, 50), LocalDateTime.of(2026, 10, 10, 23, 55))
        val p = CalendarMath.layoutDay(listOf(e), date(2026, 10, 10), berlin).single()
        assertEquals(24 * 60, p.endMinute)
        assertEquals(24 * 60 - CalendarMath.MIN_DISPLAY_MINUTES, p.startMinute)
    }

    @Test
    fun layoutIgnoresAllDayEventsAndOtherDays() {
        val whole = allDay(1, date(2026, 10, 10), date(2026, 10, 11))
        val other = timed(2, LocalDateTime.of(2026, 10, 11, 8, 0), LocalDateTime.of(2026, 10, 11, 9, 0))
        assertTrue(CalendarMath.layoutDay(listOf(whole, other), date(2026, 10, 10), berlin).isEmpty())
    }

    @Test
    fun recurringInstancesHaveDistinctKeys() {
        val first = timed(7, LocalDateTime.of(2026, 10, 10, 9, 0), LocalDateTime.of(2026, 10, 10, 10, 0))
        val second = timed(7, LocalDateTime.of(2026, 10, 17, 9, 0), LocalDateTime.of(2026, 10, 17, 10, 0))
        assertTrue(first.key != second.key)
    }
}
