// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sonnenstand, Tageslicht und Schatten; alles offline berechnet (vereinfachte Formeln nach Spencer 1971). */
object SunMath {

    data class SolarAngles(val elevationDeg: Double, val azimuthDeg: Double)

    /**
     * Sonnenhöhe und Azimut (0° = Nord, 90° = Ost) für die Ortszeit [localTime] am Standort mit dem
     * Zeitzonenversatz [utcOffsetSeconds].
     */
    fun solarAngles(latDeg: Double, lonDeg: Double, localTime: LocalDateTime, utcOffsetSeconds: Int): SolarAngles {
        val b = Math.toRadians(360.0 / 365.0 * (localTime.dayOfYear - 81))
        val decRad = Math.toRadians(23.45 * sin(b))
        val equationOfTimeMin = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val tzHours = utcOffsetSeconds / 3600.0
        val localMin = localTime.hour * 60.0 + localTime.minute
        val trueSolarTimeH = (localMin + 4.0 * (lonDeg - tzHours * 15.0) + equationOfTimeMin) / 60.0
        val hourAngleDeg = (trueSolarTimeH - 12.0) * 15.0
        val ha = Math.toRadians(hourAngleDeg)
        val lat = Math.toRadians(latDeg)
        val sinEl = sin(lat) * sin(decRad) + cos(lat) * cos(decRad) * cos(ha)
        val elevation = Math.toDegrees(asin(sinEl.coerceIn(-1.0, 1.0)))
        val el = Math.toRadians(elevation)
        val cosAz = (sin(decRad) - sin(lat) * sin(el)) / (cos(lat) * cos(el))
        val azBase = Math.toDegrees(acos(cosAz.coerceIn(-1.0, 1.0)))
        val azimuth = if (hourAngleDeg >= 0) 360.0 - azBase else azBase
        return SolarAngles(elevation, azimuth)
    }

    /** Azimut des Sonnenaufgangs in Grad (0° = Nord). Der Sonnenuntergang liegt bei 360° minus diesem Wert. */
    fun sunriseAzimuth(latDeg: Double, date: LocalDate): Double {
        val b = Math.toRadians(360.0 / 365.0 * (date.dayOfYear - 81))
        val declinationDeg = 23.45 * sin(b)
        val cosAz = (sin(Math.toRadians(declinationDeg)) / cos(Math.toRadians(latDeg))).coerceIn(-1.0, 1.0)
        // acos liefert bereits den Winkel von Nord: unter 90° im Sommer (Aufgang nördlich von Ost), darüber im Winter.
        return Math.toDegrees(acos(cosAz))
    }

    /** Index 0–15 in der 16-teiligen Windrose (N, NNO, NO, ONO, O, ...). */
    fun compass16Index(azimuthDeg: Double): Int {
        val n = ((azimuthDeg % 360.0) + 360.0) % 360.0
        return ((n + 11.25) / 22.5).toInt() % 16
    }

    /** Stufe der Sonnenhöhe: 0 unter dem Horizont, 1 sehr flach (<10°), 2 niedrig (<30°), 3 mittel (<50°), 4 hoch. */
    fun elevationLevel(elevationDeg: Double): Int = when {
        elevationDeg < 0 -> 0
        elevationDeg < 10 -> 1
        elevationDeg < 30 -> 2
        elevationDeg < 50 -> 3
        else -> 4
    }

    /** Schattenlänge in Metern für einen Gegenstand der Höhe [heightM]; `null`, wenn die Sonne unter dem Horizont steht. */
    fun shadowLength(heightM: Double, elevationDeg: Double): Double? =
        if (elevationDeg <= 0.0) null else heightM / tan(Math.toRadians(elevationDeg))

    data class Daylight(
        val duration: Duration,
        val dawn: LocalDateTime,
        val dusk: LocalDateTime,
        /** Differenz zur Tageslänge des Vortags in Minuten; `null`, wenn kein Vortag vorliegt. */
        val trendMinutes: Long?,
        val goldenMorningStart: LocalDateTime,
        val goldenMorningEnd: LocalDateTime,
        val goldenEveningStart: LocalDateTime,
        val goldenEveningEnd: LocalDateTime,
    )

    private const val CIVIL_TWILIGHT_MIN = 35L

    fun daylight(sunrise: LocalDateTime, sunset: LocalDateTime, previous: Pair<LocalDateTime, LocalDateTime>?): Daylight {
        val duration = Duration.between(sunrise, sunset)
        val trend = previous?.let { duration.toMinutes() - Duration.between(it.first, it.second).toMinutes() }
        return Daylight(
            duration = duration,
            dawn = sunrise.minusMinutes(CIVIL_TWILIGHT_MIN),
            dusk = sunset.plusMinutes(CIVIL_TWILIGHT_MIN),
            trendMinutes = trend,
            goldenMorningStart = sunrise,
            goldenMorningEnd = sunrise.plusHours(1),
            goldenEveningStart = sunset.minusHours(1),
            goldenEveningEnd = sunset,
        )
    }

    /** Wie weit Aufgang/Untergang vom Ost- bzw. Westpunkt abweichen; Hilfsfunktion für Anzeige und Tests. */
    fun offsetFromEastWest(azimuth: Double, east: Boolean): Double = abs(azimuth - if (east) 90.0 else 270.0)
}
