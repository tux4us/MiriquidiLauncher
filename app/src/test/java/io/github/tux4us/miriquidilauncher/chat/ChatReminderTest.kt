// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReminderTest {
    private val propfind = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
 <d:response><d:href>/remote.php/dav/calendars/anna/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/anna/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/anna/contact_birthdays/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Geburtstage</d:displayname>
  <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/anna/work/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Arbeit &amp; Büro</d:displayname>
  <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/anna/personal/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Persönlich</d:displayname>
  <cal:supported-calendar-component-set><cal:comp name="VEVENT"/><cal:comp name="VTODO"/></cal:supported-calendar-component-set></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/anna/tasks/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Aufgaben</d:displayname>
  <cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set></d:prop></d:propstat></d:response>
</d:multistatus>"""

    @Test
    fun parsesOnlyEventCalendars() {
        val list = ChatReminder.parseCalendars(propfind)
        assertEquals(listOf("/remote.php/dav/calendars/anna/work/", "/remote.php/dav/calendars/anna/personal/"), list.map { it.href })
        assertEquals("Arbeit & Büro", list[0].name)
    }

    @Test
    fun picksPersonalOtherwiseFirst() {
        val list = ChatReminder.parseCalendars(propfind)
        assertEquals("Persönlich", ChatReminder.pickCalendar(list)?.name)
        assertEquals("Arbeit & Büro", ChatReminder.pickCalendar(list.take(1))?.name)
        assertNull(ChatReminder.pickCalendar(emptyList()))
        assertTrue(ChatReminder.parseCalendars("<html>nichts</html>").isEmpty())
    }

    @Test
    fun resolvesOnlySameHostHttps() {
        assertEquals(
            "https://cloud.example/remote.php/dav/calendars/anna/personal/",
            ChatReminder.resolveHref("https://cloud.example", "/remote.php/dav/calendars/anna/personal/"),
        )
        assertEquals(
            "https://cloud.example/nc/remote.php/dav/calendars/anna/personal/",
            ChatReminder.resolveHref("https://cloud.example/nc", "/nc/remote.php/dav/calendars/anna/personal/"),
        )
        assertNull(ChatReminder.resolveHref("https://cloud.example", "https://evil.example/x/"))
        assertNull(ChatReminder.resolveHref("https://cloud.example", "http://cloud.example/x/"))
        assertNull(ChatReminder.resolveHref("https://cloud.example", "/pfad mit leerzeichen"))
    }

    @Test
    fun escapesSpecialCharacters() {
        assertEquals("a\\\\b\\;c\\,d\\ne", ChatReminder.escapeText("a\\b;c,d\ne"))
        assertEquals("ab", ChatReminder.escapeText("a\r\u0001b"))
    }

    @Test
    fun foldsLongLinesAtSeventyFiveBytes() {
        val line = "DESCRIPTION:" + "ä".repeat(100)
        val folded = ChatReminder.fold(line)
        folded.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        // Entfalten ergibt die Ausgangszeile.
        assertEquals(line, folded.replace("\r\n ", ""))
        assertEquals("kurz", ChatReminder.fold("kurz"))
    }

    @Test
    fun buildsIcsWithAlarmAndUtcTimes() {
        // 2026-12-01 08:00:00 UTC
        val start = 1_796_112_000_000L
        val ics = ChatReminder.buildIcs("abc-1@miriquidilauncher", "Zahnarzt; Termin", "Anna · Familie\n\nBitte anrufen, 8 Uhr", "https://cloud.example/call/tok#message_5", start, start - 3_600_000L)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue(ics.contains("UID:abc-1@miriquidilauncher\r\n"))
        assertTrue(ics.contains("DTSTART:20261201T080000Z\r\n"))
        assertTrue(ics.contains("DTEND:20261201T083000Z\r\n"))
        assertTrue(ics.contains("DTSTAMP:20261201T070000Z\r\n"))
        assertTrue(ics.contains("SUMMARY:Zahnarzt\\; Termin\r\n"))
        assertTrue(ics.contains("TRIGGER:PT0S\r\n"))
        assertTrue(ics.contains("URL:https://cloud.example/call/tok#message_5\r\n"))
        assertFalse(ics.contains("\n\n"))
        // Jede physische Zeile endet auf CRLF; kein einzelnes LF.
        assertEquals(ics.count { it == '\n' }, ics.split("\r\n").size - 1)
    }

    @Test
    fun omitsUnsafeUrl() {
        val ics = ChatReminder.buildIcs("u", "s", "d", "javascript:alert(1)", 0L, 0L)
        assertFalse(ics.contains("URL:"))
    }

    @Test
    fun summaryAndDescription() {
        assertEquals("Erste Zeile", ChatReminder.summary("\n  Erste Zeile  \nzweite", "Raum"))
        assertEquals("Raum", ChatReminder.summary("   ", "Raum"))
        assertEquals(80, ChatReminder.summary("x".repeat(200), "Raum").length)
        assertEquals("Anna · Familie\n\nHallo", ChatReminder.description("Hallo", "Anna", "Familie"))
        assertEquals("Hallo", ChatReminder.description("Hallo", "", ""))
        assertEquals("https://c.example/call/tok#message_7", ChatReminder.talkLink("https://c.example", "tok", 7))
    }

    @Test
    fun calendarHomeUrlEncodesUser() {
        assertEquals("https://c.example/remote.php/dav/calendars/anna%20b/", ChatReminder.calendarHomeUrl("https://c.example", "anna b"))
        assertNotNull(ChatReminder.PROPFIND_BODY)
    }
}
