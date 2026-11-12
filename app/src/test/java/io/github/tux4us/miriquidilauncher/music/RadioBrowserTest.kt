// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserTest {
    private val json = """
        [
          {"stationuuid":"a1","name":" Radio Eins ","url":"http://old.example/1","url_resolved":"https://stream.example/eins.mp3","homepage":"https://eins.example",
           "favicon":"http://logo.example/eins.png","tags":"pop, rock,news,extra","country":"Germany","codec":"MP3","bitrate":128,"lastcheckok":1,"ssl_error":0},
          {"stationuuid":"a2","name":"Nur HTTP","url":"http://plain.example/stream","url_resolved":"http://plain.example/stream","lastcheckok":1},
          {"stationuuid":"a3","name":"Defekt","url_resolved":"https://dead.example/s","lastcheckok":0},
          {"stationuuid":"a4","name":"Zertifikat","url_resolved":"https://badcert.example/s","lastcheckok":1,"ssl_error":1},
          {"stationuuid":"a1","name":"Doppelt","url_resolved":"https://other.example/s","lastcheckok":1},
          {"stationuuid":"a5","name":"Gleiche Adresse","url_resolved":"https://STREAM.example/eins.mp3","lastcheckok":1},
          {"stationuuid":"a6","name":"Zugang","url_resolved":"https://user:pw@secret.example/s","lastcheckok":1},
          {"stationuuid":"a7","name":"","url_resolved":"https://noname.example/s","lastcheckok":1},
          {"name":"Ohne Kennung","url_resolved":"https://x.example/s"},
          {"stationuuid":"a8","name":"Fallback auf url","url":"https://fallback.example/s","url_resolved":"","lastcheckok":1}
        ]
    """.trimIndent()

    @Test
    fun parsesAndFiltersInHttpsOnlyMode() {
        val stations = RadioBrowser.parseStations(json, allowHttp = false)
        assertEquals(listOf("a1", "a8"), stations.map { it.id })
        val first = stations[0]
        assertEquals("Radio Eins", first.name)
        assertEquals("https://stream.example/eins.mp3", first.streamUrl)
        assertEquals("https://logo.example/eins.png", first.logoUrl)
        assertEquals("pop, rock, news", first.tags)
        assertEquals(128, first.bitrate)
        assertEquals("MP3", first.codec)
        assertEquals("https://eins.example", first.homepage)
    }

    @Test
    fun allowsHttpStreamsOnlyWhenEnabled() {
        val stations = RadioBrowser.parseStations(json, allowHttp = true)
        assertEquals(listOf("a1", "a2", "a8"), stations.map { it.id })
        assertEquals("http://plain.example/stream", stations[1].streamUrl)
    }

    @Test
    fun garbageResponsesGiveEmptyLists() {
        assertTrue(RadioBrowser.parseStations("kein json", true).isEmpty())
        assertTrue(RadioBrowser.parseStations("{}", true).isEmpty())
        assertTrue(RadioBrowser.parseStations("[1,2,\"x\"]", true).isEmpty())
        assertTrue(RadioBrowser.parseServers("<html>").isEmpty())
    }

    @Test
    fun serverListKeepsOnlyRadioBrowserHosts() {
        val servers = RadioBrowser.parseServers(
            """[{"ip":"1.2.3.4","name":"de1.api.radio-browser.info"},{"name":"evil.example.com"},{"name":"nl1.api.radio-browser.info"},{"name":"de1.api.radio-browser.info"},{"name":"x.api.radio-browser.info.evil.com"}]""",
        )
        assertEquals(listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info"), servers)
    }

    @Test
    fun buildsSearchUrls() {
        val host = "de1.api.radio-browser.info"
        val name = RadioBrowser.searchUrl(host, RadioSearch.NAME, " Jazz & Soul ", allowHttp = false)!!
        assertTrue(name, name.startsWith("https://de1.api.radio-browser.info/json/stations/search?"))
        assertTrue(name.contains("&name=Jazz+%26+Soul"))
        assertTrue(name.contains("&is_https=true"))
        assertTrue(name.contains("hidebroken=true"))
        val http = RadioBrowser.searchUrl(host, RadioSearch.TAG, "rock", allowHttp = true)!!
        assertTrue(!http.contains("is_https"))
        assertTrue(http.contains("&tag=rock"))
        assertTrue(RadioBrowser.searchUrl(host, RadioSearch.COUNTRY, "Germany", false)!!.contains("&country=Germany"))
        assertNull(RadioBrowser.searchUrl(host, RadioSearch.NAME, "   ", false))
        assertNull(RadioBrowser.searchUrl("evil.example.com", RadioSearch.TOP, "", false))
    }

    @Test
    fun topSearchUsesCountryCodeOnlyWhenValid() {
        val host = "fi1.api.radio-browser.info"
        assertTrue(RadioBrowser.searchUrl(host, RadioSearch.TOP, "", false, "de")!!.contains("&countrycode=DE"))
        assertTrue(!RadioBrowser.searchUrl(host, RadioSearch.TOP, "", false, "")!!.contains("countrycode"))
        assertTrue(!RadioBrowser.searchUrl(host, RadioSearch.TOP, "", false, "D1")!!.contains("countrycode"))
        assertTrue(!RadioBrowser.searchUrl(host, RadioSearch.TOP, "", false, "deu")!!.contains("countrycode"))
    }

    @Test
    fun queryIsLimitedInLength() {
        val url = RadioBrowser.searchUrl("de1.api.radio-browser.info", RadioSearch.NAME, "a".repeat(500), false)!!
        assertTrue(url.substringAfter("&name=").takeWhile { it != '&' }.length <= RadioBrowser.MAX_QUERY)
    }

    @Test
    fun streamUrlsAreValidated() {
        assertEquals("https://a.example/s.mp3", RadioBrowser.playableUrl("  https://a.example/s.mp3 ", false))
        assertNull(RadioBrowser.playableUrl("http://a.example/s", false))
        assertEquals("http://a.example/s", RadioBrowser.playableUrl("http://a.example/s", true))
        assertNull(RadioBrowser.playableUrl("ftp://a.example/s", true))
        assertNull(RadioBrowser.playableUrl("file:///etc/passwd", true))
        assertNull(RadioBrowser.playableUrl("javascript:alert(1)", true))
        assertNull(RadioBrowser.playableUrl("https://u:p@a.example/s", true))
        assertNull(RadioBrowser.playableUrl("https://a.example/with space", true))
        assertNull(RadioBrowser.playableUrl("https:///nohost", true))
        assertNull(RadioBrowser.playableUrl("", true))
        assertNull(RadioBrowser.playableUrl(null, true))
        assertNull(RadioBrowser.playableUrl("https://a.example/" + "x".repeat(2100), true))
    }

    @Test
    fun logoUrlsAreUpgradedToHttps() {
        assertEquals("https://l.example/a.png", RadioBrowser.logoUrl("http://l.example/a.png"))
        assertEquals("https://l.example/a.png", RadioBrowser.logoUrl("https://l.example/a.png"))
        assertNull(RadioBrowser.logoUrl("ftp://l.example/a.png"))
        assertNull(RadioBrowser.logoUrl(""))
    }
}
