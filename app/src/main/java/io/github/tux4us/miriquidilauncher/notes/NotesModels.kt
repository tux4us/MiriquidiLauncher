// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

/**
 * Eine lokale Notiz. [path] ist der Pfad relativ zum Notizordner mit `/` als Trenner und Endung (`Kategorie/Titel.md`),
 * der Ordner ist die Kategorie. [favorite] liegt nicht in der Datei, sondern im lokalen Zustand.
 */
data class Note(
    val path: String,
    val content: String,
    val modifiedMillis: Long,
    val favorite: Boolean = false,
) {
    val category: String get() = NotesLogic.categoryOf(path)
    val title: String get() = NotesLogic.titleOf(content).ifEmpty { NotesLogic.baseNameOf(path) }
}

enum class NoteSort { MODIFIED, TITLE }

/** Eine Notiz der Nextcloud-Notes-App. [modified] in Sekunden seit 1970, wie vom Server geliefert. */
data class RemoteNote(
    val id: Long,
    val etag: String,
    val title: String,
    val content: String,
    val category: String,
    val favorite: Boolean,
    val modified: Long,
    val readonly: Boolean = false,
)

/** Zuordnung einer lokalen Datei zu einer Server-Notiz nach dem letzten Abgleich. */
data class IndexEntry(
    val path: String,
    val remoteId: Long,
    val etag: String,
    /** SHA-256 des Inhalts beim letzten Abgleich. */
    val hash: String,
    val favorite: Boolean,
)

/** Lokaler Zustand außerhalb der Dateien: Favoriten und Abgleich-Zuordnung. */
data class NotesState(
    val favorites: Set<String> = emptySet(),
    val index: List<IndexEntry> = emptyList(),
)
