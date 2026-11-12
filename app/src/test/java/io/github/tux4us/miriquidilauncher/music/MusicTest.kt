// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {
    private fun track(id: Long, title: String, artist: String = "", album: String = "", albumId: Long = 0, number: Int = 0) =
        Track(id, title, artist, album, albumId, 180_000L, number)

    private fun station(id: String, url: String = "https://s.example/$id") = Station(id, "Sender $id", url)

    @Test
    fun cleanTagMapsUnknownToEmpty() {
        assertEquals("", MusicLogic.cleanTag("<unknown>"))
        assertEquals("", MusicLogic.cleanTag("  "))
        assertEquals("", MusicLogic.cleanTag(null))
        assertEquals("Abba", MusicLogic.cleanTag(" Abba "))
    }

    @Test
    fun addStationIgnoresDuplicatesAndRespectsTheLimit() {
        var data = MusicData()
        data = MusicLogic.addStation(data, station("1"))!!
        assertEquals(1, data.stations.size)
        assertSame(data, MusicLogic.addStation(data, station("1")))
        assertSame(data, MusicLogic.addStation(data, station("2", "HTTPS://S.EXAMPLE/1")))
        assertTrue(MusicLogic.isSaved(data, station("9", "https://s.example/1")))
        val full = MusicData(stations = (1..MusicLogic.MAX_STATIONS).map { station("x$it") })
        assertNull(MusicLogic.addStation(full, station("neu")))
    }

    @Test
    fun renameAndRemoveStations() {
        val data = MusicData(stations = listOf(station("1"), station("2")))
        assertEquals("Neu", MusicLogic.renameStation(data, "1", "  Neu ").stations[0].name)
        assertSame(data, MusicLogic.renameStation(data, "1", "   "))
        assertEquals(listOf("2"), MusicLogic.removeStation(data, "1").stations.map { it.id })
    }

    @Test
    fun manualStationIdIsStable() {
        val a = MusicLogic.manualStationId("https://a.example/s")
        assertEquals(a, MusicLogic.manualStationId(" https://a.example/s "))
        assertTrue(a.startsWith("u-"))
        assertTrue(a != MusicLogic.manualStationId("https://b.example/s"))
    }

    @Test
    fun stationsAreSortedIgnoringCaseAndAccents() {
        val sorted = MusicLogic.sortedStations(listOf(Station("1", "Zeta", "https://z"), Station("2", "Älpler", "https://a"), Station("3", "berg", "https://b")))
        assertEquals(listOf("Älpler", "berg", "Zeta"), sorted.map { it.name })
    }

    @Test
    fun favoritesToggleAndLimit() {
        var data = MusicData()
        data = MusicLogic.toggleFavorite(data, 5)!!
        assertEquals(listOf(5L), data.favoriteTracks)
        data = MusicLogic.toggleFavorite(data, 5)!!
        assertTrue(data.favoriteTracks.isEmpty())
        val full = MusicData(favoriteTracks = (1L..MusicLogic.MAX_FAVORITE_TRACKS).toList())
        assertNull(MusicLogic.toggleFavorite(full, 99_999))
        assertNotNull(MusicLogic.toggleFavorite(full, 1))
    }

    @Test
    fun playlistsNeedUniqueNames() {
        val (one, a) = MusicLogic.createPlaylist(MusicData(), " Sport ")!!
        assertEquals("Sport", a.name)
        assertNull(MusicLogic.createPlaylist(one, "sport"))
        assertNull(MusicLogic.createPlaylist(one, "   "))
        val (two, b) = MusicLogic.createPlaylist(one, "Ruhe")!!
        assertTrue(a.id != b.id)
        assertNull(MusicLogic.renamePlaylist(two, b.id, "SPORT"))
        assertEquals("Ruhe 2", MusicLogic.renamePlaylist(two, b.id, "Ruhe 2")!!.playlists[1].name)
        assertEquals(1, MusicLogic.deletePlaylist(two, a.id).playlists.size)
    }

    @Test
    fun playlistIdsDoNotRepeatAfterDeletion() {
        val (one, a) = MusicLogic.createPlaylist(MusicData(), "A")!!
        val (two, b) = MusicLogic.createPlaylist(one, "B")!!
        val three = MusicLogic.deletePlaylist(two, a.id)
        val (_, c) = MusicLogic.createPlaylist(three, "C")!!
        assertTrue(c.id != b.id && c.id != a.id)
    }

    @Test
    fun playlistTracksAreDeduplicatedAndLimited() {
        val (data, p) = MusicLogic.createPlaylist(MusicData(), "L")!!
        val a = MusicLogic.addToPlaylist(data, p.id, listOf(1, 2, 2, 3))!!
        assertEquals(listOf(1L, 2L, 3L), a.playlists[0].trackIds)
        val b = MusicLogic.addToPlaylist(a, p.id, listOf(3, 4))!!
        assertEquals(listOf(1L, 2L, 3L, 4L), b.playlists[0].trackIds)
        assertEquals(listOf(1L, 3L, 4L), MusicLogic.removeFromPlaylist(b, p.id, 2).playlists[0].trackIds)
        assertNull(MusicLogic.addToPlaylist(data, p.id, (1L..(MusicLogic.MAX_PLAYLIST_TRACKS + 1L)).toList()))
    }

    @Test
    fun movingInPlaylistStaysInBounds() {
        val data = MusicData(playlists = listOf(Playlist("p1", "L", listOf(1, 2, 3, 4))))
        assertEquals(listOf(2L, 1L, 3L, 4L), MusicLogic.moveInPlaylist(data, "p1", 1, 1).playlists[0].trackIds)
        assertEquals(listOf(4L, 1L, 2L, 3L), MusicLogic.moveInPlaylist(data, "p1", 4, -10).playlists[0].trackIds)
        assertEquals(data, MusicLogic.moveInPlaylist(data, "p1", 99, 1))
    }

    @Test
    fun resolveSkipsMissingTracksAndKeepsOrder() {
        val tracks = listOf(track(1, "A"), track(2, "B"), track(3, "C"))
        assertEquals(listOf(3L, 1L), MusicLogic.resolve(listOf(3, 99, 1), tracks).map { it.id })
    }

    @Test
    fun filterMatchesAllWordsIgnoringAccents() {
        val tracks = listOf(track(1, "Über den Wolken", "Reinhard Mey", "Wolken"), track(2, "Café del Mar", "Energy 52"), track(3, "Andere", "Mey"))
        assertEquals(listOf(1L), MusicLogic.filter(tracks, "uber mey").map { it.id })
        assertEquals(listOf(2L), MusicLogic.filter(tracks, "cafe").map { it.id })
        assertEquals(3, MusicLogic.filter(tracks, "  ").size)
        assertTrue(MusicLogic.filter(tracks, "xyz").isEmpty())
    }

    @Test
    fun albumsAndArtistsAreGrouped() {
        val tracks = listOf(
            track(1, "B-Seite", "Band", "Album X", 10, 2),
            track(2, "A-Seite", "Band", "Album X", 10, 1),
            track(3, "Solo", "Anna", "Mix", 20, 0),
            track(4, "Gast", "Ben", "Mix", 20, 0),
            track(5, "Ohne Album", "Band", "", 0, 0),
        )
        val albums = MusicLogic.albums(tracks)
        assertEquals(listOf("Album X", "Mix"), albums.map { it.title })
        assertEquals("Band", albums[0].artist)
        assertEquals("", albums[1].artist) // mehrere Interpreten
        assertEquals(2, albums[0].trackCount)
        assertEquals(listOf(2L, 1L), MusicLogic.tracksOfAlbum(tracks, 10).map { it.id })
        val artists = MusicLogic.artists(tracks)
        assertEquals(listOf("Anna", "Band", "Ben"), artists.map { it.name })
        assertEquals(3, artists.first { it.name == "Band" }.trackCount)
        assertEquals(listOf(2L, 1L, 5L), MusicLogic.tracksOfArtist(tracks, "Band").map { it.id }.let { ids -> ids })
    }

    @Test
    fun albumOrderPutsNumberedTracksFirst() {
        val tracks = listOf(track(1, "Z", number = 0), track(2, "B", number = 2), track(3, "A", number = 1), track(4, "C", number = 1002))
        assertEquals(listOf(3L, 2L, 4L, 1L), MusicLogic.albumOrder(tracks).map { it.id })
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:00", MusicLogic.formatTime(0))
        assertEquals("0:00", MusicLogic.formatTime(-5))
        assertEquals("3:07", MusicLogic.formatTime(187_999))
        assertEquals("1:02:03", MusicLogic.formatTime(3_723_000))
    }

    @Test
    fun windowKeepsShortListsAndCentersLongOnes() {
        val tracks = (0L until 3000L).map { track(it, "T$it") }
        val (all, startAll) = MusicLogic.window(tracks.take(10), 4)
        assertEquals(10, all.size)
        assertEquals(4, startAll)
        val (middle, i) = MusicLogic.window(tracks, 1500)
        assertEquals(MusicLogic.MAX_QUEUE, middle.size)
        assertEquals(1500L, middle[i].id)
        assertEquals(1300L, middle.first().id)
        val (head, h) = MusicLogic.window(tracks, 5)
        assertEquals(0L, head.first().id)
        assertEquals(5, h)
        val (tail, t) = MusicLogic.window(tracks, 2999)
        assertEquals(2999L, tail[t].id)
        assertEquals(2999L, tail.last().id)
        assertEquals(MusicLogic.MAX_QUEUE, tail.size)
        assertTrue(MusicLogic.window(emptyList(), 3).first.isEmpty())
        assertEquals(4, MusicLogic.window(tracks.take(5), 99).second)
    }

    @Test
    fun repeatModeCycles() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
        assertEquals(RepeatMode.OFF, RepeatMode.fromId("quatsch"))
        assertEquals(RepeatMode.ONE, RepeatMode.fromId("ONE"))
    }

    // region Speicherformat ----------------------------------------------------------------------

    private fun sample() = MusicData(
        stations = listOf(
            Station("a1", "Radio \"Eins\"", "https://s.example/a.mp3", "https://h.example", "https://l.example/a.png", "Germany", "pop, rock", "MP3", 128),
            Station("u-1", "Eigener", "http://plain.example/x"),
        ),
        favoriteTracks = listOf(5, 9, 12),
        playlists = listOf(Playlist("p1", "Sport", listOf(1, 2, 3)), Playlist("p4", "Ruhe", emptyList())),
        eq = EqSettings(enabled = true, bandsDb = listOf(1f, 2f, 3f, 4f, 5f, -6f, -7f, 8f, 9f, 10f), preampDb = -2.5f, bassDb = 4f, protect = false, preset = null),
        shuffle = true,
        repeat = RepeatMode.ALL,
        radioEnabled = true,
        allowHttp = true,
        showLogos = false,
    )

    @Test
    fun codecRoundTrips() {
        val data = sample()
        assertEquals(data, MusicCodec.decode(MusicCodec.encode(data)))
    }

    @Test
    fun emptyDataRoundTrips() {
        assertEquals(MusicData(), MusicCodec.decode(MusicCodec.encode(MusicData())))
    }

    @Test
    fun backupOmitsConsentAndHttpSwitch() {
        val text = MusicCodec.encodeBackup(sample())
        val restored = MusicCodec.decode(text)!!
        assertFalse(restored.radioEnabled)
        assertFalse(restored.allowHttp)
        assertEquals(sample().stations, restored.stations)
        assertEquals(sample().eq, restored.eq)
        val root = io.github.tux4us.miriquidilauncher.weather.MiniJson.parse(MusicCodec.encode(sample())) as Map<String, Any?>
        val fromRoot = MusicCodec.decodeBackup(root)!!
        assertFalse(fromRoot.radioEnabled)
        assertFalse(fromRoot.allowHttp)
        assertTrue(fromRoot.shuffle)
    }

    @Test
    fun decodeRejectsGarbageAndForeignVersions() {
        assertNull(MusicCodec.decode("kein json"))
        assertNull(MusicCodec.decode("[]"))
        assertNull(MusicCodec.decode("{\"v\":2}"))
        assertNull(MusicCodec.decode("{}"))
    }

    @Test
    fun decodeDropsUnsafeOrBrokenEntries() {
        val text = """{"v":1,"st":[
            {"id":"ok","n":"Gut","u":"https://s.example/a"},
            {"id":"ftp","n":"FTP","u":"ftp://s.example/a"},
            {"id":"cred","n":"Zugang","u":"https://u:p@s.example/a"},
            {"id":"","n":"Ohne","u":"https://s.example/b"},
            {"id":"noname","n":"","u":"https://s.example/c"},
            {"id":"ok","n":"Doppelt","u":"https://s.example/d"}],
            "eq":{"on":true,"b":[99,-99,1],"pre":50,"bass":-4,"preset":"unbekannt"},
            "pl":[{"id":"p1","n":"A","t":[1,1,2]},{"n":"keine id"}]}"""
        val data = MusicCodec.decode(text)!!
        assertEquals(listOf("ok"), data.stations.map { it.id })
        assertEquals("Gut", data.stations[0].name)
        assertEquals(10, data.eq.bandsDb.size)
        assertEquals(12f, data.eq.bandsDb[0], 0f)
        assertEquals(-12f, data.eq.bandsDb[1], 0f)
        assertEquals(12f, data.eq.preampDb, 0f)
        assertEquals(0f, data.eq.bassDb, 0f)
        assertNull(data.eq.preset)
        assertEquals(listOf(1L, 2L), data.playlists.single().trackIds)
    }

    // endregion

    // region Cover-Farben ------------------------------------------------------------------------

    private fun solid(r: Int, g: Int, b: Int, n: Int = 64) = IntArray(n) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    @Test
    fun paletteFollowsTheDominantHue() {
        val red = CoverColors.palette(solid(200, 30, 30))
        assertTrue(((red.accent shr 16) and 0xFF) > ((red.accent shr 8) and 0xFF) + 40)
        val blue = CoverColors.palette(solid(20, 40, 210))
        assertTrue((blue.accent and 0xFF) > ((blue.accent shr 16) and 0xFF) + 40)
        // Hintergrundfarben sind deutlich dunkler als der Akzent.
        fun lum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        assertTrue(lum(red.top) < lum(red.accent))
        assertTrue(lum(red.bottom) < lum(red.top))
        assertEquals(0xFF, red.top ushr 24)
    }

    @Test
    fun grayTransparentAndEmptyImagesGiveTheDefault() {
        assertEquals(CoverPalette.DEFAULT, CoverColors.palette(solid(128, 128, 128)))
        assertEquals(CoverPalette.DEFAULT, CoverColors.palette(IntArray(16) { 0x00FF0000 }))
        assertEquals(CoverPalette.DEFAULT, CoverColors.palette(IntArray(0)))
        assertEquals(CoverPalette.DEFAULT, CoverColors.palette(solid(0, 0, 0)))
    }

    @Test
    fun majorityHueWins() {
        val pixels = solid(220, 30, 30, 80) + solid(30, 30, 220, 20)
        val p = CoverColors.palette(pixels)
        assertTrue(((p.accent shr 16) and 0xFF) > (p.accent and 0xFF))
    }

    @Test
    fun stationBadgeIsStableAndInitialsAreSensible() {
        assertEquals(CoverColors.forName("Radio Eins"), CoverColors.forName("radio eins"))
        assertEquals("RE", CoverColors.initials("Radio Eins"))
        assertEquals("B", CoverColors.initials("bayern3"))
        assertEquals("♪", CoverColors.initials("  ---  "))
        assertEquals("8F", CoverColors.initials("  *80er  FM "))
    }

    // endregion
}
