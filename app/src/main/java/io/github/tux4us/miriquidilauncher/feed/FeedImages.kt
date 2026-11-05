// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Lädt Vorschaubilder der Cards direkt von den Servern der Feed-Quellen (nur HTTPS). Bilder werden nur im
 * Arbeitsspeicher gehalten und beim Laden auf Vorschaugröße verkleinert. Der Aufruf geschieht nur, wenn
 * "Bilder laden" eingeschaltet ist; es werden keine Cookies oder Zugangsdaten gesendet.
 */
object FeedImages {
    private const val MAX_BYTES = 4 * 1024 * 1024
    const val DEFAULT_EDGE_PX = 320
    private const val TIMEOUT_MS = 10_000

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = LinkedHashSet<String>()
    private val limit = Semaphore(4)

    /**
     * Lädt das Bild unter [url] und verkleinert es so, dass die längere Kante mindestens [edgePx] Pixel behält (so wenig
     * Speicher wie möglich). Kleine Vorschaubilder in Listen brauchen weniger als der Standardwert.
     */
    suspend fun load(url: String, edgePx: Int = DEFAULT_EDGE_PX): ImageBitmap? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val key = if (edgePx == DEFAULT_EDGE_PX) url else "$url@$edgePx"
        synchronized(cache) { cache.get(key) }?.let { return it.asImageBitmap() }
        if (synchronized(failed) { url in failed }) return null
        val bitmap = limit.withPermit { withContext(Dispatchers.IO) { download(url, edgePx) } }
        if (bitmap == null) {
            synchronized(failed) {
                if (failed.size >= 200) failed.remove(failed.first())
                failed.add(url)
            }
            return null
        }
        synchronized(cache) { cache.put(key, bitmap) }
        return bitmap.asImageBitmap()
    }

    private fun download(url: String, edgePx: Int): Bitmap? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (ignored: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MiriquidiLauncher")
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode !in 200..299) return null
            val data = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            decode(data, edgePx)
        } catch (ignored: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(data: ByteArray, edgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= edgePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }
}
