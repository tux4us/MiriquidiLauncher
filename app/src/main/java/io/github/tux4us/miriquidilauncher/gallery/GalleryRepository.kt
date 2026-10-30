// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.tux4us.miriquidilauncher.files.FileVolumes
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Lesen der Mediathek (MediaStore) und Zugriffsprüfung. Es gibt keinen Netzwerkzugriff. */
object GalleryRepository {
    /** Berechtigungen, die die Galerie anfragt. Ab Android 14 darf der Nutzer auch nur eine Auswahl freigeben. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Darf die Galerie die Mediathek lesen? Mit „Zugriff auf alle Dateien“ ist das ohne weitere Berechtigung der Fall;
     * sonst zählt die Medienberechtigung (ab Android 14 auch die eingeschränkte Auswahl).
     */
    fun hasMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && FileVolumes.hasAllFilesAccess(context)) return true
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(context, Manifest.permission.READ_MEDIA_IMAGES) || granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                    granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 ->
                granted(context, Manifest.permission.READ_MEDIA_IMAGES) || granted(context, Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun uriOf(entry: MediaEntry): Uri =
        ContentUris.withAppendedId(
            if (entry.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            entry.id,
        )

    /** Alle Fotos und Videos der Mediathek. Läuft auf dem Aufrufer-Thread; bitte nicht auf dem Hauptthread. */
    fun load(context: Context): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false, out)
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, out)
        return out
    }

    @Suppress("DEPRECATION")
    private fun query(context: Context, uri: Uri, video: Boolean, out: MutableList<MediaEntry>) {
        val columns = arrayListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            "bucket_id",
            "bucket_display_name",
            "datetaken",
        )
        if (video) columns.add("duration")
        val cursor = try {
            context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)
        } catch (e: SecurityException) {
            null
        } ?: return
        cursor.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dataI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val wI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val hI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val mimeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val addedI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucketI = c.getColumnIndexOrThrow("bucket_id")
            val bucketNameI = c.getColumnIndexOrThrow("bucket_display_name")
            val takenI = c.getColumnIndexOrThrow("datetaken")
            val durI = if (video) c.getColumnIndexOrThrow("duration") else -1
            while (c.moveToNext()) {
                val path = c.getString(dataI).orEmpty()
                if (path.contains("/.MiriquidiTrash/")) continue
                val taken = c.getLong(takenI).takeIf { it > 0 }
                    ?: c.getLong(modI).takeIf { it > 0 }?.times(1000)
                    ?: c.getLong(addedI) * 1000
                out.add(
                    MediaEntry(
                        id = c.getLong(idI),
                        isVideo = video,
                        name = c.getString(nameI).orEmpty().ifEmpty { path.substringAfterLast('/') },
                        path = path,
                        bucketId = c.getLong(bucketI),
                        bucketName = c.getString(bucketNameI).orEmpty(),
                        takenMillis = taken,
                        sizeBytes = c.getLong(sizeI),
                        width = c.getInt(wI),
                        height = c.getInt(hI),
                        durationMs = if (durI >= 0) c.getLong(durI) else 0L,
                        mime = c.getString(mimeI).orEmpty(),
                    ),
                )
            }
        }
    }

    /** Meldet geänderte Dateien dem Medienindex und wartet höchstens 5 s auf dessen Abschluss. */
    suspend fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val distinct = paths.distinct().toTypedArray()
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val left = AtomicInteger(distinct.size)
                MediaScannerConnection.scanFile(context.applicationContext, distinct, null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }
}
