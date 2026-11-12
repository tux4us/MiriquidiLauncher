// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationListTest {
    private val a = Station("id-a", "Radio \"A\"", "https://a.example/stream", homepage = "https://a.example", logoUrl = "https://a.example/l.png", country = "Germany", tags = "pop,rock", codec = "MP3", bitrate = 128)
    private val b = Station("id-b", "Radio B", "http://b.example/live")

    @Test fun exportAndParseRoundTrip() {
        val parsed = StationList.parse(StationList.export(listOf(a, b)))!!
        assertEquals(listOf(a, b), parsed.stations)
        assertEquals(0, parsed.invalid)
    }

    @Test fun emptyListRoundTrips() {
        val parsed = StationList.parse(StationList.export(emptyList()))!!
        assertTrue(parsed.stations.isEmpty())
    }

    @Test fun readsBareArrayAndRadioBrowserFieldNames() {
        val text = """[{"stationuuid":"u1","name":"X","url_resolved":"https://x.example/s","favicon":"http://x.example/f.png","bitrate":64}]"""
        val s = StationList.parse(text)!!.stations.single()
        assertEquals("u1", s.id)
        assertEquals("https://x.example/f.png", s.logoUrl)
        assertEquals(64, s.bitrate)
    }

    @Test fun createsIdFromUrlWhenMissing() {
        val s = StationList.parse("""[{"name":"Y","url":"https://y.example/s"}]""")!!.stations.single()
        assertEquals(MusicLogic.manualStationId("https://y.example/s"), s.id)
    }

    @Test fun countsInvalidEntriesAndDropsUnsafeAddresses() {
        val text = """{"format":"miriquidilauncher-radio","version":1,"stations":[
            {"name":"ok","url":"https://ok.example/s"},
            {"name":"","url":"https://noname.example/s"},
            {"name":"ftp","url":"ftp://ftp.example/s"},
            {"name":"cred","url":"https://user:pw@c.example/s"},
            {"name":"dup","url":"https://OK.example/s"},
            "text"]}"""
        val parsed = StationList.parse(text)!!
        assertEquals(1, parsed.stations.size)
        assertEquals(4, parsed.invalid)
    }

    @Test fun rejectsForeignFormatVersionAndGarbage() {
        assertNull(StationList.parse("kein json"))
        assertNull(StationList.parse("""{"format":"other","stations":[]}"""))
        assertNull(StationList.parse("""{"format":"miriquidilauncher-radio","version":2,"stations":[]}"""))
        assertNull(StationList.parse("""{"foo":1}"""))
        assertNull(StationList.parse("42"))
        assertNotNull(StationList.parse("[]"))
    }

    @Test fun mergeKeepsExistingAndCountsDuplicates() {
        val data = MusicData(stations = listOf(a))
        val renamed = a.copy(name = "andere Bezeichnung")
        val sameUrl = Station("zzz", "Gleiche Adresse", a.streamUrl.uppercase().replace("HTTPS", "https"))
        val merge = StationList.merge(data, listOf(renamed, sameUrl, b))
        assertEquals(1, merge.added)
        assertEquals(2, merge.duplicates)
        assertEquals(listOf("Radio \"A\"", "Radio B"), merge.data.stations.map { it.name })
    }

    @Test fun mergeStopsAtLimit() {
        val many = (1..MusicLogic.MAX_STATIONS).map { Station("s$it", "S$it", "https://s$it.example/x") }
        val merge = StationList.merge(MusicData(stations = many), listOf(a, b))
        assertEquals(0, merge.added)
        assertEquals(2, merge.overLimit)
        assertEquals(MusicLogic.MAX_STATIONS, merge.data.stations.size)
    }
}
