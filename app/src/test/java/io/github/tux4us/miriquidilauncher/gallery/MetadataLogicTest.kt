// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import io.github.tux4us.miriquidilauncher.gallery.edit.MetadataFields
import io.github.tux4us.miriquidilauncher.gallery.edit.MetadataLogic
import io.github.tux4us.miriquidilauncher.gallery.edit.MetadataPlan
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLogicTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun parsesSeveralDateFormats() {
        val expected = LocalDateTime.of(2026, 11, 24, 14, 30)
        assertEquals(expected, MetadataLogic.parseDate("2026-11-24 14:30"))
        assertEquals(expected, MetadataLogic.parseDate("24.11.2026 14:30"))
        assertEquals(expected, MetadataLogic.parseDate("2026:11:24 14:30:00"))
        assertEquals(expected.withSecond(5), MetadataLogic.parseDate("24.11.2026 14:30:05"))
        assertNull(MetadataLogic.parseDate("morgen"))
        assertNull(MetadataLogic.parseDate("31.02.2026 10:00"))
        assertNull(MetadataLogic.parseDate(""))
    }

    @Test
    fun exifRoundTrip() {
        val dt = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("2026:01:02 03:04:05", MetadataLogic.toExifDate(dt))
        assertEquals(dt, MetadataLogic.fromExifDate("2026:01:02 03:04:05"))
        assertEquals("02.01.2026 03:04", MetadataLogic.displayDate("2026:01:02 03:04:05"))
        assertEquals("", MetadataLogic.displayDate("kaputt"))
    }

    @Test
    fun cleansText() {
        assertEquals("a b", MetadataLogic.cleanText("  a\u0000 b\n "))
        assertEquals(500, MetadataLogic.cleanText("x".repeat(900)).length)
    }

    @Test
    fun plansOnlyChanges() {
        val old = MetadataFields("Alt", "Anna", "", "2026:11:24 14:30:00", hasGps = true)
        val none = MetadataLogic.plan(old, "Alt", "Anna", "", "24.11.2026 14:30", removeGps = false, zone = utc) as MetadataPlan.Ok
        assertTrue(none.isEmpty)

        val changed = MetadataLogic.plan(old, "Neu", "", "(c) Anna", "24.11.2026 14:30", removeGps = true, zone = utc) as MetadataPlan.Ok
        assertEquals("Neu", changed.set[MetadataLogic.TAG_DESCRIPTION])
        assertTrue(changed.set.containsKey(MetadataLogic.TAG_ARTIST))
        assertNull(changed.set[MetadataLogic.TAG_ARTIST]) // leerer Text entfernt das Feld
        assertEquals("(c) Anna", changed.set[MetadataLogic.TAG_COPYRIGHT])
        assertTrue(changed.removeGps)
        assertNull(changed.takenMillis)
    }

    @Test
    fun dateChangeSetsBothFieldsAndMillis() {
        val old = MetadataFields(dateOriginal = "2026:11:24 14:30:00")
        val plan = MetadataLogic.plan(old, "", "", "", "25.12.2026 08:00", removeGps = false, zone = utc) as MetadataPlan.Ok
        assertEquals("2026:12:25 08:00:00", plan.set[MetadataLogic.TAG_DATE_ORIGINAL])
        assertEquals("2026:12:25 08:00:00", plan.set[MetadataLogic.TAG_DATE_DIGITIZED])
        assertEquals(LocalDateTime.of(2026, 12, 25, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), plan.takenMillis)
    }

    @Test
    fun invalidDateIsReported() {
        val plan = MetadataLogic.plan(MetadataFields(), "", "", "", "gestern", removeGps = false, zone = utc)
        assertEquals(MetadataPlan.InvalidDate, plan)
    }

    @Test
    fun blankDateKeepsExistingDate() {
        val old = MetadataFields(dateOriginal = "2026:11:24 14:30:00")
        val plan = MetadataLogic.plan(old, "", "", "", "", removeGps = false, zone = utc) as MetadataPlan.Ok
        assertTrue(plan.isEmpty)
    }

    @Test
    fun gpsRemovalOnlyWhenPresent() {
        val without = MetadataLogic.plan(MetadataFields(hasGps = false), "", "", "", "", removeGps = true, zone = utc) as MetadataPlan.Ok
        assertTrue(without.isEmpty)
        assertTrue(MetadataLogic.GPS_TAGS.contains("GPSLatitude"))
    }
}
