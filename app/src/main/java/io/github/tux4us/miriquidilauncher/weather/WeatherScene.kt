// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import kotlin.math.abs
import kotlin.math.sin

/**
 * Szenenbeschreibung für den animierten Wetter-Hintergrund (reine Logik, ohne Android-Bezug, JVM-getestet).
 *
 * Eingabe sind nur Werte, die der Wetterabruf ohnehin liefert: WMO-Wettercode, Tag/Nacht, Bewölkung (optional),
 * Windgeschwindigkeit (km/h) und Windrichtung (Grad). Die Zeichnung übernimmt `ui/WeatherBackgroundView`.
 * Herkunft: aus der HundeWildnis-App übernommen und getrennt in Logik und Darstellung.
 */
object WeatherScene {

    private const val BLACK = 0xFF000000.toInt()

    class Scene(
        val skyTop: Int,
        val skyBottom: Int,
        val cloudFrac: Float,     // 0..1 Bewölkungsgrad
        val cloudTint: Int,
        val fog: Float,           // 0..1
        val rain: Float,          // 0..1 Intensität
        val snow: Float,          // 0..1 Intensität
        val sun: Boolean,
        val moon: Boolean,
        val stars: Float,         // 0..1 Sichtbarkeit
        val lightning: Boolean,
        val slant: Float,         // horizontale Verschiebung je vertikaler Einheit (Niederschlag)
        val drift: Float          // Wolkentempo mit Vorzeichen (Bildbreiten pro Sekunde)
    )

    enum class Kind { CLEAR, CLOUD, FOG, RAIN, SNOW, STORM }


    fun build(code: Int, isDay: Boolean, cloudCover: Int?, windKmh: Double, windDir: Double?): Scene {
        var kind = Kind.CLEAR
        var rain = 0f
        var snow = 0f
        var fog = 0f
        var lightning = false
        // Bewölkung: bei Niederschlag/Nebel fest, sonst nach Messwert (Fallback: nach Code)
        var cloudFrac = when (code) {
            0 -> 0.05f
            1 -> 0.2f
            2 -> 0.5f
            3 -> 1f
            else -> 0.9f
        }
        when (code) {
            0, 1, 2, 3 -> {
                if (cloudCover != null && cloudCover >= 0) cloudFrac = (cloudCover / 100f).coerceIn(0f, 1f)
                kind = if (cloudFrac >= 0.5f) Kind.CLOUD else Kind.CLEAR
            }
            45, 48 -> { kind = Kind.FOG; fog = 1f; cloudFrac = 0.5f }
            51 -> { kind = Kind.RAIN; rain = 0.2f }
            53 -> { kind = Kind.RAIN; rain = 0.3f }
            55, 56 -> { kind = Kind.RAIN; rain = 0.4f }
            57 -> { kind = Kind.RAIN; rain = 0.45f }
            61 -> { kind = Kind.RAIN; rain = 0.5f }
            63, 66 -> { kind = Kind.RAIN; rain = 0.7f }
            65, 67 -> { kind = Kind.RAIN; rain = 1f }
            80 -> { kind = Kind.RAIN; rain = 0.45f; cloudFrac = 0.8f }
            81 -> { kind = Kind.RAIN; rain = 0.7f; cloudFrac = 0.85f }
            82 -> { kind = Kind.RAIN; rain = 1f }
            71, 77 -> { kind = Kind.SNOW; snow = 0.35f }
            73, 85 -> { kind = Kind.SNOW; snow = 0.6f }
            75, 86 -> { kind = Kind.SNOW; snow = 1f }
            95, 96, 99 -> { kind = Kind.STORM; rain = 0.9f; lightning = true; cloudFrac = 1f }
            else -> {
                if (cloudCover != null && cloudCover >= 0) cloudFrac = (cloudCover / 100f).coerceIn(0f, 1f)
                kind = if (cloudFrac >= 0.5f) Kind.CLOUD else Kind.CLEAR
            }
        }

        // Himmelsfarben (bewusst gedämpft und dunkel, passend zum Glas-Design)
        val clearDay = 0xFF17304D.toInt() to 0xFF2D4C70.toInt()
        val cloudDay = 0xFF1F2B39.toInt() to 0xFF3A4857.toInt()
        var (top, bottom) = when (kind) {
            Kind.CLEAR, Kind.CLOUD -> {
                val t = (cloudFrac * 1.2f).coerceIn(0f, 1f)
                lerpColor(clearDay.first, cloudDay.first, t) to lerpColor(clearDay.second, cloudDay.second, t)
            }
            Kind.FOG -> 0xFF252E38.toInt() to 0xFF454F59.toInt()
            Kind.RAIN -> 0xFF17202B.toInt() to 0xFF2E3A48.toInt()
            Kind.SNOW -> 0xFF202B39.toInt() to 0xFF3E4C5C.toInt()
            Kind.STORM -> 0xFF10141D.toInt() to 0xFF252E3B.toInt()
        }
        if (!isDay) {
            if (kind == Kind.CLEAR || kind == Kind.CLOUD) {
                val t = (cloudFrac * 1.2f).coerceIn(0f, 1f)
                val nightClear = 0xFF070B16.toInt() to 0xFF16233F.toInt()
                val nightCloud = lerpColor(cloudDay.first, 0xFF03060B.toInt(), 0.5f) to
                        lerpColor(cloudDay.second, 0xFF03060B.toInt(), 0.5f)
                top = lerpColor(nightClear.first, nightCloud.first, t)
                bottom = lerpColor(nightClear.second, nightCloud.second, t)
            } else {
                top = lerpColor(top, 0xFF03060B.toInt(), 0.5f)
                bottom = lerpColor(bottom, 0xFF03060B.toInt(), 0.5f)
            }
        }

        var tint = when (kind) {
            Kind.CLEAR, Kind.CLOUD -> 0xFFAFC0D4.toInt()
            Kind.FOG -> 0xFFB4BEC8.toInt()
            Kind.RAIN -> 0xFF7C8A9C.toInt()
            Kind.SNOW -> 0xFFC4CFDC.toInt()
            Kind.STORM -> 0xFF5E6978.toInt()
        }
        if (!isDay) tint = lerpColor(tint, BLACK, 0.45f)

        // Wind: Neigung des Niederschlags und Wolkenrichtung. Richtung = Herkunft, Bewegung zeigt in die Gegenrichtung.
        val dirRad = Math.toRadians(windDir ?: 270.0)
        var sign = (-sin(dirRad)).toFloat()
        if (abs(sign) < 0.15f) sign = if (sign < 0f) -0.15f else 0.15f
        val windFrac = (windKmh / 50.0).coerceIn(0.0, 1.0).toFloat()
        val slant = sign * windFrac * 0.55f
        val drift = sign * (0.008f + 0.03f * windFrac)

        val clearish = cloudFrac < 0.6f && kind == Kind.CLEAR
        return Scene(
            skyTop = top, skyBottom = bottom,
            cloudFrac = cloudFrac, cloudTint = tint,
            fog = fog, rain = rain, snow = snow,
            sun = isDay && clearish,
            moon = !isDay && clearish,
            stars = if (!isDay) (1f - cloudFrac).coerceIn(0f, 1f) else 0f,
            lightning = lightning,
            slant = slant, drift = drift
        )
    }


    /** Mischt zwei ARGB-Farben; [t] von 0 (nur [a]) bis 1 (nur [b]). */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * k).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
