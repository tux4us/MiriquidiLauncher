// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, PDF, APK, OTHER }

/** Ein Eintrag der Ordneransicht. [path] ist der absolute Pfad. */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val hidden: Boolean,
    val kind: FileKind,
) {
    val file: File get() = File(path)
}

enum class SortField { NAME, SIZE, DATE, TYPE }

data class SortOrder(val field: SortField = SortField.NAME, val ascending: Boolean = true)

/** Fortschritt eines Vorgangs. [totalBytes] ist 0, solange die Größe noch ermittelt wird. */
data class FileProgress(val doneBytes: Long, val totalBytes: Long, val currentName: String)

/** Ergebnis eines Vorgangs auf mehreren Einträgen: Zahl der erledigten Einträge und die Namen der fehlgeschlagenen. */
data class OpResult(val done: Int, val failed: List<String>, val skipped: Int = 0) {
    val ok: Boolean get() = failed.isEmpty()
}

/** Wird geworfen, wenn der Nutzer einen Vorgang abbricht. Angefangene Zieldateien sind dann bereits gelöscht. */
class OperationCancelled : Exception("Abgebrochen")

/** Größe eines Ordnerbaums. */
data class SizeInfo(val bytes: Long, val files: Int, val folders: Int)

/** Verkürzter Pfadteil für die Pfadleiste. */
data class Crumb(val name: String, val path: String)
