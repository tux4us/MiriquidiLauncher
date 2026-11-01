// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

/** Reine Zustandsänderungen der Podcast-Daten; jede Funktion liefert einen neuen Stand. */
object PodcastLogic {
    const val MAX_SUBSCRIPTIONS = 200
    const val MAX_FAVORITES = 500

    /** Bleibt weniger als diese Zeit übrig, gilt die Folge als gehört. */
    const val PLAYED_REMAINING_MS = 30_000L

    /** Positionen unter dieser Grenze werden nicht gemerkt. */
    const val MIN_POSITION_MS = 5_000L

    val SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** Fügt ein Abo hinzu (Duplikate nach Adresse ohne Beachtung der Groß-/Kleinschreibung entfallen). `null` bei Limit. */
    fun subscribe(data: PodcastData, podcast: Podcast): PodcastData? {
        if (data.subscriptions.any { it.feedUrl.equals(podcast.feedUrl, ignoreCase = true) }) return data
        if (data.subscriptions.size >= MAX_SUBSCRIPTIONS) return null
        return data.copy(subscriptions = data.subscriptions + podcast)
    }

    /** Entfernt Abo und Zwischenspeicher; Favoriten und geladene Dateien bleiben erhalten. */
    fun unsubscribe(data: PodcastData, feedUrl: String): PodcastData = data.copy(
        subscriptions = data.subscriptions.filterNot { it.feedUrl.equals(feedUrl, ignoreCase = true) },
        episodes = data.episodes - feedUrl,
    )

    /** Übernimmt einen geladenen Feed: Titel, Autor und Bild des Abos werden aktualisiert, die Folgenliste ersetzt. */
    fun applyFeed(data: PodcastData, parsed: ParsedPodcast): PodcastData {
        val url = parsed.podcast.feedUrl
        val subscriptions = data.subscriptions.map { if (it.feedUrl == url) parsed.podcast else it }
        return data.copy(subscriptions = subscriptions, episodes = data.episodes + (url to parsed.episodes))
    }

    /** So viele Folgen erscheinen in der Liste aller Folgen je Schritt; beim Scrollen kommen weitere hinzu. */
    const val ALL_PAGE_SIZE = 40

    /**
     * Alle bekannten Folgen der abonnierten Feeds, neueste zuerst. Folgen ohne Datum (Zeit 0) stehen am Ende; bei
     * gleicher Zeit entscheidet die Kennung, damit die Reihenfolge stabil bleibt. Doppelte Kennungen entfallen.
     */
    fun allEpisodes(data: PodcastData): List<Episode> {
        val seen = HashSet<String>()
        return data.subscriptions
            .flatMap { data.episodes[it.feedUrl].orEmpty() }
            .filter { seen.add(it.id) }
            .sortedWith(compareByDescending<Episode> { it.publishedSeconds }.thenBy { it.id })
    }

    fun isFavorite(data: PodcastData, id: String): Boolean = data.favorites.any { it.id == id }

    /** Schaltet den Favoritenstatus um; neue Favoriten stehen vorn. `null` bei Limit. */
    fun toggleFavorite(data: PodcastData, episode: Episode): PodcastData? =
        if (isFavorite(data, episode.id)) {
            data.copy(favorites = data.favorites.filterNot { it.id == episode.id })
        } else if (data.favorites.size >= MAX_FAVORITES) {
            null
        } else {
            data.copy(favorites = listOf(episode) + data.favorites)
        }

    /**
     * Merkt die Wiedergabeposition. Bleibt bis zum Ende weniger als [PLAYED_REMAINING_MS], gilt die Folge als
     * gehört und die Position entfällt; sehr frühe Positionen werden nicht gemerkt.
     */
    fun savePosition(data: PodcastData, id: String, positionMs: Long, durationMs: Long): PodcastData {
        if (positionMs < 0) return data
        if (durationMs > 0 && durationMs - positionMs < PLAYED_REMAINING_MS) {
            return data.copy(positionsMs = data.positionsMs - id, played = data.played + id)
        }
        if (positionMs < MIN_POSITION_MS) return data.copy(positionsMs = data.positionsMs - id)
        return data.copy(positionsMs = data.positionsMs + (id to positionMs))
    }

    fun resumePosition(data: PodcastData, id: String): Long = data.positionsMs[id] ?: 0L

    fun setPlayed(data: PodcastData, id: String, played: Boolean): PodcastData = data.copy(
        played = if (played) data.played + id else data.played - id,
        positionsMs = data.positionsMs - id,
    )

    /** Merkt eine heruntergeladene Folge ([fileName] gesetzt) oder entfernt sie (`null`). */
    fun setDownload(data: PodcastData, episode: Episode, fileName: String?): PodcastData =
        if (fileName == null) {
            data.copy(downloads = data.downloads - episode.id, saved = data.saved.filterNot { it.id == episode.id })
        } else {
            data.copy(
                downloads = data.downloads + (episode.id to fileName),
                saved = listOf(episode) + data.saved.filterNot { it.id == episode.id },
            )
        }

    /** Alle bekannten Folgen (Zwischenspeicher, Favoriten und geladene Folgen) nach Kennung, für Downloads und Wiedergabelisten. */
    fun knownEpisodes(data: PodcastData): Map<String, Episode> {
        val map = LinkedHashMap<String, Episode>()
        data.saved.forEach { map[it.id] = it }
        data.favorites.forEach { map[it.id] = it }
        data.episodes.values.forEach { list -> list.forEach { map[it.id] = it } }
        return map
    }

    /** Dateiname für den Download: Kennung plus Endung nach Medientyp oder Adresse, nur aus einer festen Liste. */
    fun downloadFileName(episode: Episode): String {
        val byMime = when (episode.mime.substringBefore(';').trim()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> null
        }
        val path = episode.audioUrl.substringBefore('?').substringAfterLast('/').lowercase()
        val byUrl = listOf("mp3", "m4a", "aac", "ogg", "opus", "wav").firstOrNull { path.endsWith(".$it") }
        return "${episode.id}.${byMime ?: byUrl ?: "mp3"}"
    }

    /** `1:02:03` oder `12:03`; Sekunden unter 0 werden wie 0 behandelt. */
    fun formatClock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Wechselt zur nächsten Geschwindigkeit der Liste und beginnt danach von vorn. */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return if (index < 0) 1.0f else SPEEDS[(index + 1) % SPEEDS.size]
    }
}
