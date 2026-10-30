// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.markets.ChartChoice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.CryptoRange
import io.github.tux4us.miriquidilauncher.markets.Position
import io.github.tux4us.miriquidilauncher.markets.PositionKind
import io.github.tux4us.miriquidilauncher.markets.SeriesRange
import io.github.tux4us.miriquidilauncher.podcasts.Episode
import io.github.tux4us.miriquidilauncher.podcasts.Podcast
import io.github.tux4us.miriquidilauncher.podcasts.PodcastData
import io.github.tux4us.miriquidilauncher.podcasts.SearchProvider
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val full = BackupData(
        appFavorites = listOf("org.fdroid/.Main#0", "a.b/.C#10"),
        contactFavorites = listOf(FavoriteContact("Müller \"Mutti\"", "+49 170 1234567"), FavoriteContact("Tab\tName", "0301")),
        weatherFavorites = listOf(GeoPlace("Köln, Nordrhein-Westfalen", 50.94, 6.96)),
        selectedPlace = GeoPlace("Berlin", 52.52, 13.405),
        shadowHeights = ShadowHeights(1.8, 0.5),
    )

    @Test fun roundTrip() {
        val text = BackupCodec.encode(full, "2026-10-02T21:51:00Z")
        assertEquals(full, BackupCodec.decode(text))
    }

    @Test fun appGroupsRoundTrip() {
        val groups = listOf(
            AppGroup("g1", "Messenger", listOf("org.signal/.Main#0", "org.telegram/.Main#0")),
            AppGroup("g2", "Finanz \"Apps\"", emptyList()),
        )
        val data = full.copy(appGroups = groups)
        assertEquals(groups, BackupCodec.decode(BackupCodec.encode(data, "x")).appGroups)
    }

    @Test fun backupWithoutGroupsLeavesThemUntouched() {
        assertNull(BackupCodec.decode(BackupCodec.encode(full, "x")).appGroups)
    }

    @Test fun invalidGroupsAreDropped() {
        val json = """{"format":"miriquidilauncher-backup","version":1,"appFavorites":[],
            "appGroups":[{"id":"a","name":"Ok","apps":["x","x",""]},{"id":"","name":"NoId","apps":[]},
            {"id":"b","name":"  ","apps":[]},{"id":"c","name":"Zwei","apps":[]},7]}"""
        val groups = BackupCodec.decode(json).appGroups!!
        assertEquals(listOf("Ok", "Zwei"), groups.map { it.name })
        assertEquals(listOf("x"), groups.first().appKeys)
    }

    @Test fun contactsOptional() {
        val without = full.copy(contactFavorites = null)
        val decoded = BackupCodec.decode(BackupCodec.encode(without, "x"))
        assertNull(decoded.contactFavorites)
        assertEquals(full.appFavorites, decoded.appFavorites)
    }

    @Test fun emptyBackupIsValid() {
        val empty = BackupData(emptyList(), emptyList(), emptyList(), null, null)
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty, "x")))
    }

    @Test fun invalidEntriesAreDropped() {
        val json = """{"format":"miriquidilauncher-backup","version":1,"appFavorites":["a","a","",3],
            "contactFavorites":[{"name":"X","number":"  "},{"name":"Y","number":"0301"},{"name":"Z","number":"03-01"}],
            "weatherFavorites":[{"name":"Bad","lat":95,"lon":0},{"name":"Ok","lat":10,"lon":20}],
            "selectedPlace":{"name":"","lat":1,"lon":1},"shadowHeights":{"person":9,"object":1}}"""
        val d = BackupCodec.decode(json)
        assertEquals(listOf("a"), d.appFavorites)
        assertEquals(listOf("0301"), d.contactFavorites!!.map { it.number })
        assertEquals(listOf("Ok"), d.weatherFavorites.map { it.name })
        assertNull(d.selectedPlace); assertNull(d.shadowHeights)
    }

    @Test fun legacyFormatFromBcLauncherIsAccepted() {
        val d = BackupCodec.decode("""{"format":"bc-launcher-backup","version":1,"appFavorites":["a"]}""")
        assertEquals(listOf("a"), d.appFavorites)
    }

    @Test fun rejectsForeignOrBrokenFiles() {
        listOf("", "[]", "{", """{"format":"other","version":1}""", """{"format":"miriquidilauncher-backup"}""",
            """{"format":"miriquidilauncher-backup","version":2}""").forEach {
            try { BackupCodec.decode(it); throw AssertionError("accepted: $it") } catch (e: BackupCodec.BackupException) { assertTrue(true) }
        }
    }

    @Test fun notesRoundTrip() {
        val notes = listOf(
            BackupNote("Einkauf.md", "Einkauf\n- Milch \"fett\"\n\tTab", true, 1700000000000L),
            BackupNote("Arbeit/Plan.md", "Plan", false, 5L),
        )
        assertEquals(notes, BackupCodec.decode(BackupCodec.encode(full.copy(notes = notes), "x")).notes)
        assertNull(BackupCodec.decode(BackupCodec.encode(full, "x")).notes)
        assertEquals(emptyList<BackupNote>(), BackupCodec.decode(BackupCodec.encode(full.copy(notes = emptyList()), "x")).notes)
    }

    @Test fun unsafeOrDuplicateNotesAreDropped() {
        val text = BackupCodec.encode(
            full.copy(
                notes = listOf(
                    BackupNote("../evil.md", "x", false, 0),
                    BackupNote("/abs.md", "x", false, 0),
                    BackupNote("bild.png", "x", false, 0),
                    BackupNote("Gut.md", "a", false, 0),
                    BackupNote("gut.md", "b", false, 0),
                ),
            ),
            "x",
        )
        assertEquals(listOf("Gut.md"), BackupCodec.decode(text).notes?.map { it.path })
    }

    private val episode = Episode(
        id = "e1", podcastUrl = "https://example.org/feed.xml", podcastTitle = "Cast \"A\"", title = "Folge 1",
        publishedSeconds = 1_700_000_000L, audioUrl = "https://example.org/e1.mp3", mime = "audio/mpeg", durationSeconds = 1800,
        notesHtml = "<p>Text</p>", link = "https://example.org/e1", imageUrl = null,
    )

    private val podcasts = PodcastData(
        subscriptions = listOf(Podcast("https://example.org/feed.xml", "Cast \"A\"", "Autor", "https://example.org/a.jpg")),
        favorites = listOf(episode),
        positionsMs = mapOf("e1" to 123_000L),
        played = setOf("e2"),
        provider = SearchProvider.FYYD,
        speed = 1.5f,
    )

    private val markets = BackupMarkets(
        positions = listOf(Position("SAP.DE", "SAP SE", "716460", 12.5, PositionKind.Stock), Position("bitcoin", "Bitcoin", "", 0.25, PositionKind.Crypto)),
        choice = ChartChoice("sp500", SeriesRange.Year, "silver", SeriesRange.FiveYears, CryptoRange.Month),
        range = ChartRange.Week,
        chartCurrency = ChartCurrency.Usd,
        fxFrom = "CHF",
        fxTo = "JPY",
        yahooEnabled = false,
        stooqEnabled = true,
        baseUrl = "https://mempool.example.org",
        holdingsVisible = true,
    )

    @Test fun podcastsRoundTrip() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full.copy(podcasts = podcasts), "x"), BackupCodec.MAX_FILE_BYTES)
        assertEquals(podcasts, decoded.podcasts)
    }

    @Test fun podcastBackupLeavesCachesOut() {
        val withCache = podcasts.copy(
            episodes = mapOf("https://example.org/feed.xml" to listOf(episode)),
            saved = listOf(episode),
            downloads = mapOf("e1" to "e1.mp3"),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(full.copy(podcasts = withCache), "x"), BackupCodec.MAX_FILE_BYTES).podcasts!!
        assertTrue(decoded.episodes.isEmpty())
        assertTrue(decoded.saved.isEmpty())
        assertTrue(decoded.downloads.isEmpty())
    }

    @Test fun podcastsAreSanitizedOnImport() {
        val bad = podcasts.copy(
            subscriptions = podcasts.subscriptions + Podcast("http://example.org/plain.xml", "Unsicher") + podcasts.subscriptions,
            favorites = listOf(
                episode.copy(notesHtml = "<p>ok</p><script>alert(1)</script>", link = "javascript:alert(1)"),
                episode.copy(id = "e2", audioUrl = "http://example.org/e2.mp3"),
            ),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(full.copy(podcasts = bad), "x"), BackupCodec.MAX_FILE_BYTES).podcasts!!
        assertEquals(listOf("https://example.org/feed.xml"), decoded.subscriptions.map { it.feedUrl })
        assertEquals(listOf("e1"), decoded.favorites.map { it.id })
        assertTrue(!decoded.favorites[0].notesHtml.contains("script", ignoreCase = true))
        assertNull(decoded.favorites[0].link)
    }

    @Test fun marketsRoundTrip() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full.copy(markets = markets), "x"), BackupCodec.MAX_FILE_BYTES)
        assertEquals(markets, decoded.markets)
    }

    @Test fun marketsIgnoreInvalidValues() {
        val text = BackupCodec.encode(full.copy(markets = markets), "x")
            .replace("\"CHF\"", "\"XXX\"")
            .replace("https://mempool.example.org", "http://mempool.example.org")
        val decoded = BackupCodec.decode(text, BackupCodec.MAX_FILE_BYTES).markets!!
        assertEquals("EUR", decoded.fxFrom)
        assertNull(decoded.baseUrl)
    }

    @Test fun oldBackupsLeavePodcastsAndMarketsUntouched() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full, "x"))
        assertNull(decoded.podcasts)
        assertNull(decoded.markets)
    }
}
