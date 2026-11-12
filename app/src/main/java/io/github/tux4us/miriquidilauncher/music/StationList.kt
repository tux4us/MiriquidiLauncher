// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/**
 * Import und Export der Senderliste als JSON-Datei.
 *
 * Format (`miriquidilauncher-radio`, Version 1): ein Objekt mit `format`, `version` und `stations`; jeder Sender hat
 * `name` und `url`, optional `id`, `homepage`, `logo`, `country`, `tags`, `codec`, `bitrate`.
 *
 * Der Import liest zusätzlich ein bloßes Array von Objekten und die Feldnamen der radio-browser.info-API
 * (`url_resolved`, `favicon`, `stationuuid`), damit Listen anderer Quellen übernommen werden können.
 */
object StationList {
    const val FORMAT = "miriquidilauncher-radio"
    const val VERSION = 1

    /** Obergrenze der Dateigröße beim Import. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** Höchstens so viele Einträge einer Datei werden betrachtet. */
    const val MAX_ENTRIES = 2000

    /** Gelesene Sender und die Zahl der Einträge ohne gültigen Namen oder gültige Adresse. */
    data class Parsed(val stations: List<Station>, val invalid: Int)

    /** Ergebnis des Zusammenführens mit dem vorhandenen Bestand. */
    data class Merge(val data: MusicData, val added: Int, val duplicates: Int, val overLimit: Int)

    fun export(stations: List<Station>): String {
        val sb = StringBuilder(256 + stations.size * 200)
        sb.append("{\n  \"format\": ").append(str(FORMAT)).append(",\n  \"version\": ").append(VERSION).append(",\n  \"stations\": [")
        stations.forEachIndexed { i, s ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    {\"id\": ").append(str(s.id)).append(", \"name\": ").append(str(s.name)).append(", \"url\": ").append(str(s.streamUrl))
            s.homepage?.let { sb.append(", \"homepage\": ").append(str(it)) }
            s.logoUrl?.let { sb.append(", \"logo\": ").append(str(it)) }
            if (s.country.isNotEmpty()) sb.append(", \"country\": ").append(str(s.country))
            if (s.tags.isNotEmpty()) sb.append(", \"tags\": ").append(str(s.tags))
            if (s.codec.isNotEmpty()) sb.append(", \"codec\": ").append(str(s.codec))
            if (s.bitrate > 0) sb.append(", \"bitrate\": ").append(s.bitrate)
            sb.append('}')
        }
        sb.append(if (stations.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
        return sb.toString()
    }

    /** Liest eine Senderliste. `null`, wenn der Text kein JSON ist, nicht dem Format entspricht oder eine fremde Version hat. */
    fun parse(text: String): Parsed? {
        if (text.length > MAX_BYTES) return null
        val root = try {
            MiniJson.parse(text)
        } catch (ignored: MiniJson.JsonException) {
            return null
        }
        val entries = when (root) {
            is List<*> -> root
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val o = root as Map<String, Any?>
                val format = o["format"].asString()
                if (format != null && format != FORMAT) return null
                val version = o["version"].asDouble()?.toInt()
                if (version != null && version != VERSION) return null
                o["stations"].asArray() ?: return null
            }
            else -> return null
        }
        val seen = HashSet<String>()
        val stations = ArrayList<Station>()
        var invalid = 0
        for (entry in entries.take(MAX_ENTRIES)) {
            val s = readStation(entry)
            if (s == null) invalid++ else if (seen.add(s.streamUrl.lowercase())) stations.add(s)
        }
        return Parsed(stations, invalid)
    }

    /** Fügt [imported] dem Bestand hinzu. Vorhandene Sender (gleiche Kennung oder Adresse) bleiben unverändert. */
    fun merge(data: MusicData, imported: List<Station>): Merge {
        var current = data
        var added = 0
        var duplicates = 0
        var overLimit = 0
        for (station in imported) {
            if (MusicLogic.isSaved(current, station)) {
                duplicates++
                continue
            }
            val next = MusicLogic.addStation(current, station)
            if (next == null) overLimit++ else { current = next; added++ }
        }
        return Merge(current, added, duplicates, overLimit)
    }

    private fun readStation(value: Any?): Station? {
        val o = value.asObject() ?: return null
        // http wird beim Import nicht ausgefiltert; abgespielt wird es nur mit dem Schalter „http-Streams erlauben“.
        val url = RadioBrowser.playableUrl(
            o["url"].asString()?.takeIf { it.isNotBlank() } ?: o["url_resolved"].asString(),
            allowHttp = true,
        ) ?: return null
        val name = o["name"].asString()?.trim()?.take(MusicLogic.MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
        val id = (o["id"].asString() ?: o["stationuuid"].asString())?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: MusicLogic.manualStationId(url)
        return Station(
            id = id,
            name = name,
            streamUrl = url,
            homepage = (o["homepage"].asString())?.let { RadioBrowser.playableUrl(it, allowHttp = true) },
            logoUrl = RadioBrowser.logoUrl(o["logo"].asString() ?: o["favicon"].asString()),
            country = o["country"].asString().orEmpty().trim().take(60),
            tags = o["tags"].asString().orEmpty().trim().take(80),
            codec = o["codec"].asString().orEmpty().trim().take(12),
            bitrate = (o["bitrate"].asDouble()?.toInt() ?: 0).coerceIn(0, 2000),
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
