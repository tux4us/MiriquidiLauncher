// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherInsightsTest {

    private val day = LocalDate.of(2026, 6, 10)
    private val t0 = day.atStartOfDay()

    private fun hour(
        h: Int,
        temp: Double = 15.0,
        precip: Double = 0.0,
        gust: Double = 10.0,
        speed: Double = 8.0,
        dir: Double = 270.0,
        uv: Double = 0.0,
        pressure: Double? = 1000.0,
        date: LocalDate = day,
    ) = HourPoint(
        time = date.atStartOfDay().plusHours(h.toLong()), temperature = temp, feelsLike = temp, precipitation = precip,
        precipitationProbability = 0, weatherCode = 0, windSpeed = speed, windGusts = gust, windDirection = dir,
        cape = 0.0, pressure = pressure, uvIndex = uv,
    )

    private fun dayHours(date: LocalDate = day, build: (Int) -> HourPoint) = (0 until 24).map(build)

    private fun dayPoint(date: LocalDate = day, rise: Int = 5, set: Int = 21) = DayPoint(
        date = date, temperatureMin = 8.0, temperatureMax = 20.0, weatherCode = 0, precipitationProbabilityMax = 0,
        windSpeedMax = 10.0, windGustsMax = 20.0, uvIndexMax = 5.0, sunshineMinutes = 600.0,
        sunrise = date.atStartOfDay().plusHours(rise.toLong()), sunset = date.atStartOfDay().plusHours(set.toLong()),
    )

    // --- Niederschlag ---

    @Test fun dryDayHasNoWindow() {
        val hours = dayHours { hour(it) }
        val o = WeatherInsights.rainOutlook(hours, t0)
        assertEquals(WeatherInsights.RainState.DRY, o.state)
        assertNull(o.first)
        assertEquals(0.0, o.totalMm, 1e-9)
    }

    @Test fun rainLaterWindowAndEnd() {
        val hours = dayHours { hour(it, precip = if (it in 15..16) 1.2 else 0.0) }
        val o = WeatherInsights.rainOutlook(hours, t0.plusHours(8))
        assertEquals(WeatherInsights.RainState.RAIN_LATER, o.state)
        assertEquals(t0.plusHours(15), o.first!!.start)
        assertEquals(t0.plusHours(17), o.first!!.end)
        assertEquals(2.4, o.first!!.totalMm, 1e-9)
        assertEquals(1.2, o.first!!.peakMm, 1e-9)
        assertTrue(o.endsWithinHorizon)
    }

    @Test fun rainingNowAndContinues() {
        val hours = dayHours { hour(it, precip = if (it >= 10) 0.5 else 0.0) }
        val o = WeatherInsights.rainOutlook(hours, t0.plusHours(10))
        assertEquals(WeatherInsights.RainState.RAINING_NOW, o.state)
        assertFalse(o.endsWithinHorizon)
    }

    @Test fun singleDryHourDoesNotSplitWindow() {
        val hours = dayHours { hour(it, precip = if (it == 10 || it == 12) 0.4 else 0.0) }
        val w = WeatherInsights.rainWindows(hours, t0)
        assertEquals(1, w.size)
        assertEquals(t0.plusHours(10), w[0].start)
        assertEquals(t0.plusHours(13), w[0].end)
    }

    @Test fun twoDryHoursSplitWindows() {
        val hours = dayHours { hour(it, precip = if (it == 10 || it == 13) 0.4 else 0.0) }
        val o = WeatherInsights.rainOutlook(hours, t0)
        assertNotNull(o.next)
        assertEquals(t0.plusHours(13), o.next!!.start)
    }

    @Test fun traceBelowThresholdIsDry() {
        val hours = dayHours { hour(it, precip = 0.05) }
        assertTrue(WeatherInsights.rainWindows(hours, t0).isEmpty())
    }

    @Test fun emptyInput() {
        assertEquals(WeatherInsights.RainState.DRY, WeatherInsights.rainOutlook(emptyList(), t0).state)
        assertNull(WeatherInsights.extremes(emptyList()))
        assertNull(WeatherInsights.windOutlook(emptyList()))
    }

    // --- Temperatur und Wind ---

    @Test fun extremesPickMaxAndMin() {
        val hours = dayHours { hour(it, temp = if (it == 15) 22.0 else if (it == 4) 6.0 else 14.0) }
        val e = WeatherInsights.extremes(hours)!!
        assertEquals(15, e.max.time.hour)
        assertEquals(4, e.min.time.hour)
    }

    @Test fun windPeakAndStrongGust() {
        val hours = dayHours { hour(it, gust = if (it == 18) 62.0 else 20.0, speed = if (it == 18) 35.0 else 12.0) }
        val w = WeatherInsights.windOutlook(hours)!!
        assertEquals(18, w.peakGust.time.hour)
        assertTrue(w.strongGust)
        assertEquals(18, w.peakSpeed.time.hour)
    }

    @Test fun gustBelowThresholdIsNotStrong() {
        val hours = dayHours { hour(it, gust = 49.0) }
        assertFalse(WeatherInsights.windOutlook(hours)!!.strongGust)
    }

    @Test fun directionShiftOverNorth() {
        // 350° -> 20° sind 30° Unterschied (kein Wechsel); 270° -> 350° sind 80°
        val small = dayHours { hour(it, dir = if (it == 0) 350.0 else if (it == 23) 20.0 else 0.0) }
        assertFalse(WeatherInsights.windOutlook(small)!!.directionShift)
        val big = dayHours { hour(it, dir = if (it == 0) 270.0 else if (it == 23) 350.0 else 300.0) }
        assertTrue(WeatherInsights.windOutlook(big)!!.directionShift)
    }

    @Test fun calmWindIgnoresDirection() {
        val hours = dayHours { hour(it, speed = 2.0, dir = if (it == 0) 0.0 else 180.0) }
        assertFalse(WeatherInsights.windOutlook(hours)!!.directionShift)
    }

    @Test fun angleDiffWrapsAround() {
        assertEquals(20.0, WeatherInsights.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, WeatherInsights.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(0.0, WeatherInsights.angleDiff(720.0, 0.0), 1e-9)
    }

    // --- UV ---

    @Test fun uvWindowAroundNoon() {
        val hours = dayHours { hour(it, uv = when (it) { in 11..14 -> 5.5; 10, 15 -> 2.9; else -> 0.0 }) }
        val w = WeatherInsights.uvWindow(hours, t0)!!
        assertEquals(t0.plusHours(11), w.start)
        assertEquals(t0.plusHours(15), w.end)
        assertEquals(5.5, w.peakUv, 1e-9)
    }

    @Test fun uvWindowNullWhenLow() {
        assertNull(WeatherInsights.uvWindow(dayHours { hour(it, uv = 2.9) }, t0))
    }

    @Test fun uvWindowStartsAtFirstHourWhenAlreadyHigh() {
        val hours = dayHours { hour(it, uv = if (it in 10..16) 4.0 else 0.0) }
        val w = WeatherInsights.uvWindow(hours, t0.plusHours(12))!!
        assertEquals(t0.plusHours(12), w.start)
        assertEquals(t0.plusHours(17), w.end)
    }

    // --- Luftdruck ---

    @Test fun pressureFallingTrend() {
        val hours = dayHours { hour(it, pressure = 1015.0 - it * 0.5) }
        val p = WeatherInsights.pressureOutlook(hours, t0.plusHours(6))!!
        assertEquals(WeatherInsights.PressureTrend.FALLING, p.trend)
        assertEquals(-8.5, p.changeAhead, 1e-9)
        assertEquals(-1.5, p.change3hPast!!, 1e-9)
    }

    @Test fun pressureSteadyAndRising() {
        assertEquals(
            WeatherInsights.PressureTrend.STEADY,
            WeatherInsights.pressureOutlook(dayHours { hour(it, pressure = 1010.0 + it * 0.1) }, t0)!!.trend,
        )
        assertEquals(
            WeatherInsights.PressureTrend.RISING,
            WeatherInsights.pressureOutlook(dayHours { hour(it, pressure = 1000.0 + it * 0.5) }, t0)!!.trend,
        )
    }

    @Test fun pressureNullWithoutData() {
        assertNull(WeatherInsights.pressureOutlook(dayHours { hour(it, pressure = null) }, t0))
        assertNull(WeatherInsights.pressureOutlook(dayHours { hour(it) }.take(4), t0))
    }

    @Test fun pressureTrendBoundaries() {
        assertEquals(WeatherInsights.PressureTrend.RISING, WeatherInsights.pressureTrend(1.0))
        assertEquals(WeatherInsights.PressureTrend.STEADY, WeatherInsights.pressureTrend(0.99))
        assertEquals(WeatherInsights.PressureTrend.FALLING, WeatherInsights.pressureTrend(-1.0))
    }

    // --- Tageswerte ---

    @Test fun dayStatsSumAndAverages() {
        val hours = dayHours { hour(it, temp = if (it in 5..20) 20.0 else 10.0, precip = if (it in 8..9) 1.0 else 0.0) }
        val s = WeatherInsights.dayStats(hours, dayPoint())!!
        assertEquals(2.0, s.precipSumMm, 1e-9)
        assertEquals(2, s.wetHours)
        assertEquals(20.0, s.dayAvg!!, 1e-9)
        assertEquals(10.0, s.nightAvg!!, 1e-9)
    }

    @Test fun dayStatsLongestDryDaylightWindow() {
        // nass um 8 und 14: trockene Stücke 5–7 (3 h), 9–13 (5 h), 15–20 (6 h)
        val hours = dayHours { hour(it, precip = if (it == 8 || it == 14) 1.0 else 0.0) }
        val s = WeatherInsights.dayStats(hours, dayPoint())!!
        assertEquals(day.atStartOfDay().plusHours(15), s.dryStart)
        assertEquals(day.atStartOfDay().plusHours(21), s.dryEnd)
    }

    @Test fun dayStatsNoDryWindowWhenAlwaysWet() {
        val s = WeatherInsights.dayStats(dayHours { hour(it, precip = 1.0) }, dayPoint())!!
        assertNull(s.dryStart)
        assertNull(s.dryEnd)
    }

    @Test fun dayStatsIncompleteDayIsNull() {
        assertNull(WeatherInsights.dayStats(dayHours { hour(it) }.take(12), dayPoint()))
    }

    @Test fun dayStatsOtherDateIgnored() {
        val other = day.plusDays(1)
        val hours = dayHours { hour(it, date = other, precip = 1.0) } + dayHours { hour(it, precip = 0.0) }
        assertEquals(0.0, WeatherInsights.dayStats(hours, dayPoint())!!.precipSumMm, 1e-9)
    }

    @Test fun dayStatsWithoutSunTimesUsesFixedDaylight() {
        val noSun = dayPoint().copy(sunrise = null, sunset = null)
        val hours = dayHours { hour(it, temp = if (it in 6..20) 18.0 else 9.0) }
        val s = WeatherInsights.dayStats(hours, noSun)!!
        assertEquals(18.0, s.dayAvg!!, 1e-9)
        assertEquals(9.0, s.nightAvg!!, 1e-9)
    }

    // --- Luft und Pollen ---

    private fun air(date: LocalDate, h: Int, pm25: Double, birch: Double = 0.0, grass: Double = 0.0) = AirHour(
        time = date.atStartOfDay().plusHours(h.toLong()), pm25 = pm25, pm10 = 0.0, no2 = 0.0, ozone = 0.0,
        pollen = mapOf(PollenType.BIRCH to birch, PollenType.GRASS to grass),
    )

    @Test fun airDaysPerDayMaximum() {
        val d2 = day.plusDays(1)
        val list = listOf(
            air(day, 0, 4.0), air(day, 12, 12.0, birch = 30.0, grass = 60.0),
            air(d2, 0, 30.0), air(d2, 12, 8.0),
        )
        val days = WeatherInsights.airDays(list, day)
        assertEquals(2, days.size)
        assertEquals(1, days[0].aqiLevel)
        assertEquals(PollenType.GRASS, days[0].topPollen)
        assertEquals(3, days[0].pollenLevel)
        assertEquals(3, days[1].aqiLevel)
        assertNull(days[1].topPollen)
        assertEquals(0, days[1].pollenLevel)
    }

    @Test fun airDaysSkipsPastAndHandlesEmpty() {
        val list = listOf(air(day.minusDays(1), 0, 99.0), air(day, 0, 5.0))
        assertEquals(1, WeatherInsights.airDays(list, day).size)
        assertTrue(WeatherInsights.airDays(emptyList(), day).isEmpty())
    }
}
