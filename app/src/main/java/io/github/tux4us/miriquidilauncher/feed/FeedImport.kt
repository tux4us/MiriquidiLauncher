// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import java.net.URI
import java.net.URISyntaxException

/** Ergebnis der Prüfung einer eingegebenen Feed-Adresse. */
sealed interface FeedUrlResult {
    data class Ok(val url: String) : FeedUrlResult
    data object Empty : FeedUrlResult

    /** Die Adresse beginnt mit `http://`; Feeds werden nur über HTTPS abgerufen. */
    data object NotHttps : FeedUrlResult
    data object Invalid : FeedUrlResult
}

object FeedUrl {
    /**
     * Prüft die Eingabe. Ohne Schema wird `https://` ergänzt, `feed://` gilt als `https://`. Adressen mit
     * Benutzerangaben werden abgelehnt. Das Fragment (`#…`) wird entfernt, der Host kleingeschrieben.
     */
    fun parse(input: String): FeedUrlResult {
        var text = input.trim()
        if (text.isEmpty()) return FeedUrlResult.Empty
        if (text.startsWith("feed://", ignoreCase = true)) text = "https://" + text.substring(7)
        if (text.startsWith("feed:https://", ignoreCase = true)) text = text.substring(5)
        val withScheme = if (text.contains("://")) text else "https://$text"
        if (withScheme.startsWith("http://", ignoreCase = true)) return FeedUrlResult.NotHttps
        if (!withScheme.startsWith("https://", ignoreCase = true)) return FeedUrlResult.Invalid
        val uri = try {
            URI(withScheme)
        } catch (ignored: URISyntaxException) {
            return FeedUrlResult.Invalid
        }
        val host = uri.host
        if (host.isNullOrBlank() || uri.userInfo != null) return FeedUrlResult.Invalid
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return FeedUrlResult.Ok("https://${host.lowercase()}$port$path$query")
    }
}

/** Ein Eintrag einer OPML-Datei: die Feed-Adresse und der angezeigte Name (kann leer sein). */
data class OpmlFeed(val url: String, val title: String)

/** Inhalt einer OPML-Datei. Nicht übernommene Einträge werden gezählt, damit die Oberfläche sie nennen kann. */
data class OpmlResult(val feeds: List<OpmlFeed>, val skippedInsecure: Int, val skippedInvalid: Int)

object Opml {
    /**
     * Liest alle `outline`-Elemente mit `xmlUrl` (auch verschachtelt in Ordnern). Nur HTTPS-Adressen werden
     * übernommen; Doppelte entfallen.
     *
     * @throws FeedParseException wenn die Datei kein OPML ist
     */
    fun parse(root: XmlNode): OpmlResult {
        if (root.local.lowercase() != "opml") throw FeedParseException("Kein OPML")
        val body = root.childLocal("body") ?: throw FeedParseException("OPML ohne body")
        val feeds = LinkedHashMap<String, OpmlFeed>()
        var insecure = 0
        var invalid = 0

        fun visit(node: XmlNode) {
            for (outline in node.childrenLocal("outline")) {
                val raw = attribute(outline, "xmlUrl")
                if (raw != null) {
                    when (val url = FeedUrl.parse(raw)) {
                        is FeedUrlResult.Ok -> {
                            val title = (attribute(outline, "title") ?: attribute(outline, "text")).orEmpty()
                            feeds.putIfAbsent(url.url.lowercase(), OpmlFeed(url.url, FeedText.plainText(title)))
                        }
                        FeedUrlResult.NotHttps -> insecure++
                        FeedUrlResult.Empty, FeedUrlResult.Invalid -> invalid++
                    }
                }
                visit(outline)
            }
        }
        visit(body)
        return OpmlResult(feeds.values.toList(), insecure, invalid)
    }

    private fun attribute(node: XmlNode, name: String): String? =
        node.attributes.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
}

/** Suche nach der Feed-Adresse in einer Webseite (`<link rel="alternate" type="application/rss+xml" …>`). */
object FeedDiscovery {
    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val FEED_TYPE = Regex("application/(rss\\+xml|atom\\+xml|feed\\+json|json)", RegexOption.IGNORE_CASE)

    private fun attribute(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(tag)?.groupValues?.get(2)?.let { FeedText.decodeEntities(it).trim() }

    /** Erste Feed-Adresse (nur HTTPS) der Seite, aufgelöst gegen [pageUrl], oder `null`. */
    fun find(html: String, pageUrl: String): String? {
        val head = html.take(200_000)
        for (match in LINK_TAG.findAll(head)) {
            val tag = match.value
            val rel = attribute(tag, "rel")?.lowercase() ?: continue
            val type = attribute(tag, "type") ?: continue
            val href = attribute(tag, "href") ?: continue
            if ("alternate" !in rel.split(' ') || !FEED_TYPE.containsMatchIn(type)) continue
            val resolved = try {
                URI(pageUrl).resolve(href).toString()
            } catch (ignored: IllegalArgumentException) {
                continue
            }
            val ok = FeedUrl.parse(resolved) as? FeedUrlResult.Ok ?: continue
            return ok.url
        }
        return null
    }
}

/**
 * Ergebnis einer Datei-Einfuhr. [seeds] enthält Artikel, die gleich angezeigt werden können (Schlüssel =
 * Adresse des Feeds), z. B. aus einer JSON-Feed-Datei.
 */
data class ImportPlan(
    val feeds: List<LocalFeed>,
    val seeds: Map<String, ParsedFeed>,
    val skippedInsecure: Int,
    val skippedInvalid: Int,
)

object FeedImport {
    /**
     * Erkennt den Inhalt einer Datei: OPML (Liste von Abos), JSON Feed oder RSS/Atom (Artikel). Ein Feed mit
     * eigener HTTPS-Adresse wird als Abo übernommen und seine Artikel dienen als Anfangsbestand; ohne Adresse
     * werden die Artikel als feste Sammlung gespeichert.
     *
     * @throws FeedParseException wenn die Datei weder OPML noch ein Feed ist
     */
    fun parse(text: String): ImportPlan {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.startsWith("<")) {
            val root = FeedParser.parseXml(trimmed)
            if (root.local.lowercase() == "opml") {
                val opml = Opml.parse(root)
                return ImportPlan(
                    feeds = opml.feeds.map { LocalFeed(it.url, it.title) },
                    seeds = emptyMap(),
                    skippedInsecure = opml.skippedInsecure,
                    skippedInvalid = opml.skippedInvalid,
                )
            }
            return planFor(FeedParser.fromXml(root))
        }
        return planFor(FeedParser.parse(trimmed))
    }

    /** Plan für einen einzelnen Feed, den der Nutzer als Datei oder per Adresse hinzufügt. */
    fun planFor(parsed: ParsedFeed, fetchedFrom: String? = null): ImportPlan {
        val live = fetchedFrom ?: (parsed.feedUrl?.let { (FeedUrl.parse(it) as? FeedUrlResult.Ok)?.url })
        val url = live ?: LocalFeeds.staticUrl(parsed)
        val title = parsed.title.ifBlank { parsed.link?.substringAfter("://")?.substringBefore('/').orEmpty() }
        return ImportPlan(listOf(LocalFeed(url, title)), mapOf(url to parsed), 0, 0)
    }
}
