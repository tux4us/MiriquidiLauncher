// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>Test &amp; Podcast</title>
            <itunes:author>Autor</itunes:author>
            <itunes:image href="http://example.org/cover.jpg"/>
            <item>
              <title>Folge 1</title>
              <guid>g1</guid>
              <pubDate>Mon, 05 Oct 2026 10:00:00 +0000</pubDate>
              <enclosure url="http://example.org/a1.mp3?x=1" length="1" type="audio/mpeg"/>
              <itunes:duration>1:02:03</itunes:duration>
              <description><![CDATA[<p>Notes</p><script>alert(1)</script>]]></description>
            </item>
            <item>
              <title>Folge 2</title>
              <guid>g2</guid>
              <pubDate>Tue, 06 Oct 2026 10:00:00 +0000</pubDate>
              <enclosure url="https://example.org/a2.m4a" type="audio/mp4"/>
              <itunes:duration>754</itunes:duration>
            </item>
            <item><title>Ohne Audio</title></item>
          </channel>
        </rss>
    """.trimIndent()

    private val feedUrl = "https://example.org/feed.xml"

    @Test
    fun parsesEpisodesSortedAndUpgradesHttp() {
        val p = PodcastParser.parse(feed, feedUrl)
        assertEquals("Test & Podcast", p.podcast.title)
        assertEquals("Autor", p.podcast.author)
        assertEquals("https://example.org/cover.jpg", p.podcast.imageUrl)
        assertEquals(2, p.episodes.size)
        assertEquals("Folge 2", p.episodes[0].title)
        assertEquals("https://example.org/a1.mp3?x=1", p.episodes[1].audioUrl)
        assertEquals(3723, p.episodes[1].durationSeconds)
        assertEquals(754, p.episodes[0].durationSeconds)
        assertFalse(p.episodes[1].notesHtml.contains("script"))
        assertTrue(p.episodes[1].notesHtml.contains("Notes"))
        assertEquals(32, p.episodes[0].id.length)
    }

    @Test
    fun rejectsNonRss() {
        try {
            PodcastParser.parse("<html></html>", feedUrl)
            org.junit.Assert.fail()
        } catch (expected: io.github.tux4us.miriquidilauncher.feed.FeedParseException) {
        }
    }

    @Test
    fun durations() {
        assertEquals(0, PodcastParser.parseDuration(null))
        assertEquals(0, PodcastParser.parseDuration("abc"))
        assertEquals(125, PodcastParser.parseDuration("2:05"))
        assertEquals(90, PodcastParser.parseDuration("90.7"))
        assertEquals(0, PodcastParser.parseDuration("1:2:3:4"))
    }

    @Test
    fun searchParsers() {
        val itunes = """{"resultCount":2,"results":[{"collectionName":"A","artistName":"X","feedUrl":"http://a.org/f","artworkUrl600":"https://a.org/i.jpg"},{"collectionName":"B"}]}"""
        val hits = PodcastSearch.parse(SearchProvider.ITUNES, itunes)
        assertEquals(1, hits.size)
        assertEquals("https://a.org/f", hits[0].feedUrl)
        val fyyd = """{"status":1,"data":[{"title":"F","xmlURL":"https://f.org/x","author":"Y","imgURL":"https://f.org/i.png"}]}"""
        assertEquals("F", PodcastSearch.parse(SearchProvider.FYYD, fyyd)[0].title)
        assertTrue(PodcastSearch.parse(SearchProvider.ITUNES, "kein json").isEmpty())
        assertNull(PodcastSearch.url(SearchProvider.ITUNES, "  "))
        assertTrue(PodcastSearch.url(SearchProvider.FYYD, "Wild & Natur")!!.contains("Wild+%26+Natur"))
    }

    @Test
    fun opmlRoundTrip() {
        val subs = listOf(Podcast("https://a.org/f?x=1&y=2", "A & B"), Podcast("https://b.org/f", "C"))
        val text = PodcastOpml.export(subs, "Abos")
        val result = PodcastOpml.parse(text)
        assertEquals(listOf("https://a.org/f?x=1&y=2", "https://b.org/f"), result.feeds.map { it.url })
        assertEquals("A & B", result.feeds[0].title)
    }

    @Test
    fun htmlRoundTripAndCandidates() {
        val subs = listOf(Podcast("https://a.org/f?x=1&y=2", "A <b>", "Au"), Podcast("https://b.org/f", "C"))
        val html = PodcastHtml.export(subs, "Abos")
        assertTrue(html.contains("&lt;b&gt;"))
        val back = PodcastHtml.parse(html)
        assertEquals(listOf("https://a.org/f?x=1&y=2", "https://b.org/f"), back.map { it.url })
        val plain = PodcastHtml.parse("""<a href="http://x.org/feed">X</a> <a href="mailto:a@b">m</a> <a href="https://x.org/feed">dup</a>""")
        assertEquals(listOf("https://x.org/feed"), plain.map { it.url })
    }

    @Test
    fun subscribeAndFavorites() {
        val p = PodcastParser.parse(feed, feedUrl)
        var d = PodcastLogic.subscribe(PodcastData(), p.podcast)!!
        assertEquals(d, PodcastLogic.subscribe(d, p.podcast.copy(feedUrl = feedUrl.uppercase())))
        d = PodcastLogic.applyFeed(d, p)
        assertEquals(2, d.episodes[feedUrl]!!.size)
        val e = p.episodes[0]
        d = PodcastLogic.toggleFavorite(d, e)!!
        assertTrue(PodcastLogic.isFavorite(d, e.id))
        d = PodcastLogic.unsubscribe(d, feedUrl)
        assertTrue(d.subscriptions.isEmpty() && d.episodes.isEmpty())
        assertTrue(PodcastLogic.isFavorite(d, e.id))
        assertNotNull(PodcastLogic.knownEpisodes(d)[e.id])
        d = PodcastLogic.toggleFavorite(d, e)!!
        assertFalse(PodcastLogic.isFavorite(d, e.id))
    }

    @Test
    fun positions() {
        var d = PodcastData()
        d = PodcastLogic.savePosition(d, "a", 3_000, 600_000)
        assertEquals(0L, PodcastLogic.resumePosition(d, "a"))
        d = PodcastLogic.savePosition(d, "a", 120_000, 600_000)
        assertEquals(120_000L, PodcastLogic.resumePosition(d, "a"))
        d = PodcastLogic.savePosition(d, "a", 590_000, 600_000)
        assertEquals(0L, PodcastLogic.resumePosition(d, "a"))
        assertTrue("a" in d.played)
        d = PodcastLogic.setPlayed(d, "a", false)
        assertFalse("a" in d.played)
    }

    @Test
    fun fileNamesAndFormats() {
        val e = PodcastParser.parse(feed, feedUrl).episodes
        assertTrue(PodcastLogic.downloadFileName(e[0]).endsWith(".m4a"))
        assertTrue(PodcastLogic.downloadFileName(e[1]).endsWith(".mp3"))
        assertEquals("1:02:03", PodcastLogic.formatClock(3723))
        assertEquals("2:05", PodcastLogic.formatClock(125))
        assertEquals(1.25f, PodcastLogic.nextSpeed(1.0f), 0.001f)
        assertEquals(0.8f, PodcastLogic.nextSpeed(2.0f), 0.001f)
        assertEquals(1.0f, PodcastLogic.nextSpeed(3.3f), 0.001f)
    }

    @Test
    fun codecRoundTrip() {
        val p = PodcastParser.parse(feed, feedUrl)
        var d = PodcastLogic.applyFeed(PodcastLogic.subscribe(PodcastData(), p.podcast)!!, p)
        d = PodcastLogic.toggleFavorite(d, p.episodes[0])!!
        d = PodcastLogic.savePosition(d, p.episodes[1].id, 100_000, 3_723_000)
        d = PodcastLogic.setDownload(d, p.episodes[0], "x.m4a").copy(provider = SearchProvider.FYYD, speed = 1.5f)
        val back = PodcastCodec.decode(PodcastCodec.encode(d))
        assertEquals(d, back)
        assertEquals(1, back!!.saved.size)
        val core = PodcastCodec.encode(d, withEpisodes = false)
        assertTrue(PodcastCodec.decode(core)!!.episodes.isEmpty())
        assertEquals(d, PodcastCodec.decode(core, PodcastCodec.encodeEpisodes(d.episodes)))
        d = PodcastLogic.setDownload(d, p.episodes[0], null)
        assertTrue(d.saved.isEmpty() && d.downloads.isEmpty())
        assertNull(PodcastCodec.decode("{\"v\":99}"))
        assertNull(PodcastCodec.decode("kaputt"))
    }

    private fun ep(id: String, feed: String, published: Long) = Episode(
        id = id, podcastUrl = feed, podcastTitle = feed, title = id, publishedSeconds = published,
        audioUrl = "https://example.org/$id.mp3", mime = "audio/mpeg", durationSeconds = 60, notesHtml = "", link = null, imageUrl = null,
    )

    @Test fun allEpisodesAreSortedNewestFirstAcrossFeeds() {
        val a = "https://a.example/feed"
        val b = "https://b.example/feed"
        val data = PodcastData(
            subscriptions = listOf(Podcast(a, "A"), Podcast(b, "B")),
            episodes = mapOf(
                a to listOf(ep("a-old", a, 100), ep("a-new", a, 300), ep("a-undated", a, 0)),
                b to listOf(ep("b-mid", b, 200), ep("b-same", b, 300)),
            ),
        )
        assertEquals(listOf("a-new", "b-same", "b-mid", "a-old", "a-undated"), PodcastLogic.allEpisodes(data).map { it.id })
    }

    @Test fun allEpisodesIgnoreUnsubscribedFeedsAndDuplicates() {
        val a = "https://a.example/feed"
        val gone = "https://gone.example/feed"
        val data = PodcastData(
            subscriptions = listOf(Podcast(a, "A")),
            episodes = mapOf(a to listOf(ep("x", a, 5), ep("x", a, 5)), gone to listOf(ep("g", gone, 99))),
        )
        assertEquals(listOf("x"), PodcastLogic.allEpisodes(data).map { it.id })
        assertTrue(PodcastLogic.allEpisodes(PodcastData()).isEmpty())
    }
}
