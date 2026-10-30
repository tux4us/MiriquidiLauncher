// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

/** Ein abonnierter Podcast (oder ein Suchtreffer). [feedUrl] ist immer eine HTTPS-Adresse und dient als Schlüssel. */
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val imageUrl: String? = null,
)

/** Eine Folge. [id] ist stabil (Hash aus Feed-Adresse und GUID) und eignet sich als Dateiname. */
data class Episode(
    val id: String,
    val podcastUrl: String,
    val podcastTitle: String,
    val title: String,
    val publishedSeconds: Long,
    val audioUrl: String,
    val mime: String,
    val durationSeconds: Int,
    /** Bereinigtes HTML der Shownotes (ohne Skripte, Rahmen, Formulare). */
    val notesHtml: String,
    val link: String?,
    val imageUrl: String?,
)

data class ParsedPodcast(val podcast: Podcast, val episodes: List<Episode>)

/** Suchtreffer eines Verzeichnisses. */
data class PodcastHit(val feedUrl: String, val title: String, val author: String, val imageUrl: String?)

/** Verzeichnis, über das die Suche läuft. Beide arbeiten ohne Schlüssel und ohne Konto. */
enum class SearchProvider(val id: String) {
    ITUNES("itunes"),
    FYYD("fyyd");

    companion object {
        fun fromId(id: String?): SearchProvider = entries.firstOrNull { it.id == id } ?: ITUNES
    }
}

/**
 * Alle lokal gespeicherten Podcast-Daten. [episodes] ist der Zwischenspeicher je Feed für die Offline-Anzeige;
 * [favorites] enthält vollständige Kopien, damit Favoriten auch ohne Abo und ohne Netz abspielbar sind.
 */
data class PodcastData(
    val subscriptions: List<Podcast> = emptyList(),
    val episodes: Map<String, List<Episode>> = emptyMap(),
    val favorites: List<Episode> = emptyList(),
    val positionsMs: Map<String, Long> = emptyMap(),
    val played: Set<String> = emptySet(),
    /** Folgen-ID → Dateiname im App-Speicher. */
    val downloads: Map<String, String> = emptyMap(),
    /** Vollständige Kopien der heruntergeladenen Folgen, damit sie auch ohne Abo und ohne Netz erscheinen. */
    val saved: List<Episode> = emptyList(),
    val provider: SearchProvider = SearchProvider.ITUNES,
    val speed: Float = 1.0f,
)
