// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

/** Ampelstufen für Biowetter-Indikatoren; die Reihenfolge entspricht der Schwere. */
enum class BioLevel { NONE, LOW, MODERATE, HIGH, EXTREME }

enum class BioKind { PRESSURE, HUMIDITY, HEAT, COLD, THERMAL_NEUTRAL, CAPE }

/**
 * Ein Indikator. [value] ist je nach Art die Drucktendenz (hPa/3 h), der Taupunkt (°C), der Hitze-Index
 * oder Windchill (°C) oder der CAPE-Wert (J/kg); `null` bei fehlender Datenlage.
 */
data class BioIndicator(
    val kind: BioKind,
    val level: BioLevel,
    val value: Double?,
    val thunderLevel: ThunderLevel? = null,
)

data class BioResult(val indicators: List<BioIndicator>, val overall: BioLevel)

/** Gewitterpotenzial aus CAPE, Niederschlagswahrscheinlichkeit und Wettercode. */
enum class ThunderLevel { LOW, MODERATE, HIGH }

object Thunderstorm {
    private val THUNDER_CODES = setOf(95, 96, 99)
    private val MODERATE_TO_HEAVY_PRECIP_CODES = setOf(63, 65, 81, 82)

    /**
     * CAPE beschreibt nur das Potenzial für Konvektion, nicht ihre Auslösung. Deshalb führt CAPE allein
     * nie zur Stufe "hoch": Dafür muss der Wettercode ein Gewitter anzeigen oder mäßiger bis starker
     * Niederschlag vorhergesagt sein. Die Niederschlagswahrscheinlichkeit stammt aus einem Modell mit
     * grober Gitterauflösung und wird deshalb nur zusammen mit CAPE gewertet.
     */
    fun classify(cape: Double, precipitationProbability: Int, weatherCode: Int): ThunderLevel {
        val isThunder = weatherCode in THUNDER_CODES
        val precip = weatherCode in MODERATE_TO_HEAVY_PRECIP_CODES
        return when {
            isThunder -> ThunderLevel.HIGH
            cape >= 2500.0 && precip -> ThunderLevel.HIGH
            cape >= 1000.0 && precip -> ThunderLevel.MODERATE
            cape >= 2500.0 -> ThunderLevel.MODERATE
            cape >= 1000.0 && precipitationProbability >= 50 -> ThunderLevel.MODERATE
            precipitationProbability >= 70 && cape >= 500.0 -> ThunderLevel.MODERATE
            else -> ThunderLevel.LOW
        }
    }

    data class Entry(val hour: HourPoint, val level: ThunderLevel)

    data class Forecast(val maxLevel: ThunderLevel, val peak: Entry?, val entries: List<Entry>)

    /** Bewertet die nächsten [hours] Stunden ab [from] (Ortszeit); [entries] enthält nur Stunden ab Stufe "erhöht". */
    fun forecast(all: List<HourPoint>, from: java.time.LocalDateTime, hours: Int = 12): Forecast {
        val window = all.filter { !it.time.isBefore(from.withMinute(0).withSecond(0).withNano(0)) }.take(hours)
        val entries = window
            .map { Entry(it, classify(it.cape, it.precipitationProbability, it.weatherCode)) }
            .filter { it.level != ThunderLevel.LOW }
        val peak = entries.maxByOrNull { it.level.ordinal }
        return Forecast(peak?.level ?: ThunderLevel.LOW, peak, entries)
    }
}

object Biowetter {

    /**
     * @param pressureHourly Luftdruck der letzten Stunden in hPa, zeitlich aufsteigend (letzter Wert = jetzt)
     * @param cape CAPE in J/kg, negativ wenn nicht verfügbar
     */
    fun evaluate(
        pressureHourly: List<Double>,
        tempC: Double,
        relHumidityPct: Int,
        windSpeedKmh: Double,
        cape: Double,
        weatherCode: Int,
        precipitationProbability: Int,
    ): BioResult {
        val indicators = mutableListOf<BioIndicator>()
        indicators += pressure(WeatherMath.pressureTendency3h(pressureHourly))
        indicators += humidity(WeatherMath.dewPoint(tempC, relHumidityPct))
        indicators += thermal(tempC, relHumidityPct, windSpeedKmh)
        if (cape >= 0) {
            val thunder = Thunderstorm.classify(cape, precipitationProbability, weatherCode)
            val level = when (thunder) {
                ThunderLevel.LOW -> BioLevel.NONE
                ThunderLevel.MODERATE -> BioLevel.MODERATE
                ThunderLevel.HIGH -> BioLevel.HIGH
            }
            indicators += BioIndicator(BioKind.CAPE, level, cape, thunder)
        }
        val overall = indicators.maxOf { it.level }
        return BioResult(indicators, overall)
    }

    private fun pressure(tendency: Double?): BioIndicator {
        if (tendency == null) return BioIndicator(BioKind.PRESSURE, BioLevel.NONE, null)
        val a = kotlin.math.abs(tendency)
        val level = when {
            a < 1.0 -> BioLevel.NONE
            a < 3.0 -> BioLevel.LOW
            a < 6.0 -> BioLevel.MODERATE
            a < 10.0 -> BioLevel.HIGH
            else -> BioLevel.EXTREME
        }
        return BioIndicator(BioKind.PRESSURE, level, tendency)
    }

    private fun humidity(dewPoint: Double): BioIndicator {
        val level = when {
            dewPoint < 10 -> BioLevel.NONE
            dewPoint < 16 -> BioLevel.LOW
            dewPoint < 20 -> BioLevel.MODERATE
            dewPoint < 24 -> BioLevel.HIGH
            else -> BioLevel.EXTREME
        }
        return BioIndicator(BioKind.HUMIDITY, level, dewPoint)
    }

    private fun thermal(tempC: Double, relHumidityPct: Int, windKmh: Double): BioIndicator = when {
        tempC >= 27 && relHumidityPct >= 40 -> {
            val hi = WeatherMath.heatIndex(tempC, relHumidityPct)
            val level = when {
                hi < 27 -> BioLevel.NONE
                hi < 32 -> BioLevel.LOW
                hi < 38 -> BioLevel.MODERATE
                hi < 44 -> BioLevel.HIGH
                else -> BioLevel.EXTREME
            }
            BioIndicator(BioKind.HEAT, level, hi)
        }
        tempC <= 10 && windKmh >= 10 -> {
            val v = Math.pow(windKmh, 0.16)
            val wc = 13.12 + 0.6215 * tempC - 11.37 * v + 0.3965 * tempC * v
            val level = when {
                wc > 0 -> BioLevel.NONE
                wc > -10 -> BioLevel.LOW
                wc > -20 -> BioLevel.MODERATE
                wc > -30 -> BioLevel.HIGH
                else -> BioLevel.EXTREME
            }
            BioIndicator(BioKind.COLD, level, wc)
        }
        else -> BioIndicator(BioKind.THERMAL_NEUTRAL, BioLevel.NONE, null)
    }
}
