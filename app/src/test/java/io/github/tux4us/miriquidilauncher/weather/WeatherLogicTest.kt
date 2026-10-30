// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {

    @Test fun miniJsonParsesNestedValues() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":{"c":"x\"yä"},"d":null,"e":true}""").asObject()!!
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"].asArray())
        assertEquals("x\"yä", v["b"].asObject()!!["c"])
        assertNull(v["d"])
        assertEquals(true, v["e"])
    }

    @Test(expected = MiniJson.JsonException::class) fun miniJsonRejectsGarbage() { MiniJson.parse("{\"a\":") }

    @Test fun windchillAndBeaufort() {
        assertEquals(10.0, WeatherMath.windchill(10.0, 30.0), 1e-9)
        assertEquals(-5.2, WeatherMath.windchill(0.0, 20.0), 0.1)
        assertEquals(0, WeatherMath.beaufort(0.5))
        assertEquals(5, WeatherMath.beaufort(30.0))
        assertEquals(12, WeatherMath.beaufort(200.0))
    }

    @Test fun compass() {
        assertEquals(0, WeatherMath.compassIndex(0.0))
        assertEquals(0, WeatherMath.compassIndex(359.0))
        assertEquals(2, WeatherMath.compassIndex(90.0))
        assertEquals(6, WeatherMath.compassIndex(-90.0))
    }

    @Test fun codesAndLevels() {
        assertEquals(0, WeatherMath.weatherCodeIndex(0))
        assertNull(WeatherMath.weatherCodeIndex(4))
        assertEquals(27, WeatherMath.weatherCodeIndex(99))
        assertEquals(0, WeatherMath.aqiLevel(5.0)); assertEquals(5, WeatherMath.aqiLevel(100.0))
        assertEquals(0, WeatherMath.pollenLevel(0.0)); assertEquals(4, WeatherMath.pollenLevel(500.0))
        assertEquals(2, WeatherMath.uvLevel(3.0)); assertEquals(5, WeatherMath.uvLevel(12.0))
    }

    @Test fun dewPointAndHeatIndex() {
        assertEquals(12.0, WeatherMath.dewPoint(20.0, 60), 0.3)
        assertEquals(20.0, WeatherMath.dewPoint(20.0, 100), 0.1)
        assertEquals(45.0, WeatherMath.heatIndex(35.0, 60), 1.0)
    }

    @Test fun pressureTendency() {
        assertNull(WeatherMath.pressureTendency3h(listOf(1000.0, 1001.0)))
        assertEquals(-1.0, WeatherMath.pressureTendency3h(listOf(1010.0, 1008.0, 1006.0, 1005.0, 1007.0))!!, 1e-9)
    }

    @Test fun moonKnownDates() {
        // Vollmond 2024-01-25 17:54 UTC, Neumond 2024-01-11 11:57 UTC
        val full = WeatherMath.moon(Instant.parse("2024-01-25T17:54:00Z"))
        assertEquals(4, full.phaseIndex); assertTrue(full.illuminationPercent > 98)
        val new = WeatherMath.moon(Instant.parse("2024-01-11T11:57:00Z"))
        assertEquals(0, new.phaseIndex); assertTrue(new.illuminationPercent < 2)
        val firstQuarter = WeatherMath.moon(Instant.parse("2024-01-18T03:52:00Z"))
        assertEquals(2, firstQuarter.phaseIndex)
    }

    @Test fun thunderClassification() {
        assertEquals(ThunderLevel.HIGH, Thunderstorm.classify(0.0, 0, 95))
        assertEquals(ThunderLevel.LOW, Thunderstorm.classify(3000.0, 10, 1).let { if (it == ThunderLevel.MODERATE) ThunderLevel.LOW else it })
        assertEquals(ThunderLevel.HIGH, Thunderstorm.classify(2600.0, 80, 65))
        assertEquals(ThunderLevel.MODERATE, Thunderstorm.classify(1200.0, 20, 63))
        assertEquals(ThunderLevel.MODERATE, Thunderstorm.classify(1200.0, 60, 3))
        assertEquals(ThunderLevel.LOW, Thunderstorm.classify(100.0, 10, 3))
    }

    private fun hour(t: LocalDateTime, cape: Double, code: Int, prob: Int) = HourPoint(
        t, 20.0, 20.0, 0.0, prob, code, 5.0, 8.0, 180.0, cape, 1000.0, 0.0,
    )

    @Test fun thunderForecastWindow() {
        val base = LocalDateTime.of(2026, 7, 1, 10, 0)
        val hours = (0 until 30).map { hour(base.plusHours(it.toLong()), if (it == 5) 3000.0 else 0.0, if (it == 5) 95 else 1, 10) } 
        val f = Thunderstorm.forecast(hours, base.plusMinutes(30), 12)
        assertEquals(ThunderLevel.HIGH, f.maxLevel)
        assertEquals(base.plusHours(5), f.peak!!.hour.time)
        val none = Thunderstorm.forecast(hours, base.plusHours(7), 12)
        assertEquals(ThunderLevel.LOW, none.maxLevel); assertNull(none.peak)
    }

    @Test fun biowetter() {
        val r = Biowetter.evaluate(listOf(1015.0, 1013.0, 1011.0, 1010.0), 30.0, 70, 5.0, 0.0, 1, 0)
        assertEquals(BioLevel.MODERATE, r.indicators.first { it.kind == BioKind.PRESSURE }.level)
        assertTrue(r.indicators.any { it.kind == BioKind.HEAT })
        assertTrue(r.overall >= BioLevel.MODERATE)
        val calm = Biowetter.evaluate(listOf(1015.0, 1015.0, 1015.0, 1015.0), 15.0, 50, 5.0, -1.0, 1, 0)
        assertEquals(BioLevel.NONE, calm.overall)
        assertTrue(calm.indicators.none { it.kind == BioKind.CAPE })
    }

    @Test fun placeCodec() {
        val a = GeoPlace("Berlin, Deutschland", 52.52, 13.41)
        val b = GeoPlace("Köln", 50.94, 6.96)
        val list = PlaceCodec.decode(PlaceCodec.encode(listOf(a, b)))
        assertEquals(listOf(a, b), list)
        assertEquals(listOf(a, b), PlaceCodec.add(list, GeoPlace("Berlin X", 52.5201, 13.4101)))
        assertEquals(listOf(b), PlaceCodec.remove(list, a))
        assertTrue(PlaceCodec.decode("müll").isEmpty())
        assertTrue(PlaceCodec.decode(null).isEmpty())
    }

    @Test fun parserForecastAirGeocoding() {
        val json = """{"utc_offset_seconds":7200,
          "current":{"temperature_2m":21.5,"apparent_temperature":20.0,"relative_humidity_2m":55,"precipitation":0.0,"weather_code":2,"surface_pressure":1012.3,"wind_speed_10m":12.0,"wind_direction_10m":270,"wind_gusts_10m":25.0,"uv_index":4.2,"is_day":1},
          "hourly":{"time":["2026-07-01T10:00","2026-07-01T11:00"],"temperature_2m":[21.0,22.0],"cape":[500,1500],"surface_pressure":[1012,1011],"precipitation_probability":[10,60],"weather_code":[1,95]},
          "daily":{"time":["2026-07-01"],"temperature_2m_max":[25.0],"temperature_2m_min":[14.0],"weather_code":[2],"sunrise":["2026-07-01T05:12"],"sunset":["2026-07-01T21:30"]}}"""
        val f = OpenMeteoParser.parseForecast(json)
        assertEquals(7200, f.utcOffsetSeconds)
        assertEquals(21.5, f.current.temperature, 0.0)
        assertTrue(f.current.isDay)
        assertEquals(2, f.hours.size)
        assertEquals(95, f.hours[1].weatherCode)
        assertEquals(1, f.days.size)
        assertEquals(25.0, f.days[0].temperatureMax, 0.0)
        assertNotNull(f.days[0].sunrise)

        val air = OpenMeteoParser.parseAirQuality("""{"hourly":{"time":["2026-07-01T10:00"],"pm2_5":[8.0],"pm10":[12.0],"nitrogen_dioxide":[10.0],"ozone":[80.0],"birch_pollen":[null],"grass_pollen":[35.0]}}""")
        assertEquals(1, air.size)
        assertEquals(35.0, air[0].pollen[PollenType.GRASS]!!, 0.0)

        val geo = OpenMeteoParser.parseGeocoding("""{"results":[{"name":"Berlin","latitude":52.52,"longitude":13.41,"admin1":"Berlin","country":"Deutschland"}]}""")
        assertEquals("Berlin, Deutschland", geo[0].name)
        assertTrue(OpenMeteoParser.parseGeocoding("""{}""").isEmpty())

        val rev = OpenMeteoParser.parseReverseGeocoding("""{"address":{"city":"Bonn","state":"Nordrhein-Westfalen"}}""")
        assertNotNull(rev); assertTrue(rev!!.contains("Bonn"))
    }

    @Test(expected = OpenMeteoParser.ParseException::class) fun parserRejectsMissingCurrent() {
        OpenMeteoParser.parseForecast("""{"hourly":{}}""")
    }
}
