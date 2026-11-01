// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.IOException

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

    /** Darf die Galerie die Mediathek lesen? Es zählt die Medienberechtigung (ab Android 14 auch die eingeschränkte Auswahl). */
    fun hasMediaAccess(context: Context): Boolean {
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

    /**
     * Änderungen an fremden Medien (Papierkorb, Löschen, Umbenennen, Verschieben) bieten Android ab Version 11 über
     * Systemdialoge an; ohne diese Version sind sie nicht möglich.
     */
    val canManage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Alle Fotos und Videos der Mediathek, mit [trashed] nur die im Papierkorb der Mediathek (ab Android 11).
     * Läuft auf dem Aufrufer-Thread; bitte nicht auf dem Hauptthread.
     */
    fun load(context: Context, trashed: Boolean = false): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        if (trashed && !canManage) return out
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false, trashed, out)
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, trashed, out)
        return out
    }

    private fun urisOf(entries: List<MediaEntry>): List<Uri> = entries.map { uriOf(it) }

    /** Systemdialog zum Verschieben in den Papierkorb ([trash]) bzw. zum Wiederherstellen. Ab Android 11. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun trashRequest(context: Context, entries: List<MediaEntry>, trash: Boolean): IntentSender =
        MediaStore.createTrashRequest(context.contentResolver, urisOf(entries), trash).intentSender

    /** Systemdialog zum endgültigen Löschen. Ab Android 11. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(context: Context, entries: List<MediaEntry>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, urisOf(entries)).intentSender

    /** Systemdialog für Schreibzugriff (Umbenennen, Verschieben). Ab Android 11. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun writeRequest(context: Context, entries: List<MediaEntry>): IntentSender =
        MediaStore.createWriteRequest(context.contentResolver, urisOf(entries)).intentSender

    /** Benennt [entry] um; wirkt erst, nachdem der Nutzer den Schreibzugriff erlaubt hat. */
    fun rename(context: Context, entry: MediaEntry, newName: String): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        return try {
            context.contentResolver.update(uriOf(entry), values, null, null) > 0
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Verschiebt [entry] in den Ordner [relativePath] (`Pictures/Urlaub/`); wirkt erst nach erlaubtem Schreibzugriff. */
    fun moveToFolder(context: Context, entry: MediaEntry, relativePath: String): Boolean {
        val values = ContentValues().apply { put("relative_path", relativePath) }
        return try {
            context.contentResolver.update(uriOf(entry), values, null, null) > 0
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Legt eine Kopie von [entry] im Ordner [relativePath] an (ab Android 10). Die Kopie gehört der App und braucht keinen
     * Systemdialog. [onBytes] meldet die gelesenen Bytes; [cancelled] bricht ab und entfernt die angefangene Kopie.
     */
    fun copyToFolder(context: Context, entry: MediaEntry, relativePath: String, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val resolver = context.contentResolver
        val collection = if (entry.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            if (entry.mime.isNotEmpty()) put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put("relative_path", relativePath)
            put("is_pending", 1)
        }
        val target = resolver.insert(collection, values) ?: throw IOException("Ziel nicht anlegbar")
        try {
            val input = resolver.openInputStream(uriOf(entry)) ?: throw IOException("Quelle nicht lesbar")
            val output = resolver.openOutputStream(target) ?: throw IOException("Ziel nicht schreibbar")
            input.use { src ->
                output.use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("Abgebrochen")
                        val n = src.read(buffer)
                        if (n < 0) break
                        dst.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            val done = ContentValues().apply { put("is_pending", 0) }
            resolver.update(target, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    @Suppress("DEPRECATION")
    private fun query(context: Context, uri: Uri, video: Boolean, trashed: Boolean, out: MutableList<MediaEntry>) {
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
        if (trashed) columns.add("date_expires")
        val cursor = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Der Papierkorb der Mediathek bleibt ohne diese Angabe verborgen.
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                }
                context.contentResolver.query(uri, columns.toTypedArray(), args, null)
            } else {
                context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)
            }
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
            val expI = if (trashed) c.getColumnIndexOrThrow("date_expires") else -1
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
                        expiresMillis = if (expI >= 0) c.getLong(expI) * 1000 else 0L,
                    ),
                )
            }
        }
    }
}
