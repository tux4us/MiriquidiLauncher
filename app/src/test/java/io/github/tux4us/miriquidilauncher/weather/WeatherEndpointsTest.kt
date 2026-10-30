// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import io.github.tux4us.miriquidilauncher.feed.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherEndpointsTest {
    @Test
    fun publicDefaultsWithoutServer() {
        assertEquals("https://api.open-meteo.com", WeatherEndpoints.forecastBase(null))
        assertEquals("https://air-quality-api.open-meteo.com", WeatherEndpoints.airBase(null))
        assertEquals("", WeatherEndpoints.keyParam(null))
    }

    @Test
    fun customServerUsesOwnBaseAndKey() {
        val s = WeatherServer("https://wetter.example.org", null, "a b&c")
        assertEquals("https://wetter.example.org", WeatherEndpoints.forecastBase(s))
        assertEquals("https://wetter.example.org", WeatherEndpoints.airBase(s))
        assertEquals("&apikey=a+b%26c", WeatherEndpoints.keyParam(s))
        val t = WeatherServer("https://a.example.org", "https://b.example.org", null)
        assertEquals("https://b.example.org", WeatherEndpoints.airBase(t))
        assertEquals("", WeatherEndpoints.keyParam(t))
    }

    @Test
    fun parsesAddresses() {
        assertEquals(ServerUrl.Ok("https://wetter.example.org"), WeatherEndpoints.parseBase(" wetter.example.org/ "))
        assertEquals(ServerUrl.Ok("https://wetter.example.org:8443/om"), WeatherEndpoints.parseBase("https://Wetter.Example.org:8443/om/v1/forecast"))
        assertEquals(ServerUrl.Empty, WeatherEndpoints.parseBase("  "))
        assertEquals(ServerUrl.NotHttps, WeatherEndpoints.parseBase("http://wetter.example.org"))
        assertEquals(ServerUrl.Invalid, WeatherEndpoints.parseBase("ftp://wetter.example.org"))
        assertEquals(ServerUrl.Invalid, WeatherEndpoints.parseBase("https://user:pw@wetter.example.org"))
        assertEquals(ServerUrl.Invalid, WeatherEndpoints.parseBase("https://wetter.example.org/?apikey=x"))
    }

    @Test
    fun validatesKeys() {
        assertTrue(WeatherEndpoints.isValidKey(""))
        assertTrue(WeatherEndpoints.isValidKey("AbC123-_"))
        assertFalse(WeatherEndpoints.isValidKey("ab cd"))
        assertFalse(WeatherEndpoints.isValidKey("x".repeat(201)))
    }
}
