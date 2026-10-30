// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunMathTest {

    @Test fun noonSummerBerlin() {
        // 21.06., 13:08 MESZ ≈ Sonnenhöchststand in Berlin (52,5° N, 13,4° O): Höhe ca. 61°, Azimut ca. 180°
        val a = SunMath.solarAngles(52.52, 13.405, LocalDateTime.of(2026, 6, 21, 13, 8), 7200)
        assertEquals(61.0, a.elevationDeg, 1.5)
        assertEquals(180.0, a.azimuthDeg, 6.0)
    }

    @Test fun midnightSunBelowHorizon() {
        val a = SunMath.solarAngles(52.52, 13.405, LocalDateTime.of(2026, 12, 21, 0, 0), 3600)
        assertTrue(a.elevationDeg < 0)
        assertNull(SunMath.shadowLength(1.75, a.elevationDeg))
    }

    @Test fun morningAzimuthIsEastAfternoonWest() {
        val morning = SunMath.solarAngles(48.0, 11.0, LocalDateTime.of(2026, 9, 23, 8, 0), 7200)
        val evening = SunMath.solarAngles(48.0, 11.0, LocalDateTime.of(2026, 9, 23, 17, 0), 7200)
        assertTrue(morning.azimuthDeg in 60.0..130.0)
        assertTrue(evening.azimuthDeg in 230.0..300.0)
    }

    @Test fun sunriseAzimuthSeasons() {
        assertTrue(SunMath.sunriseAzimuth(52.0, LocalDate.of(2026, 6, 21)) < 60)
        assertTrue(SunMath.sunriseAzimuth(52.0, LocalDate.of(2026, 12, 21)) > 120)
        assertEquals(90.0, SunMath.sunriseAzimuth(52.0, LocalDate.of(2026, 3, 21)), 3.0)
    }

    @Test fun shadowAndLevels() {
        assertEquals(1.0, SunMath.shadowLength(1.0, 45.0)!!, 1e-9)
        assertEquals(0, SunMath.elevationLevel(-1.0)); assertEquals(1, SunMath.elevationLevel(5.0))
        assertEquals(2, SunMath.elevationLevel(10.0)); assertEquals(4, SunMath.elevationLevel(60.0))
    }

    @Test fun compass16() {
        assertEquals(0, SunMath.compass16Index(0.0)); assertEquals(0, SunMath.compass16Index(359.0))
        assertEquals(4, SunMath.compass16Index(90.0)); assertEquals(8, SunMath.compass16Index(180.0))
        assertEquals(12, SunMath.compass16Index(270.0)); assertEquals(1, SunMath.compass16Index(22.5))
    }

    @Test fun daylightValues() {
        val d = SunMath.daylight(
            LocalDateTime.of(2026, 6, 21, 5, 0), LocalDateTime.of(2026, 6, 21, 21, 30),
            LocalDateTime.of(2026, 6, 20, 5, 0) to LocalDateTime.of(2026, 6, 20, 21, 30),
        )
        assertEquals(16 * 60 + 30L, d.duration.toMinutes())
        assertEquals(LocalDateTime.of(2026, 6, 21, 4, 25), d.dawn)
        assertEquals(LocalDateTime.of(2026, 6, 21, 22, 5), d.dusk)
        assertEquals(0L, d.trendMinutes)
        assertEquals(LocalDateTime.of(2026, 6, 21, 6, 0), d.goldenMorningEnd)
        assertEquals(LocalDateTime.of(2026, 6, 21, 20, 30), d.goldenEveningStart)
        assertNull(SunMath.daylight(d.dawn, d.dusk, null).trendMinutes)
    }

    @Test fun zodiacInRange() {
        val z = WeatherMath.moonZodiacIndex(java.time.Instant.parse("2026-10-02T12:00:00Z"))
        assertTrue(z in 0..11); assertNotNull(z)
    }
}
