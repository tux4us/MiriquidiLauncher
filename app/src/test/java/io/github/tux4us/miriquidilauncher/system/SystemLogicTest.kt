// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLogicTest {
    private val meminfo = """
        MemTotal:        7936000 kB
        MemFree:          500000 kB
        MemAvailable:    3000000 kB
        Buffers:           50000 kB
        Cached:          2000000 kB
        SwapTotal:       2097148 kB
        SwapFree:        1000000 kB
    """.trimIndent()

    @Test fun parsesMeminfo() {
        val m = MemInfoParser.parse(meminfo)!!
        assertEquals(7936000L, m.totalKb)
        assertEquals(3000000L, m.availableKb)
        assertEquals(4936000L, m.usedKb)
        assertEquals(1097148L, m.swapUsedKb)
        assertEquals(0.622, m.usedFraction, 0.001)
    }

    @Test fun estimatesAvailableWhenMissing() {
        val m = MemInfoParser.parse("MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 50 kB\nCached: 250 kB")!!
        assertEquals(400L, m.availableKb)
    }

    @Test fun invalidMeminfo() {
        assertNull(MemInfoParser.parse(""))
        assertNull(MemInfoParser.parse("MemFree: 100 kB"))
        assertNull(MemInfoParser.parse("garbage"))
    }

    @Test fun formatsBytes() {
        assertEquals("0 B", SystemFormat.bytes(0))
        assertEquals("999 B", SystemFormat.bytes(999))
        assertEquals("1 KB", SystemFormat.bytes(1000))
        assertEquals("1,5 MB", SystemFormat.bytes(1_500_000))
        assertEquals("64,0 GB", SystemFormat.bytes(64_000_000_000L))
        assertEquals("2,50 GB", SystemFormat.bytes(2_500_000_000L))
        assertEquals("2.50 GB", SystemFormat.bytes(2_500_000_000L, 1000, '.'))
        assertEquals("1,00 GB", SystemFormat.bytes(1024L * 1024 * 1024, 1024))
        assertEquals("0 B", SystemFormat.bytes(-5))
    }

    @Test fun appendCappedDropsOldest() {
        assertEquals(listOf(2, 3, 4), SystemFormat.appendCapped(listOf(1, 2, 3), 4, 3))
        assertEquals(listOf(1, 2), SystemFormat.appendCapped(listOf(1), 2, 5))
    }

    private fun s(minute: Long, level: Int, charging: Boolean = false, temp: Int? = 300) =
        BatterySample(minute * 60_000L, level, temp, charging)

    @Test fun shouldRecordRules() {
        val last = s(0, 80)
        assertTrue(BatteryStatsLogic.shouldRecord(null, last))
        assertTrue(!BatteryStatsLogic.shouldRecord(last, s(2, 80)))
        assertTrue(BatteryStatsLogic.shouldRecord(last, s(2, 79)))
        assertTrue(BatteryStatsLogic.shouldRecord(last, s(2, 80, charging = true)))
        assertTrue(BatteryStatsLogic.shouldRecord(last, s(5, 80)))
        assertTrue(BatteryStatsLogic.shouldRecord(last, s(-1, 80)))
    }

    @Test fun pruneRemovesOldAndExcess() {
        val now = 10L * 24 * 3_600_000
        val old = BatterySample(now - BatteryStatsLogic.MAX_AGE_MS - 1, 50, null, false)
        val fresh = BatterySample(now - 1000, 60, null, false)
        assertEquals(listOf(fresh), BatteryStatsLogic.prune(listOf(old, fresh), now))
        val many = (0 until 3500).map { BatterySample(now - 3500 + it, 50, null, false) }
        assertEquals(BatteryStatsLogic.MAX_SAMPLES, BatteryStatsLogic.prune(many, now).size)
    }

    @Test fun summaryDischargeRateAndRemaining() {
        // 4 Prozentpunkte in 2 Stunden = 2 %/h, Stand 90 -> 45 h
        val samples = listOf(s(0, 94), s(60, 92), s(120, 90))
        val summary = BatteryStatsLogic.summarize(samples)
        assertEquals(2.0, summary.dischargePctPerHour!!, 1e-9)
        assertEquals(45.0, summary.hoursRemaining!!, 1e-9)
        assertEquals(0, summary.chargeSessions)
        assertEquals(30.0, summary.avgTempC!!, 1e-9)
        assertEquals(90, summary.minLevel)
        assertEquals(120 * 60_000L, summary.spanMillis)
    }

    @Test fun summaryChargingSessionsAndRate() {
        val samples = listOf(s(0, 50), s(10, 52, true), s(40, 62, true), s(70, 72, true), s(100, 70))
        val summary = BatteryStatsLogic.summarize(samples)
        assertEquals(1, summary.chargeSessions)
        assertEquals(20.0, summary.chargePctPerHour!!, 1e-9)
        assertNull(summary.dischargePctPerHour)
    }

    @Test fun largeGapsAreIgnoredForRates() {
        val samples = listOf(s(0, 90), s(600, 50), s(660, 49), s(720, 48))
        val summary = BatteryStatsLogic.summarize(samples)
        assertEquals(1.0, summary.dischargePctPerHour!!, 1e-9)
    }

    @Test fun emptyAndShortHistory() {
        assertEquals(0, BatteryStatsLogic.summarize(emptyList()).sampleCount)
        val one = BatteryStatsLogic.summarize(listOf(s(0, 80)))
        assertNull(one.dischargePctPerHour)
        assertNull(one.hoursRemaining)
        assertNotNull(one.avgTempC)
    }

    @Test fun codecRoundTripAndTolerance() {
        val samples = listOf(s(0, 80), s(5, 79, true, null))
        assertEquals(samples, BatteryHistoryCodec.decode(BatteryHistoryCodec.encode(samples)))
        val decoded = BatteryHistoryCodec.decode("1,50,300,0\nkaputt\n2,150,300,0\n3,40,abc,1\n4,30,300,x\n")
        assertEquals(2, decoded.size)
        assertNull(decoded[1].tempTenthsC)
        assertTrue(BatteryHistoryCodec.decode(null).isEmpty())
    }
}
