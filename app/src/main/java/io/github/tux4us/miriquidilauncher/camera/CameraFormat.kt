// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Darstellung von Zahlen und Dateinamen der Kamera-Scope; technisch, daher unabhängig von der Systemsprache. */
object CameraFormat {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    /** Aufnahmedauer als `mm:ss`, ab einer Stunde `h:mm:ss`. Negative Werte gelten als 0. */
    fun duration(nanos: Long): String {
        val total = (nanos.coerceAtLeast(0L) / 1_000_000_000L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** Belichtungszeit: unter einer Sekunde als Bruch („1/250“), sonst in Sekunden („2 s“, „1.5 s“). */
    fun shutter(nanos: Long): String {
        if (nanos <= 0L) return "–"
        if (nanos >= 1_000_000_000L) {
            val seconds = nanos / 1e9
            val whole = seconds.roundToLong()
            return if (kotlin.math.abs(seconds - whole) < 0.05) "$whole s" else String.format(Locale.ROOT, "%.1f s", seconds)
        }
        val denominator = (1e9 / nanos).roundToInt().coerceAtLeast(2)
        return "1/$denominator"
    }

    fun iso(value: Int): String = "ISO $value"

    /** Zoomfaktor, z. B. „2×“ oder „2.5×“. */
    fun zoom(ratio: Float): String {
        val tenths = (ratio * 10f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}×" else String.format(Locale.ROOT, "%.1f×", tenths / 10f)
    }

    /** Fokusabstand: „∞“ für unendlich, sonst Meter („1.5 m“) bzw. Zentimeter („40 cm“). */
    fun focus(diopters: Float): String {
        if (diopters < 0.05f) return "∞"
        val meters = 1f / diopters
        return if (meters >= 1f) {
            String.format(Locale.ROOT, "%.1f m", meters)
        } else {
            "${(meters * 100f).roundToInt()} cm"
        }
    }

    /** Dateiname (ohne Endung) eines Fotos, z. B. `IMG_20261021_143305`. */
    fun photoName(epochMillis: Long, zone: ZoneId): String = "IMG_" + stamp(epochMillis, zone)

    /** Dateiname (ohne Endung) eines Videos, z. B. `VID_20261021_143305`. */
    fun videoName(epochMillis: Long, zone: ZoneId): String = "VID_" + stamp(epochMillis, zone)

    private fun stamp(epochMillis: Long, zone: ZoneId): String =
        STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    const val MIME_JPEG = "image/jpeg"
    const val MIME_DNG = "image/x-adobe-dng"
    const val MIME_MP4 = "video/mp4"

    fun mimeType(format: PhotoFormat): String = if (format == PhotoFormat.Raw) MIME_DNG else MIME_JPEG

    /** Ordner unterhalb von `Pictures` bzw. `Movies` (ab Android 10 per `RELATIVE_PATH`). */
    const val FOLDER = "MiriquidiLauncher"
}
