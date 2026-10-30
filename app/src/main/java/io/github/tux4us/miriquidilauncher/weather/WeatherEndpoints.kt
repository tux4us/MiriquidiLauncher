// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import io.github.tux4us.miriquidilauncher.feed.ServerUrl
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Eigener Wetterserver (Open-Meteo-kompatibel): selbst betriebene Instanz oder Abonnement mit API-Schlüssel.
 * [airBase] ist die Adresse für Luftqualität und Pollen; fehlt sie, gilt [forecastBase] auch dafür.
 */
data class WeatherServer(val forecastBase: String, val airBase: String?, val apiKey: String?)

/** Adressen der Wetterdienste. Ohne Eintrag eines eigenen Servers gelten die öffentlichen Open-Meteo-Adressen, ohne Schlüssel. */
object WeatherEndpoints {
    const val PUBLIC_FORECAST = "https://api.open-meteo.com"
    const val PUBLIC_AIR = "https://air-quality-api.open-meteo.com"
    const val PUBLIC_GEOCODING = "https://geocoding-api.open-meteo.com"
    const val MAX_KEY_LENGTH = 200

    fun forecastBase(server: WeatherServer?): String = server?.forecastBase ?: PUBLIC_FORECAST

    fun airBase(server: WeatherServer?): String = when {
        server == null -> PUBLIC_AIR
        else -> server.airBase ?: server.forecastBase
    }

    /** Anhang für die Abfrage (`&apikey=…`); der Schlüssel geht nur an einen eigenen Server, nie an die öffentlichen Adressen. */
    fun keyParam(server: WeatherServer?): String {
        val key = server?.apiKey?.takeIf { it.isNotEmpty() } ?: return ""
        return "&apikey=" + URLEncoder.encode(key, "UTF-8")
    }

    /**
     * Prüft die Eingabe: nur HTTPS, ohne Benutzerangaben, ohne Query und Fragment. Ohne Schema wird `https://`
     * ergänzt. Ein eingefügter Pfad ab `/v1/` (z. B. `…/v1/forecast`) wird abgeschnitten.
     */
    fun parseBase(input: String): ServerUrl {
        val text = input.trim()
        if (text.isEmpty()) return ServerUrl.Empty
        val withScheme = if (text.contains("://")) text else "https://$text"
        if (withScheme.startsWith("http://", ignoreCase = true)) return ServerUrl.NotHttps
        if (!withScheme.startsWith("https://", ignoreCase = true)) return ServerUrl.Invalid
        val uri = try {
            URI(withScheme)
        } catch (ignored: URISyntaxException) {
            return ServerUrl.Invalid
        }
        val host = uri.host
        if (host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return ServerUrl.Invalid
        val path = (uri.rawPath ?: "").substringBefore("/v1/").removeSuffix("/v1").trimEnd('/')
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return ServerUrl.Ok("https://${host.lowercase()}$port$path")
    }

    /** Ein Schlüssel darf keine Leerzeichen oder Steuerzeichen enthalten und höchstens [MAX_KEY_LENGTH] Zeichen lang sein. */
    fun isValidKey(key: String): Boolean = key.length <= MAX_KEY_LENGTH && key.none { it.isWhitespace() || it.isISOControl() }
}
