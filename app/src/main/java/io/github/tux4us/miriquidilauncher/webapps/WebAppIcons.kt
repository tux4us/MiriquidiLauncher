// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * Eigene Symbole der Webapps: quadratisch zugeschnitten, [SIZE] × [SIZE] Pixel, als PNG im privaten Speicher der App
 * (`files/webapps/<Kennung>.png`). Die Symbole werden nicht gesendet und stehen nicht im Backup.
 */
class WebAppIcons(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    /** Liest das Bild hinter [uri] und liefert das zugeschnittene Symbol oder `null`, wenn es nicht lesbar oder zu groß ist. */
    fun render(uri: Uri): Bitmap? {
        return try {
            val resolver = appContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Mit inJustDecodeBounds liefert decodeStream immer null; die Maße stehen danach in `bounds`.
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
                true
            } ?: return null
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
            var sample = 1
            while (width / (sample * 2) >= SIZE && height / (sample * 2) >= SIZE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val side = min(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Speichert [bitmap] als Symbol der Webapp [id]; `false` bei ungültiger Kennung oder Schreibfehler. */
    fun save(id: String, bitmap: Bitmap): Boolean {
        if (!WebAppLogic.isSafeId(id)) return false
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val target = File(dir, "$id.png")
            val temp = File(dir, "$id.png.tmp")
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("compress")
            }
            if (target.exists() && !target.delete()) throw IOException("delete")
            if (!temp.renameTo(target)) throw IOException("rename")
            true
        } catch (e: IOException) {
            false
        }
    }

    fun load(id: String): Bitmap? {
        if (!WebAppLogic.isSafeId(id)) return null
        val file = File(dir, "$id.png")
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    fun delete(id: String) {
        if (WebAppLogic.isSafeId(id)) File(dir, "$id.png").delete()
    }

    /** Kennungen, zu denen eine Symboldatei vorliegt. */
    fun existing(): Set<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".png") }
            .map { it.name.removeSuffix(".png") }
            .filter { WebAppLogic.isSafeId(it) }
            .toSet()

    /** Löscht alle Symboldateien, deren Kennung nicht in [ids] steht (auch übrig gebliebene temporäre Dateien). */
    fun retain(ids: Set<String>) {
        dir.listFiles().orEmpty().forEach { file ->
            val id = file.name.removeSuffix(".png").removeSuffix(".png.tmp")
            if (id !in ids || file.name.endsWith(".tmp")) file.delete()
        }
    }

    companion object {
        const val SIZE = 128
        private const val DIR_NAME = "webapps"
        private const val MAX_PIXELS = 120_000_000L
    }
}
