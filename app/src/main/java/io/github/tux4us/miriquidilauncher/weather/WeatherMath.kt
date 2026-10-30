// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.Instant
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/** Reine Rechenfunktionen für den Wetterscreen, ohne Android-Abhängigkeiten. */
object WeatherMath {

    /** Gefühlte Temperatur durch Wind (Windchill); gilt unter 10 °C und ab 4,8 km/h, sonst die Lufttemperatur. */
    fun windchill(tempC: Double, windKmh: Double): Double {
        if (tempC >= 10.0 || windKmh < 4.8) return tempC
        val v = windKmh.pow(0.16)
        return 13.12 + 0.6215 * tempC - 11.37 * v + 0.3965 * tempC * v
    }

    /** Windstärke nach Beaufort (0–12) aus km/h. */
    fun beaufort(windKmh: Double): Int = when {
        windKmh < 1 -> 0
        windKmh < 6 -> 1
        windKmh < 12 -> 2
        windKmh < 20 -> 3
        windKmh < 29 -> 4
        windKmh < 39 -> 5
        windKmh < 50 -> 6
        windKmh < 62 -> 7
        windKmh < 75 -> 8
        windKmh < 89 -> 9
        windKmh < 103 -> 10
        windKmh < 118 -> 11
        else -> 12
    }

    /** Himmelsrichtung als Index 0–7 (N, NO, O, SO, S, SW, W, NW) aus Grad (Richtung, aus der der Wind kommt). */
    fun compassIndex(degrees: Double): Int {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return (((normalized + 22.5) / 45.0).toInt()) % 8
    }

    /**
     * Index in der Liste der Wetterbeschreibungen (siehe `wx_codes` in den String-Ressourcen) für einen
     * WMO-Wettercode, oder `null` bei unbekanntem Code.
     */
    fun weatherCodeIndex(code: Int): Int? = WEATHER_CODES.indexOf(code).takeIf { it >= 0 }

    val WEATHER_CODES = listOf(
        0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
        71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99,
    )

    fun weatherEmoji(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 80, 81, 82 -> "🌦️"
        56, 57, 61, 63, 65, 66, 67 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    /** Luftqualitätsstufe 0–5 aus PM2,5 (µg/m³) nach WHO/EU-Schwellen. */
    fun aqiLevel(pm25: Double): Int = when {
        pm25 < 10 -> 0
        pm25 < 20 -> 1
        pm25 < 25 -> 2
        pm25 < 50 -> 3
        pm25 < 75 -> 4
        else -> 5
    }

    /** Pollenstufe 0–4 aus Körnern pro m³ (Skala angelehnt an den DWD). */
    fun pollenLevel(grainsPerM3: Double): Int = when {
        grainsPerM3 < 1 -> 0
        grainsPerM3 < 10 -> 1
        grainsPerM3 < 50 -> 2
        grainsPerM3 < 200 -> 3
        else -> 4
    }

    /** UV-Stufe 0–5 (minimal, gering, mittel, hoch, sehr hoch, extrem). */
    fun uvLevel(uv: Double): Int = when {
        uv < 1 -> 0
        uv < 3 -> 1
        uv < 6 -> 2
        uv < 8 -> 3
        uv < 11 -> 4
        else -> 5
    }

    /** Taupunkt (°C) nach der Magnus-Formel (Alduchov und Eskridge 1996). */
    fun dewPoint(tempC: Double, relHumidityPct: Int): Double {
        val a = 17.625
        val b = 243.04
        val rh = relHumidityPct.coerceIn(1, 100) / 100.0
        val alpha = ln(rh) + a * tempC / (b + tempC)
        return b * alpha / (a - alpha)
    }

    /** Hitze-Index (°C) nach der Rothfusz-Regression des US National Weather Service. */
    fun heatIndex(tempC: Double, relHumidityPct: Int): Double {
        val t = tempC * 9.0 / 5.0 + 32.0
        val r = relHumidityPct.toDouble()
        val hi = -42.379 + 2.04901523 * t + 10.14333127 * r -
            0.22475541 * t * r - 0.00683783 * t * t -
            0.05481717 * r * r + 0.00122874 * t * t * r +
            0.00085282 * t * r * r - 0.00000199 * t * t * r * r
        return (hi - 32.0) * 5.0 / 9.0
    }

    /** Luftdrucktendenz der letzten 3 Stunden (hPa); `null`, wenn weniger als 4 Werte vorliegen. */
    fun pressureTendency3h(pressureHourly: List<Double>): Double? {
        if (pressureHourly.size < 4) return null
        return pressureHourly.last() - pressureHourly[pressureHourly.size - 4]
    }

    // --- Mond -------------------------------------------------------------------------------------

    const val SYNODIC_PERIOD_DAYS = 29.53059
    private const val REFERENCE_NEW_MOON_JD = 2451550.259

    data class MoonInfo(
        /** 0 Neumond, 1 zunehmende Sichel, 2 erstes Viertel, 3 zunehmender Mond, 4 Vollmond, 5 abnehmender Mond, 6 letztes Viertel, 7 abnehmende Sichel. */
        val phaseIndex: Int,
        val illuminationPercent: Double,
        val ageDays: Double,
        val daysToFullMoon: Double,
        val daysToNewMoon: Double,
    )

    fun julianDate(instant: Instant): Double = instant.toEpochMilli() / 86_400_000.0 + 2440587.5

    fun moon(instant: Instant): MoonInfo {
        val jd = julianDate(instant)
        var age = (jd - REFERENCE_NEW_MOON_JD) % SYNODIC_PERIOD_DAYS
        if (age < 0) age += SYNODIC_PERIOD_DAYS
        val illumination = (1.0 - cos(Math.PI * age / (SYNODIC_PERIOD_DAYS / 2.0))) / 2.0 * 100.0
        val half = SYNODIC_PERIOD_DAYS / 2.0
        val toFull = if (age <= half) half - age else SYNODIC_PERIOD_DAYS - age + half
        val toNew = SYNODIC_PERIOD_DAYS - age
        val progress = age / SYNODIC_PERIOD_DAYS
        val phase = when {
            progress < 0.03 || progress > 0.97 -> 0
            progress < 0.22 -> 1
            progress < 0.28 -> 2
            progress < 0.47 -> 3
            progress < 0.53 -> 4
            progress < 0.72 -> 5
            progress < 0.78 -> 6
            else -> 7
        }
        return MoonInfo(phase, illumination, age, toFull, toNew)
    }

    private const val SIDEREAL_PERIOD_DAYS = 27.32158

    /** Index 0–11 (Widder … Fische) des Tierkreiszeichens, in dem der Mond näherungsweise steht. */
    fun moonZodiacIndex(instant: Instant): Int {
        var age = (julianDate(instant) - REFERENCE_NEW_MOON_JD) % SIDEREAL_PERIOD_DAYS
        if (age < 0) age += SIDEREAL_PERIOD_DAYS
        return ((age / SIDEREAL_PERIOD_DAYS) * 12.0).toInt().coerceIn(0, 11)
    }
}
