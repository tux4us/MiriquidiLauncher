// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.time.ZoneId

/**
 * Zugriff auf die eigenen Aufnahmen: Ziel in der Mediathek, Miniatur der letzten Aufnahme und Öffnen in der
 * System-Galerie. Es wird nur gelesen, was die App selbst aufgenommen hat; eine eigene Galerie gibt es nicht.
 */
internal object CameraMedia {

    private const val THUMBNAIL_PX = 192

    /** Eintrag für ein neues Foto. Ab Android 10 im Ordner `Pictures/MiriquidiLauncher`, davor im Ordner `Pictures`. */
    fun photoValues(format: PhotoFormat, now: Long = System.currentTimeMillis()): ContentValues {
        val extension = if (format == PhotoFormat.Raw) "dng" else "jpg"
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CameraFormat.photoName(now, ZoneId.systemDefault()) + "." + extension)
            put(MediaStore.MediaColumns.MIME_TYPE, CameraFormat.mimeType(format))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/" + CameraFormat.FOLDER)
            }
        }
    }

    /** Eintrag für ein neues Video. Ab Android 10 im Ordner `Movies/MiriquidiLauncher`, davor im Ordner `Movies`. */
    fun videoValues(now: Long = System.currentTimeMillis()): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CameraFormat.videoName(now, ZoneId.systemDefault()) + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, CameraFormat.MIME_MP4)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/" + CameraFormat.FOLDER)
            }
        }

    /** Kleine Vorschau der Datei, oder `null`, wenn sie nicht mehr existiert oder nicht lesbar ist. */
    fun thumbnail(context: Context, uriText: String, isVideo: Boolean): Bitmap? {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
            } else if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 8 })
                }
            }
        }.getOrNull()
    }

    /** Öffnet die Datei in der System-Galerie. `false`, wenn keine App sie öffnen kann. */
    fun open(context: Context, uriText: String): Boolean {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return false
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
