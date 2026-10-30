// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import java.io.File
import java.nio.file.Files

/** Speicherbelegung einer App in Byte. [dataBytes] enthält [cacheBytes]. */
data class AppStorage(
    val packageName: String,
    val label: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes
}

object AppStorageLogic {
    /** Die [limit] Apps mit dem größten Cache, größte zuerst; bei gleichem Cache nach Name. Apps ohne Cache entfallen. */
    fun topByCache(apps: List<AppStorage>, limit: Int): List<AppStorage> =
        apps.filter { it.cacheBytes > 0 }
            .sortedWith(compareByDescending<AppStorage> { it.cacheBytes }.thenBy { it.label.lowercase() })
            .take(limit)

    fun totalCache(apps: List<AppStorage>): Long = apps.sumOf { it.cacheBytes }
}

/**
 * Ordner mit Größe. [ownBytes] sind die Dateien direkt in diesem Ordner, [totalBytes] schließt alle Unterordner ein.
 * Bewusst keine Datenklasse: Gleichheit soll die Identität sein, nicht der gesamte Teilbaum.
 */
class DirNode(val name: String) {
    var ownBytes: Long = 0
    var ownFiles: Int = 0
    var totalBytes: Long = 0
    var totalFiles: Long = 0
    val children: MutableList<DirNode> = ArrayList()
}

/** Liest einen Ordnerbaum und summiert die Dateigrößen. Symbolische Links werden nicht verfolgt. */
object DirectoryScanner {
    const val MAX_DEPTH = 64
    const val DEFAULT_MAX_DIRS = 150_000
    private const val PROGRESS_EVERY = 500

    /**
     * Liefert den Baum ab [root] oder `null`, wenn [isCancelled] vorzeitig `true` wurde oder [root] kein Ordner ist.
     * Über [maxDirs] hinaus werden keine Knoten mehr angelegt; die Größen fließen dann in den übergeordneten Ordner ein.
     * [onProgress] erhält die Zahl der bisherigen Ordner und Dateien.
     */
    fun scan(
        root: File,
        maxDirs: Int = DEFAULT_MAX_DIRS,
        isCancelled: () -> Boolean = { false },
        onProgress: (dirs: Int, files: Long) -> Unit = { _, _ -> },
    ): DirNode? {
        if (!root.isDirectory) return null
        val state = State(maxDirs, isCancelled, onProgress)
        val node = DirNode(root.name.ifEmpty { root.path })
        state.dirs = 1
        walk(root, node, 0, state)
        if (isCancelled()) return null
        finish(node)
        onProgress(state.dirs, state.files)
        return node
    }

    private class State(val maxDirs: Int, val isCancelled: () -> Boolean, val onProgress: (Int, Long) -> Unit) {
        var dirs = 0
        var files = 0L
    }

    private fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun walk(dir: File, node: DirNode, depth: Int, state: State) {
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (entry in entries) {
            if (state.isCancelled()) return
            if (isLink(entry)) continue
            if (entry.isDirectory) {
                if (depth + 1 > MAX_DEPTH) continue
                if (state.dirs >= state.maxDirs) {
                    // Zu viele Knoten: Größe wird nur noch dem Elternordner zugerechnet.
                    node.ownBytes += sumOnly(entry, depth + 1, state)
                    continue
                }
                state.dirs++
                val child = DirNode(entry.name)
                node.children += child
                walk(entry, child, depth + 1, state)
            } else if (entry.isFile) {
                node.ownBytes += entry.length()
                node.ownFiles++
                state.files++
                if (state.files % PROGRESS_EVERY == 0L) state.onProgress(state.dirs, state.files)
            }
        }
    }

    private fun sumOnly(dir: File, depth: Int, state: State): Long {
        if (depth > MAX_DEPTH) return 0
        var sum = 0L
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        for (entry in entries) {
            if (state.isCancelled()) return sum
            if (isLink(entry)) continue
            if (entry.isDirectory) sum += sumOnly(entry, depth + 1, state)
            else if (entry.isFile) {
                sum += entry.length()
                state.files++
            }
        }
        return sum
    }

    private fun finish(node: DirNode) {
        var bytes = node.ownBytes
        var files = node.ownFiles.toLong()
        for (child in node.children) {
            finish(child)
            bytes += child.totalBytes
            files += child.totalFiles
        }
        node.totalBytes = bytes
        node.totalFiles = files
        node.children.sortWith(compareByDescending<DirNode> { it.totalBytes }.thenBy { it.name.lowercase() })
    }
}
