// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent

/** Teilen, Öffnen, Bearbeiten und „Als Hintergrund“ über Mediathek-Adressen mit Leserecht nur für die genannten Dateien. */
object GalleryIntents {
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun mimeOf(entry: MediaEntry): String = entry.mime.ifEmpty { if (entry.isVideo) "video/*" else "image/*" }

    fun share(context: Context, entries: List<MediaEntry>): Boolean {
        if (entries.isEmpty()) return false
        val uris = entries.map { GalleryRepository.uriOf(it) }
        val intent = if (entries.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeOf(entries[0])).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            val allImages = entries.none { it.isVideo }
            val allVideos = entries.all { it.isVideo }
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(if (allImages) "image/*" else if (allVideos) "video/*" else "*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        return start(context, Intent.createChooser(intent, null))
    }

    fun view(context: Context, entry: MediaEntry): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW).setDataAndType(GalleryRepository.uriOf(entry), mimeOf(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )

    /** Öffnet einen Editor einer anderen App. Die Bearbeitung ändert die Datei nur, wenn die andere App das tut. */
    fun edit(context: Context, entry: MediaEntry): Boolean = start(
        context,
        Intent(Intent.ACTION_EDIT).setDataAndType(GalleryRepository.uriOf(entry), mimeOf(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
    )

    /** „Verwenden als“ (Hintergrundbild, Kontaktbild …) über eine andere App. */
    fun useAs(context: Context, entry: MediaEntry): Boolean = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(GalleryRepository.uriOf(entry), mimeOf(entry))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ),
    )
}
