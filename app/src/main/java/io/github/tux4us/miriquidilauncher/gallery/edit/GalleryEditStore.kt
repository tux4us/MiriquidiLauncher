// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import io.github.tux4us.miriquidilauncher.gallery.GalleryImages
import io.github.tux4us.miriquidilauncher.gallery.GalleryRepository
import io.github.tux4us.miriquidilauncher.gallery.MediaEntry
import java.io.IOException

/**
 * Lesen und Schreiben für die Bildbearbeitung: Bearbeitete Bilder werden immer als Kopie in der Mediathek angelegt
 * (ab Android 10, ohne Systemdialog). Nur die Metadaten-Bearbeitung ändert die Originaldatei, und zwar erst nach
 * ausdrücklicher Zustimmung des Nutzers über den Systemdialog der Mediathek.
 */
object GalleryEditStore {
    /** Längste Kante des Bildes, das im Editor geladen wird. Größere Bilder werden dafür verkleinert. */
    const val MAX_EDIT_DIMENSION = 4096

    private const val JPEG_QUALITY = 95

    /** EXIF-Felder, die in eine bearbeitete Kopie übernommen werden (ohne Orientierung, Größe und Vorschaubild). */
    private val COPY_TAGS: List<String> = listOf(
        "DateTimeOriginal", "DateTimeDigitized", "Make", "Model", "ExposureTime", "FNumber", "ISOSpeedRatings",
        "FocalLength", "ImageDescription", "Artist", "Copyright",
    ) + MetadataLogic.GPS_TAGS

    /** Das Bild in voller (bis [MAX_EDIT_DIMENSION] begrenzter) Auflösung, nach EXIF gedreht; `null` bei Fehlern. */
    fun loadForEdit(context: Context, entry: MediaEntry): Bitmap? = try {
        GalleryImages.decodeUri(context, GalleryRepository.uriOf(entry), MAX_EDIT_DIMENSION)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Legt [bitmap] als neue Datei im Ordner [relativePath] an und gibt deren Adresse zurück. Mit [copyMetadata] werden
     * die Kamera-, Datums- und Standortangaben des Originals [entry] übernommen (nur bei JPEG). Bei Fehlern wird die
     * angefangene Datei entfernt.
     */
    @Throws(IOException::class)
    fun saveCopy(context: Context, entry: MediaEntry, bitmap: Bitmap, relativePath: String, copyMetadata: Boolean): Uri {
        val resolver = context.contentResolver
        val png = EditNaming.isPng(entry.mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EditNaming.copyName(entry.name, png))
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put("relative_path", relativePath)
            if (entry.takenMillis > 0) put("datetaken", entry.takenMillis)
            put("is_pending", 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Ziel nicht anlegbar")
        try {
            val out = resolver.openOutputStream(target) ?: throw IOException("Ziel nicht schreibbar")
            out.use {
                val ok = if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                if (!ok) throw IOException("Kodieren fehlgeschlagen")
            }
            if (copyMetadata && !png) copyExif(context, entry, target)
            resolver.update(target, ContentValues().apply { put("is_pending", 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return target
    }

    /** Übernimmt die Angaben in [COPY_TAGS] vom Original in die Kopie; Fehler dabei lassen die Kopie unberührt. */
    private fun copyExif(context: Context, source: MediaEntry, target: Uri) {
        try {
            val resolver = context.contentResolver
            val values = resolver.openInputStream(GalleryRepository.uriOf(source))?.use { input ->
                val exif = ExifInterface(input)
                COPY_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }
            }.orEmpty()
            if (values.isEmpty()) return
            resolver.openFileDescriptor(target, "rw")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                for ((tag, value) in values) exif.setAttribute(tag, value)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
            }
        } catch (e: IOException) {
            // Die Kopie bleibt ohne Metadaten bestehen.
        } catch (e: RuntimeException) {
            // dito
        }
    }

    /** Ob Metadaten dieser Datei geschrieben werden können: nur JPEG, nur mit bekanntem Pfad. */
    fun canEditMetadata(entry: MediaEntry): Boolean =
        !entry.isVideo && entry.mime.equals("image/jpeg", ignoreCase = true)

    /** Liest die bearbeitbaren Angaben; leer, wenn nichts lesbar ist. */
    fun readMetadata(context: Context, entry: MediaEntry): MetadataFields = try {
        context.contentResolver.openInputStream(GalleryRepository.uriOf(entry))?.use { input ->
            val exif = ExifInterface(input)
            MetadataFields(
                description = exif.getAttribute(MetadataLogic.TAG_DESCRIPTION).orEmpty(),
                artist = exif.getAttribute(MetadataLogic.TAG_ARTIST).orEmpty(),
                copyright = exif.getAttribute(MetadataLogic.TAG_COPYRIGHT).orEmpty(),
                dateOriginal = exif.getAttribute(MetadataLogic.TAG_DATE_ORIGINAL).orEmpty(),
                hasGps = exif.getAttribute("GPSLatitude") != null || exif.getAttribute("GPSLongitude") != null,
            )
        } ?: MetadataFields()
    } catch (e: IOException) {
        MetadataFields()
    } catch (e: SecurityException) {
        MetadataFields()
    } catch (e: RuntimeException) {
        MetadataFields()
    }

    /**
     * Schreibt [plan] in die Originaldatei. Setzt voraus, dass der Nutzer den Schreibzugriff bereits erlaubt hat.
     * Bei geändertem Aufnahmedatum wird auch die Sortierung der Mediathek aktualisiert. `false` bei Fehlern.
     */
    fun writeMetadata(context: Context, entry: MediaEntry, plan: MetadataPlan.Ok): Boolean {
        if (plan.isEmpty) return true
        val resolver = context.contentResolver
        val uri = GalleryRepository.uriOf(entry)
        return try {
            val ok = resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                for ((tag, value) in plan.set) exif.setAttribute(tag, value)
                if (plan.removeGps) for (tag in MetadataLogic.GPS_TAGS) exif.setAttribute(tag, null)
                exif.saveAttributes()
                true
            } ?: false
            if (ok && plan.takenMillis != null) {
                runCatching { resolver.update(uri, ContentValues().apply { put("datetaken", plan.takenMillis) }, null, null) }
            }
            ok
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }
}
