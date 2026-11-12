// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lesen der lokalen Musik über die Mediathek des Geräts (MediaStore). Kein Dateizugriff, kein Netzwerk. */
object MusicLibrary {
    /** Berechtigung, die die Musik-Scope anfragt: ab Android 13 die Audio-Medienberechtigung, davor der Speicherzugriff. */
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    fun trackUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Adresse des Album-Covers für die Benachrichtigung; fehlt das Cover, bleibt die Benachrichtigung ohne Bild. */
    fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    /** Alle Musikstücke (ohne Klingeltöne und Benachrichtigungstöne). Läuft auf dem Aufrufer-Thread. */
    fun load(context: Context): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
        )
        val out = ArrayList<Track>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val number = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                while (cursor.moveToNext()) {
                    out += Track(
                        id = cursor.getLong(id),
                        title = MusicLogic.cleanTag(cursor.getString(title)).ifEmpty { "–" },
                        artist = MusicLogic.cleanTag(cursor.getString(artist)),
                        album = MusicLogic.cleanTag(cursor.getString(album)),
                        albumId = cursor.getLong(albumId),
                        durationMs = cursor.getLong(duration).coerceAtLeast(0L),
                        trackNumber = cursor.getInt(number).coerceAtLeast(0),
                        year = cursor.getInt(year).coerceAtLeast(0),
                    )
                }
            }
        } catch (ignored: SecurityException) {
            // Berechtigung wurde entzogen: leere Liste, die Oberfläche zeigt die Freigabe-Karte.
        } catch (ignored: IllegalArgumentException) {
            // Unbekannte Spalte auf einem eigenwilligen System: leere Liste statt Absturz.
        }
        return out
    }

    private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()

    /**
     * Eingebettetes oder Album-Cover eines Liedes, höchstens [edgePx] Pixel an der längeren Kante. Ab Android 10 über
     * `loadThumbnail`, davor über die Album-Adresse. `null`, wenn kein Cover vorhanden ist.
     */
    suspend fun cover(context: Context, track: Track, edgePx: Int): Bitmap? {
        val key = "${track.id}@$edgePx"
        synchronized(covers) { covers.get(key) }?.let { return it }
        if (synchronized(missing) { key in missing }) return null
        val bitmap = withContext(Dispatchers.IO) { loadCover(context.applicationContext, track, edgePx) }
        if (bitmap == null) {
            synchronized(missing) {
                if (missing.size > 2000) missing.clear()
                missing.add(key)
            }
            return null
        }
        synchronized(covers) { covers.put(key, bitmap) }
        return bitmap
    }

    private fun loadCover(context: Context, track: Track, edgePx: Int): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return context.contentResolver.loadThumbnail(trackUri(track.id), Size(edgePx, edgePx), null)
            }
            if (track.albumId <= 0) return null
            val uri = albumArtUri(track.albumId)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= edgePx && bounds.outHeight / (sample * 2) >= edgePx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (ignored: IOException) {
            return null
        } catch (ignored: SecurityException) {
            return null
        } catch (ignored: IllegalArgumentException) {
            return null
        } catch (ignored: UnsupportedOperationException) {
            return null
        }
    }

    /** Farbpalette aus einem Cover: Das Bild wird auf 24 × 24 Pixel verkleinert und ausgewertet. */
    fun palette(bitmap: Bitmap): CoverPalette {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        if (small !== bitmap) small.recycle()
        return CoverColors.palette(pixels)
    }
}
