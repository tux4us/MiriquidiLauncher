// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarEditTest {
    private val berlin = "Europe/Berlin"

    private fun draft(allDay: Boolean = false, repeat: Repeat = Repeat.NONE) = EventDraft(
        calendarId = 3,
        title = "Test",
        allDay = allDay,
        startDate = LocalDate.of(2026, 10, 28),
        startTime = LocalTime.of(14, 30),
        endDate = LocalDate.of(2026, 10, 28),
        endTime = LocalTime.of(15, 45),
        zoneId = berlin,
        repeat = repeat,
    )

    @Test
    fun timedEventValues() {
        val v = CalendarEdit.toValues(draft())
        // 28.10.2026 14:30 CET (UTC+1) = 13:30 UTC
        assertEquals(1_793_194_200_000L, v.dtStart)
        assertEquals(v.dtStart + 75 * 60_000, v.dtEnd)
        assertNull(v.duration)
        assertEquals(berlin, v.timeZone)
        assertNull(v.rrule)
    }

    @Test
    fun allDayUsesUtcAndExclusiveEnd() {
        val d = draft(allDay = true).copy(endDate = LocalDate.of(2026, 10, 30))
        val v = CalendarEdit.toValues(d)
        assertEquals("UTC", v.timeZone)
        assertEquals(3 * 86_400_000L, v.dtEnd!! - v.dtStart)
        assertEquals(0L, v.dtStart % 86_400_000L)
    }

    @Test
    fun recurringUsesDurationInsteadOfEnd() {
        val v = CalendarEdit.toValues(draft(repeat = Repeat.WEEKLY))
        assertNull(v.dtEnd)
        assertEquals("P4500S", v.duration)
        assertEquals("FREQ=WEEKLY", v.rrule)
        val allDay = CalendarEdit.toValues(draft(allDay = true, repeat = Repeat.YEARLY))
        assertEquals("P1D", allDay.duration)
    }

    @Test
    fun validation() {
        assertNull(CalendarEdit.validate(draft()))
        assertEquals(DraftError.END_BEFORE_START, CalendarEdit.validate(draft().copy(endTime = LocalTime.of(14, 30))))
        assertEquals(DraftError.NO_CALENDAR, CalendarEdit.validate(draft().copy(calendarId = -1)))
        assertEquals(DraftError.END_BEFORE_START, CalendarEdit.validate(draft(true).copy(endDate = LocalDate.of(2026, 10, 27))))
        assertNull(CalendarEdit.validate(draft(true)))
    }

    @Test
    fun repeatRules() {
        assertEquals(Repeat.NONE, CalendarEdit.repeatOf(null))
        assertEquals(Repeat.WEEKLY, CalendarEdit.repeatOf("RRULE:FREQ=WEEKLY"))
        assertEquals(Repeat.KEEP, CalendarEdit.repeatOf("FREQ=WEEKLY;BYDAY=MO,WE"))
        val keep = draft(repeat = Repeat.KEEP).copy(keepRule = "FREQ=MONTHLY;COUNT=3")
        assertEquals("FREQ=MONTHLY;COUNT=3", CalendarEdit.toValues(keep).rrule)
    }

    @Test
    fun durations() {
        assertEquals(86_400_000L, CalendarEdit.parseDuration("P1D"))
        assertEquals(5_400_000L, CalendarEdit.parseDuration("PT1H30M"))
        assertEquals(604_800_000L, CalendarEdit.parseDuration("P1W"))
        assertEquals(3_600_000L, CalendarEdit.parseDuration("P3600S"))
        assertNull(CalendarEdit.parseDuration("P"))
        assertNull(CalendarEdit.parseDuration("abc"))
        assertNull(CalendarEdit.parseDuration(null))
    }

    @Test
    fun roundTripThroughDetails() {
        val original = draft().copy(eventId = 9, location = "Ort", description = "Text", reminderMinutes = 15)
        val v = CalendarEdit.toValues(original)
        val details = EventDetails(9, 3, "Test", "Ort", "Text", v.dtStart, v.dtEnd, v.duration, v.timeZone, v.allDay, v.rrule, 15)
        assertEquals(original, CalendarEdit.draftFrom(details))
        // ganztägig, mehrtägig
        val allDay = draft(true).copy(eventId = 9, endDate = LocalDate.of(2026, 10, 30), zoneId = ZoneId.systemDefault().id)
        val av = CalendarEdit.toValues(allDay)
        val back = CalendarEdit.draftFrom(EventDetails(9, 3, "Test", "", "", av.dtStart, av.dtEnd, null, "UTC", true, null, null))
        assertEquals(allDay.startDate, back.startDate)
        assertEquals(allDay.endDate, back.endDate)
        // Serie: Dauer statt Ende
        val rec = draft(repeat = Repeat.DAILY).copy(eventId = 9)
        val rv = CalendarEdit.toValues(rec)
        val recBack = CalendarEdit.draftFrom(EventDetails(9, 3, "Test", "", "", rv.dtStart, null, rv.duration, rv.timeZone, false, rv.rrule, null))
        assertEquals(rec.endTime, recBack.endTime)
        assertEquals(Repeat.DAILY, recBack.repeat)
    }

    @Test
    fun newDraftDefaultsToNextHour() {
        val d = CalendarEdit.newDraft(LocalDate.of(2026, 10, 28), LocalTime.of(19, 34), ZoneId.of(berlin), 5)
        assertEquals(LocalTime.of(20, 0), d.startTime)
        assertEquals(LocalTime.of(21, 0), d.endTime)
        val late = CalendarEdit.newDraft(LocalDate.of(2026, 10, 28), LocalTime.of(23, 40), ZoneId.of(berlin), 5)
        assertEquals(LocalTime.of(22, 0), late.startTime)
        assertEquals(LocalDate.of(2026, 10, 28), late.endDate)
    }

    @Test
    fun endFixAfterStartChange() {
        val moved = draft().copy(startTime = LocalTime.of(16, 0))
        val fixed = CalendarEdit.withEndFixed(moved)
        assertEquals(LocalTime.of(17, 0), fixed.endTime)
        assertEquals(draft(), CalendarEdit.withEndFixed(draft()))
    }

    @Test
    fun movingStartKeepsLength() {
        val moved = CalendarEdit.moveStart(draft(), LocalDate.of(2026, 11, 1), LocalTime.of(23, 0))
        assertEquals(LocalDate.of(2026, 11, 2), moved.endDate)
        assertEquals(LocalTime.of(0, 15), moved.endTime)
        val allDay = CalendarEdit.moveStart(draft(true).copy(endDate = LocalDate.of(2026, 10, 30)), LocalDate.of(2026, 12, 1), LocalTime.NOON)
        assertEquals(LocalDate.of(2026, 12, 3), allDay.endDate)
    }
}
