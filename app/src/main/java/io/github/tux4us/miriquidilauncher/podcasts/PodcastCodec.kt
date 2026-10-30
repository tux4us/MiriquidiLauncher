// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Speichert [PodcastData] als JSON-Text. Ein beschädigter oder fremder Text ergibt `null`. */
object PodcastCodec {
    private const val VERSION = 1

    /**
     * Mit [withEpisodes] `false` entfällt der Folgen-Zwischenspeicher; er liegt in einer eigenen Datei, damit das
     * häufige Speichern der Wiedergabeposition klein bleibt (siehe [encodeEpisodes]).
     */
    fun encode(data: PodcastData, withEpisodes: Boolean = true): String {
        val sb = StringBuilder(4096)
        sb.append("{\"v\":").append(VERSION)
        sb.append(",\"provider\":").append(str(data.provider.id))
        sb.append(",\"speed\":").append(data.speed)
        sb.append(",\"subs\":[")
        data.subscriptions.forEachIndexed { i, p -> if (i > 0) sb.append(','); podcast(sb, p) }
        sb.append("],")
        if (withEpisodes) sb.append("\"eps\":").append(episodeMap(data.episodes)).append(',')
        sb.append("\"sav\":[")
        data.saved.forEachIndexed { i, e -> if (i > 0) sb.append(','); episode(sb, e) }
        sb.append("],\"fav\":[")
        data.favorites.forEachIndexed { i, e -> if (i > 0) sb.append(','); episode(sb, e) }
        sb.append("],\"pos\":{")
        data.positionsMs.entries.forEachIndexed { i, (k, v) -> if (i > 0) sb.append(','); sb.append(str(k)).append(':').append(v) }
        sb.append("},\"played\":[")
        data.played.forEachIndexed { i, id -> if (i > 0) sb.append(','); sb.append(str(id)) }
        sb.append("],\"dl\":{")
        data.downloads.entries.forEachIndexed { i, (k, v) -> if (i > 0) sb.append(','); sb.append(str(k)).append(':').append(str(v)) }
        sb.append("}}")
        return sb.toString()
    }

    /** Zwischenspeicher der Folgen als eigener Text. */
    fun encodeEpisodes(episodes: Map<String, List<Episode>>): String = "{\"v\":$VERSION,\"eps\":" + episodeMap(episodes) + "}"

    private fun episodeMap(episodes: Map<String, List<Episode>>): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        var first = true
        for ((url, list) in episodes) {
            if (!first) sb.append(',')
            first = false
            sb.append(str(url)).append(":[")
            list.forEachIndexed { i, e -> if (i > 0) sb.append(','); episode(sb, e) }
            sb.append(']')
        }
        return sb.append('}').toString()
    }

    private fun readEpisodeMap(value: Any?): Map<String, List<Episode>> {
        val map = LinkedHashMap<String, List<Episode>>()
        value.asObject()?.forEach { (url, list) -> map[url] = list.asArray().orEmpty().mapNotNull(::readEpisode) }
        return map
    }

    /** [episodesText] ist der Text aus [encodeEpisodes]; er wird nur gelesen, wenn [text] keine Folgen enthält. */
    fun decode(text: String, episodesText: String? = null): PodcastData? {
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (ignored: MiniJson.JsonException) {
            null
        } ?: return null
        return fromRoot(root, episodesText)
    }

    /**
     * Teil der Podcast-Daten für das Launcher-Backup: Abos, Favoriten, Wiedergabepositionen, Gehört-Markierungen,
     * Verzeichnis und Geschwindigkeit. Ohne Folgen-Zwischenspeicher, Download-Zuordnung und Audiodateien.
     */
    fun encodeBackup(data: PodcastData): String =
        encode(data.copy(episodes = emptyMap(), saved = emptyList(), downloads = emptyMap()), withEpisodes = false)

    /** Liest den Teil aus [encodeBackup], bereits als JSON-Objekt geparst; `null` bei fremder Version. */
    fun decodeBackup(root: Map<String, Any?>): PodcastData? = fromRoot(root, null)

    private fun fromRoot(root: Map<String, Any?>, episodesText: String?): PodcastData? {
        if (root["v"].asDouble()?.toInt() != VERSION) return null
        val episodes = if (root.containsKey("eps")) {
            readEpisodeMap(root["eps"])
        } else {
            val separate = episodesText?.let { try { MiniJson.parse(it).asObject() } catch (ignored: MiniJson.JsonException) { null } }
            readEpisodeMap(separate?.get("eps"))
        }
        val positions = LinkedHashMap<String, Long>()
        root["pos"].asObject()?.forEach { (id, v) -> v.asDouble()?.let { positions[id] = it.toLong() } }
        val downloads = LinkedHashMap<String, String>()
        root["dl"].asObject()?.forEach { (id, v) -> v.asString()?.let { downloads[id] = it } }
        return PodcastData(
            subscriptions = root["subs"].asArray().orEmpty().mapNotNull(::readPodcast),
            episodes = episodes,
            saved = root["sav"].asArray().orEmpty().mapNotNull(::readEpisode),
            favorites = root["fav"].asArray().orEmpty().mapNotNull(::readEpisode),
            positionsMs = positions,
            played = root["played"].asArray().orEmpty().mapNotNull { it.asString() }.toSet(),
            downloads = downloads,
            provider = SearchProvider.fromId(root["provider"].asString()),
            speed = (root["speed"].asDouble()?.toFloat() ?: 1.0f).coerceIn(0.5f, 3.0f),
        )
    }

    private fun podcast(sb: StringBuilder, p: Podcast) {
        sb.append("{\"u\":").append(str(p.feedUrl)).append(",\"t\":").append(str(p.title)).append(",\"a\":").append(str(p.author))
        p.imageUrl?.let { sb.append(",\"i\":").append(str(it)) }
        sb.append('}')
    }

    private fun episode(sb: StringBuilder, e: Episode) {
        sb.append("{\"id\":").append(str(e.id)).append(",\"pu\":").append(str(e.podcastUrl)).append(",\"pt\":").append(str(e.podcastTitle))
            .append(",\"t\":").append(str(e.title)).append(",\"d\":").append(e.publishedSeconds).append(",\"au\":").append(str(e.audioUrl))
            .append(",\"m\":").append(str(e.mime)).append(",\"s\":").append(e.durationSeconds).append(",\"n\":").append(str(e.notesHtml))
        e.link?.let { sb.append(",\"l\":").append(str(it)) }
        e.imageUrl?.let { sb.append(",\"i\":").append(str(it)) }
        sb.append('}')
    }

    private fun readPodcast(value: Any?): Podcast? {
        val o = value.asObject() ?: return null
        return Podcast(o["u"].asString() ?: return null, o["t"].asString().orEmpty(), o["a"].asString().orEmpty(), o["i"].asString())
    }

    private fun readEpisode(value: Any?): Episode? {
        val o = value.asObject() ?: return null
        return Episode(
            id = o["id"].asString() ?: return null,
            podcastUrl = o["pu"].asString() ?: return null,
            podcastTitle = o["pt"].asString().orEmpty(),
            title = o["t"].asString().orEmpty(),
            publishedSeconds = o["d"].asDouble()?.toLong() ?: 0L,
            audioUrl = o["au"].asString() ?: return null,
            mime = o["m"].asString().orEmpty(),
            durationSeconds = o["s"].asDouble()?.toInt() ?: 0,
            notesHtml = o["n"].asString().orEmpty(),
            link = o["l"].asString(),
            imageUrl = o["i"].asString(),
        )
    }

    private fun str(text: String): String = buildString(text.length + 2) {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
