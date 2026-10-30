// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Ergebnis des Entpackens: Zielordner, Zahl der Dateien und der übersprungenen Einträge (unsichere Pfade, Fehler). */
data class UnzipResult(val folder: File, val files: Int, val skipped: Int)

/** Grenzen beim Entpacken, gegen Archivbomben. */
data class UnzipLimits(val maxEntries: Int = 100_000, val maxTotalBytes: Long = 16L * 1024 * 1024 * 1024)

class ZipEncryptedException : IOException("Verschlüsseltes Archiv")
class ZipLimitException : IOException("Archiv überschreitet die Grenzen")

/** ZIP packen und entpacken mit `java.util.zip`. Ohne Android-Klassen, auf der JVM testbar. */
object FileZip {
    private const val BUFFER = 64 * 1024

    /**
     * Packt [sources] in [target]. Die Namen im Archiv beginnen mit dem Namen der Quelle, Ordnerstrukturen bleiben
     * erhalten. Verknüpfungen entfallen, ebenso die Zieldatei selbst, falls sie in einer Quelle liegt. Geschrieben
     * wird in eine Teildatei, die erst bei Erfolg umbenannt wird.
     */
    fun create(
        sources: List<File>,
        target: File,
        progress: (FileProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Int {
        if (target.exists()) throw IOException("Ziel existiert")
        val part = File(target.parentFile, target.name + ".part")
        val total = sources.sumOf { runCatching { FileOps.treeSize(it, cancelled).bytes }.getOrDefault(0L) }
        var done = 0L
        var count = 0
        try {
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                zip.setLevel(6)
                val buffer = ByteArray(BUFFER)
                fun add(file: File, entryName: String) {
                    if (cancelled()) throw OperationCancelled()
                    if (FileOps.isLink(file) || file.path == part.path || file.path == target.path) return
                    if (file.isDirectory) {
                        val entry = ZipEntry("$entryName/")
                        entry.time = file.lastModified()
                        zip.putNextEntry(entry)
                        zip.closeEntry()
                        file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$entryName/${it.name}") }
                    } else {
                        val entry = ZipEntry(entryName)
                        entry.time = file.lastModified()
                        zip.putNextEntry(entry)
                        file.inputStream().use { input ->
                            while (true) {
                                if (cancelled()) throw OperationCancelled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                zip.write(buffer, 0, n)
                                done += n
                                progress(FileProgress(done, total, file.name))
                            }
                        }
                        zip.closeEntry()
                        count++
                    }
                }
                for (source in sources) add(source, source.name)
            }
            if (!part.renameTo(target)) throw IOException("Archiv nicht speicherbar")
            return count
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /**
     * Entpackt [zip] in einen neuen Ordner unter [targetDir], benannt nach dem Archiv. Einträge mit unsicheren
     * Pfaden (absolut, `..`, außerhalb des Zielordners) werden übersprungen. Bei Abbruch oder Fehler wird der
     * angefangene Ordner gelöscht.
     *
     * @throws ZipEncryptedException bei verschlüsselten Archiven
     * @throws ZipLimitException wenn Eintragszahl oder entpackte Größe die Grenzen überschreiten
     */
    fun extract(
        zip: File,
        targetDir: File,
        limits: UnzipLimits = UnzipLimits(),
        progress: (FileProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): UnzipResult {
        val folder = File(targetDir, FilePaths.uniqueName(targetDir, FilePaths.splitExtension(zip.name).first))
        val archive = openArchive(zip)
        try {
            if (!folder.mkdir()) throw IOException("Ordner nicht anlegbar")
            val total = archive.entries().asSequence().sumOf { if (it.size > 0) it.size else 0L }
            var done = 0L
            var files = 0
            var skipped = 0
            var entries = 0
            val buffer = ByteArray(BUFFER)
            for (entry in archive.entries()) {
                if (cancelled()) throw OperationCancelled()
                if (++entries > limits.maxEntries) throw ZipLimitException()
                val relative = safeEntryPath(entry.name)
                if (relative == null) {
                    skipped++
                    continue
                }
                val out = File(folder, relative)
                if (!FilePaths.isInside(folder, out)) {
                    skipped++
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                try {
                    archive.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            while (true) {
                                if (cancelled()) throw OperationCancelled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (done > limits.maxTotalBytes) throw ZipLimitException()
                                progress(FileProgress(done, total, out.name))
                            }
                        }
                    }
                } catch (e: ZipException) {
                    if (e.message?.contains("encrypted", ignoreCase = true) == true) throw ZipEncryptedException()
                    out.delete()
                    skipped++
                    continue
                }
                if (entry.time > 0) out.setLastModified(entry.time)
                files++
            }
            return UnzipResult(folder, files, skipped)
        } catch (e: IOException) {
            FileOps.deleteTree(folder)
            throw e
        } catch (e: OperationCancelled) {
            FileOps.deleteTree(folder)
            throw e
        } finally {
            archive.close()
        }
    }

    private fun openArchive(zip: File): ZipFile =
        try {
            ZipFile(zip, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // Ältere Archive mit Namen in einer Landeskodierung.
            ZipFile(zip, Charsets.ISO_8859_1)
        } catch (e: ZipException) {
            if (e.message?.contains("encrypted", ignoreCase = true) == true) throw ZipEncryptedException()
            throw e
        }

    /**
     * Bereinigter relativer Pfad eines Archiveintrags oder `null`, wenn er unsicher ist: absolut, mit Laufwerksbuchstaben,
     * mit `..`-Teilen oder leer. Rückwärtsschrägstriche gelten als Trenner.
     */
    fun safeEntryPath(raw: String): String? {
        val name = raw.replace('\\', '/')
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000')) return null
        if (name.length >= 2 && name[1] == ':') return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
