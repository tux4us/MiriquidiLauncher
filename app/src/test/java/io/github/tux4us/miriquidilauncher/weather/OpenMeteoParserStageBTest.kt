// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Neue Open-Meteo-Felder (Stufe B) und Kompatibilität mit Antworten und Zwischenspeichern ohne diese Felder. */
class OpenMeteoParserStageBTest {

    private fun forecast(extraCurrent: String = "", extraHourly: String = "", extraDaily: String = "") = """
    {
      "utc_offset_seconds": 3600,
      "current": {
        "temperature_2m": 4.2, "apparent_temperature": 1.0, "relative_humidity_2m": 90, "precipitation": 0.0,
        "weather_code": 3, "surface_pressure": 950.3, "wind_speed_10m": 8.0, "wind_direction_10m": 250,
        "wind_gusts_10m": 20.0, "uv_index": 0.5, "is_day": 1 $extraCurrent
      },
      "hourly": {
        "time": ["2026-11-12T10:00", "2026-11-12T11:00", "2026-11-12T12:00"],
        "temperature_2m": [3.0, 4.0, 5.0] $extraHourly
      },
      "daily": {
        "time": ["2026-11-12"],
        "temperature_2m_max": [6.0], "temperature_2m_min": [1.0] $extraDaily
      }
    }
    """

    @Test fun oldResponseWithoutNewFieldsParsesWithNulls() {
        val f = OpenMeteoParser.parseForecast(forecast())
        assertNull(f.current.cloudCover)
        val h = f.hours[0]
        assertNull(h.relativeHumidity)
        assertNull(h.cloudCover)
        assertNull(h.visibility)
        assertNull(h.snowfall)
        assertNull(h.snowDepth)
        assertNull(h.freezingLevel)
        assertNull(f.days[0].precipitationSum)
    }

    @Test fun newForecastFieldsAreParsed() {
        val f = OpenMeteoParser.parseForecast(
            forecast(
                extraCurrent = ", \"cloud_cover\": 75",
                extraHourly = """, "relative_humidity_2m": [80, 85, 90], "cloud_cover": [10, 55, 100],
                    "visibility": [24140.0, 800.0, 150.0], "snowfall": [0.0, 0.2, 0.7],
                    "snow_depth": [0.0, 0.01, 0.03], "freezing_level_height": [1500.0, 1450.0, 1400.0]""",
                extraDaily = ", \"precipitation_sum\": [3.4]",
            ),
        )
        assertEquals(75, f.current.cloudCover)
        assertEquals(85.0, f.hours[1].relativeHumidity!!, 1e-9)
        assertEquals(100.0, f.hours[2].cloudCover!!, 1e-9)
        assertEquals(800.0, f.hours[1].visibility!!, 1e-9)
        assertEquals(0.7, f.hours[2].snowfall!!, 1e-9)
        assertEquals(0.03, f.hours[2].snowDepth!!, 1e-9)
        assertEquals(1450.0, f.hours[1].freezingLevel!!, 1e-9)
        assertEquals(3.4, f.days[0].precipitationSum!!, 1e-9)
    }

    @Test fun cloudCoverIsClampedAndNullValuesStayNull() {
        val f = OpenMeteoParser.parseForecast(
            forecast(
                extraCurrent = ", \"cloud_cover\": 130",
                extraHourly = ", \"visibility\": [null, 500.0, null]",
            ),
        )
        assertEquals(100, f.current.cloudCover)
        assertNull(f.hours[0].visibility)
        assertEquals(500.0, f.hours[1].visibility!!, 1e-9)
        assertNull(f.hours[2].visibility)
    }

    @Test fun shortArraysDoNotCrash() {
        val f = OpenMeteoParser.parseForecast(forecast(extraHourly = ", \"cloud_cover\": [10]"))
        assertEquals(10.0, f.hours[0].cloudCover!!, 1e-9)
        assertNull(f.hours[1].cloudCover)
    }

    @Test fun airQualityNewFields() {
        val air = OpenMeteoParser.parseAirQuality(
            """{"hourly": {"time": ["2026-11-12T10:00", "2026-11-12T11:00"], "pm2_5": [5.0, 6.0],
                "european_aqi": [34.0, 61.0], "dust": [12.5, 3.0], "sulphur_dioxide": [1.2, 1.4],
                "carbon_monoxide": [180.0, 200.0]}}""",
        )
        assertEquals(2, air.size)
        assertEquals(34.0, air[0].europeanAqi!!, 1e-9)
        assertEquals(12.5, air[0].dust!!, 1e-9)
        assertEquals(1.4, air[1].sulphurDioxide!!, 1e-9)
        assertEquals(200.0, air[1].carbonMonoxide!!, 1e-9)
    }

    @Test fun airQualityWithoutNewFields() {
        val air = OpenMeteoParser.parseAirQuality(
            """{"hourly": {"time": ["2026-11-12T10:00"], "pm2_5": [5.0]}}""",
        )
        assertNotNull(air.firstOrNull())
        assertNull(air[0].europeanAqi)
        assertNull(air[0].dust)
        assertTrue(air[0].pollen.isEmpty())
    }
}
