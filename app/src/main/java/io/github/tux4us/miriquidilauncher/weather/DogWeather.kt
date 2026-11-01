// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.sin

/** Einstufung einer Stunde für einen Spaziergang mit Hund. */
enum class DogLevel { GOOD, CAUTION, AVOID }

/** Gründe für eine schlechtere Einstufung als [DogLevel.GOOD]. */
enum class DogReason { PAVEMENT, HEAT, COLD, THUNDER, STORM, RAIN }

/**
 * Bewertung einer Stunde. [surfaceC] ist die geschätzte Temperatur von dunklem Asphalt in der Sonne (nicht im Schatten),
 * [daylight] gibt an, ob die Stunde zwischen einer Stunde vor Sonnenaufgang und einer Stunde nach Sonnenuntergang liegt.
 */
data class DogHour(
    val time: LocalDateTime,
    val surfaceC: Double,
    val feelsLikeC: Double,
    val level: DogLevel,
    val reasons: Set<DogReason>,
    val daylight: Boolean,
)

/** Zusammenhängender Zeitraum gleicher Einstufung; [end] ist das Ende der letzten Stunde (exklusiv). */
data class WalkWindow(val start: LocalDateTime, val end: LocalDateTime)

/** Zeckenhinweis für einen Tag: [active], wenn die Höchsttemperatur über [DogWeather.TICK_ACTIVE_C] liegt. */
data class TickInfo(val active: Boolean, val maxTempC: Double)

/**
 * Ergebnis für die nächsten 24 Stunden. [windows] enthält die Zeiträume der Einstufung [windowsLevel]: bevorzugt
 * [DogLevel.GOOD], sonst [DogLevel.CAUTION]; `null` und leer, wenn keine Stunde mindestens Vorsicht erlaubt.
 */
data class DogForecast(
    val hours: List<DogHour>,
    val now: DogHour?,
    val windows: List<WalkWindow>,
    val windowsLevel: DogLevel?,
    val peakSurface: DogHour?,
    val tick: TickInfo?,
)

/**
 * Hinweise für Hundehalter aus den Wetterdaten. Reine Logik ohne Android-Bezug.
 *
 * Quellenlage: Die Tabelle „Lufttemperatur → Asphalttemperatur“ (25 °C → 52 °C, 31 °C → 62 °C, 35 °C → 65 °C) und der
 * Handrückentest (7 Sekunden) stammen von Vier Pfoten; die Zeckenaktivität ab etwa 7 °C aus allgemeinen Angaben zur
 * Zeckensaison. Die Stufengrenzen für Asphalt (40 °C und 50 °C), gefühlte Temperatur (25 °C und 30 °C, angelehnt an die
 * DWD-Begriffe Sommertag und heißer Tag), Kälte, Böen und Regen sind eigene Richtwerte und keine tierärztliche
 * Einstufung. Die Asphalttemperatur ist ein Modell (Strahlung, Wind, Speicherwirkung), kein Messwert.
 */
object DogWeather {
    /** Erwärmung des Asphalts über die Lufttemperatur je W/m² Einstrahlung bei Windstille; geeicht auf die Tabelle oben. */
    const val SURFACE_K_PER_WM2 = 0.032

    /** Windkilometer, bei denen die Erwärmung auf die Hälfte sinkt (Dämpfung `1 / (1 + Wind / Wert)`). */
    const val WIND_DAMPING_KMH = 60.0

    /** Gewichte für Einstrahlung der aktuellen und der beiden vorigen Stunden (Wärmespeicherung des Belags). */
    private val STORAGE_WEIGHTS = doubleArrayOf(0.5, 0.3, 0.2)

    const val PAVEMENT_CAUTION_C = 40.0
    const val PAVEMENT_AVOID_C = 50.0
    const val HEAT_CAUTION_C = 25.0
    const val HEAT_AVOID_C = 30.0
    const val COLD_CAUTION_C = -10.0
    const val COLD_AVOID_C = -20.0
    const val GUST_CAUTION_KMH = 62.0
    const val GUST_AVOID_KMH = 75.0
    const val HEAVY_RAIN_MM_PER_H = 7.6
    const val TICK_ACTIVE_C = 7.0
    const val MAX_WINDOWS = 4

    /** Geschätzte Temperatur von Asphalt in der Sonne bei Lufttemperatur [airC], wirksamer Einstrahlung und Wind. */
    fun surfaceTemperature(airC: Double, effectiveRadiationWm2: Double, windKmh: Double): Double =
        airC + SURFACE_K_PER_WM2 * effectiveRadiationWm2.coerceAtLeast(0.0) / (1.0 + windKmh.coerceAtLeast(0.0) / WIND_DAMPING_KMH)

    /** Anteil der Strahlung bei klarem Himmel, der bei diesem Wettercode (WMO) den Boden erreicht. */
    fun cloudTransmittance(weatherCode: Int): Double = when (weatherCode) {
        0 -> 1.0
        1 -> 0.9
        2 -> 0.65
        3 -> 0.3
        45, 48 -> 0.25
        in 51..57 -> 0.25
        in 61..67 -> 0.2
        in 71..77 -> 0.2
        in 80..82 -> 0.3
        85, 86 -> 0.2
        95, 96, 99 -> 0.2
        else -> 0.5
    }

    /** Einstrahlung in W/m² aus Sonnenhöhe und Wettercode, wenn die Wetterdaten keine Strahlung liefern. */
    fun estimateRadiation(elevationDeg: Double, weatherCode: Int): Double =
        if (elevationDeg <= 0.0) 0.0 else 1000.0 * sin(Math.toRadians(elevationDeg)) * cloudTransmittance(weatherCode)

    /** Stufe für die Pfoten aus der geschätzten Asphalttemperatur. */
    fun pavementLevel(surfaceC: Double): DogLevel = when {
        surfaceC >= PAVEMENT_AVOID_C -> DogLevel.AVOID
        surfaceC >= PAVEMENT_CAUTION_C -> DogLevel.CAUTION
        else -> DogLevel.GOOD
    }

    /**
     * Bewertet die Stunden ab [from] (Ortszeit) für 24 Stunden. [hours] muss aufsteigend nach Zeit sortiert sein und darf
     * frühere Stunden enthalten (für die Wärmespeicherung). Sonnenstand und Zeitzone ([latitude], [longitude],
     * [utcOffsetSeconds]) werden nur gebraucht, wenn eine Stunde keine gemessene Strahlung enthält.
     */
    fun forecast(
        hours: List<HourPoint>,
        days: List<DayPoint>,
        latitude: Double,
        longitude: Double,
        utcOffsetSeconds: Int,
        from: LocalDateTime,
    ): DogForecast {
        val start = from.truncatedTo(ChronoUnit.HOURS)
        val end = start.plusHours(24)
        val radiation = hours.map { h ->
            h.shortwaveRadiation ?: estimateRadiation(
                SunMath.solarAngles(latitude, longitude, h.time, utcOffsetSeconds).elevationDeg, h.weatherCode,
            )
        }
        val rated = ArrayList<DogHour>()
        for (i in hours.indices) {
            val h = hours[i]
            if (h.time.isBefore(start) || !h.time.isBefore(end)) continue
            val effective = effectiveRadiation(hours, radiation, i)
            rated += rate(h, effective, isDaylight(h.time, days))
        }
        val (level, windows) = windows(rated)
        return DogForecast(
            hours = rated,
            now = rated.firstOrNull { it.time == start },
            windows = windows,
            windowsLevel = level,
            peakSurface = rated.filter { it.daylight }.maxByOrNull { it.surfaceC },
            tick = tick(days, start.toLocalDate()),
        )
    }

    /** Gewichtetes Mittel der Einstrahlung der Stunden `i`, `i-1`, `i-2`; fehlende oder nicht anschließende Stunden entfallen. */
    internal fun effectiveRadiation(hours: List<HourPoint>, radiation: List<Double>, i: Int): Double {
        var sum = 0.0
        var weights = 0.0
        for (k in STORAGE_WEIGHTS.indices) {
            val j = i - k
            if (j < 0) break
            if (k > 0 && ChronoUnit.HOURS.between(hours[j].time, hours[j + 1].time) != 1L) break
            sum += STORAGE_WEIGHTS[k] * radiation[j]
            weights += STORAGE_WEIGHTS[k]
        }
        return if (weights == 0.0) 0.0 else sum / weights
    }

    internal fun rate(h: HourPoint, effectiveRadiation: Double, daylight: Boolean): DogHour {
        val surface = surfaceTemperature(h.temperature, effectiveRadiation, h.windSpeed)
        var level = DogLevel.GOOD
        val reasons = LinkedHashSet<DogReason>()
        fun add(l: DogLevel, reason: DogReason) {
            if (l == DogLevel.GOOD) return
            reasons += reason
            level = maxOf(level, l)
        }
        add(pavementLevel(surface), DogReason.PAVEMENT)
        add(
            when {
                h.feelsLike >= HEAT_AVOID_C -> DogLevel.AVOID
                h.feelsLike >= HEAT_CAUTION_C -> DogLevel.CAUTION
                else -> DogLevel.GOOD
            },
            DogReason.HEAT,
        )
        add(
            when {
                h.feelsLike <= COLD_AVOID_C -> DogLevel.AVOID
                h.feelsLike <= COLD_CAUTION_C -> DogLevel.CAUTION
                else -> DogLevel.GOOD
            },
            DogReason.COLD,
        )
        add(
            when (Thunderstorm.classify(h.cape, h.precipitationProbability, h.weatherCode)) {
                ThunderLevel.HIGH -> DogLevel.AVOID
                ThunderLevel.MODERATE -> DogLevel.CAUTION
                ThunderLevel.LOW -> DogLevel.GOOD
            },
            DogReason.THUNDER,
        )
        add(
            when {
                h.windGusts >= GUST_AVOID_KMH -> DogLevel.AVOID
                h.windGusts >= GUST_CAUTION_KMH -> DogLevel.CAUTION
                else -> DogLevel.GOOD
            },
            DogReason.STORM,
        )
        add(if (h.precipitation >= HEAVY_RAIN_MM_PER_H) DogLevel.CAUTION else DogLevel.GOOD, DogReason.RAIN)
        return DogHour(h.time, surface, h.feelsLike, level, reasons, daylight)
    }

    /** `true` zwischen einer Stunde vor Sonnenaufgang und einer Stunde nach Sonnenuntergang; ohne Angaben immer `true`. */
    internal fun isDaylight(time: LocalDateTime, days: List<DayPoint>): Boolean {
        val day = days.firstOrNull { it.date == time.toLocalDate() } ?: return true
        val rise = day.sunrise ?: return true
        val set = day.sunset ?: return true
        val from = rise.truncatedTo(ChronoUnit.HOURS).minusHours(1)
        val to = set.plusHours(1)
        return !time.isBefore(from) && !time.isAfter(to)
    }

    /** Zeiträume mit Tageslicht: bevorzugt Stunden der Stufe GOOD, sonst CAUTION; höchstens [MAX_WINDOWS]. */
    internal fun windows(rated: List<DogHour>): Pair<DogLevel?, List<WalkWindow>> {
        for (target in listOf(DogLevel.GOOD, DogLevel.CAUTION)) {
            val found = ArrayList<WalkWindow>()
            var runStart: LocalDateTime? = null
            var last: LocalDateTime? = null
            for (h in rated) {
                val fits = h.daylight && h.level == target
                val continues = last != null && ChronoUnit.HOURS.between(last, h.time) == 1L
                if (fits && runStart != null && continues) {
                    last = h.time
                    continue
                }
                if (runStart != null) found += WalkWindow(runStart, last!!.plusHours(1))
                runStart = if (fits) h.time else null
                last = if (fits) h.time else null
            }
            if (runStart != null) found += WalkWindow(runStart, last!!.plusHours(1))
            if (found.isNotEmpty()) return target to found.take(MAX_WINDOWS)
        }
        return null to emptyList()
    }

    /** Zeckenhinweis für [date]; `null`, wenn die Tagesvorhersage fehlt. */
    fun tick(days: List<DayPoint>, date: LocalDate): TickInfo? =
        days.firstOrNull { it.date == date }?.let { TickInfo(it.temperatureMax > TICK_ACTIVE_C, it.temperatureMax) }

    /** Höchste Stufe aller Stunden eines Zeitraums, z. B. für eine Kurzfassung. */
    fun worst(hours: List<DogHour>): DogLevel = hours.fold(DogLevel.GOOD) { acc, h -> max(acc.ordinal, h.level.ordinal).let { DogLevel.entries[it] } }
}
