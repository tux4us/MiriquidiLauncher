// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogWeatherTest {
    private val day = LocalDate.of(2026, 7, 15)

    private fun hour(
        h: Int, temp: Double = 20.0, feels: Double = temp, wind: Double = 5.0, gusts: Double = 10.0,
        rain: Double = 0.0, code: Int = 0, cape: Double = 0.0, prob: Int = 0, radiation: Double? = 0.0,
    ) = HourPoint(
        time = day.atTime(h, 0), temperature = temp, feelsLike = feels, precipitation = rain,
        precipitationProbability = prob, weatherCode = code, windSpeed = wind, windGusts = gusts,
        windDirection = 180.0, cape = cape, pressure = 1013.0, uvIndex = 0.0, shortwaveRadiation = radiation,
    )

    private fun dayPoint(max: Double = 25.0) = DayPoint(
        date = day, temperatureMin = 10.0, temperatureMax = max, weatherCode = 0, precipitationProbabilityMax = 0,
        windSpeedMax = 10.0, windGustsMax = 20.0, uvIndexMax = 5.0, sunshineMinutes = 600.0,
        sunrise = day.atTime(5, 30), sunset = day.atTime(21, 30),
    )

    @Test fun surfaceMatchesReferenceTable() {
        // Vier Pfoten: 25 °C -> ca. 52 °C, 31 °C -> ca. 62 °C, 35 °C -> ca. 65 °C (windstill, volle Sonne ~900 W/m²)
        assertEquals(52.0, DogWeather.surfaceTemperature(25.0, 900.0, 0.0), 5.0)
        assertEquals(62.0, DogWeather.surfaceTemperature(31.0, 900.0, 0.0), 5.0)
        assertEquals(65.0, DogWeather.surfaceTemperature(35.0, 900.0, 0.0), 6.0)
    }

    @Test fun windAndNightCoolSurface() {
        assertTrue(DogWeather.surfaceTemperature(25.0, 900.0, 30.0) < DogWeather.surfaceTemperature(25.0, 900.0, 0.0))
        assertEquals(20.0, DogWeather.surfaceTemperature(20.0, 0.0, 0.0), 0.001)
        assertEquals(20.0, DogWeather.surfaceTemperature(20.0, -50.0, -5.0), 0.001)
    }

    @Test fun pavementThresholds() {
        assertEquals(DogLevel.GOOD, DogWeather.pavementLevel(39.9))
        assertEquals(DogLevel.CAUTION, DogWeather.pavementLevel(40.0))
        assertEquals(DogLevel.AVOID, DogWeather.pavementLevel(50.0))
    }

    @Test fun estimateRadiationUsesElevationAndClouds() {
        assertEquals(0.0, DogWeather.estimateRadiation(-5.0, 0), 0.0)
        assertTrue(DogWeather.estimateRadiation(60.0, 0) > DogWeather.estimateRadiation(60.0, 3))
        assertEquals(1000.0, DogWeather.estimateRadiation(90.0, 0), 0.001)
    }

    @Test fun mildHourIsGood() {
        val r = DogWeather.rate(hour(10), 100.0, true)
        assertEquals(DogLevel.GOOD, r.level)
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun hotSunnyHourFlagsPavementAndHeat() {
        val r = DogWeather.rate(hour(14, temp = 32.0, feels = 33.0, wind = 3.0), 900.0, true)
        assertEquals(DogLevel.AVOID, r.level)
        assertTrue(DogReason.PAVEMENT in r.reasons)
        assertTrue(DogReason.HEAT in r.reasons)
    }

    @Test fun gustsColdRainAndThunder() {
        assertEquals(DogLevel.CAUTION, DogWeather.rate(hour(10, gusts = 65.0), 0.0, true).level)
        assertEquals(DogLevel.AVOID, DogWeather.rate(hour(10, gusts = 80.0), 0.0, true).level)
        assertEquals(DogLevel.CAUTION, DogWeather.rate(hour(10, temp = -5.0, feels = -12.0), 0.0, true).level)
        assertEquals(DogLevel.AVOID, DogWeather.rate(hour(10, temp = -15.0, feels = -22.0), 0.0, true).level)
        assertEquals(DogLevel.CAUTION, DogWeather.rate(hour(10, rain = 8.0), 0.0, true).level)
        assertEquals(DogLevel.AVOID, DogWeather.rate(hour(10, code = 95, cape = 2000.0, prob = 90), 0.0, true).level)
    }

    @Test fun daylightWindow() {
        assertTrue(DogWeather.isDaylight(day.atTime(4, 30), listOf(dayPoint())))
        assertTrue(!DogWeather.isDaylight(day.atTime(3, 0), listOf(dayPoint())))
        assertTrue(DogWeather.isDaylight(day.atTime(22, 30), listOf(dayPoint())))
        assertTrue(!DogWeather.isDaylight(day.atTime(23, 0), listOf(dayPoint())))
        assertTrue(DogWeather.isDaylight(day.atTime(3, 0), emptyList()))
    }

    @Test fun forecastFindsMorningAndEveningWindowsOnHotDay() {
        val hours = (0..23).map { h ->
            val sun = if (h in 8..19) 850.0 else if (h in 6..7 || h in 20..21) 150.0 else 0.0
            hour(h, temp = if (h in 10..18) 33.0 else 20.0, feels = if (h in 10..18) 34.0 else 20.0, wind = 3.0, radiation = sun)
        }
        val f = DogWeather.forecast(hours, listOf(dayPoint(33.0)), 50.0, 8.0, 7200, day.atTime(0, 0))
        assertEquals(24, f.hours.size)
        assertEquals(DogLevel.GOOD, f.windowsLevel)
        assertTrue(f.windows.size >= 2)
        // kein Fenster in der Mittagshitze
        assertTrue(f.windows.none { it.start.hour in 11..16 })
        assertNotNull(f.peakSurface)
        assertTrue(f.peakSurface!!.surfaceC >= 50.0)
    }

    @Test fun heatStorageKeepsEveningAsphaltWarm() {
        val hours = (0..23).map { h -> hour(h, temp = 30.0, wind = 0.0, radiation = if (h <= 18) 900.0 else 0.0) }
        val f = DogWeather.forecast(hours, listOf(dayPoint(30.0)), 50.0, 8.0, 7200, day.atTime(0, 0))
        val at19 = f.hours.first { it.time.hour == 19 }
        val at23 = f.hours.first { it.time.hour == 23 }
        assertTrue(at19.surfaceC > 30.0 + 5.0)
        assertEquals(30.0, at23.surfaceC, 0.001)
    }

    @Test fun fallsBackToCautionWindowAndNone() {
        val warm = (0..23).map { h -> hour(h, temp = 20.0, radiation = if (h in 8..19) 1000.0 else 0.0, wind = 0.0) }
        val f = DogWeather.forecast(warm, listOf(dayPoint(20.0)), 50.0, 8.0, 7200, day.atTime(0, 0))
        assertNotNull(f.windowsLevel)
        val storm = (0..23).map { h -> hour(h, gusts = 90.0) }
        val g = DogWeather.forecast(storm, listOf(dayPoint()), 50.0, 8.0, 7200, day.atTime(0, 0))
        assertNull(g.windowsLevel)
        assertTrue(g.windows.isEmpty())
    }

    @Test fun missingRadiationIsEstimated() {
        val hours = (0..23).map { h -> hour(h, temp = 30.0, wind = 0.0, radiation = null) }
        val f = DogWeather.forecast(hours, listOf(dayPoint(30.0)), 50.0, 8.0, 7200, day.atTime(0, 0))
        assertTrue(f.hours.first { it.time.hour == 13 }.surfaceC > f.hours.first { it.time.hour == 2 }.surfaceC)
    }

    @Test fun tickThreshold() {
        assertEquals(true, DogWeather.tick(listOf(dayPoint(7.1)), day)!!.active)
        assertEquals(false, DogWeather.tick(listOf(dayPoint(7.0)), day)!!.active)
        assertNull(DogWeather.tick(emptyList(), day))
    }

    @Test fun worstPicksHighest() {
        val hs = listOf(
            DogWeather.rate(hour(10), 0.0, true),
            DogWeather.rate(hour(11, gusts = 80.0), 0.0, true),
        )
        assertEquals(DogLevel.AVOID, DogWeather.worst(hs))
        assertEquals(DogLevel.GOOD, DogWeather.worst(emptyList()))
    }
}
