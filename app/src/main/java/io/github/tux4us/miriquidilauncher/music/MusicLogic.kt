// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import io.github.tux4us.miriquidilauncher.search.TextNormalizer
import java.security.MessageDigest

/** Reine Zustandsänderungen der Musikdaten und Listenfunktionen; jede Funktion liefert einen neuen Stand. */
object MusicLogic {
    const val MAX_STATIONS = 500
    const val MAX_PLAYLISTS = 100
    const val MAX_PLAYLIST_TRACKS = 2000
    const val MAX_FAVORITE_TRACKS = 2000
    const val MAX_NAME = 80

    /** MediaStore liefert für fehlende Angaben den Text `<unknown>`. */
    fun cleanTag(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return if (text.isEmpty() || text.equals("<unknown>", ignoreCase = true)) "" else text
    }

    // region Sender ------------------------------------------------------------------------------

    /** Kennung eines selbst eingegebenen Senders: Hash der Stream-Adresse. */
    fun manualStationId(streamUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(streamUrl.trim().toByteArray(Charsets.UTF_8))
        return "u-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Fügt einen Sender hinzu; doppelte Kennung oder Adresse ergibt den unveränderten Stand, `null` bei Limit. */
    fun addStation(data: MusicData, station: Station): MusicData? {
        if (data.stations.any { it.id == station.id || it.streamUrl.equals(station.streamUrl, ignoreCase = true) }) return data
        if (data.stations.size >= MAX_STATIONS) return null
        return data.copy(stations = data.stations + station.copy(name = station.name.trim().take(MAX_NAME)))
    }

    fun removeStation(data: MusicData, id: String): MusicData = data.copy(stations = data.stations.filterNot { it.id == id })

    fun renameStation(data: MusicData, id: String, name: String): MusicData {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return data
        return data.copy(stations = data.stations.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun isSaved(data: MusicData, station: Station): Boolean =
        data.stations.any { it.id == station.id || it.streamUrl.equals(station.streamUrl, ignoreCase = true) }

    /** Meine Sender nach Name sortiert (ohne Beachtung von Akzenten und Groß-/Kleinschreibung). */
    fun sortedStations(stations: List<Station>): List<Station> =
        stations.sortedWith(compareBy({ TextNormalizer.normalize(it.name) }, { it.id }))

    // endregion

    // region Favoriten und Wiedergabelisten ------------------------------------------------------

    /** Schaltet das Lied als Favorit um. `null` bei Limit. */
    fun toggleFavorite(data: MusicData, trackId: Long): MusicData? {
        if (trackId in data.favoriteTracks) return data.copy(favoriteTracks = data.favoriteTracks - trackId)
        if (data.favoriteTracks.size >= MAX_FAVORITE_TRACKS) return null
        return data.copy(favoriteTracks = data.favoriteTracks + trackId)
    }

    /** Legt eine Wiedergabeliste an. `null` bei leerem oder doppeltem Namen (ohne Beachtung der Groß-/Kleinschreibung) und bei Limit. */
    fun createPlaylist(data: MusicData, name: String): Pair<MusicData, Playlist>? {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty() || data.playlists.size >= MAX_PLAYLISTS) return null
        if (data.playlists.any { it.name.equals(clean, ignoreCase = true) }) return null
        val next = (data.playlists.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val playlist = Playlist("p$next", clean, emptyList())
        return data.copy(playlists = data.playlists + playlist) to playlist
    }

    fun renamePlaylist(data: MusicData, id: String, name: String): MusicData? {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return null
        if (data.playlists.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return null
        return data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun deletePlaylist(data: MusicData, id: String): MusicData = data.copy(playlists = data.playlists.filterNot { it.id == id })

    /** Hängt Lieder an die Liste an (Duplikate entfallen). `null` bei Limit. */
    fun addToPlaylist(data: MusicData, id: String, trackIds: List<Long>): MusicData? {
        val playlist = data.playlists.firstOrNull { it.id == id } ?: return data
        val merged = (playlist.trackIds + trackIds.filter { it !in playlist.trackIds }).distinct()
        if (merged.size > MAX_PLAYLIST_TRACKS) return null
        return data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(trackIds = merged) else it })
    }

    fun removeFromPlaylist(data: MusicData, id: String, trackId: Long): MusicData =
        data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(trackIds = it.trackIds - trackId) else it })

    /** Verschiebt das Lied [trackId] in der Liste um [delta] Plätze (negativ = nach vorn). */
    fun moveInPlaylist(data: MusicData, id: String, trackId: Long, delta: Int): MusicData = data.copy(
        playlists = data.playlists.map { playlist ->
            if (playlist.id != id) return@map playlist
            val from = playlist.trackIds.indexOf(trackId)
            if (from < 0) return@map playlist
            val to = (from + delta).coerceIn(0, playlist.trackIds.lastIndex)
            val moved = playlist.trackIds.toMutableList()
            moved.add(to, moved.removeAt(from))
            playlist.copy(trackIds = moved)
        },
    )

    /** Löst Kennungen in Lieder auf (in Reihenfolge der Kennungen); unbekannte Kennungen entfallen. */
    fun resolve(ids: List<Long>, tracks: List<Track>): List<Track> {
        val byId = tracks.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // endregion

    // region Mediathek ---------------------------------------------------------------------------

    /** Sortierung nach Titel (ohne Beachtung von Akzenten und Groß-/Kleinschreibung). */
    fun sortByTitle(tracks: List<Track>): List<Track> =
        tracks.sortedWith(compareBy({ TextNormalizer.normalize(it.title) }, { it.id }))

    /** Lieder eines Albums in Albumreihenfolge: erst nach Spurnummer (MediaStore kodiert die CD als Tausender), dann nach Titel. */
    fun albumOrder(tracks: List<Track>): List<Track> =
        tracks.sortedWith(compareBy<Track>({ if (it.trackNumber > 0) 0 else 1 }, { it.trackNumber }, { TextNormalizer.normalize(it.title) }, { it.id }))

    /** Filtert nach Titel, Interpret und Album; jedes Wort der Eingabe muss vorkommen. Leere Eingabe ergibt alle. */
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return tracks
        return tracks.filter { track ->
            val haystack = TextNormalizer.normalize("${track.title} ${track.artist} ${track.album}")
            words.all { it in haystack }
        }
    }

    fun albums(tracks: List<Track>): List<Album> = tracks
        .filter { it.albumId > 0 && it.album.isNotEmpty() }
        .groupBy { it.albumId }
        .map { (id, list) ->
            val artists = list.map { it.artist }.filter { it.isNotEmpty() }.distinct()
            // Bei mehreren Interpreten bleibt der Interpret leer; die Oberfläche zeigt dann „Verschiedene Interpreten“.
            Album(id, list.first().album, if (artists.size == 1) artists.first() else "", list.size)
        }
        .sortedWith(compareBy({ TextNormalizer.normalize(it.title) }, { it.id }))

    fun tracksOfAlbum(tracks: List<Track>, albumId: Long): List<Track> = albumOrder(tracks.filter { it.albumId == albumId })

    fun artists(tracks: List<Track>): List<Artist> = tracks
        .filter { it.artist.isNotEmpty() }
        .groupBy { it.artist }
        .map { (name, list) -> Artist(name, list.size) }
        .sortedWith(compareBy({ TextNormalizer.normalize(it.name) }, { it.name }))

    fun tracksOfArtist(tracks: List<Track>, artist: String): List<Track> =
        sortByTitle(tracks.filter { it.artist == artist })

    // endregion

    /** Zeit als `m:ss` bzw. `h:mm:ss`; negative Werte zählen als 0. */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** So viele Lieder legt die Wiedergabe höchstens in die Warteschlange (Rest der Liste wird nicht übergeben). */
    const val MAX_QUEUE = 1000

    /**
     * Ausschnitt von höchstens [max] Liedern um [startIndex]: Rund ein Fünftel davor, der Rest danach. Liefert den
     * Ausschnitt und die Position des Startlieds darin. Kurze Listen bleiben unverändert.
     */
    fun window(tracks: List<Track>, startIndex: Int, max: Int = MAX_QUEUE): Pair<List<Track>, Int> {
        if (tracks.isEmpty()) return emptyList<Track>() to 0
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (tracks.size <= max) return tracks to start
        val from = (start - max / 5).coerceAtLeast(0).coerceAtMost(tracks.size - max)
        return tracks.subList(from, from + max) to (start - from)
    }
}

/** Farben für die Player-Oberfläche, aus dem Cover gewonnen. Alle Werte sind ARGB-Ganzzahlen (voll deckend). */
data class CoverPalette(val accent: Int, val top: Int, val bottom: Int) {
    companion object {
        val DEFAULT = CoverPalette(accent = 0xFF8AB4F8.toInt(), top = 0xFF1B2433.toInt(), bottom = 0xFF0B0F16.toInt())
    }
}

/** Ermittelt die Palette aus den Pixeln eines kleinen Cover-Bildes und erzeugt Ersatzfarben für Sender ohne Logo. */
object CoverColors {
    private const val HUE_BUCKETS = 12

    /** Wertet [pixels] (ARGB) aus; fast graue oder fast schwarze/weiße Bilder ergeben [CoverPalette.DEFAULT]. */
    fun palette(pixels: IntArray): CoverPalette {
        if (pixels.isEmpty()) return CoverPalette.DEFAULT
        val weight = DoubleArray(HUE_BUCKETS)
        val sumR = DoubleArray(HUE_BUCKETS)
        val sumG = DoubleArray(HUE_BUCKETS)
        val sumB = DoubleArray(HUE_BUCKETS)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val (h, s, v) = hsv(r, g, b)
            if (s < 0.2 || v < 0.15) continue
            val w = s * v
            val bucket = ((h / 360.0) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[bucket] += w
            sumR[bucket] += r * w
            sumG[bucket] += g * w
            sumB[bucket] += b * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return CoverPalette.DEFAULT
        if (weight[best] <= 0.0) return CoverPalette.DEFAULT
        val r = (sumR[best] / weight[best]).toInt()
        val g = (sumG[best] / weight[best]).toInt()
        val b = (sumB[best] / weight[best]).toInt()
        return fromBase(r, g, b)
    }

    /** Farben für einen Sender ohne Logo, stabil nach Name. */
    fun forName(name: String): CoverPalette {
        val hue = ((name.lowercase().hashCode().toLong() and 0x7FFFFFFFL) % 360L).toDouble()
        val (r, g, b) = rgb(hue, 0.6, 0.85)
        return fromBase(r, g, b)
    }

    /** Ein bis zwei Anfangsbuchstaben des Namens in Großschreibung. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return "♪"
        val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
        return letters.joinToString("").uppercase()
    }

    private fun fromBase(r: Int, g: Int, b: Int): CoverPalette {
        val (h, s, v) = hsv(r, g, b)
        val accent = rgb(h, s.coerceIn(0.45, 0.9), v.coerceIn(0.75, 0.95))
        val top = rgb(h, s.coerceIn(0.35, 0.8), (v * 0.38).coerceIn(0.14, 0.4))
        val bottom = rgb(h, s.coerceIn(0.3, 0.7), (v * 0.12).coerceIn(0.05, 0.14))
        return CoverPalette(argb(accent), argb(top), argb(bottom))
    }

    private fun argb(c: Triple<Int, Int, Int>): Int = (0xFF shl 24) or (c.first shl 16) or (c.second shl 8) or c.third

    private fun hsv(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val h = when {
            d == 0.0 -> 0.0
            max == rf -> 60 * (((gf - bf) / d) % 6)
            max == gf -> 60 * (((bf - rf) / d) + 2)
            else -> 60 * (((rf - gf) / d) + 4)
        }.let { if (it < 0) it + 360 else it }
        val s = if (max == 0.0) 0.0 else d / max
        return Triple(h, s, max)
    }

    private fun rgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Triple(((r + m) * 255).toInt().coerceIn(0, 255), ((g + m) * 255).toInt().coerceIn(0, 255), ((b + m) * 255).toInt().coerceIn(0, 255))
    }
}
