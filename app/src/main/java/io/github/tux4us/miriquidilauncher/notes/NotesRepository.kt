// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import android.content.Context
import android.os.Environment
import io.github.tux4us.miriquidilauncher.backup.BackupNote
import io.github.tux4us.miriquidilauncher.files.FileOps
import io.github.tux4us.miriquidilauncher.files.FilePaths
import io.github.tux4us.miriquidilauncher.files.FileTrash
import io.github.tux4us.miriquidilauncher.files.OpResult
import io.github.tux4us.miriquidilauncher.files.TrashItem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncSummary(val downloaded: Int, val uploaded: Int, val deleted: Int, val conflicts: Int, val failed: Int)

/**
 * Notizen als Markdown-Dateien im app-eigenen Dokumentenordner (`Android/data/<Paket>/files/Documents/MiriquidiNotes`,
 * Unterordner = Kategorien), Papierkorb über [FileTrash] und Abgleich mit Nextcloud Notes. Dafür ist keine
 * Speicherberechtigung nötig. Der Ordner wird beim Deinstallieren gelöscht; das Backup sichert die Notizen. Alle
 * Vorgänge laufen nacheinander (Sperre), damit sich Speichern und Abgleich nicht überschneiden.
 */
class NotesRepository(context: Context, private val store: NotesStore) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** App-eigener Dokumentenordner (Wurzel für Notizen und Papierkorb). */
    private fun baseDir(): File =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(appContext.filesDir, "Documents")

    fun root(): File = File(baseDir(), FOLDER)

    /** Der app-eigene Ordner braucht keine Berechtigung. */
    fun hasAccess(): Boolean = true

    private fun volumeRoots(): List<File> = listOf(baseDir())

    // region Dateien -----------------------------------------------------------------------------

    suspend fun load(): List<Note> = mutex.withLock {
        val state = store.loadState()
        withContext(Dispatchers.IO) { scan(state.favorites) }
    }

    private fun scan(favorites: Set<String>): List<Note> {
        val base = root()
        if (!base.isDirectory) return emptyList()
        val out = ArrayList<Note>()
        fun walk(dir: File, prefix: String, depth: Int) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (out.size >= NotesLogic.MAX_NOTES) return
                if (f.name.startsWith(".") || FileOps.isLink(f)) continue
                val rel = prefix + f.name
                if (f.isDirectory) {
                    if (depth < NotesLogic.MAX_DEPTH) walk(f, "$rel/", depth + 1)
                } else if (NotesLogic.isNotePath(f.name) && f.length() <= MAX_NOTE_BYTES) {
                    val text = try {
                        f.readText(Charsets.UTF_8)
                    } catch (ignored: IOException) {
                        continue
                    }
                    out.add(Note(rel, text, f.lastModified(), rel in favorites))
                }
            }
        }
        walk(base, "", 0)
        return out
    }

    private fun takenPaths(): Set<String> {
        val base = root()
        val paths = ArrayList<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            for (f in dir.listFiles().orEmpty()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < NotesLogic.MAX_DEPTH) walk(f, prefix + f.name + "/", depth + 1)
                } else {
                    paths.add(prefix + f.name)
                }
            }
        }
        if (base.isDirectory) walk(base, "", 0)
        return NotesLogic.takenSet(paths)
    }

    /** Datei zu einem relativen Pfad; wirft, wenn der Pfad aus dem Notizordner herausführen würde. */
    private fun fileFor(path: String): File {
        if (!NotesLogic.isSafePath(path)) throw IOException("Ungültiger Pfad")
        val file = File(root(), path)
        if (!FilePaths.isInside(root(), file)) throw IOException("Ungültiger Pfad")
        return file
    }

    private fun writeFile(path: String, content: String, modifiedMillis: Long? = null) {
        val file = fileFor(path)
        val parent = file.parentFile ?: throw IOException("Ungültiger Pfad")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Ordner nicht anlegbar")
        val temporary = File(parent, ".${file.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("Speichern fehlgeschlagen")
            }
        }
        if (modifiedMillis != null && modifiedMillis > 0) file.setLastModified(modifiedMillis)
    }

    /**
     * Speichert eine Notiz. [oldPath] `null` legt sie neu an (Name aus dem Titel). Mit [rename] folgt der Dateiname dem
     * Titel; sonst bleibt der Pfad (für das Zwischenspeichern während des Schreibens).
     */
    suspend fun save(oldPath: String?, content: String, category: String, rename: Boolean): Note = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (content.length > NotesLogic.MAX_NOTE_CHARS) throw IOException("Notiz zu lang")
            val state = store.loadState()
            val taken = takenPaths()
            val cat = NotesLogic.sanitizeCategory(category)
            val path = when {
                oldPath == null -> NotesLogic.uniquePath(taken, cat, NotesLogic.fileBase(NotesLogic.titleOf(content)))
                rename -> NotesLogic.pathAfterEdit(oldPath, content, taken)
                else -> oldPath
            }
            writeFile(path, content)
            var next = state
            if (oldPath != null && path != oldPath) {
                fileFor(oldPath).delete()
                next = moveInState(state, oldPath, path)
                store.saveState(next)
            }
            Note(path, content, fileFor(path).lastModified(), path in next.favorites)
        }
    }

    private fun moveInState(state: NotesState, from: String, to: String): NotesState = NotesState(
        favorites = if (from in state.favorites) state.favorites - from + to else state.favorites,
        index = state.index.map { if (it.path == from) it.copy(path = to) else it },
    )

    suspend fun setFavorite(path: String, favorite: Boolean) = mutex.withLock {
        val state = store.loadState()
        store.saveState(state.copy(favorites = if (favorite) state.favorites + path else state.favorites - path))
    }

    /** Verschiebt in eine andere Kategorie (Ordner). Liefert den neuen Pfad. */
    suspend fun moveToCategory(path: String, category: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = store.loadState()
            val target = NotesLogic.pathInCategory(path, category, takenPaths())
            if (target == path) return@withContext path
            val targetFile = fileFor(target)
            val parent = targetFile.parentFile ?: throw IOException("Ungültiger Pfad")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Ordner nicht anlegbar")
            if (!fileFor(path).renameTo(targetFile)) throw IOException("Verschieben fehlgeschlagen")
            store.saveState(moveInState(state, path, target))
            target
        }
    }

    // endregion

    // region Papierkorb --------------------------------------------------------------------------

    suspend fun trash(paths: List<String>): OpResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val files = paths.mapNotNull { runCatching { fileFor(it) }.getOrNull() }
            val result = FileTrash.trash(files, volumeRoots(), System.currentTimeMillis())
            val state = store.loadState()
            store.saveState(state.copy(favorites = state.favorites - paths.toSet()))
            result
        }
    }

    suspend fun listTrash(): List<TrashItem> = withContext(Dispatchers.IO) {
        val prefix = root().absolutePath + "/"
        FileTrash.list(volumeRoots()).filter { it.originalPath.startsWith(prefix) && NotesLogic.isNotePath(it.originalPath) }
    }

    suspend fun restore(items: List<TrashItem>): OpResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            var done = 0
            val failed = ArrayList<String>()
            for (item in items) {
                try {
                    FileTrash.restore(item)
                    done++
                } catch (ignored: IOException) {
                    failed.add(item.displayName)
                }
            }
            OpResult(done, failed)
        }
    }

    suspend fun purge(items: List<TrashItem>): OpResult = withContext(Dispatchers.IO) {
        var done = 0
        val failed = ArrayList<String>()
        for (item in items) if (FileTrash.purge(item)) done++ else failed.add(item.displayName)
        OpResult(done, failed)
    }

    // endregion

    // region Backup ------------------------------------------------------------------------------

    /** Alle Notizen für das Backup; `null`, wenn der Zugriff auf Dateien fehlt. */
    suspend fun exportForBackup(): List<BackupNote>? {
        if (!hasAccess()) return null
        return load().map { BackupNote(it.path, it.content, it.favorite, it.modifiedMillis) }
    }

    /**
     * Spielt Notizen aus einem Backup ein. Vorhandene Notizen werden nie überschrieben: Ist die Datei gleich,
     * geschieht nichts, sonst entsteht eine Kopie mit Zähler. Liefert die Zahl der eingespielten oder bereits
     * vorhandenen Notizen.
     */
    suspend fun importFromBackup(notes: List<BackupNote>): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasAccess()) throw IOException("Kein Zugriff auf Dateien")
            val state = store.loadState()
            val taken = HashSet(takenPaths())
            var favorites = state.favorites
            var count = 0
            for (n in notes) {
                if (!NotesLogic.isSafePath(n.path) || !NotesLogic.isNotePath(n.path)) continue
                val existing = fileFor(n.path)
                var target = n.path
                if (existing.isFile) {
                    val same = try { existing.readText(Charsets.UTF_8) == n.content } catch (ignored: IOException) { false }
                    if (same) { count++; continue }
                    val ext = "." + NotesLogic.extensionOf(n.path)
                    target = NotesLogic.uniquePath(taken, NotesLogic.categoryOf(n.path), NotesLogic.baseNameOf(n.path), ext)
                }
                try {
                    writeFile(target, n.content, n.modifiedMillis)
                } catch (ignored: IOException) {
                    continue
                }
                taken.add(target.lowercase())
                if (n.favorite) favorites = favorites + target
                count++
            }
            store.saveState(state.copy(favorites = favorites))
            count
        }
    }

    // endregion

    // region Abgleich mit Nextcloud Notes --------------------------------------------------------

    suspend fun sync(credentials: NotesCredentials): SyncSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remote = NotesRemote(credentials.baseUrl, credentials.user, credentials.password)
            val remoteNotes = remote.list()
            val state = store.loadState()
            val local = scan(state.favorites)
            val actions = NotesSync.plan(local.map { LocalNote(it.path, it.content, it.favorite) }, state.index, remoteNotes)

            val index = LinkedHashMap<String, IndexEntry>()
            state.index.forEach { index[it.path] = it }
            val favorites = HashSet(state.favorites)
            val localByPath = local.associateBy { it.path }
            var downloaded = 0
            var uploaded = 0
            var deleted = 0
            var conflicts = 0
            var failed = 0

            suspend fun upload(path: String, content: String, remoteId: Long?, modifiedMillis: Long) {
                if (content.isBlank()) return
                val fav = path in favorites
                val seconds = modifiedMillis / 1000
                val r = if (remoteId == null) {
                    remote.create(content, NotesLogic.categoryOf(path), fav, seconds)
                } else {
                    remote.update(remoteId, content, NotesLogic.categoryOf(path), fav, seconds)
                }
                index[path] = IndexEntry(path, r.id, r.etag, NotesLogic.hash(content), fav)
                uploaded++
            }

            for (action in actions) {
                try {
                    when (action) {
                        is SyncAction.Download -> {
                            val r = action.remote
                            writeFile(action.path, r.content, r.modified * 1000)
                            if (action.oldPath != null && action.oldPath != action.path) {
                                fileFor(action.oldPath).delete()
                                index.remove(action.oldPath)
                                favorites.remove(action.oldPath)
                            }
                            index[action.path] = IndexEntry(action.path, r.id, r.etag, NotesLogic.hash(r.content), r.favorite)
                            if (r.favorite) favorites.add(action.path) else favorites.remove(action.path)
                            downloaded++
                        }
                        is SyncAction.Upload -> {
                            val note = localByPath[action.path]
                            if (note != null) upload(action.path, note.content, action.remoteId, note.modifiedMillis)
                        }
                        is SyncAction.DeleteLocal -> {
                            FileTrash.trash(listOf(fileFor(action.path)), volumeRoots(), System.currentTimeMillis())
                            index.remove(action.path)
                            favorites.remove(action.path)
                            deleted++
                        }
                        is SyncAction.DeleteRemote -> {
                            remote.delete(action.remoteId)
                            index.remove(action.path)
                            favorites.remove(action.path)
                            deleted++
                        }
                        is SyncAction.Conflict -> {
                            val r = action.remote
                            writeFile(action.copyPath, r.content, r.modified * 1000)
                            if (r.favorite) favorites.add(action.copyPath)
                            upload(action.copyPath, r.content, null, r.modified * 1000)
                            val note = localByPath[action.path]
                            if (note != null) upload(action.path, note.content, r.id, note.modifiedMillis)
                            conflicts++
                        }
                        is SyncAction.DropIndex -> index.remove(action.path)
                        is SyncAction.Adopt -> {
                            val r = action.remote
                            index[action.path] = IndexEntry(action.path, r.id, r.etag, NotesLogic.hash(r.content), r.favorite)
                        }
                    }
                } catch (ignored: IOException) {
                    failed++
                }
            }
            store.saveState(NotesState(favorites, index.values.toList()))
            store.saveLastSync(System.currentTimeMillis())
            SyncSummary(downloaded, uploaded, deleted, conflicts, failed)
        }
    }

    // endregion

    companion object {
        const val FOLDER = "MiriquidiNotes"
        private const val MAX_NOTE_BYTES = 400_000L
    }
}
