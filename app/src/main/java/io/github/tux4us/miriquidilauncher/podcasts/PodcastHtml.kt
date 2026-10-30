// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.feed.FeedText
import io.github.tux4us.miriquidilauncher.feed.OpmlFeed

/**
 * HTML-Export und -Import der Abos. Der Export ist eine lesbare Seite mit einem Link je Podcast
 * (`<a class="feed" href="Feed-Adresse">Name</a>`) und lässt sich im Browser öffnen. Der Import liest zuerst
 * diese Links; fehlen sie, gelten alle HTTPS-Links der Datei als Kandidaten. Ob ein Kandidat ein Podcast-Feed
 * ist, prüft erst der Abruf (Ergebnis: übernommen oder übersprungen).
 */
object PodcastHtml {
    const val MAX_CANDIDATES = 200

    private val ANCHOR = Regex("<a\\b([^>]*)>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun export(subscriptions: List<Podcast>, title: String): String = buildString {
        val heading = FeedText.escapeHtml(title)
        append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>").append(heading).append("</title>\n</head>\n<body>\n")
        append("<h1>").append(heading).append("</h1>\n<ul>\n")
        for (p in subscriptions) {
            append("<li><a class=\"feed\" href=\"").append(FeedText.escapeHtml(p.feedUrl)).append("\">")
                .append(FeedText.escapeHtml(p.title)).append("</a>")
            if (p.author.isNotBlank()) append(" – ").append(FeedText.escapeHtml(p.author))
            append("</li>\n")
        }
        append("</ul>\n</body>\n</html>\n")
    }

    /** Kandidaten aus einer HTML-Datei; nur HTTPS (`http://` wird hochgestuft), ohne Doppelte, höchstens [MAX_CANDIDATES]. */
    fun parse(html: String): List<OpmlFeed> {
        val all = ArrayList<Pair<Boolean, OpmlFeed>>()
        for (match in ANCHOR.findAll(html.take(2_000_000))) {
            val attrs = match.groupValues[1]
            val href = attribute(attrs, "href") ?: continue
            val url = PodcastUrls.https(href) ?: continue
            val isFeed = attribute(attrs, "class")?.split(' ')?.contains("feed") == true
            all.add(isFeed to OpmlFeed(url, FeedText.plainText(match.groupValues[2])))
        }
        val marked = all.filter { it.first }
        val chosen = if (marked.isNotEmpty()) marked else all
        val seen = HashSet<String>()
        return chosen.map { it.second }.filter { seen.add(it.url.lowercase()) }.take(MAX_CANDIDATES)
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(tag)?.groupValues?.get(2)?.let { FeedText.decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() }
}
