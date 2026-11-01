// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Rohdaten eines Abrufs; wird als Text zwischengespeichert, damit offline der letzte Stand sichtbar bleibt. */
data class RawReport(val forecastJson: String, val airJson: String?, val fetchedAtMillis: Long)

/**
 * Netzwerkzugriff auf Open-Meteo (Vorhersage, Luftqualität, Ortssuche) und Nominatim (Ortsname zu
 * Koordinaten). Es werden nur Koordinaten bzw. der eingegebene Suchbegriff übertragen, keine Kennungen.
 */
class WeatherRepository {

    /** [server] ist ein vom Nutzer eingetragener eigener Wetterserver; `null` = öffentliche Open-Meteo-Adressen. */
    suspend fun fetch(place: GeoPlace, server: WeatherServer? = null): RawReport = coroutineScope {
        val forecast = async(Dispatchers.IO) { get(forecastUrl(place, server)) }
        // Luftqualität ist optional (z. B. außerhalb Europas ohne Pollendaten); ein Fehler bricht nichts ab.
        val air = async(Dispatchers.IO) { runCatching { get(airUrl(place, server)) }.getOrNull() }
        RawReport(forecast.await(), air.await(), System.currentTimeMillis())
    }

    fun parse(raw: RawReport, place: GeoPlace): WeatherReport = WeatherReport(
        place = place,
        forecast = OpenMeteoParser.parseForecast(raw.forecastJson),
        air = raw.airJson?.let { runCatching { OpenMeteoParser.parseAirQuality(it) }.getOrNull() }.orEmpty(),
        fetchedAtMillis = raw.fetchedAtMillis,
    )

    suspend fun search(query: String, languageTag: String): List<GeoPlace> {
        val lang = languageTag.substringBefore('-').ifBlank { "en" }
        val url = WeatherEndpoints.PUBLIC_GEOCODING + "/v1/search?name=${enc(query.trim())}&count=8&language=$lang&format=json"
        return OpenMeteoParser.parseGeocoding(get(url))
    }

    /** Ortsname für Koordinaten oder `null`, wenn der Dienst nicht erreichbar ist. */
    suspend fun reverseGeocode(latitude: Double, longitude: Double, languageTag: String): String? =
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=${coord(latitude)}&lon=${coord(longitude)}" +
                "&format=jsonv2&zoom=10&accept-language=${enc(languageTag)}"
            OpenMeteoParser.parseReverseGeocoding(get(url))
        }.getOrNull()

    private fun forecastUrl(p: GeoPlace, server: WeatherServer?) = WeatherEndpoints.forecastBase(server) + "/v1/forecast?latitude=${coord(p.latitude)}&longitude=${coord(p.longitude)}" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,surface_pressure," +
        "wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,is_day" +
        "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,wind_speed_10m," +
        "wind_gusts_10m,wind_direction_10m,cape,surface_pressure,uv_index,shortwave_radiation" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max," +
        "wind_gusts_10m_max,uv_index_max,sunshine_duration,sunrise,sunset" +
        "&timezone=auto&forecast_days=7&past_days=1&wind_speed_unit=kmh" + WeatherEndpoints.keyParam(server)

    private fun airUrl(p: GeoPlace, server: WeatherServer?) = WeatherEndpoints.airBase(server) + "/v1/air-quality?latitude=${coord(p.latitude)}&longitude=${coord(p.longitude)}" +
        "&hourly=pm10,pm2_5,nitrogen_dioxide,ozone,birch_pollen,grass_pollen,alder_pollen,mugwort_pollen,olive_pollen,ragweed_pollen" +
        "&timezone=auto&forecast_days=3" + WeatherEndpoints.keyParam(server)

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("Antwort zu groß")
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun coord(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    private companion object {
        const val TIMEOUT_MS = 12_000
        const val MAX_BYTES = 4 * 1024 * 1024
        const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"
    }
}
