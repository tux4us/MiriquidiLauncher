// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File
import java.io.IOException

/** Ein Eintrag im Papierkorb. [item] ist die verschobene Datei oder der Ordner, [infoFile] die zugehörige Beschreibung. */
data class TrashItem(
    val item: File,
    val infoFile: File,
    val originalPath: String,
    val deletedAtMillis: Long,
    val isDirectory: Boolean,
) {
    val displayName: String get() = File(originalPath).name
}

/**
 * Eigener Papierkorb je Speicher: Ordner `.MiriquidiTrash` in der Wurzel des Speichers. Das Verschieben dorthin ist
 * auf demselben Speicher ein Umbenennen und damit schnell. Zu jedem Eintrag gehört eine Textdatei mit dem
 * ursprünglichen Pfad und dem Zeitpunkt. Ohne Android-Klassen, auf der JVM testbar.
 */
object FileTrash {
    const val FOLDER = ".MiriquidiTrash"
    const val RETENTION_DAYS = 30
    private const val INFO_SUFFIX = ".info"

    fun trashDir(volumeRoot: File): File = File(volumeRoot, FOLDER)

    /** Ist [file] bereits im Papierkorb eines der Speicher? */
    fun isInTrash(file: File, roots: List<File>): Boolean = roots.any { FilePaths.isInside(trashDir(it), file) }

    /**
     * Verschiebt [files] in den Papierkorb des Speichers, in dem sie liegen. Dateien, die in keinem der [roots] liegen,
     * werden nicht bearbeitet und als fehlgeschlagen gemeldet; die Oberfläche bietet dafür endgültiges Löschen an.
     */
    fun trash(
        files: List<File>,
        roots: List<File>,
        now: Long,
        progress: (FileProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): OpResult {
        var done = 0
        val failed = ArrayList<String>()
        for (file in files) {
            if (cancelled()) throw OperationCancelled()
            val root = FilePaths.rootOf(file.absolutePath, roots.map { it.absolutePath })
            if (root == null || file.absolutePath.contains('\n')) {
                failed.add(file.name)
                continue
            }
            val dir = trashDir(File(root))
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Papierkorb nicht anlegbar")
                val id = "$now-${(Math.random() * 1_000_000).toInt()}-${file.name}"
                val target = File(dir, id)
                val info = File(dir, id + INFO_SUFFIX)
                info.writeText(file.absolutePath + "\n" + now + "\n" + (if (file.isDirectory) "d" else "f") + "\n")
                try {
                    FileOps.moveTo(file, target, progress, cancelled)
                } catch (e: IOException) {
                    info.delete()
                    throw e
                } catch (e: OperationCancelled) {
                    info.delete()
                    throw e
                }
                done++
            } catch (e: IOException) {
                failed.add(file.name)
            }
        }
        return OpResult(done, failed)
    }

    /** Alle Einträge der Papierkörbe, neueste zuerst. Beschädigte oder verwaiste Einträge werden ignoriert. */
    fun list(roots: List<File>): List<TrashItem> {
        val items = ArrayList<TrashItem>()
        for (root in roots) {
            val dir = trashDir(root)
            val infos = dir.listFiles { f -> f.name.endsWith(INFO_SUFFIX) } ?: continue
            for (info in infos) {
                val item = File(dir, info.name.removeSuffix(INFO_SUFFIX))
                if (!item.exists() && !FileOps.isLink(item)) continue
                val lines = runCatching { info.readText().split('\n') }.getOrNull() ?: continue
                if (lines.size < 3) continue
                val at = lines[1].toLongOrNull() ?: continue
                items.add(TrashItem(item, info, lines[0], at, lines[2] == "d"))
            }
        }
        return items.sortedByDescending { it.deletedAtMillis }
    }

    /**
     * Stellt wieder her: an den ursprünglichen Pfad, bei vorhandenem Namen mit Zähler. Fehlt der Ordner, wird er
     * angelegt. Liefert den neuen Pfad.
     */
    fun restore(item: TrashItem, progress: (FileProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): File {
        val original = File(item.originalPath)
        val parent = original.parentFile ?: throw IOException("Ungültiger Pfad")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Ordner nicht anlegbar")
        val target = File(parent, FilePaths.uniqueName(parent, original.name))
        FileOps.moveTo(item.item, target, progress, cancelled)
        item.infoFile.delete()
        return target
    }

    fun purge(item: TrashItem): Boolean {
        val ok = FileOps.deleteTree(item.item)
        if (ok) item.infoFile.delete()
        return ok
    }

    fun empty(roots: List<File>): OpResult {
        var done = 0
        val failed = ArrayList<String>()
        for (item in list(roots)) if (purge(item)) done++ else failed.add(item.displayName)
        return OpResult(done, failed)
    }

    /** Entfernt Einträge, die länger als [RETENTION_DAYS] Tage im Papierkorb liegen. Liefert die Zahl der entfernten. */
    fun purgeExpired(roots: List<File>, now: Long): Int {
        val limit = now - RETENTION_DAYS * 24L * 3600 * 1000
        return list(roots).count { it.deletedAtMillis < limit && purge(it) }
    }
}
