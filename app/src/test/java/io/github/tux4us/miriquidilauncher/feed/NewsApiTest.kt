// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsApiTest {
    private fun ok(input: String) = (NewsApi.parseServerUrl(input) as ServerUrl.Ok).baseUrl

    @Test
    fun serverUrlGetsHttpsAndLosesTrailingSlash() {
        assertEquals("https://cloud.example.org", ok("cloud.example.org"))
        assertEquals("https://cloud.example.org", ok("  https://Cloud.Example.org/  "))
    }

    @Test
    fun serverUrlKeepsSubdirectoryAndPort() {
        assertEquals("https://example.org/nextcloud", ok("https://example.org/nextcloud/"))
        assertEquals("https://example.org:8443/nc", ok("example.org:8443/nc"))
    }

    @Test
    fun serverUrlCutsPastedNewsAppAddress() {
        assertEquals("https://cloud.example.org", ok("https://cloud.example.org/index.php/apps/news/"))
        assertEquals("https://cloud.example.org/nc", ok("https://cloud.example.org/nc/apps/news"))
        assertEquals("https://cloud.example.org", ok("https://cloud.example.org/?foo=bar#x"))
    }

    @Test
    fun serverUrlRejectsCleartextAndCredentialsAndGarbage() {
        assertEquals(ServerUrl.NotHttps, NewsApi.parseServerUrl("http://cloud.example.org"))
        assertEquals(ServerUrl.NotHttps, NewsApi.parseServerUrl("HTTP://cloud.example.org"))
        assertEquals(ServerUrl.Empty, NewsApi.parseServerUrl("   "))
        assertEquals(ServerUrl.Invalid, NewsApi.parseServerUrl("https://user:pass@cloud.example.org"))
        assertEquals(ServerUrl.Invalid, NewsApi.parseServerUrl("ftp://cloud.example.org"))
        assertEquals(ServerUrl.Invalid, NewsApi.parseServerUrl("https://exa mple.org"))
    }

    @Test
    fun apiUrlAndItemsPath() {
        assertEquals(
            "https://c.example/index.php/apps/news/api/v1-3/feeds",
            NewsApi.url("https://c.example", "feeds"),
        )
        assertEquals("items?type=3&getRead=false&batchSize=100&oldestFirst=false", NewsApi.itemsPath(false, 100))
        assertEquals("items?type=3&getRead=true&batchSize=50&oldestFirst=false&offset=812", NewsApi.itemsPath(true, 50, 812))
        assertEquals("items?type=0&id=7&getRead=false&batchSize=100&oldestFirst=false", NewsApi.itemsPath(false, 100, feedId = 7))
        assertEquals("items?type=0&id=7&getRead=true&batchSize=50&oldestFirst=false&offset=9", NewsApi.itemsPath(true, 50, 9, 7))
    }

    @Test
    fun basicAuthEncodesUtf8() {
        // "Jörg:pässword" in UTF-8, Base64
        assertEquals("Basic SsO2cmc6cMOkc3N3b3Jk", NewsApi.basicAuth("Jörg", "pässword"))
    }

    @Test
    fun parsesFeeds() {
        val json = """{"starredCount":2,"newestItemId":99,"feeds":[
            {"id":3,"url":"https://a.example/rss","title":"Blog A","faviconLink":null,"added":1,"folderId":0,"unreadCount":4,"ordering":0,"link":"https://a.example","pinned":false,"updateErrorCount":0,"lastUpdateError":null},
            {"id":7,"url":"https://b.example/rss","title":"","link":"https://b.example"},
            {"title":"ohne id"}
        ]}"""
        val feeds = NewsApi.parseFeeds(json)
        assertEquals(2, feeds.size)
        assertEquals(FeedInfo(3, "Blog A", "https://a.example"), feeds[0])
        assertEquals("https://b.example", feeds[1].title) // leerer Titel fällt auf die Adresse zurück
    }

    @Test
    fun parsesItems() {
        val json = """{"items":[
            {"id":120,"guid":"x","guidHash":"h","url":"https://a.example/post","title":"  Hallo Welt ","author":"Anna","pubDate":1759650000,
             "body":"<p>Text &amp; mehr</p>","feedId":3,"unread":true,"starred":false,"lastModified":1759650001000000,"rtl":false,"fingerprint":"f"},
            {"id":119,"title":"Ohne Felder","feedId":7,"unread":false,"starred":true,"author":"  ","url":null,"body":null,"pubDate":null},
            {"title":"ohne id"}
        ]}"""
        val items = NewsApi.parseItems(json)
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals(120L, first.id)
        assertEquals("Hallo Welt", first.title)
        assertEquals("Anna", first.author)
        assertEquals("https://a.example/post", first.url)
        assertEquals(1759650000L, first.publishedSeconds)
        assertTrue(first.unread)
        assertFalse(first.starred)
        val second = items[1]
        assertNull(second.author)
        assertNull(second.url)
        assertEquals("", second.body)
        assertEquals(0L, second.publishedSeconds)
        assertTrue(second.starred)
    }

    @Test
    fun emptyOrUnexpectedResponsesGiveEmptyLists() {
        assertTrue(NewsApi.parseItems("""{"items":[]}""").isEmpty())
        assertTrue(NewsApi.parseItems("""{"other":1}""").isEmpty())
        assertTrue(NewsApi.parseFeeds("[]").isEmpty())
    }

    @Test
    fun bodyIsTruncated() {
        val long = "a".repeat(NewsApi.MAX_BODY_CHARS + 500)
        val items = NewsApi.parseItems("""{"items":[{"id":1,"body":"$long","unread":true}]}""")
        assertEquals(NewsApi.MAX_BODY_CHARS, items.single().body.length)
    }
}
