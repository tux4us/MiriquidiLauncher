// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

/** Lokaler Stand einer Notiz für den Abgleich. */
data class LocalNote(val path: String, val content: String, val favorite: Boolean)

/** Schritt des Abgleichs. Die Ausführung (Dateien, Netzwerk) übernimmt das Repository. */
sealed interface SyncAction {
    /** Server-Notiz lokal nach [path] schreiben; [oldPath] (falls gesetzt und verschieden) wird danach entfernt. */
    data class Download(val remote: RemoteNote, val path: String, val oldPath: String?) : SyncAction

    /** Lokale Notiz zum Server: neu (POST) bei [remoteId] `null`, sonst ändern (PUT). */
    data class Upload(val path: String, val remoteId: Long?) : SyncAction

    /** Lokale Datei entfernen (auf dem Server gelöscht, lokal unverändert). */
    data class DeleteLocal(val path: String) : SyncAction

    /** Notiz auf dem Server löschen (lokal gelöscht, auf dem Server unverändert). */
    data class DeleteRemote(val remoteId: Long, val path: String) : SyncAction

    /** Beide Seiten geändert: Serverstand als neue lokale Datei [copyPath], lokaler Stand überschreibt den Server. */
    data class Conflict(val path: String, val remote: RemoteNote, val copyPath: String) : SyncAction

    /** Zuordnung vergessen (Server-Notiz weg und lokal geändert; die Datei wird danach neu hochgeladen). */
    data class DropIndex(val path: String) : SyncAction

    /** Beide Seiten sind inhaltsgleich; nur die Zuordnung auf den neuen Stand setzen. */
    data class Adopt(val path: String, val remote: RemoteNote) : SyncAction
}

/** Planung des Abgleichs zwischen lokalen Dateien und Nextcloud Notes. Reine Funktion, ohne Netzwerk und Dateien. */
object NotesSync {
    fun plan(local: List<LocalNote>, index: List<IndexEntry>, remote: List<RemoteNote>): List<SyncAction> {
        val localByPath = local.associateBy { it.path }
        val indexByPath = index.associateBy { it.path }
        val indexById = index.associateBy { it.remoteId }
        val taken = HashSet(NotesLogic.takenSet(local.map { it.path }))
        val actions = ArrayList<SyncAction>()
        val dropped = HashSet<String>()

        fun targetFor(r: RemoteNote, suffix: String = ""): String {
            val base = NotesLogic.fileBase(r.title.ifBlank { NotesLogic.titleOf(r.content) }) + suffix
            val path = NotesLogic.uniquePath(taken, NotesLogic.sanitizeCategory(r.category), base)
            taken.add(path.lowercase())
            return path
        }

        for (r in remote) {
            val entry = indexById[r.id]
            if (entry == null) {
                actions += SyncAction.Download(r, targetFor(r), null)
                continue
            }
            val l = localByPath[entry.path]
            val remoteChanged = r.etag != entry.etag
            if (l == null) {
                if (!remoteChanged) {
                    actions += SyncAction.DeleteRemote(r.id, entry.path)
                } else {
                    actions += SyncAction.Download(r, targetFor(r), null)
                }
                continue
            }
            val localChanged = NotesLogic.hash(l.content) != entry.hash || l.favorite != entry.favorite
            val sameContent = l.content == r.content && l.favorite == r.favorite
            when {
                !remoteChanged && !localChanged -> Unit
                sameContent -> actions += SyncAction.Adopt(entry.path, r)
                remoteChanged && !localChanged -> {
                    val wanted = targetFor(r)
                    // Bleibt der Zielpfad bis auf einen Zähler gleich, wird nicht umbenannt.
                    taken.remove(wanted.lowercase())
                    val same = NotesLogic.categoryOf(wanted) == NotesLogic.categoryOf(entry.path) &&
                        NotesLogic.baseNameOf(wanted).replace(Regex(" \\(\\d+\\)$"), "")
                            .equals(NotesLogic.baseNameOf(entry.path).replace(Regex(" \\(\\d+\\)$"), ""), ignoreCase = true)
                    if (same) {
                        actions += SyncAction.Download(r, entry.path, null)
                    } else {
                        taken.add(wanted.lowercase())
                        actions += SyncAction.Download(r, wanted, entry.path)
                    }
                }
                !remoteChanged && localChanged -> if (!r.readonly) actions += SyncAction.Upload(entry.path, r.id)
                else -> actions += SyncAction.Conflict(entry.path, r, targetFor(r, " (Konflikt)"))
            }
        }

        val remoteIds = remote.mapTo(HashSet()) { it.id }
        for (entry in index) {
            if (entry.remoteId in remoteIds) continue
            val l = localByPath[entry.path]
            when {
                l == null -> actions += SyncAction.DropIndex(entry.path)
                NotesLogic.hash(l.content) == entry.hash && l.favorite == entry.favorite -> actions += SyncAction.DeleteLocal(entry.path)
                else -> {
                    actions += SyncAction.DropIndex(entry.path)
                    dropped.add(entry.path)
                }
            }
        }

        for (l in local) {
            if (indexByPath[l.path] == null || l.path in dropped) actions += SyncAction.Upload(l.path, null)
        }
        return actions
    }
}
