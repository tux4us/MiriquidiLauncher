// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTextTest {
    @Test
    fun plainTextStripsTagsAndDecodesEntities() {
        val html = "<p>Zwei&nbsp;W&ouml;rter &amp; mehr</p><p>Neue&#32;Zeile &#x41; &lt;ok&gt;</p>"
        assertEquals("Zwei Wörter & mehr Neue Zeile A <ok>", FeedText.plainText(html))
    }

    @Test
    fun plainTextDropsScriptAndStyleContent() {
        val html = "<style>p{color:red}</style><script>alert(1)</script><p>Sichtbar</p>"
        assertEquals("Sichtbar", FeedText.plainText(html))
    }

    @Test
    fun plainTextSeparatesBlocks() {
        assertEquals("Eins Zwei", FeedText.plainText("<p>Eins</p><p>Zwei</p>"))
        assertEquals("a b", FeedText.plainText("a<br/>b"))
    }

    @Test
    fun unknownEntitiesStayUntouched() {
        assertEquals("&foo; &#99999999;", FeedText.decodeEntities("&foo; &#99999999;"))
    }

    @Test
    fun excerptCutsAtWordBoundary() {
        val e = FeedText.excerpt("<p>Dies ist ein ziemlich langer Satz für die Vorschau.</p>", 30)
        assertEquals("Dies ist ein ziemlich langer…", e)
        assertEquals("Kurz", FeedText.excerpt("<b>Kurz</b>", 30))
    }

    @Test
    fun firstImageSkipsTrackingPixelsAndInsecureSources() {
        val html = """
            <img src="https://t.example/pixel.gif" width="1" height="1">
            <img src="http://insecure.example/a.jpg">
            <img alt="x" src='https://img.example/a.jpg?x=1&amp;y=2'>
        """
        assertEquals("https://img.example/a.jpg?x=1&y=2", FeedText.firstImageUrl(html))
        assertNull(FeedText.firstImageUrl("<p>kein Bild</p>"))
        assertNull(FeedText.firstImageUrl("<script><img src=\"https://x.example/a.png\"></script>"))
    }

    @Test
    fun sanitizeRemovesActiveContent() {
        val html = """
            <p onclick="x()">Text</p>
            <script>alert(1)</script>
            <iframe src="https://evil.example"></iframe>
            <form action="/x"><input name="a"></form>
            <a href="javascript:alert(1)">Link</a>
            <img src="https://ok.example/a.jpg" onerror='steal()'>
            <meta http-equiv="refresh" content="0;url=https://evil.example">
            <link rel="stylesheet" href="https://evil.example/s.css">
        """
        val clean = FeedText.sanitize(html)
        for (bad in listOf("<script", "alert(1)", "<iframe", "<form", "javascript:", "onclick", "onerror", "<meta", "<link")) {
            assertFalse("enthält noch $bad", clean.contains(bad, ignoreCase = true))
        }
        assertTrue(clean.contains("<p>Text</p>"))
        assertTrue(clean.contains("""<img src="https://ok.example/a.jpg">"""))
        assertTrue(clean.contains("""href="#""""))
    }

    @Test
    fun headCutsOffIncompleteTag() {
        assertEquals("<p>abc</p>", FeedText.head("<p>abc</p>", 50))
        assertEquals("<p>abc</p>", FeedText.head("<p>abc</p><img src=\"x\">", 12))
        assertEquals("<p>abc</p>text", FeedText.head("<p>abc</p>text more", 14))
    }

    @Test
    fun escapeHtml() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;", FeedText.escapeHtml("<a href=\"x\">Tom & Jerry's</a>"))
    }

    @Test
    fun readerDocumentEscapesMetaAndAppliesPolicy() {
        val colors = ReaderColors("#101010", "#eeeeee", "#999999", "#88aaff")
        val doc = FeedHtml.readerDocument("A <b> & B", "Quelle <x>", "<p>Inhalt</p><script>x()</script>", colors, loadImages = false)
        assertTrue(doc.contains("<h1>A &lt;b&gt; &amp; B</h1>"))
        assertTrue(doc.contains("Quelle &lt;x&gt;"))
        assertTrue(doc.contains("<p>Inhalt</p>"))
        assertFalse(doc.contains("x()"))
        assertTrue(doc.contains("default-src 'none'; img-src data:;"))
        val withImages = FeedHtml.readerDocument("t", "m", "", colors, loadImages = true)
        assertTrue(withImages.contains("img-src https: data:"))
        assertFalse(withImages.contains("script-src"))
    }
}
