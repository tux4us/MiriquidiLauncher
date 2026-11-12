// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

/** Ein Lied der lokalen Mediathek. [id] ist die MediaStore-Kennung; Leerstrings stehen für unbekannte Angaben. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int = 0,
    val year: Int = 0,
)

/** Ein Album, abgeleitet aus den Liedern ([id] ist die MediaStore-Album-Kennung). */
data class Album(val id: Long, val title: String, val artist: String, val trackCount: Int)

/** Ein Interpret, abgeleitet aus den Liedern. */
data class Artist(val name: String, val trackCount: Int)

/**
 * Ein Radiosender. [id] ist die Kennung im Verzeichnis (UUID) bzw. bei selbst eingegebenen Sendern ein Hash der Adresse.
 * [streamUrl] ist die abspielbare Stream-Adresse (HTTPS, mit eingeschaltetem Schalter auch HTTP).
 */
data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val logoUrl: String? = null,
    val country: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
)

/** Eine Wiedergabeliste aus MediaStore-Kennungen. Nach einer Neuindizierung der Mediathek können Einträge fehlen; sie werden übersprungen. */
data class Playlist(val id: String, val name: String, val trackIds: List<Long>)

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): RepeatMode = entries.firstOrNull { it.name == id } ?: OFF
    }
}

/**
 * Alle lokal gespeicherten Daten der Musik-Scope. [radioEnabled] ist die Zustimmung zur Sendersuche im Verzeichnis
 * (kein Teil des Backups); [allowHttp] erlaubt unverschlüsselte Streams und ist standardmäßig aus (ebenfalls nicht im Backup).
 */
data class MusicData(
    val stations: List<Station> = emptyList(),
    val favoriteTracks: List<Long> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val eq: EqSettings = EqSettings(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val radioEnabled: Boolean = false,
    val allowHttp: Boolean = false,
    val showLogos: Boolean = true,
)
