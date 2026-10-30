// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Ein Ort mit Koordinaten. */
data class GeoPlace(val name: String, val latitude: Double, val longitude: Double) {
    /** Schlüssel zum Erkennen desselben Ortes (auf ca. 1 km gerundet). */
    val key: String get() = "%.2f,%.2f".format(java.util.Locale.ROOT, latitude, longitude)
}

data class CurrentWeather(
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val precipitation: Double,
    val weatherCode: Int,
    val pressure: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
    val uvIndex: Double,
    val isDay: Boolean,
)

/** Stundenwerte; alle Zeiten sind Ortszeit des Standorts. */
data class HourPoint(
    val time: LocalDateTime,
    val temperature: Double,
    val feelsLike: Double,
    val precipitation: Double,
    val precipitationProbability: Int,
    val weatherCode: Int,
    val windSpeed: Double,
    val windGusts: Double,
    val windDirection: Double,
    val cape: Double,
    val pressure: Double?,
    val uvIndex: Double,
)

data class DayPoint(
    val date: LocalDate,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val weatherCode: Int,
    val precipitationProbabilityMax: Int,
    val windSpeedMax: Double,
    val windGustsMax: Double,
    val uvIndexMax: Double,
    val sunshineMinutes: Double,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
)

enum class PollenType(val apiKey: String) {
    BIRCH("birch_pollen"),
    GRASS("grass_pollen"),
    ALDER("alder_pollen"),
    MUGWORT("mugwort_pollen"),
    OLIVE("olive_pollen"),
    RAGWEED("ragweed_pollen"),
}

data class AirHour(
    val time: LocalDateTime,
    val pm25: Double,
    val pm10: Double,
    val no2: Double,
    val ozone: Double,
    val pollen: Map<PollenType, Double>,
)

data class ForecastData(
    val utcOffsetSeconds: Int,
    val current: CurrentWeather,
    val hours: List<HourPoint>,
    val days: List<DayPoint>,
) {
    /** Aktuelle Ortszeit des Standorts. */
    fun localNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
}

/** Vollständiger Wetterbericht für einen Ort. [fetchedAtMillis] = Zeitpunkt des Abrufs. */
data class WeatherReport(
    val place: GeoPlace,
    val forecast: ForecastData,
    val air: List<AirHour>,
    val fetchedAtMillis: Long,
)

/** Höhen für die Schattenberechnung in Metern: Person und frei wählbarer Gegenstand. */
data class ShadowHeights(val personM: Double, val objectM: Double) {
    companion object {
        const val DEFAULT_PERSON_M = 1.75
        const val DEFAULT_OBJECT_M = 1.0
        val PERSON_RANGE = 0.3..2.5
        val OBJECT_RANGE = 0.1..10.0
    }
}
