// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File
import java.io.IOException

enum class NameCheck { OK, EMPTY, INVALID_CHARS, RESERVED, TOO_LONG }

/** Reine Pfad- und Namensfunktionen. */
object FilePaths {
    private const val MAX_NAME_BYTES = 255
    private val DOUBLE_EXTENSIONS = listOf(".tar.gz", ".tar.bz2", ".tar.xz")

    fun checkName(raw: String): NameCheck {
        val name = raw.trim()
        return when {
            name.isEmpty() -> NameCheck.EMPTY
            name == "." || name == ".." -> NameCheck.RESERVED
            name.any { it == '/' || it == '\u0000' } -> NameCheck.INVALID_CHARS
            name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> NameCheck.TOO_LONG
            else -> NameCheck.OK
        }
    }

    /** Teilt `name` in Grundname und Endung (mit Punkt); `.tar.gz` bleibt zusammen, Punktdateien haben keine Endung. */
    fun splitExtension(name: String): Pair<String, String> {
        val lower = name.lowercase()
        DOUBLE_EXTENSIONS.firstOrNull { lower.endsWith(it) && name.length > it.length }?.let {
            return name.substring(0, name.length - it.length) to name.substring(name.length - it.length)
        }
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** Ein Name, den es in [dir] noch nicht gibt: `name`, sonst `name (1).ext`, `name (2).ext` … */
    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists() && !isDanglingLink(File(dir, name))) return name
        val (base, ext) = splitExtension(name)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            val file = File(dir, candidate)
            if (!file.exists() && !isDanglingLink(file)) return candidate
            n++
        }
    }

    private fun isDanglingLink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** `true`, wenn [child] gleich [parent] ist oder darin liegt (nach Auflösen von `..` und Verknüpfungen). */
    fun isInside(parent: File, child: File): Boolean {
        val p = canonical(parent) ?: return false
        val c = canonical(child) ?: return false
        return c == p || c.startsWith(if (p.endsWith(File.separator)) p else p + File.separator)
    }

    private fun canonical(file: File): String? = try {
        file.canonicalPath
    } catch (ignored: IOException) {
        null
    }

    /** Pfadleiste von [rootPath] bis [path]; die Wurzel heißt [rootName]. Liegt [path] außerhalb, ist es nur die Wurzel. */
    fun breadcrumbs(path: String, rootPath: String, rootName: String): List<Crumb> {
        val root = rootPath.trimEnd('/')
        val crumbs = ArrayList<Crumb>()
        crumbs.add(Crumb(rootName, root.ifEmpty { "/" }))
        val full = path.trimEnd('/')
        if (full.length <= root.length || !full.startsWith("$root/")) return crumbs
        var current = root
        for (part in full.substring(root.length + 1).split('/')) {
            if (part.isEmpty()) continue
            current = "$current/$part"
            crumbs.add(Crumb(part, current))
        }
        return crumbs
    }

    /** Übergeordneter Ordner, aber nie oberhalb von [rootPath]; an der Wurzel `null`. */
    fun parentWithin(path: String, rootPath: String): String? {
        val root = rootPath.trimEnd('/')
        val full = path.trimEnd('/')
        if (full == root || full.isEmpty() || !full.startsWith("$root/")) return null
        return full.substringBeforeLast('/').ifEmpty { "/" }
    }

    /** Wurzel, in der [path] liegt (die längste passende), oder `null`. */
    fun rootOf(path: String, roots: List<String>): String? =
        roots.map { it.trimEnd('/') }.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
}

/** Natürliche Sortierung ohne Beachtung der Groß-/Kleinschreibung: `datei2` vor `datei10`. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object FileSorter {
    /** Ordner stehen immer vor Dateien; innerhalb davon gilt [order]. */
    fun sort(entries: List<FileEntry>, order: SortOrder): List<FileEntry> {
        val byName = Comparator<FileEntry> { x, y -> NaturalOrder.compare(x.name, y.name) }
        val primary: Comparator<FileEntry> = when (order.field) {
            SortField.NAME -> byName
            SortField.SIZE -> compareBy<FileEntry> { it.sizeBytes }.then(byName)
            SortField.DATE -> compareBy<FileEntry> { it.modifiedMillis }.then(byName)
            SortField.TYPE -> compareBy<FileEntry> { FileTypes.extension(it.name) }.then(byName)
        }
        val directed = if (order.ascending) primary else primary.reversed()
        val foldersFirst = Comparator<FileEntry> { x, y -> (if (x.isDirectory) 0 else 1) - (if (y.isDirectory) 0 else 1) }
        return entries.sortedWith(foldersFirst.then(directed))
    }
}
