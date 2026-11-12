// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.min

/**
 * Auswertungen aus den ohnehin geladenen Stunden-, Tages- und Luftdaten (keine neuen Datenquellen):
 * Niederschlagsfenster, Temperaturextreme, Böen und Winddrehung, UV-Fenster, Luftdruckverlauf,
 * Tageswerte aus Stundendaten und Tagesvorschau für Luft und Pollen.
 *
 * Die Schwellen sind, soweit nicht anders vermerkt, eigene Richtwerte und in der Oberfläche so benannt.
 * Alle Zeiten sind Ortszeit des Standorts. Reine Logik ohne Android-Bezug.
 */
object WeatherInsights {

    /** Ab diesem Stundenniederschlag (mm) gilt eine Stunde als nass. */
    const val WET_HOUR_MM = 0.1

    /** UV-Index, ab dem Sonnenschutz empfohlen wird (WHO). */
    const val UV_PROTECTION = 3.0

    /** Böen (km/h), ab denen sie in der Zusammenfassung genannt werden (Beaufort 7, Richtwert). */
    const val STRONG_GUST_KMH = 50.0

    /** Luftdruckänderung (hPa je 3 Stunden), unterhalb der der Druck als gleichbleibend gilt (Richtwert). */
    const val STEADY_HPA_3H = 1.0

    /** Winkeländerung (Grad), ab der eine Winddrehung genannt wird (Richtwert). */
    const val DIRECTION_SHIFT_DEG = 45.0

    /** Windgeschwindigkeit (km/h), unterhalb der die Windrichtung nicht ausgewertet wird. */
    const val CALM_KMH = 5.0

    /** Mindestzahl Stundenwerte, damit ein Tag als vollständig gilt. */
    const val COMPLETE_DAY_HOURS = 20

    // --- Niederschlag ---------------------------------------------------------------------------

    /** Zusammenhängender Niederschlag; [end] ist das Ende der letzten nassen Stunde. */
    data class RainWindow(val start: LocalDateTime, val end: LocalDateTime, val totalMm: Double, val peakMm: Double)

    enum class RainState { DRY, RAINING_NOW, RAIN_LATER }

    /**
     * @property endsWithinHorizon `false`, wenn der erste Niederschlag bis zum Ende des Zeitraums anhält.
     */
    data class RainOutlook(
        val state: RainState,
        val first: RainWindow?,
        val next: RainWindow?,
        val totalMm: Double,
        val endsWithinHorizon: Boolean,
    )

    private fun upcoming(hours: List<HourPoint>, from: LocalDateTime, count: Int): List<HourPoint> =
        hours.filter { !it.time.isBefore(from) }.take(count)

    /** Niederschlagsfenster ab [from]; eine einzelne trockene Stunde zwischen zwei nassen Stunden trennt nicht. */
    fun rainWindows(hours: List<HourPoint>, from: LocalDateTime, count: Int = 24): List<RainWindow> {
        val slice = upcoming(hours, from, count)
        val windows = ArrayList<RainWindow>()
        var i = 0
        while (i < slice.size) {
            if (slice[i].precipitation < WET_HOUR_MM) {
                i++
                continue
            }
            var last = i
            var j = i + 1
            while (j < slice.size) {
                if (slice[j].precipitation >= WET_HOUR_MM) {
                    last = j
                    j++
                } else if (j + 1 < slice.size && slice[j + 1].precipitation >= WET_HOUR_MM) {
                    j++
                } else {
                    break
                }
            }
            val part = slice.subList(i, last + 1)
            windows += RainWindow(
                start = part.first().time,
                end = part.last().time.plusHours(1),
                totalMm = part.sumOf { it.precipitation },
                peakMm = part.maxOf { it.precipitation },
            )
            i = last + 1
        }
        return windows
    }

    fun rainOutlook(hours: List<HourPoint>, from: LocalDateTime, count: Int = 24): RainOutlook {
        val slice = upcoming(hours, from, count)
        val windows = rainWindows(hours, from, count)
        if (slice.isEmpty() || windows.isEmpty()) return RainOutlook(RainState.DRY, null, null, 0.0, true)
        val first = windows[0]
        val now = first.start == slice.first().time
        val horizonEnd = slice.last().time.plusHours(1)
        return RainOutlook(
            state = if (now) RainState.RAINING_NOW else RainState.RAIN_LATER,
            first = first,
            next = windows.getOrNull(1),
            totalMm = windows.sumOf { it.totalMm },
            endsWithinHorizon = first.end.isBefore(horizonEnd),
        )
    }

    // --- Temperatur und Wind --------------------------------------------------------------------

    data class Extremes(val max: HourPoint, val min: HourPoint)

    fun extremes(hours: List<HourPoint>): Extremes? {
        if (hours.isEmpty()) return null
        return Extremes(hours.maxByOrNull { it.temperature }!!, hours.minByOrNull { it.temperature }!!)
    }

    data class WindOutlook(
        val peakGust: HourPoint,
        val peakSpeed: HourPoint,
        /** `true`, wenn sich die Windrichtung zwischen erster und letzter Stunde deutlich ändert. */
        val directionShift: Boolean,
        val fromDegrees: Double,
        val toDegrees: Double,
    ) {
        val strongGust: Boolean get() = peakGust.windGusts >= STRONG_GUST_KMH
    }

    fun windOutlook(hours: List<HourPoint>): WindOutlook? {
        if (hours.size < 2) return null
        val first = hours.first()
        val last = hours.last()
        val calm = first.windSpeed < CALM_KMH || last.windSpeed < CALM_KMH
        val shift = !calm && angleDiff(first.windDirection, last.windDirection) >= DIRECTION_SHIFT_DEG
        return WindOutlook(
            peakGust = hours.maxByOrNull { it.windGusts }!!,
            peakSpeed = hours.maxByOrNull { it.windSpeed }!!,
            directionShift = shift,
            fromDegrees = first.windDirection,
            toDegrees = last.windDirection,
        )
    }

    /** Kleinster Winkelabstand zweier Richtungen in Grad (0–180). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((((a - b) % 360.0) + 360.0) % 360.0)
        return min(d, 360.0 - d)
    }

    // --- UV -------------------------------------------------------------------------------------

    data class UvWindow(val start: LocalDateTime, val end: LocalDateTime, val peakUv: Double, val peakTime: LocalDateTime)

    /** Erstes zusammenhängendes Fenster mit UV-Index ab [UV_PROTECTION] ab [from]; `null`, wenn es keins gibt. */
    fun uvWindow(hours: List<HourPoint>, from: LocalDateTime, count: Int = 24): UvWindow? {
        val slice = upcoming(hours, from, count)
        val startIndex = slice.indexOfFirst { it.uvIndex >= UV_PROTECTION }
        if (startIndex < 0) return null
        var end = startIndex
        while (end + 1 < slice.size && slice[end + 1].uvIndex >= UV_PROTECTION) end++
        val part = slice.subList(startIndex, end + 1)
        val peak = part.maxByOrNull { it.uvIndex }!!
        return UvWindow(part.first().time, part.last().time.plusHours(1), peak.uvIndex, peak.time)
    }

    // --- Luftdruck ------------------------------------------------------------------------------

    enum class PressureTrend { RISING, STEADY, FALLING }

    /**
     * @property values Luftdruck (hPa) je Stunde ab jetzt
     * @property times zugehörige Zeiten
     * @property change3hPast Änderung der letzten 3 Stunden (hPa) oder `null`
     * @property changeAhead Änderung vom ersten bis zum letzten Wert (hPa)
     * @property trend Richtung der vorhergesagten Änderung, bezogen auf 3 Stunden
     */
    data class PressureOutlook(
        val values: List<Double>,
        val times: List<LocalDateTime>,
        val change3hPast: Double?,
        val changeAhead: Double,
        val trend: PressureTrend,
    )

    fun pressureTrend(changePer3h: Double): PressureTrend = when {
        changePer3h >= STEADY_HPA_3H -> PressureTrend.RISING
        changePer3h <= -STEADY_HPA_3H -> PressureTrend.FALLING
        else -> PressureTrend.STEADY
    }

    fun pressureOutlook(all: List<HourPoint>, from: LocalDateTime, count: Int = 24): PressureOutlook? {
        val ahead = upcoming(all, from, count).filter { it.pressure != null }
        if (ahead.size < 6) return null
        val values = ahead.map { it.pressure!! }
        val change = values.last() - values.first()
        val per3h = change / ((values.size - 1) / 3.0)
        val past = all.filter { !it.time.isAfter(from) }.mapNotNull { it.pressure }.takeLast(4)
        return PressureOutlook(values, ahead.map { it.time }, WeatherMath.pressureTendency3h(past), change, pressureTrend(per3h))
    }

    // --- Tageswerte aus Stundendaten ------------------------------------------------------------

    /**
     * @property precipSumMm Niederschlag des ganzen Tages (mm)
     * @property wetHours Stunden mit Niederschlag
     * @property dayAvg Durchschnittstemperatur zwischen Sonnenaufgang und -untergang
     * @property nightAvg Durchschnittstemperatur außerhalb davon
     * @property dryStart Beginn des längsten trockenen Zeitraums bei Tageslicht (mindestens 2 Stunden)
     */
    data class DayStats(
        val date: LocalDate,
        val precipSumMm: Double,
        val wetHours: Int,
        val dayAvg: Double?,
        val nightAvg: Double?,
        val dryStart: LocalDateTime?,
        val dryEnd: LocalDateTime?,
    )

    /** Tageswerte für [day]; `null`, wenn weniger als [COMPLETE_DAY_HOURS] Stundenwerte vorliegen. */
    fun dayStats(hours: List<HourPoint>, day: DayPoint): DayStats? {
        val ofDay = hours.filter { it.time.toLocalDate() == day.date }
        if (ofDay.size < COMPLETE_DAY_HOURS) return null
        val rise = day.sunrise
        val set = day.sunset
        val isDaylight: (HourPoint) -> Boolean = if (rise != null && set != null) {
            { h -> h.time.plusHours(1).isAfter(rise) && h.time.isBefore(set) }
        } else {
            { h -> h.time.hour in 6..20 }
        }
        val daylight = ofDay.filter(isDaylight)
        val night = ofDay.filterNot(isDaylight)

        var bestStart: LocalDateTime? = null
        var bestEnd: LocalDateTime? = null
        var bestLen = 0
        var i = 0
        while (i < daylight.size) {
            if (daylight[i].precipitation >= WET_HOUR_MM) {
                i++
                continue
            }
            var j = i
            while (j + 1 < daylight.size && daylight[j + 1].precipitation < WET_HOUR_MM &&
                daylight[j + 1].time == daylight[j].time.plusHours(1)
            ) {
                j++
            }
            val len = j - i + 1
            if (len > bestLen) {
                bestLen = len
                bestStart = daylight[i].time
                bestEnd = daylight[j].time.plusHours(1)
            }
            i = j + 1
        }
        val hasDry = bestLen >= 2
        return DayStats(
            date = day.date,
            precipSumMm = ofDay.sumOf { it.precipitation },
            wetHours = ofDay.count { it.precipitation >= WET_HOUR_MM },
            dayAvg = daylight.takeIf { it.isNotEmpty() }?.map { it.temperature }?.average(),
            nightAvg = night.takeIf { it.isNotEmpty() }?.map { it.temperature }?.average(),
            dryStart = if (hasDry) bestStart else null,
            dryEnd = if (hasDry) bestEnd else null,
        )
    }

    // --- Luft und Pollen je Tag -----------------------------------------------------------------

    /**
     * @property aqiLevel Stufe 0–5 aus dem Tageshöchstwert PM2,5
     * @property topPollen Pollenart mit der höchsten Stufe des Tages oder `null`
     * @property pollenLevel Stufe 0–4
     */
    data class AirDay(
        val date: LocalDate,
        val pm25Max: Double,
        val aqiLevel: Int,
        val topPollen: PollenType?,
        val pollenLevel: Int,
    )

    fun airDays(air: List<AirHour>, from: LocalDate): List<AirDay> =
        air.filter { !it.time.toLocalDate().isBefore(from) }
            .groupBy { it.time.toLocalDate() }
            .toSortedMap()
            .map { (date, hours) ->
                val pm = hours.maxOf { it.pm25 }
                var topType: PollenType? = null
                var topGrains = 0.0
                for (type in PollenType.values()) {
                    val grains = hours.maxOf { it.pollen[type] ?: 0.0 }
                    if (grains > topGrains) {
                        topGrains = grains
                        topType = type
                    }
                }
                val level = WeatherMath.pollenLevel(topGrains)
                AirDay(date, pm, WeatherMath.aqiLevel(pm), if (level == 0) null else topType, level)
            }
}
