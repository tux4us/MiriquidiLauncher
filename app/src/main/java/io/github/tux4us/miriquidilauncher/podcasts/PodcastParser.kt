// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.feed.FeedDates
import io.github.tux4us.miriquidilauncher.feed.FeedParseException
import io.github.tux4us.miriquidilauncher.feed.FeedParser
import io.github.tux4us.miriquidilauncher.feed.FeedText
import io.github.tux4us.miriquidilauncher.feed.FeedUrl
import io.github.tux4us.miriquidilauncher.feed.FeedUrlResult
import io.github.tux4us.miriquidilauncher.feed.XmlNode
import java.security.MessageDigest

/** Adressen: Podcast-Server liefern Audio- und Bildadressen oft noch mit `http://`; die App lädt nur über HTTPS. */
object PodcastUrls {
    /** Liefert eine bereinigte HTTPS-Adresse oder `null`. `http://` wird zu `https://` hochgestuft. */
    fun https(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val upgraded = if (text.startsWith("http://", ignoreCase = true)) "https://" + text.substring(7) else text
        return (FeedUrl.parse(upgraded) as? FeedUrlResult.Ok)?.url
    }
}

object PodcastIds {
    /** Stabile Kennung einer Folge: 32 Hexzeichen aus SHA-256 von Feed-Adresse und GUID (oder Audio-Adresse). */
    fun episodeId(feedUrl: String, guid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$feedUrl\n$guid".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Liest Podcast-Feeds (RSS 2.0 mit `enclosure` und iTunes-Erweiterungen) mit dem vorhandenen XML-Leser.
 * Plattformunabhängig und auf der JVM testbar.
 */
object PodcastParser {
    const val MAX_EPISODES = 100

    /** Obergrenze der Shownotes je Folge in Zeichen, damit der lokale Speicher klein bleibt. */
    const val MAX_NOTES_CHARS = 30_000

    /** @throws FeedParseException wenn die Datei kein RSS-Feed ist */
    fun parse(text: String, feedUrl: String): ParsedPodcast {
        val root = FeedParser.parseXml(text.trimStart('﻿', ' ', '\t', '\r', '\n'))
        if (root.local.lowercase() != "rss") throw FeedParseException("Kein RSS-Podcast-Feed")
        val channel = root.childLocal("channel") ?: throw FeedParseException("RSS ohne channel")
        val title = plain(channel.child("title")?.text.orEmpty()).ifBlank { feedUrl }
        val author = plain(channel.child("itunes:author")?.text.orEmpty())
        val image = channelImage(channel)
        val podcast = Podcast(feedUrl, title, author, image)
        val episodes = channel.childrenNamed("item").mapNotNull { episode(it, podcast) }
            .distinctBy { it.id }
            .sortedByDescending { it.publishedSeconds }
            .take(MAX_EPISODES)
        return ParsedPodcast(podcast, episodes)
    }

    private fun channelImage(channel: XmlNode): String? =
        PodcastUrls.https(channel.child("itunes:image")?.attributes?.get("href"))
            ?: PodcastUrls.https(channel.child("image")?.child("url")?.text)

    private fun episode(item: XmlNode, podcast: Podcast): Episode? {
        val enclosure = item.child("enclosure") ?: return null
        val audio = PodcastUrls.https(enclosure.attributes["url"]) ?: return null
        val guid = item.child("guid")?.text?.takeIf { it.isNotBlank() } ?: audio
        val title = plain(item.child("title")?.text.orEmpty()).ifBlank { plain(item.child("itunes:title")?.text.orEmpty()) }
        val notes = item.child("content:encoded")?.text?.takeIf { it.isNotBlank() }
            ?: item.child("description")?.text?.takeIf { it.isNotBlank() }
            ?: item.child("itunes:summary")?.text.orEmpty()
        return Episode(
            id = PodcastIds.episodeId(podcast.feedUrl, guid),
            podcastUrl = podcast.feedUrl,
            podcastTitle = podcast.title,
            title = title.ifBlank { audio.substringAfterLast('/').substringBefore('?') },
            publishedSeconds = FeedDates.parse(item.child("pubDate")?.text) ?: 0L,
            audioUrl = audio,
            mime = enclosure.attributes["type"].orEmpty().lowercase(),
            durationSeconds = parseDuration(item.child("itunes:duration")?.text),
            notesHtml = FeedText.sanitize(notes.take(MAX_NOTES_CHARS)),
            link = PodcastUrls.https(item.child("link")?.text),
            imageUrl = PodcastUrls.https(item.child("itunes:image")?.attributes?.get("href")),
        )
    }

    /** `HH:MM:SS`, `MM:SS` oder Sekunden (auch mit Nachkommastellen); unlesbare Werte ergeben 0. */
    fun parseDuration(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val parts = text.split(':')
        if (parts.size > 3) return 0
        var total = 0.0
        for (part in parts) {
            val value = part.trim().toDoubleOrNull() ?: return 0
            if (value < 0) return 0
            total = total * 60 + value
        }
        return if (total > 24 * 3600 * 7) 0 else total.toInt()
    }

    private fun plain(text: String): String = if (text.isBlank()) "" else FeedText.plainText(text)
}
