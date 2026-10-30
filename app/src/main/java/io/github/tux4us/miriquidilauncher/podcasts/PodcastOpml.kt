// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.feed.FeedParser
import io.github.tux4us.miriquidilauncher.feed.FeedText
import io.github.tux4us.miriquidilauncher.feed.Opml
import io.github.tux4us.miriquidilauncher.feed.OpmlResult

/** OPML-Import und -Export der Podcast-Abos. Der Import nutzt den OPML-Leser des FeedReaders (nur HTTPS, ohne Doppelte). */
object PodcastOpml {
    /** @throws io.github.tux4us.miriquidilauncher.feed.FeedParseException wenn die Datei kein OPML ist */
    fun parse(text: String): OpmlResult = Opml.parse(FeedParser.parseXml(text.trimStart('﻿', ' ', '\t', '\r', '\n')))

    fun export(subscriptions: List<Podcast>, title: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n  <head><title>").append(FeedText.escapeHtml(title)).append("</title></head>\n  <body>\n")
        for (p in subscriptions) {
            val name = FeedText.escapeHtml(p.title)
            append("    <outline type=\"rss\" text=\"").append(name).append("\" title=\"").append(name)
                .append("\" xmlUrl=\"").append(FeedText.escapeHtml(p.feedUrl)).append("\"/>\n")
        }
        append("  </body>\n</opml>\n")
    }
}
