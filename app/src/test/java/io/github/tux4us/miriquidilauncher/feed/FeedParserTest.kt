// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/"
     xmlns:atom="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
<channel>
  <title>Beispiel &amp; Co</title>
  <link>https://example.org/</link>
  <atom:link href="https://example.org/feed.xml" rel="self" type="application/rss+xml"/>
  <item>
    <title>Erster Beitrag</title>
    <link>https://example.org/1</link>
    <guid isPermaLink="false">id-1</guid>
    <pubDate>Tue, 10 Jun 2025 04:00:00 +0200</pubDate>
    <dc:creator>Anna</dc:creator>
    <description>Kurztext</description>
    <content:encoded><![CDATA[<p>Langer <b>Text</b></p>]]></content:encoded>
    <enclosure url="https://example.org/bild.jpg" type="image/jpeg" length="1"/>
  </item>
  <item>
    <title>Zweiter</title>
    <guid>https://example.org/2</guid>
    <description>&lt;p&gt;Nur Beschreibung&lt;/p&gt;</description>
  </item>
</channel></rss>"""

    @Test
    fun rssItems() {
        val feed = FeedParser.parse(rss)
        assertEquals("Beispiel & Co", feed.title)
        assertEquals("https://example.org/", feed.link)
        assertEquals("https://example.org/feed.xml", feed.feedUrl)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("id-1", first.key)
        assertEquals("Erster Beitrag", first.title)
        assertEquals("https://example.org/1", first.url)
        assertEquals("Anna", first.author)
        assertEquals(1749520800L, first.publishedSeconds)
        assertTrue(first.body.contains("<p>Langer <b>Text</b></p>"))
        // Bild des Eintrags wird vorangestellt, weil der Text keines enthält
        assertTrue(first.body.startsWith("<p><img src=\"https://example.org/bild.jpg\"></p>"))
        val second = feed.items[1]
        assertEquals("https://example.org/2", second.key)
        assertEquals("https://example.org/2", second.url)
        assertEquals("<p>Nur Beschreibung</p>", second.body)
        assertEquals(0L, second.publishedSeconds)
    }

    @Test
    fun atomEntries() {
        val atom = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Atom-Blog</title>
  <link rel="self" href="https://a.example/feed"/>
  <link rel="alternate" href="https://a.example/"/>
  <author><name>Standard</name></author>
  <entry>
    <title type="html">Titel &amp;amp; mehr</title>
    <id>tag:a.example,2025:1</id>
    <link rel="alternate" href="https://a.example/p1"/>
    <link rel="enclosure" href="https://a.example/x.mp3"/>
    <updated>2025-06-10T04:00:00+02:00</updated>
    <content type="html">&lt;p&gt;Inhalt&lt;/p&gt;</content>
  </entry>
  <entry>
    <title>Mit XHTML</title>
    <id>2</id>
    <published>2025-06-09T10:00:00Z</published>
    <author><name>Berta</name></author>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hallo</p></div></content>
  </entry>
  <entry>
    <title>Nur Text</title>
    <id>3</id>
    <summary>1 &lt; 2 und 3 &gt; 2</summary>
  </entry>
</feed>"""
        val feed = FeedParser.parse(atom)
        assertEquals("Atom-Blog", feed.title)
        assertEquals("https://a.example/", feed.link)
        assertEquals("https://a.example/feed", feed.feedUrl)
        assertEquals(3, feed.items.size)
        val a = feed.items[0]
        assertEquals("tag:a.example,2025:1", a.key)
        assertEquals("Titel & mehr", a.title)
        assertEquals("https://a.example/p1", a.url)
        assertEquals("Standard", a.author)
        assertEquals(1749520800L, a.publishedSeconds)
        assertEquals("<p>Inhalt</p>", a.body)
        val b = feed.items[1]
        assertEquals("Berta", b.author)
        assertTrue(b.body.contains("<p>Hallo</p>"))
        assertNull(b.url)
        assertEquals("1 &lt; 2 und 3 &gt; 2", feed.items[2].body)
    }

    @Test
    fun rss1RdfItemsBesideChannel() {
        val rdf = """<?xml version="1.0"?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
<channel><title>RDF</title><link>https://r.example/</link></channel>
<item><title>Eins</title><link>https://r.example/1</link><description>Text</description><dc:date>2025-06-10T02:00:00Z</dc:date></item>
</rdf:RDF>"""
        val feed = FeedParser.parse(rdf)
        assertEquals("RDF", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals(1749520800L, feed.items[0].publishedSeconds)
        assertEquals("https://r.example/1", feed.items[0].key)
    }

    @Test
    fun jsonFeed() {
        val json = """{
          "version": "https://jsonfeed.org/version/1.1",
          "title": "JSON Blog",
          "home_page_url": "https://j.example/",
          "feed_url": "https://j.example/feed.json",
          "authors": [{"name": "Chris"}],
          "items": [
            {"id": "1", "url": "https://j.example/1", "title": "Eins", "content_html": "<p>HTML</p>",
             "date_published": "2025-06-10T04:00:00+02:00", "image": "https://j.example/i.png"},
            {"id": "2", "content_text": "Zeile 1\nZeile <2>", "authors": [{"name": "Dora"}]},
            {"url": "https://j.example/3", "summary": "Nur Zusammenfassung"},
            "kein Objekt"
          ]
        }"""
        val feed = FeedParser.parse(json)
        assertEquals("JSON Blog", feed.title)
        assertEquals("https://j.example/feed.json", feed.feedUrl)
        assertEquals(3, feed.items.size)
        assertEquals("Chris", feed.items[0].author)
        assertEquals(1749520800L, feed.items[0].publishedSeconds)
        assertTrue(feed.items[0].body.contains("<p>HTML</p>"))
        assertTrue(feed.items[0].body.startsWith("<p><img src=\"https://j.example/i.png\">"))
        assertEquals("Zeile 1<br>Zeile &lt;2&gt;", feed.items[1].body)
        assertEquals("Dora", feed.items[1].author)
        assertEquals("https://j.example/3", feed.items[2].key)
        assertEquals("Nur Zusammenfassung", feed.items[2].body)
    }

    @Test
    fun imageWithoutHttpsIsIgnored() {
        val json = """{"items":[{"id":"1","content_html":"<p>x</p>","image":"http://j.example/i.png"}]}"""
        assertEquals("<p>x</p>", FeedParser.parse(json).items[0].body)
    }

    @Test
    fun urlsMustBeHttpOrHttps() {
        val json = """{"items":[{"id":"1","url":"javascript:alert(1)"},{"id":"2","url":"ftp://x/y"}]}"""
        val items = FeedParser.parse(json).items
        assertNull(items[0].url)
        assertNull(items[1].url)
    }

    @Test
    fun rejectsOtherDocuments() {
        for (text in listOf("", "Hallo", "<html><body>Seite</body></html>", "{\"a\":1}", "[1,2]", "{\"items\": ", "<rss><x/></rss>")) {
            assertTrue(text, runCatching { FeedParser.parse(text) }.exceptionOrNull() is FeedParseException)
        }
    }

    @Test
    fun limitsItemCountAndBodySize() {
        val many = (1..300).joinToString(",") { """{"id":"$it","content_html":"x"}""" }
        assertEquals(FeedParser.MAX_PARSED_ITEMS, FeedParser.parse("""{"items":[$many]}""").items.size)
        val huge = "a".repeat(NewsApi.MAX_BODY_CHARS + 500)
        val body = FeedParser.parse("""{"items":[{"id":"1","content_html":"$huge"}]}""").items[0].body
        assertEquals(NewsApi.MAX_BODY_CHARS, body.length)
    }

    @Test
    fun decodeUsesHeaderThenXmlDeclarationThenUtf8() {
        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>Bär</a>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(FeedParser.decode(latin, "application/rss+xml").contains("Bär"))
        val utf8 = "<a>Bär</a>".toByteArray(Charsets.UTF_8)
        assertTrue(FeedParser.decode(utf8, null).contains("Bär"))
        assertTrue(FeedParser.decode(latin, "text/xml; charset=iso-8859-1").contains("Bär"))
        assertTrue(FeedParser.decode(utf8, "text/xml; charset=unbekannt-xyz").contains("Bär"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"items\":[]}".toByteArray()
        assertEquals("{\"items\":[]}", FeedParser.decode(bom, null))
    }
}
