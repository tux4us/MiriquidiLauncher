// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime

/** Auswertung der JSON-Antworten von Open-Meteo und Nominatim. */
object OpenMeteoParser {

    class ParseException(message: String) : Exception(message)

    fun parseForecast(json: String): ForecastData {
        val root = parseObject(json)
        val offset = root["utc_offset_seconds"].asDouble()?.toInt() ?: 0
        val cur = root["current"].asObject() ?: throw ParseException("Feld 'current' fehlt")
        val current = CurrentWeather(
            temperature = cur["temperature_2m"].asDouble() ?: throw ParseException("temperature_2m fehlt"),
            feelsLike = cur["apparent_temperature"].asDouble() ?: cur["temperature_2m"].asDouble() ?: 0.0,
            humidity = cur["relative_humidity_2m"].asDouble()?.toInt() ?: 0,
            precipitation = cur["precipitation"].asDouble() ?: 0.0,
            weatherCode = cur["weather_code"].asDouble()?.toInt() ?: -1,
            pressure = cur["surface_pressure"].asDouble() ?: 0.0,
            windSpeed = cur["wind_speed_10m"].asDouble() ?: 0.0,
            windDirection = cur["wind_direction_10m"].asDouble() ?: 0.0,
            windGusts = cur["wind_gusts_10m"].asDouble() ?: 0.0,
            uvIndex = cur["uv_index"].asDouble() ?: 0.0,
            isDay = (cur["is_day"].asDouble() ?: 1.0) >= 1.0,
        )
        return ForecastData(offset, current, parseHours(root["hourly"].asObject()), parseDays(root["daily"].asObject()))
    }

    private fun parseHours(hourly: Map<String, Any?>?): List<HourPoint> {
        hourly ?: return emptyList()
        val times = hourly["time"].asArray() ?: return emptyList()
        val temp = hourly["temperature_2m"].asArray()
        val feels = hourly["apparent_temperature"].asArray()
        val precip = hourly["precipitation"].asArray()
        val prob = hourly["precipitation_probability"].asArray()
        val code = hourly["weather_code"].asArray()
        val wind = hourly["wind_speed_10m"].asArray()
        val gusts = hourly["wind_gusts_10m"].asArray()
        val dir = hourly["wind_direction_10m"].asArray()
        val cape = hourly["cape"].asArray()
        val pressure = hourly["surface_pressure"].asArray()
        val uv = hourly["uv_index"].asArray()
        val out = ArrayList<HourPoint>(times.size)
        for (i in times.indices) {
            val time = parseDateTime(times.stringAt(i)) ?: continue
            val t = temp.doubleAt(i) ?: continue
            out += HourPoint(
                time = time,
                temperature = t,
                feelsLike = feels.doubleAt(i) ?: t,
                precipitation = precip.doubleAt(i) ?: 0.0,
                precipitationProbability = prob.doubleAt(i)?.toInt() ?: 0,
                weatherCode = code.doubleAt(i)?.toInt() ?: -1,
                windSpeed = wind.doubleAt(i) ?: 0.0,
                windGusts = gusts.doubleAt(i) ?: 0.0,
                windDirection = dir.doubleAt(i) ?: 0.0,
                cape = cape.doubleAt(i) ?: 0.0,
                pressure = pressure.doubleAt(i),
                uvIndex = uv.doubleAt(i) ?: 0.0,
            )
        }
        return out
    }

    private fun parseDays(daily: Map<String, Any?>?): List<DayPoint> {
        daily ?: return emptyList()
        val times = daily["time"].asArray() ?: return emptyList()
        val tMin = daily["temperature_2m_min"].asArray()
        val tMax = daily["temperature_2m_max"].asArray()
        val code = daily["weather_code"].asArray()
        val prob = daily["precipitation_probability_max"].asArray()
        val wind = daily["wind_speed_10m_max"].asArray()
        val gusts = daily["wind_gusts_10m_max"].asArray()
        val uv = daily["uv_index_max"].asArray()
        val sun = daily["sunshine_duration"].asArray()
        val rise = daily["sunrise"].asArray()
        val set = daily["sunset"].asArray()
        val out = ArrayList<DayPoint>(times.size)
        for (i in times.indices) {
            val date = try {
                LocalDate.parse(times.stringAt(i) ?: continue)
            } catch (ignored: DateTimeException) {
                continue
            }
            out += DayPoint(
                date = date,
                temperatureMin = tMin.doubleAt(i) ?: continue,
                temperatureMax = tMax.doubleAt(i) ?: continue,
                weatherCode = code.doubleAt(i)?.toInt() ?: -1,
                precipitationProbabilityMax = prob.doubleAt(i)?.toInt() ?: 0,
                windSpeedMax = wind.doubleAt(i) ?: 0.0,
                windGustsMax = gusts.doubleAt(i) ?: 0.0,
                uvIndexMax = uv.doubleAt(i) ?: 0.0,
                sunshineMinutes = (sun.doubleAt(i) ?: 0.0) / 60.0,
                sunrise = parseDateTime(rise.stringAt(i)),
                sunset = parseDateTime(set.stringAt(i)),
            )
        }
        return out
    }

    fun parseAirQuality(json: String): List<AirHour> {
        val hourly = parseObject(json)["hourly"].asObject() ?: return emptyList()
        val times = hourly["time"].asArray() ?: return emptyList()
        val pm25 = hourly["pm2_5"].asArray()
        val pm10 = hourly["pm10"].asArray()
        val no2 = hourly["nitrogen_dioxide"].asArray()
        val ozone = hourly["ozone"].asArray()
        val out = ArrayList<AirHour>(times.size)
        for (i in times.indices) {
            val time = parseDateTime(times.stringAt(i)) ?: continue
            val pollen = HashMap<PollenType, Double>()
            for (type in PollenType.values()) {
                hourly[type.apiKey].asArray().doubleAt(i)?.let { pollen[type] = it }
            }
            out += AirHour(
                time = time,
                pm25 = pm25.doubleAt(i) ?: 0.0,
                pm10 = pm10.doubleAt(i) ?: 0.0,
                no2 = no2.doubleAt(i) ?: 0.0,
                ozone = ozone.doubleAt(i) ?: 0.0,
                pollen = pollen,
            )
        }
        return out
    }

    /** Antwort der Open-Meteo-Geocoding-API (Ortssuche). */
    fun parseGeocoding(json: String): List<GeoPlace> {
        val results = parseObject(json)["results"].asArray() ?: return emptyList()
        return results.mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val name = o["name"].asString() ?: return@mapNotNull null
            val lat = o["latitude"].asDouble() ?: return@mapNotNull null
            val lon = o["longitude"].asDouble() ?: return@mapNotNull null
            val admin = o["admin1"].asString()?.takeIf { it != name }
            val country = o["country"].asString()
            val label = listOfNotNull(name, admin, country).joinToString(", ")
            GeoPlace(label, lat, lon)
        }
    }

    /** Antwort von Nominatim `reverse`: Name des Ortes oder `null`. */
    fun parseReverseGeocoding(json: String): String? {
        val root = parseObject(json)
        val address = root["address"].asObject() ?: return root["name"].asString()?.takeIf { it.isNotBlank() }
        val locality = listOf("city", "town", "village", "hamlet", "municipality", "suburb", "county")
            .firstNotNullOfOrNull { address[it].asString()?.takeIf { value -> value.isNotBlank() } }
        val region = address["state"].asString()?.takeIf { it.isNotBlank() && it != locality }
        return when {
            locality == null -> region
            region == null -> locality
            else -> "$locality, $region"
        }
    }

    private fun parseObject(json: String): Map<String, Any?> = try {
        MiniJson.parse(json).asObject() ?: throw ParseException("Kein JSON-Objekt")
    } catch (e: MiniJson.JsonException) {
        throw ParseException(e.message ?: "Ungültiges JSON")
    }

    private fun parseDateTime(text: String?): LocalDateTime? = try {
        text?.let { LocalDateTime.parse(it) }
    } catch (ignored: DateTimeException) {
        null
    }
}
