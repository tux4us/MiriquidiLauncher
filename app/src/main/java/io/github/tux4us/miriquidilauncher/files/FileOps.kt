// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Dateivorgänge mit `java.io`, ohne Android-Klassen und auf der JVM testbar. Verknüpfungen (Symlinks) werden nie
 * verfolgt: Beim Kopieren und Packen entfallen sie, beim Löschen wird nur die Verknüpfung selbst entfernt.
 * Vorhandene Ziele werden nie überschrieben; bei gleichem Namen entsteht `name (1).ext`.
 */
object FileOps {
    private const val BUFFER = 64 * 1024

    internal fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    fun createFolder(parent: File, name: String): File {
        require(FilePaths.checkName(name) == NameCheck.OK) { "Ungültiger Name" }
        val folder = File(parent, name.trim())
        if (folder.exists()) throw IOException("Existiert bereits")
        if (!folder.mkdir()) throw IOException("Ordner nicht anlegbar")
        return folder
    }

    fun createEmptyFile(parent: File, name: String): File {
        require(FilePaths.checkName(name) == NameCheck.OK) { "Ungültiger Name" }
        val file = File(parent, name.trim())
        if (file.exists()) throw IOException("Existiert bereits")
        if (!file.createNewFile()) throw IOException("Datei nicht anlegbar")
        return file
    }

    /** Benennt um. Ein vorhandener Zielname wird abgelehnt (`IOException`). */
    fun rename(file: File, newName: String): File {
        require(FilePaths.checkName(newName) == NameCheck.OK) { "Ungültiger Name" }
        val target = File(file.parentFile, newName.trim())
        if (target.path == file.path) return file
        if (target.exists() && !target.path.equals(file.path, ignoreCase = true)) throw IOException("Existiert bereits")
        if (!file.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen")
        return target
    }

    /** Größe, Datei- und Ordnerzahl eines Baums; [cancelled] wird regelmäßig geprüft. */
    fun treeSize(root: File, cancelled: () -> Boolean = { false }): SizeInfo {
        var bytes = 0L
        var files = 0
        var folders = 0
        fun walk(f: File) {
            if (cancelled()) throw OperationCancelled()
            if (isLink(f)) return
            if (f.isDirectory) {
                folders++
                f.listFiles()?.forEach { walk(it) }
            } else {
                files++
                bytes += f.length()
            }
        }
        walk(root)
        return SizeInfo(bytes, files, if (root.isDirectory) folders - 1 else 0)
    }

    private fun totalBytes(sources: List<File>, cancelled: () -> Boolean): Long =
        sources.sumOf { runCatching { treeSize(it, cancelled).bytes }.getOrDefault(0L) }

    // region Kopieren und Verschieben ------------------------------------------------------------

    fun copy(
        sources: List<File>,
        targetDir: File,
        progress: (FileProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
        onCreated: (File) -> Unit = {},
    ): OpResult {
        val total = totalBytes(sources, cancelled)
        val state = CopyState(total, progress, cancelled)
        var done = 0
        var skipped = 0
        val failed = ArrayList<String>()
        for (source in sources) {
            if (cancelled()) throw OperationCancelled()
            if (isLink(source)) {
                skipped++
                continue
            }
            if (source.isDirectory && FilePaths.isInside(source, targetDir)) {
                failed.add(source.name)
                continue
            }
            val target = File(targetDir, FilePaths.uniqueName(targetDir, source.name))
            try {
                copyTree(source, target, state)
                onCreated(target)
                done++
            } catch (e: OperationCancelled) {
                deleteTree(target)
                throw e
            } catch (e: IOException) {
                deleteTree(target)
                failed.add(source.name)
            }
        }
        return OpResult(done, failed, skipped)
    }

    private class CopyState(val total: Long, val progress: (FileProgress) -> Unit, val cancelled: () -> Boolean) {
        var done = 0L
    }

    private fun copyTree(source: File, target: File, state: CopyState) {
        if (isLink(source)) return
        if (source.isDirectory) {
            if (!target.mkdir() && !target.isDirectory) throw IOException("Ordner nicht anlegbar")
            source.listFiles()?.forEach { copyTree(it, File(target, it.name), state) }
            target.setLastModified(source.lastModified())
        } else {
            copyFile(source, target, state)
        }
    }

    private fun copyFile(source: File, target: File, state: CopyState) {
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (state.cancelled()) throw OperationCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    state.done += n
                    state.progress(FileProgress(state.done, state.total, source.name))
                }
            }
        }
        target.setLastModified(source.lastModified())
    }

    /**
     * Verschiebt. Auf demselben Speicher genügt Umbenennen; sonst wird kopiert und erst nach vollständigem Kopieren
     * gelöscht. Ein Ordner lässt sich nicht in sich selbst verschieben.
     */
    fun move(
        sources: List<File>,
        targetDir: File,
        progress: (FileProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
        onCreated: (File) -> Unit = {},
    ): OpResult {
        var done = 0
        val failed = ArrayList<String>()
        for (source in sources) {
            if (cancelled()) throw OperationCancelled()
            if (source.isDirectory && !isLink(source) && FilePaths.isInside(source, targetDir)) {
                failed.add(source.name)
                continue
            }
            val target = File(targetDir, FilePaths.uniqueName(targetDir, source.name))
            try {
                moveTo(source, target, progress, cancelled)
                onCreated(target)
                done++
            } catch (e: IOException) {
                failed.add(source.name)
            }
        }
        return OpResult(done, failed)
    }

    /** Verschiebt [source] nach [target] (genauer Pfad, darf noch nicht existieren). */
    fun moveTo(source: File, target: File, progress: (FileProgress) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        if (target.exists()) throw IOException("Ziel existiert")
        if (source.renameTo(target)) return
        if (isLink(source)) {
            // Verknüpfungen werden nicht kopiert; Umbenennen über Speichergrenzen ist nicht möglich.
            throw IOException("Verknüpfung nicht verschiebbar")
        }
        val state = CopyState(totalBytes(listOf(source), cancelled), progress, cancelled)
        try {
            copyTree(source, target, state)
        } catch (e: OperationCancelled) {
            deleteTree(target)
            throw e
        } catch (e: IOException) {
            deleteTree(target)
            throw e
        }
        if (!deleteTree(source)) throw IOException("Quelle nicht löschbar")
    }

    // endregion

    // region Löschen -----------------------------------------------------------------------------

    /** Löscht dauerhaft. Gibt `false` zurück, wenn etwas übrig blieb. Verknüpfungen werden nicht verfolgt. */
    fun deleteTree(file: File): Boolean {
        if (!file.exists() && !isLink(file)) return true
        if (!isLink(file) && file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        return file.delete()
    }

    fun delete(files: List<File>, cancelled: () -> Boolean = { false }): OpResult {
        var done = 0
        val failed = ArrayList<String>()
        for (file in files) {
            if (cancelled()) throw OperationCancelled()
            if (deleteTree(file)) done++ else failed.add(file.name)
        }
        return OpResult(done, failed)
    }

    // endregion

    // region Suche -------------------------------------------------------------------------------

    /** Sucht Namen, die [query] enthalten (ohne Beachtung der Groß-/Kleinschreibung), im Baum unter [root]. */
    fun search(
        root: File,
        query: String,
        includeHidden: Boolean,
        limit: Int,
        cancelled: () -> Boolean = { false },
    ): List<File> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val found = ArrayList<File>()
        fun walk(dir: File) {
            if (cancelled()) throw OperationCancelled()
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (found.size >= limit) return
                if (!includeHidden && child.name.startsWith('.')) continue
                if (child.name.lowercase().contains(needle)) found.add(child)
                if (found.size >= limit) return
                if (!isLink(child) && child.isDirectory) walk(child)
            }
        }
        walk(root)
        return found
    }

    // endregion
}
