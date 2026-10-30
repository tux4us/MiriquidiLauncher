// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.nio.charset.Charset

/** Der Inhalt ist kein lesbarer Feed (RSS, Atom oder JSON Feed). */
class FeedParseException(message: String) : Exception(message)

/**
 * Ein Artikel eines Feeds. [key] ist die Kennung aus dem Feed (guid, id oder Adresse) und identifiziert den
 * Artikel über mehrere Abrufe hinweg; [publishedSeconds] ist 0, wenn der Feed kein Datum liefert.
 */
data class ParsedItem(
    val key: String,
    val title: String,
    val author: String?,
    val url: String?,
    val body: String,
    val publishedSeconds: Long,
)

/** [feedUrl] ist die im Feed selbst genannte Adresse (JSON Feed `feed_url`, Atom/RSS `rel="self"`). */
data class ParsedFeed(val title: String, val link: String?, val feedUrl: String?, val items: List<ParsedItem>)

/**
 * Liest RSS 2.0, RSS 1.0 (RDF), Atom 1.0 und JSON Feed (1.0 und 1.1). Plattformunabhängig und auf der JVM
 * testbar. Die Artikeltexte bleiben HTML; die Leseansicht bereinigt sie vor der Anzeige.
 */
object FeedParser {
    private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")

    /** @throws FeedParseException wenn das Format nicht erkannt oder der Inhalt beschädigt ist */
    fun parse(text: String): ParsedFeed {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            trimmed.startsWith("{") -> parseJson(trimmed)
            trimmed.startsWith("<") -> fromXml(parseXml(trimmed))
            else -> throw FeedParseException("Unbekanntes Format")
        }
    }

    internal fun parseXml(text: String): XmlNode = try {
        MiniXml.parse(text)
    } catch (e: XmlException) {
        throw FeedParseException(e.message ?: "XML nicht lesbar")
    }

    internal fun fromXml(root: XmlNode): ParsedFeed = when (root.local.lowercase()) {
        "rss" -> rss(root.childLocal("channel") ?: throw FeedParseException("RSS ohne channel"), root)
        "rdf" -> rss(root.childLocal("channel") ?: root, root)
        "feed" -> atom(root)
        else -> throw FeedParseException("Kein Feed (Wurzelelement ${root.name})")
    }

    // region RSS ---------------------------------------------------------------------------------

    private fun rss(channel: XmlNode, root: XmlNode): ParsedFeed {
        // RSS 2.0: item steckt im channel; RSS 1.0: item steht neben dem channel.
        val nodes = channel.childrenNamed("item").ifEmpty { root.childrenNamed("item") }
        val items = nodes.take(MAX_PARSED_ITEMS).map { node ->
            val title = plain(node.child("title")?.text)
            val link = node.child("link")?.text?.let(::cleanUrl)
            val guid = node.child("guid")
            val permalink = guid?.takeIf { it.attributes["isPermaLink"] != "false" }?.text?.let(::cleanUrl)
            val published = FeedDates.parse(node.child("pubDate")?.text) ?: FeedDates.parse(node.child("dc:date")?.text)
            val body = node.child("content:encoded")?.text?.takeIf { it.isNotBlank() }
                ?: node.child("description")?.text.orEmpty()
            val author = node.child("dc:creator")?.text?.takeIf { it.isNotBlank() }
                ?: node.child("author")?.text?.takeIf { it.isNotBlank() }
            ParsedItem(
                key = guid?.text?.takeIf { it.isNotBlank() } ?: link ?: fallbackKey(title, published),
                title = title,
                author = author?.let(::plain),
                url = link ?: permalink,
                body = withImage(body, rssImage(node)),
                publishedSeconds = published ?: 0L,
            )
        }
        val selfLink = channel.childrenNamed("atom:link").firstOrNull { it.attributes["rel"] == "self" }
        return ParsedFeed(
            title = plain(channel.child("title")?.text),
            link = channel.child("link")?.text?.let(::cleanUrl),
            feedUrl = selfLink?.attributes?.get("href")?.let(::cleanUrl),
            items = items,
        )
    }

    private fun rssImage(item: XmlNode): String? {
        for (node in item.children) {
            val url = node.attributes["url"] ?: continue
            val isImage = when (node.name) {
                "enclosure" -> node.attributes["type"]?.startsWith("image/", ignoreCase = true) == true
                "media:thumbnail" -> true
                "media:content" ->
                    node.attributes["medium"] == "image" ||
                        node.attributes["type"]?.startsWith("image/", ignoreCase = true) == true
                else -> false
            }
            if (isImage) return url
        }
        return null
    }

    // endregion

    // region Atom --------------------------------------------------------------------------------

    private fun atom(feed: XmlNode): ParsedFeed {
        val feedAuthor = feed.childLocal("author")?.childLocal("name")?.text?.takeIf { it.isNotBlank() }
        val items = feed.childrenLocal("entry").take(MAX_PARSED_ITEMS).map { entry ->
            val title = plain(atomText(entry.childLocal("title")))
            val link = atomLink(entry, "alternate")
            val published = FeedDates.parse(entry.childLocal("published")?.text)
                ?: FeedDates.parse(entry.childLocal("updated")?.text)
            val content = entry.childLocal("content")?.let { atomHtml(it) }?.takeIf { it.isNotBlank() }
                ?: entry.childLocal("summary")?.let { atomHtml(it) }.orEmpty()
            val author = entry.childLocal("author")?.childLocal("name")?.text?.takeIf { it.isNotBlank() } ?: feedAuthor
            ParsedItem(
                key = entry.childLocal("id")?.text?.takeIf { it.isNotBlank() } ?: link ?: fallbackKey(title, published),
                title = title,
                author = author?.let(::plain),
                url = link,
                body = withImage(content, null),
                publishedSeconds = published ?: 0L,
            )
        }
        return ParsedFeed(
            title = plain(atomText(feed.childLocal("title"))),
            link = atomLink(feed, "alternate"),
            feedUrl = atomLink(feed, "self"),
            items = items,
        )
    }

    private fun atomLink(node: XmlNode, wanted: String): String? =
        node.childrenLocal("link")
            .firstOrNull { (it.attributes["rel"] ?: "alternate") == wanted }
            ?.attributes?.get("href")?.let(::cleanUrl)

    /** Titel können `type="html"` sein; dann stehen Auszeichnung und Entitäten im Text. */
    private fun atomText(node: XmlNode?): String? = when (node?.attributes?.get("type")) {
        "xhtml" -> node.innerXml
        else -> node?.text
    }

    /** Inhalt als HTML: `html` unverändert, `xhtml` als inneres XML, `text` maskiert. */
    private fun atomHtml(node: XmlNode): String {
        val type = node.attributes["type"]?.lowercase()
        return when {
            type == "xhtml" -> node.innerXml
            type == "html" || type == "text/html" -> node.text
            node.text.contains(HTML_TAG) -> node.text
            else -> escapeText(node.text)
        }
    }

    // endregion

    // region JSON Feed ---------------------------------------------------------------------------

    private fun parseJson(text: String): ParsedFeed {
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            throw FeedParseException(e.message ?: "JSON nicht lesbar")
        } ?: throw FeedParseException("Kein JSON-Objekt")
        val entries = root["items"].asArray() ?: throw FeedParseException("Kein JSON Feed (items fehlt)")
        val feedAuthor = jsonAuthor(root)
        val items = entries.take(MAX_PARSED_ITEMS).mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val url = (o["url"].asString() ?: o["external_url"].asString())?.let(::cleanUrl)
            val title = plain(o["title"].asString())
            val published = FeedDates.parse(o["date_published"].asString()) ?: FeedDates.parse(o["date_modified"].asString())
            val body = o["content_html"].asString()?.takeIf { it.isNotBlank() }
                ?: o["content_text"].asString()?.takeIf { it.isNotBlank() }?.let(::escapeText)
                ?: o["summary"].asString()?.let(::escapeText).orEmpty()
            val id = o["id"].asString()?.takeIf { it.isNotBlank() }
            ParsedItem(
                key = id ?: url ?: fallbackKey(title, published),
                title = title,
                author = jsonAuthor(o) ?: feedAuthor,
                url = url,
                body = withImage(body, o["image"].asString() ?: o["banner_image"].asString()),
                publishedSeconds = published ?: 0L,
            )
        }
        return ParsedFeed(
            title = plain(root["title"].asString()),
            link = root["home_page_url"].asString()?.let(::cleanUrl),
            feedUrl = root["feed_url"].asString()?.let(::cleanUrl),
            items = items,
        )
    }

    private fun jsonAuthor(o: Map<String, Any?>): String? {
        val candidates = o["authors"].asArray().orEmpty() + listOf(o["author"])
        return candidates.firstNotNullOfOrNull { it.asObject()?.get("name").asString()?.takeIf { n -> n.isNotBlank() } }
            ?.let(::plain)
    }

    // endregion

    // region Gemeinsames -------------------------------------------------------------------------

    /** Höchstens so viele Artikel werden pro Feed ausgewertet; danach ist der Rest ohnehin nicht sichtbar. */
    const val MAX_PARSED_ITEMS = 200

    private fun plain(text: String?): String = if (text.isNullOrBlank()) "" else FeedText.plainText(text)

    private fun cleanUrl(raw: String): String? {
        val text = raw.trim()
        return text.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    }

    private fun fallbackKey(title: String, published: Long?): String = "t:$title|${published ?: 0L}"

    private fun escapeText(text: String): String = FeedText.escapeHtml(text).replace("\r\n", "\n").replace("\n", "<br>")

    /** Hat der Artikel kein Bild im Text, wird das Bild des Feed-Eintrags (nur HTTPS) vorangestellt. */
    private fun withImage(body: String, image: String?): String {
        val limited = if (body.length > NewsApi.MAX_BODY_CHARS) body.substring(0, NewsApi.MAX_BODY_CHARS) else body
        val url = image?.trim()
        if (url == null || !url.startsWith("https://", ignoreCase = true) || limited.contains("<img", ignoreCase = true)) {
            return limited
        }
        return "<p><img src=\"${FeedText.escapeHtml(url)}\"></p>$limited"
    }

    // endregion

    /**
     * Wandelt die Antwort in Text um. Die Zeichenkodierung stammt aus dem HTTP-Kopf (`charset=`), sonst aus der
     * XML-Deklaration, sonst gilt UTF-8. Eine Byte-Order-Marke hat Vorrang.
     */
    fun decode(bytes: ByteArray, contentType: String?): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val declared = contentType?.let { CHARSET_HEADER.find(it)?.groupValues?.get(1) }
            ?: CHARSET_XML.find(String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = declared?.let {
            try {
                Charset.forName(it)
            } catch (ignored: IllegalArgumentException) {
                null
            }
        } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private val CHARSET_HEADER = Regex("charset\\s*=\\s*\"?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
    private val CHARSET_XML = Regex("<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']", RegexOption.IGNORE_CASE)
}
