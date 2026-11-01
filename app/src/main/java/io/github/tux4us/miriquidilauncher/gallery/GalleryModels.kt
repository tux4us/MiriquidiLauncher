// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import java.time.LocalDate

/** Ein Foto oder Video aus der Mediathek des Geräts. [key] ist eindeutig (`i<Kennung>` bzw. `v<Kennung>`). */
data class MediaEntry(
    val id: Long,
    val isVideo: Boolean,
    val name: String,
    /** Absoluter Dateipfad oder leer, wenn das System ihn nicht nennt (dann sind Änderungen nicht möglich). */
    val path: String,
    val bucketId: Long,
    val bucketName: String,
    val takenMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mime: String,
    /** Nur im Papierkorb: Zeitpunkt, zu dem das System den Eintrag endgültig löscht (0 = unbekannt). */
    val expiresMillis: Long = 0L,
) {
    val key: String get() = (if (isVideo) "v" else "i") + id
    val dir: String get() = path.substringBeforeLast('/', "")
}

enum class MediaFilter { ALL, PHOTOS, VIDEOS }

/** Ein Album entspricht einem Ordner der Mediathek. */
data class Album(
    val bucketId: Long,
    val name: String,
    val dir: String,
    val count: Int,
    val photos: Int,
    val videos: Int,
    val cover: MediaEntry,
    val newestMillis: Long,
)

/** Aufnahmen eines Tages in der Reihenfolge der Anzeige. */
data class DaySection(val day: LocalDate, val items: List<MediaEntry>)
