// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Speichert [MusicData] als JSON-Text. Ein beschädigter oder fremder Text ergibt `null`. */
object MusicCodec {
    private const val VERSION = 1

    fun encode(data: MusicData): String {
        val sb = StringBuilder(2048)
        sb.append("{\"v\":").append(VERSION)
        sb.append(",\"sh\":").append(data.shuffle)
        sb.append(",\"rp\":").append(str(data.repeat.name))
        sb.append(",\"re\":").append(data.radioEnabled)
        sb.append(",\"ah\":").append(data.allowHttp)
        sb.append(",\"sl\":").append(data.showLogos)
        sb.append(",\"eq\":").append(eq(data.eq))
        sb.append(",\"st\":[")
        data.stations.forEachIndexed { i, s -> if (i > 0) sb.append(','); station(sb, s) }
        sb.append("],\"ft\":[")
        data.favoriteTracks.forEachIndexed { i, id -> if (i > 0) sb.append(','); sb.append(id) }
        sb.append("],\"pl\":[")
        data.playlists.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(str(p.id)).append(",\"n\":").append(str(p.name)).append(",\"t\":[")
            p.trackIds.forEachIndexed { j, id -> if (j > 0) sb.append(','); sb.append(id) }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun decode(text: String): MusicData? {
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (ignored: MiniJson.JsonException) {
            null
        } ?: return null
        return fromRoot(root)
    }

    /**
     * Teil der Musikdaten für das Launcher-Backup: Sender, Favoriten, Wiedergabelisten, Equalizer, Zufall und Wiederholung.
     * Die Zustimmung zur Sendersuche und der Schalter für unverschlüsselte Streams gehören nicht dazu.
     */
    fun encodeBackup(data: MusicData): String = encode(data.copy(radioEnabled = false, allowHttp = false))

    /** Liest den Teil aus [encodeBackup], bereits als JSON-Objekt geparst; `null` bei fremder Version. */
    fun decodeBackup(root: Map<String, Any?>): MusicData? = fromRoot(root)?.copy(radioEnabled = false, allowHttp = false)

    private fun fromRoot(root: Map<String, Any?>): MusicData? {
        if (root["v"].asDouble()?.toInt() != VERSION) return null
        val stations = LinkedHashMap<String, Station>()
        root["st"].asArray().orEmpty().mapNotNull(::readStation).forEach { if (stations.size < MusicLogic.MAX_STATIONS) stations.putIfAbsent(it.id, it) }
        val favorites = root["ft"].asArray().orEmpty().mapNotNull { it.asDouble()?.toLong() }.distinct().take(MusicLogic.MAX_FAVORITE_TRACKS)
        val playlists = root["pl"].asArray().orEmpty().mapNotNull(::readPlaylist).distinctBy { it.id }.take(MusicLogic.MAX_PLAYLISTS)
        return MusicData(
            stations = stations.values.toList(),
            favoriteTracks = favorites,
            playlists = playlists,
            eq = readEq(root["eq"].asObject()),
            shuffle = root["sh"] as? Boolean ?: false,
            repeat = RepeatMode.fromId(root["rp"].asString()),
            radioEnabled = root["re"] as? Boolean ?: false,
            allowHttp = root["ah"] as? Boolean ?: false,
            showLogos = root["sl"] as? Boolean ?: true,
        )
    }

    private fun eq(e: EqSettings): String {
        val sb = StringBuilder()
        sb.append("{\"on\":").append(e.enabled).append(",\"pre\":").append(e.preampDb).append(",\"bass\":").append(e.bassDb)
            .append(",\"prot\":").append(e.protect)
        e.preset?.let { sb.append(",\"preset\":").append(str(it)) }
        sb.append(",\"b\":[")
        e.bandsDb.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append("]}")
        return sb.toString()
    }

    private fun readEq(o: Map<String, Any?>?): EqSettings {
        if (o == null) return EqSettings()
        val bands = o["b"].asArray().orEmpty().map { it.asDouble()?.toFloat() ?: 0f }
        val preset = o["preset"].asString()?.takeIf { EqPresets.byId(it) != null }
        return EqSettings(
            enabled = o["on"] as? Boolean ?: false,
            bandsDb = bands,
            preampDb = o["pre"].asDouble()?.toFloat() ?: 0f,
            bassDb = o["bass"].asDouble()?.toFloat() ?: 0f,
            protect = o["prot"] as? Boolean ?: true,
            preset = if (o.containsKey("preset")) preset else null,
        ).normalized()
    }

    private fun station(sb: StringBuilder, s: Station) {
        sb.append("{\"id\":").append(str(s.id)).append(",\"n\":").append(str(s.name)).append(",\"u\":").append(str(s.streamUrl))
        s.homepage?.let { sb.append(",\"h\":").append(str(it)) }
        s.logoUrl?.let { sb.append(",\"l\":").append(str(it)) }
        if (s.country.isNotEmpty()) sb.append(",\"c\":").append(str(s.country))
        if (s.tags.isNotEmpty()) sb.append(",\"t\":").append(str(s.tags))
        if (s.codec.isNotEmpty()) sb.append(",\"k\":").append(str(s.codec))
        if (s.bitrate > 0) sb.append(",\"b\":").append(s.bitrate)
        sb.append('}')
    }

    private fun readStation(value: Any?): Station? {
        val o = value.asObject() ?: return null
        val id = o["id"].asString()?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val url = RadioBrowser.playableUrl(o["u"].asString(), allowHttp = true) ?: return null
        val name = o["n"].asString()?.trim()?.take(MusicLogic.MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
        return Station(
            id = id,
            name = name,
            streamUrl = url,
            homepage = o["h"].asString()?.let { RadioBrowser.playableUrl(it, allowHttp = true) },
            logoUrl = RadioBrowser.logoUrl(o["l"].asString()),
            country = o["c"].asString().orEmpty().take(60),
            tags = o["t"].asString().orEmpty().take(80),
            codec = o["k"].asString().orEmpty().take(12),
            bitrate = (o["b"].asDouble()?.toInt() ?: 0).coerceIn(0, 2000),
        )
    }

    private fun readPlaylist(value: Any?): Playlist? {
        val o = value.asObject() ?: return null
        val id = o["id"].asString()?.takeIf { it.isNotBlank() && it.length <= 16 } ?: return null
        val name = o["n"].asString()?.trim()?.take(MusicLogic.MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
        val tracks = o["t"].asArray().orEmpty().mapNotNull { it.asDouble()?.toLong() }.distinct().take(MusicLogic.MAX_PLAYLIST_TRACKS)
        return Playlist(id, name, tracks)
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
