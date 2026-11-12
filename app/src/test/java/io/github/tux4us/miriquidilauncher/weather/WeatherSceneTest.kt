// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSceneTest {

    private fun scene(code: Int, isDay: Boolean = true, cover: Int? = null, wind: Double = 10.0, dir: Double? = 270.0) =
        WeatherScene.build(code, isDay, cover, wind, dir)

    private fun brightness(color: Int): Int = ((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)

    @Test fun clearDayShowsSunOnly() {
        val s = scene(0)
        assertTrue(s.sun)
        assertFalse(s.moon)
        assertEquals(0f, s.stars, 0f)
        assertEquals(0f, s.rain, 0f)
        assertFalse(s.lightning)
    }

    @Test fun clearNightShowsMoonAndStars() {
        val s = scene(0, isDay = false)
        assertTrue(s.moon)
        assertFalse(s.sun)
        assertTrue(s.stars > 0.9f)
    }

    @Test fun overcastHasNoSunOrMoon() {
        val day = scene(3)
        assertFalse(day.sun)
        assertEquals(1f, day.cloudFrac, 0f)
        assertFalse(scene(3, isDay = false).moon)
    }

    @Test fun cloudCoverOverridesCodeForSkyCodes() {
        assertEquals(0.8f, scene(0, cover = 80).cloudFrac, 1e-6f)
        assertEquals(1f, scene(1, cover = 150).cloudFrac, 1e-6f)
        assertEquals(0.2f, scene(1).cloudFrac, 1e-6f)
    }

    @Test fun cloudCoverIgnoredForPrecipitation() {
        assertEquals(0.9f, scene(61, cover = 10).cloudFrac, 1e-6f)
    }

    @Test fun rainIntensityGrowsWithCode() {
        assertTrue(scene(61).rain < scene(63).rain)
        assertTrue(scene(63).rain < scene(65).rain)
        assertEquals(1f, scene(65).rain, 0f)
        assertEquals(0f, scene(65).snow, 0f)
    }

    @Test fun snowHasNoRain() {
        val s = scene(73)
        assertTrue(s.snow > 0f)
        assertEquals(0f, s.rain, 0f)
        assertTrue(scene(71).snow < scene(75).snow)
    }

    @Test fun thunderstormHasLightningAndRain() {
        for (code in intArrayOf(95, 96, 99)) {
            val s = scene(code)
            assertTrue(s.lightning)
            assertTrue(s.rain > 0.5f)
            assertEquals(1f, s.cloudFrac, 0f)
        }
        assertFalse(scene(65).lightning)
    }

    @Test fun fogCodes() {
        assertEquals(1f, scene(45).fog, 0f)
        assertEquals(1f, scene(48).fog, 0f)
        assertEquals(0f, scene(61).fog, 0f)
    }

    @Test fun unknownCodeFallsBackToCloudCover() {
        assertEquals(0.3f, scene(1234, cover = 30).cloudFrac, 1e-6f)
        assertEquals(0.9f, scene(1234).cloudFrac, 1e-6f)
    }

    @Test fun nightIsDarkerThanDay() {
        for (code in intArrayOf(0, 3, 45, 61, 73, 95)) {
            assertTrue("Code $code", brightness(scene(code, isDay = false).skyBottom) < brightness(scene(code, isDay = true).skyBottom))
        }
    }

    @Test fun windDirectionSetsSlantAndDrift() {
        val fromWest = scene(61, wind = 40.0, dir = 270.0)
        val fromEast = scene(61, wind = 40.0, dir = 90.0)
        assertTrue(fromWest.slant > 0f && fromWest.drift > 0f)
        assertTrue(fromEast.slant < 0f && fromEast.drift < 0f)
        assertEquals(-fromWest.slant, fromEast.slant, 1e-6f)
    }

    @Test fun stormWindSlantsMoreThanBreeze() {
        assertTrue(scene(61, wind = 50.0).slant > scene(61, wind = 5.0).slant)
        // Obergrenze: Wind über 50 km/h verstärkt die Neigung nicht weiter
        assertEquals(scene(61, wind = 50.0).slant, scene(61, wind = 120.0).slant, 1e-6f)
    }

    @Test fun northWindKeepsMinimumDirection() {
        val s = scene(61, wind = 20.0, dir = 0.0)
        assertTrue(s.slant > 0f)
        assertTrue(s.drift > 0f)
    }

    @Test fun missingDirectionDefaultsToWest() {
        val s = scene(61, wind = 20.0, dir = null)
        assertTrue(s.slant > 0f)
    }

    @Test fun lerpColorEndpointsAndMiddle() {
        val a = 0xFF000000.toInt()
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, WeatherScene.lerpColor(a, b, 0f))
        assertEquals(b, WeatherScene.lerpColor(a, b, 1f))
        assertEquals(0xFF7F7F7F.toInt(), WeatherScene.lerpColor(a, b, 0.5f))
        assertEquals(b, WeatherScene.lerpColor(a, b, 2f))
        assertEquals(a, WeatherScene.lerpColor(a, b, -1f))
    }

    @Test fun lerpColorKeepsAlpha() {
        val c = WeatherScene.lerpColor(0x80FF0000.toInt(), 0x80FF0000.toInt(), 0.3f)
        assertEquals(0x80FF0000.toInt(), c)
    }
}
