// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.calculator.AngleMode
import io.github.tux4us.miriquidilauncher.calculator.CalcPersisted
import io.github.tux4us.miriquidilauncher.calculator.HistoryEntry
import io.github.tux4us.miriquidilauncher.camera.CameraSettings
import io.github.tux4us.miriquidilauncher.camera.FlashSetting
import io.github.tux4us.miriquidilauncher.camera.RatioSetting
import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.feed.FeedInfo
import io.github.tux4us.miriquidilauncher.feed.FeedItem
import io.github.tux4us.miriquidilauncher.feed.FeedSnapshot
import io.github.tux4us.miriquidilauncher.feed.LocalFeeds
import io.github.tux4us.miriquidilauncher.markets.ChartChoice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.CryptoRange
import io.github.tux4us.miriquidilauncher.markets.Position
import io.github.tux4us.miriquidilauncher.markets.PositionKind
import io.github.tux4us.miriquidilauncher.markets.SeriesRange
import io.github.tux4us.miriquidilauncher.notes.NoteSort
import io.github.tux4us.miriquidilauncher.podcasts.Episode
import io.github.tux4us.miriquidilauncher.podcasts.Podcast
import io.github.tux4us.miriquidilauncher.podcasts.PodcastData
import io.github.tux4us.miriquidilauncher.podcasts.SearchProvider
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.github.tux4us.miriquidilauncher.dogs.DogData
import io.github.tux4us.miriquidilauncher.dogs.DogProfile
import io.github.tux4us.miriquidilauncher.dogs.HealthItem
import io.github.tux4us.miriquidilauncher.dogs.HealthKind
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

    // region Übrige Scopes ---------------------------------------------------------------------------

    private fun file(data: BackupData): BackupData =
        BackupCodec.decode(BackupCodec.encode(data, "x"), BackupCodec.MAX_FILE_BYTES)

    @Test fun scopesRoundTripAndAreAllowedInQrCodes() {
        val scopes = BackupScopes(hidden = setOf("Weather", "Camera"), order = listOf("Notes", "Weather", "Camera", "Settings"))
        // Ohne Dateigrenze (QR-Codes: MAX_BYTES) wird die Anordnung gelesen.
        val decoded = BackupCodec.decode(BackupCodec.encode(full.copy(scopes = scopes), "x"))
        assertEquals(scopes, decoded.scopes)
    }

    @Test fun invalidScopeNamesAreDropped() {
        val json = """{"format":"miriquidilauncher-backup","version":1,"appFavorites":[],
            "scopes":{"hidden":["Weather","", "Bad Name","${"x".repeat(41)}",7,"Weather"],"order":["B","A","B","a-b"]}}"""
        val scopes = BackupCodec.decode(json).scopes!!
        assertEquals(setOf("Weather"), scopes.hidden)
        assertEquals(listOf("B", "A"), scopes.order)
    }

    @Test fun weatherServerRoundTripAndPublicDefault() {
        val own = BackupWeatherServer("https://wetter.example.org", "https://luft.example.org")
        assertEquals(own, file(full.copy(weatherServer = own)).weatherServer)
        val public = BackupWeatherServer(null, null)
        assertEquals(public, file(full.copy(weatherServer = public)).weatherServer)
    }

    @Test fun weatherServerRejectsInsecureAddressesAndNeverCarriesAKey() {
        val text = BackupCodec.encode(full.copy(weatherServer = BackupWeatherServer("https://wetter.example.org", "https://luft.example.org")), "x")
        assertTrue(!text.contains("apikey", ignoreCase = true))
        val insecure = file(full.copy(weatherServer = BackupWeatherServer("http://wetter.example.org", "https://luft.example.org")))
        assertEquals(BackupWeatherServer(null, null), insecure.weatherServer)
        val insecureAir = file(full.copy(weatherServer = BackupWeatherServer("https://wetter.example.org", "http://luft.example.org")))
        assertEquals(BackupWeatherServer("https://wetter.example.org", null), insecureAir.weatherServer)
    }

    private val staticUrl = LocalFeeds.STATIC_PREFIX + "abc123"

    private fun feedItem(feedUrl: String, key: String, body: String = "<p>Text</p>") = FeedItem(
        id = LocalFeeds.itemId(feedUrl, key), feedId = LocalFeeds.feedId(feedUrl), title = key, author = "A", url = "https://example.org/$key",
        body = body, publishedSeconds = 1_700_000_000L, unread = true, starred = false,
    )

    private val localFeed = FeedSnapshot(
        feeds = listOf(
            FeedInfo(LocalFeeds.feedId("https://blog.example.org/feed.xml"), "Blog", "https://blog.example.org/feed.xml"),
            FeedInfo(LocalFeeds.feedId(staticUrl), "Importiert", staticUrl),
        ),
        items = listOf(feedItem(staticUrl, "s1"), feedItem(staticUrl, "s2")),
        fetchedAtMillis = 0L,
        pendingRead = setOf(11L, 22L),
    )

    @Test fun feedRoundTrip() {
        val feed = BackupFeed(localFeed, unreadOnly = false, loadImages = false)
        assertEquals(feed, file(full.copy(feed = feed)).feed)
    }

    @Test fun feedWithoutLocalFeedsKeepsSettings() {
        val feed = BackupFeed(null, unreadOnly = false, loadImages = true)
        assertEquals(feed, file(full.copy(feed = feed)).feed)
    }

    @Test fun feedImportDropsInsecureFeedsForeignItemsAndScripts() {
        val live = "https://blog.example.org/feed.xml"
        val bad = localFeed.copy(
            feeds = localFeed.feeds + listOf(
                FeedInfo(1L, "Unsicher", "http://plain.example.org/feed.xml"),
                FeedInfo(2L, "Doppelt", live.uppercase()),
            ),
            items = listOf(
                feedItem(staticUrl, "s1", "<p>ok</p><script>alert(1)</script>").copy(url = "javascript:alert(1)", starred = true),
                // Artikel von Feeds mit eigener Adresse werden nicht übernommen, sie werden neu abgerufen.
                feedItem(live, "l1"),
                feedItem("import:unknown", "u1"),
            ),
        )
        val decoded = file(full.copy(feed = BackupFeed(bad, true, true))).feed!!.local!!
        assertEquals(listOf(live, staticUrl), decoded.feeds.map { it.link })
        assertEquals(1, decoded.items.size)
        assertTrue(!decoded.items[0].body.contains("script", ignoreCase = true))
        assertNull(decoded.items[0].url)
        assertTrue(!decoded.items[0].starred)
        assertEquals(setOf(11L, 22L), decoded.pendingRead)
    }

    @Test fun feedWithoutAnyValidFeedBecomesNoLocalFeed() {
        val onlyInsecure = localFeed.copy(feeds = listOf(FeedInfo(1L, "Unsicher", "http://plain.example.org/feed.xml")), items = emptyList())
        assertNull(file(full.copy(feed = BackupFeed(onlyInsecure, true, true))).feed!!.local)
    }

    @Test fun galleryRoundTrip() {
        val gallery = BackupGallery(
            favorites = setOf("/storage/emulated/0/DCIM/Camera/a \"1\".jpg", "/storage/emulated/0/Pictures/b.png"),
            hiddenBuckets = setOf(-1739773001L, 42L),
            ascending = true,
            showHidden = true,
        )
        assertEquals(gallery, file(full.copy(gallery = gallery)).gallery)
    }

    @Test fun oldBackupsWithFilesSettingsStillDecode() {
        val text = BackupCodec.encode(full, "x").replaceFirst("{", "{\n  \"files\": {\"sort\": \"DATE\", \"ascending\": false, \"showHidden\": true},")
        assertEquals(full.appFavorites, BackupCodec.decode(text, BackupCodec.MAX_FILE_BYTES).appFavorites)
    }

    @Test fun calculatorRoundTrip() {
        val calc = CalcPersisted(AngleMode.Rad, memory = 2.5, ans = 42.0, history = listOf(HistoryEntry("6*7", 42.0), HistoryEntry("1+1", 2.0)))
        assertEquals(calc, file(full.copy(calculator = calc)).calculator)
    }

    @Test fun cameraRoundTripKeepsSettingsAndDropsDeviceSpecificValues() {
        val settings = CameraSettings(flash = FlashSetting.Auto, ratio = RatioSetting.R16x9, stabilization = false)
        val camera = BackupCamera(settings, buttonApp = "org.fossify.camera")
        assertEquals(camera, file(full.copy(camera = camera)).camera)
        // Eine manipulierte Datei kann weder Kamerakennung noch Datei-Adresse einschleusen.
        val withDevice = BackupCamera(settings.copy(cameraId = "7", lastUri = "content://media/external/images/media/1", lastIsVideo = true), null)
        val decoded = file(full.copy(camera = withDevice)).camera!!
        assertEquals(settings, decoded.settings)
        assertNull(decoded.buttonApp)
    }

    @Test fun cameraButtonAppMustBeAPackageName() {
        val text = BackupCodec.encode(full.copy(camera = BackupCamera(CameraSettings(), "org.fossify.camera")), "x")
            .replace("org.fossify.camera", "../evil app")
        assertNull(BackupCodec.decode(text, BackupCodec.MAX_FILE_BYTES).camera!!.buttonApp)
    }

    @Test fun notesSettingsAndAgendaRoundTrip() {
        val data = full.copy(notesSettings = BackupNotesSettings(NoteSort.TITLE, ascending = true), agendaEnabled = false)
        val decoded = file(data)
        assertEquals(BackupNotesSettings(NoteSort.TITLE, true), decoded.notesSettings)
        assertEquals(false, decoded.agendaEnabled)
    }

    @Test fun everyScopeAreaIsAbsentFromBackupsWithoutIt() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full, "x"), BackupCodec.MAX_FILE_BYTES)
        assertNull(decoded.scopes)
        assertNull(decoded.weatherServer)
        assertNull(decoded.feed)
        assertNull(decoded.gallery)
        assertNull(decoded.calculator)
        assertNull(decoded.camera)
        assertNull(decoded.notesSettings)
        assertNull(decoded.agendaEnabled)
        assertNull(decoded.dogs)
    }

    @Test fun completeBackupRoundTrips() {
        val complete = full.copy(
            appGroups = listOf(AppGroup("g1", "Messenger", listOf("a/.B#0"))),
            notes = listOf(BackupNote("Gut.md", "Text", true, 5L)),
            podcasts = podcasts,
            markets = markets,
            scopes = BackupScopes(setOf("Camera"), listOf("Notes", "Camera")),
            weatherServer = BackupWeatherServer("https://wetter.example.org", null),
            feed = BackupFeed(localFeed, true, false),
            gallery = BackupGallery(setOf("/a.jpg"), setOf(5L), false, true),
            calculator = CalcPersisted(AngleMode.Deg, null, 0.0, listOf(HistoryEntry("2+2", 4.0))),
            camera = BackupCamera(CameraSettings(flash = FlashSetting.On), null),
            notesSettings = BackupNotesSettings(NoteSort.MODIFIED, false),
            agendaEnabled = true,
            dogs = DogData(
                listOf(
                    DogProfile(
                        id = "d1", name = "Bella", ownerPhone = "0170 1", birthDate = java.time.LocalDate.of(2020, 3, 1), birthApprox = true,
                        health = listOf(HealthItem("h1", HealthKind.DEWORMING, "Wurmkur", java.time.LocalDate.of(2026, 9, 1), 3, null)),
                    ),
                ),
            ),
        )
        assertEquals(complete, file(complete))
    }

    // endregion
}
