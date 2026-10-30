// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Miniaturen für das Raster: Zwischenspeicher im Arbeitsspeicher, höchstens vier gleichzeitige Ladevorgänge. */
object GalleryThumbnails {
    private const val SIZE_PX = 256
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(4)

    suspend fun load(context: Context, entry: MediaEntry): Bitmap? {
        val key = "m:${entry.key}"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { gate.withPermit { create(context, entry) } } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** Miniatur einer Datei außerhalb der Mediathek (Papierkorb). */
    suspend fun loadFile(file: File, video: Boolean): Bitmap? {
        val key = "f:${file.absolutePath}:${file.lastModified()}"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            gate.withPermit {
                try {
                    if (video) videoThumb(file) else GalleryImages.decodeFile(file, SIZE_PX)
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun create(context: Context, entry: MediaEntry): Bitmap? = try {
        val uri = GalleryRepository.uriOf(entry)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> context.contentResolver.loadThumbnail(uri, Size(SIZE_PX, SIZE_PX), null)
            entry.isVideo -> MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, entry.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else -> MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, entry.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun videoThumb(file: File): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(SIZE_PX, SIZE_PX), null)
        } else {
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
        }
}

/** Laden eines Bildes für die Vollansicht, auf die Bildschirmgröße verkleinert und nach EXIF gedreht. */
object GalleryImages {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** `null`, wenn das Bild nicht gelesen oder nicht dekodiert werden kann. */
    suspend fun loadFull(context: Context, entry: MediaEntry, maxDimension: Int): Bitmap? {
        val key = "${entry.key}:$maxDimension"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeUri(context, GalleryRepository.uriOf(entry), maxDimension)
            } catch (e: IOException) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    internal fun decodeFile(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return rotate(bitmap, orientation)
    }

    private fun decodeUri(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return rotate(bitmap, orientation)
    }

    /** Kleinste Zweierpotenz, bei der die längere Seite höchstens etwa doppelt so groß wie [maxDimension] bleibt. */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    /** EXIF-Angaben einer Datei für die Infoansicht: Kamera, Belichtung usw. Leer, wenn nichts lesbar ist. */
    fun exifSummary(path: String): List<Pair<String, String>> {
        if (path.isEmpty()) return emptyList()
        return try {
            val exif = ExifInterface(path)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
            val camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                if (it in 0.0001..0.5) "1/${Math.round(1 / it)} s" else "%.1f s".format(it)
            }
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { "f/%.1f".format(it) }
            val iso = (exif.getAttribute("ISOSpeedRatings") ?: exif.getAttribute("PhotographicSensitivity"))
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let(::fraction)?.let { "%.0f mm".format(it) }
            listOfNotNull(
                camera.takeIf { it.isNotEmpty() }?.let { "camera" to it },
                exposure?.let { "exposure" to it },
                aperture?.let { "aperture" to it },
                iso?.let { "iso" to "ISO $it" },
                focal?.let { "focal" to it },
            )
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun fraction(text: String): Double? {
        val parts = text.split('/')
        return if (parts.size == 2) {
            val n = parts[0].toDoubleOrNull()
            val d = parts[1].toDoubleOrNull()
            if (n != null && d != null && d != 0.0) n / d else null
        } else {
            text.toDoubleOrNull()
        }
    }
}
