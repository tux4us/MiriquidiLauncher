// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedDatesTest {
    // 2025-06-10T02:00:00Z
    private val expected = 1749520800L

    @Test
    fun rfc822WithOffsetAndNamedZones() {
        assertEquals(expected, FeedDates.parse("Tue, 10 Jun 2025 04:00:00 +0200"))
        assertEquals(expected, FeedDates.parse("Tue, 10 Jun 2025 02:00:00 GMT"))
        assertEquals(expected, FeedDates.parse("10 Jun 2025 02:00 UT"))
        // EST = UTC-5: 02:00 EST ist 07:00 UTC
        assertEquals(expected + 5 * 3600, FeedDates.parse("Tue, 10 Jun 2025 02:00:00 EST"))
    }

    @Test
    fun rfc822TwoDigitYearAndMissingSeconds() {
        assertEquals(FeedDates.parse("10 Jun 2025 04:00:00 +0200"), FeedDates.parse("Tue, 10 Jun 25 04:00 +0200"))
    }

    @Test
    fun iso8601Variants() {
        assertEquals(expected, FeedDates.parse("2025-06-10T04:00:00+02:00"))
        assertEquals(expected, FeedDates.parse("2025-06-10T02:00:00Z"))
        assertEquals(expected, FeedDates.parse("2025-06-10T02:00:00.123Z"))
        assertEquals(expected, FeedDates.parse("2025-06-10 02:00:00Z"))
        assertEquals(expected, FeedDates.parse("2025-06-10T02:00:00"))
        assertEquals(1749513600L, FeedDates.parse("2025-06-10"))
    }

    @Test
    fun invalidInputGivesNull() {
        assertNull(FeedDates.parse(null))
        assertNull(FeedDates.parse(""))
        assertNull(FeedDates.parse("gestern"))
        assertNull(FeedDates.parse("31 Feb 2025 10:00:00 GMT"))
        assertNull(FeedDates.parse("10 Foo 2025 10:00:00 GMT"))
    }
}
