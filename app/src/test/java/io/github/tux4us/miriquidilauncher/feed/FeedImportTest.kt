// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedImportTest {
    private fun ok(input: String): String? = (FeedUrl.parse(input) as? FeedUrlResult.Ok)?.url

    @Test
    fun feedUrlNormalization() {
        assertEquals("https://example.org/feed.xml", ok("example.org/feed.xml"))
        assertEquals("https://example.org/a?x=1", ok("  HTTPS://Example.ORG/a?x=1#frag "))
        assertEquals("https://example.org/rss", ok("feed://example.org/rss"))
        assertEquals("https://example.org/rss", ok("feed:https://example.org/rss"))
        assertEquals("https://example.org:8443", ok("https://example.org:8443"))
    }

    @Test
    fun feedUrlRejections() {
        assertEquals(FeedUrlResult.Empty, FeedUrl.parse("   "))
        assertEquals(FeedUrlResult.NotHttps, FeedUrl.parse("http://example.org/feed"))
        assertEquals(FeedUrlResult.Invalid, FeedUrl.parse("ftp://example.org/feed"))
        assertEquals(FeedUrlResult.Invalid, FeedUrl.parse("https://user:pw@example.org/feed"))
        assertEquals(FeedUrlResult.Invalid, FeedUrl.parse("https://exa mple.org"))
        assertEquals(FeedUrlResult.Invalid, FeedUrl.parse("https://"))
    }

    private val opml = """<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0"><head><title>Abos</title></head><body>
  <outline text="Nachrichten">
    <outline type="rss" text="Tagesschau" title="Tagesschau &amp; mehr" xmlUrl="https://example.org/tagesschau.xml" htmlUrl="https://example.org"/>
    <outline type="rss" text="Alt" xmlUrl="http://old.example/feed"/>
    <outline type="rss" text="Doppelt" XMLURL="https://EXAMPLE.org/tagesschau.xml"/>
  </outline>
  <outline type="rss" text="Ohne Titel" xmlUrl="https://b.example/rss"/>
  <outline type="rss" text="Kaputt" xmlUrl="ftp://x"/>
  <outline text="Ordner ohne Feed"/>
</body></opml>"""

    @Test
    fun opmlFeedsAreCollectedFromNestedOutlines() {
        val result = Opml.parse(MiniXml.parse(opml))
        assertEquals(2, result.feeds.size)
        assertEquals(OpmlFeed("https://example.org/tagesschau.xml", "Tagesschau & mehr"), result.feeds[0])
        assertEquals(OpmlFeed("https://b.example/rss", "Ohne Titel"), result.feeds[1])
        assertEquals(1, result.skippedInsecure)
        assertEquals(1, result.skippedInvalid)
    }

    @Test
    fun opmlRequiresOpmlRoot() {
        assertTrue(runCatching { Opml.parse(MiniXml.parse("<rss/>")) }.exceptionOrNull() is FeedParseException)
    }

    @Test
    fun discoveryFindsFeedLinkInHtml() {
        val html = """<html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/atom+xml" title="Atom" href="/atom.xml">
            <link rel="alternate" type="application/rss+xml" href="https://e.example/rss">
            </head></html>"""
        assertEquals("https://e.example/atom.xml", FeedDiscovery.find(html, "https://e.example/blog/"))
        assertNull(FeedDiscovery.find("<link rel=\"alternate\" type=\"application/rss+xml\" href=\"http://e.example/rss\">", "https://e.example/"))
        assertNull(FeedDiscovery.find("<html>kein Feed</html>", "https://e.example/"))
        assertEquals(
            "https://e.example/f",
            FeedDiscovery.find("<link href='/f' type='application/feed+json' rel='alternate'>", "https://e.example/x"),
        )
    }

    @Test
    fun importOpmlGivesSubscriptionsWithoutSeeds() {
        val plan = FeedImport.parse(opml)
        assertEquals(2, plan.feeds.size)
        assertTrue(plan.seeds.isEmpty())
        assertEquals(1, plan.skippedInsecure)
    }

    @Test
    fun importJsonFeedWithOwnUrlBecomesSubscriptionWithSeed() {
        val json = """{"version":"https://jsonfeed.org/version/1.1","title":"J","feed_url":"https://j.example/feed.json",
            "items":[{"id":"1","title":"Eins","content_html":"<p>x</p>"}]}"""
        val plan = FeedImport.parse(json)
        assertEquals(listOf(LocalFeed("https://j.example/feed.json", "J")), plan.feeds)
        assertFalse(plan.feeds[0].isStatic)
        assertEquals(1, plan.seeds["https://j.example/feed.json"]?.items?.size)
    }

    @Test
    fun importFileWithoutOwnUrlBecomesStaticCollection() {
        val json = """{"title":"Sammlung","items":[{"id":"1","title":"Eins"}]}"""
        val plan = FeedImport.parse(json)
        assertEquals(1, plan.feeds.size)
        assertTrue(plan.feeds[0].isStatic)
        assertEquals("Sammlung", plan.feeds[0].title)
        assertEquals(plan.feeds[0].url, FeedImport.parse(json).feeds[0].url)
        val rssPlan = FeedImport.parse("<rss><channel><title>R</title><item><title>t</title><guid>g</guid></item></channel></rss>")
        assertTrue(rssPlan.feeds[0].isStatic)
    }

    @Test
    fun importRejectsOtherContent() {
        for (text in listOf("", "Text", "<html></html>", "{\"x\":1}")) {
            assertTrue(text, runCatching { FeedImport.parse(text) }.exceptionOrNull() is FeedParseException)
        }
    }

    @Test
    fun planForUsesFetchedAddressEvenWhenFeedNamesAnother() {
        val parsed = FeedParser.parse("""{"feed_url":"https://other.example/f","title":"T","items":[]}""")
        val plan = FeedImport.planFor(parsed, fetchedFrom = "https://mine.example/f")
        assertEquals("https://mine.example/f", plan.feeds[0].url)
    }
}
