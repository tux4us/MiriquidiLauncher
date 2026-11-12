// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.net.URI
import java.net.URLEncoder

enum class RadioSearch { TOP, NAME, TAG, COUNTRY }

/**
 * Adressen und Antwort-Leser des Senderverzeichnisses radio-browser.info (freie Community-Datenbank, ohne Konto und
 * ohne Schlüssel). Es wird nur der Suchbegriff (und beim Start der Ländercode der Geräteeinstellung) gesendet.
 */
object RadioBrowser {
    const val MAX_QUERY = 60
    const val LIMIT = 40

    /** Ausweichadressen, falls die Serverliste nicht abrufbar ist. */
    val FALLBACK_HOSTS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")

    const val SERVERS_URL = "https://all.api.radio-browser.info/json/servers"

    private val HOST = Regex("^[a-z0-9-]+\\.api\\.radio-browser\\.info$")

    /** Liest die Serverliste (`[{"name":"de1.api.radio-browser.info", ...}]`); nur Hosts der Domain api.radio-browser.info zählen. */
    fun parseServers(json: String): List<String> {
        val list = try {
            MiniJson.parse(json).asArray()
        } catch (ignored: MiniJson.JsonException) {
            null
        } ?: return emptyList()
        return list.mapNotNull { it.asObject()?.get("name").asString()?.trim()?.lowercase() }
            .filter { HOST.matches(it) }
            .distinct()
    }

    /**
     * Such-Adresse für [host]. [countryCode] (zweistellig) wirkt nur bei [RadioSearch.TOP]. Ohne HTTP-Erlaubnis
     * verlangt die Suche bereits serverseitig HTTPS-Streams. `null` bei leerem Suchbegriff (außer TOP) oder ungültigem Host.
     */
    fun searchUrl(host: String, mode: RadioSearch, query: String, allowHttp: Boolean, countryCode: String? = null): String? {
        if (!HOST.matches(host)) return null
        val term = query.trim().take(MAX_QUERY)
        if (mode != RadioSearch.TOP && term.isEmpty()) return null
        val sb = StringBuilder("https://").append(host).append("/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=").append(LIMIT)
        if (!allowHttp) sb.append("&is_https=true")
        when (mode) {
            RadioSearch.TOP -> countryCode?.trim()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
                ?.let { sb.append("&countrycode=").append(it.uppercase()) }
            RadioSearch.NAME -> sb.append("&name=").append(encode(term))
            RadioSearch.TAG -> sb.append("&tag=").append(encode(term))
            RadioSearch.COUNTRY -> sb.append("&country=").append(encode(term))
        }
        return sb.toString()
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    /**
     * Prüft eine Stream-Adresse: HTTPS, mit [allowHttp] auch HTTP; keine Zugangsdaten in der Adresse, keine Leerzeichen,
     * höchstens 2048 Zeichen. Liefert die bereinigte Adresse oder `null`. Eine HTTP-Adresse wird nie auf HTTPS umgeschrieben.
     */
    fun playableUrl(raw: String?, allowHttp: Boolean): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > 2048 || text.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try {
            URI(text)
        } catch (ignored: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return text
    }

    /** Logos werden nur über HTTPS geladen; `http://` wird hochgestuft (ein Fehlschlag zeigt dann das Ersatzbild). */
    fun logoUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val upgraded = if (text.startsWith("http://", ignoreCase = true)) "https://" + text.substring(7) else text
        return playableUrl(upgraded, allowHttp = false)
    }

    /**
     * Liest die Trefferliste. Übernommen werden Sender mit abspielbarer Adresse ([allowHttp] beachtet), ohne gemeldeten
     * Zertifikatsfehler bei HTTPS und nicht als defekt markiert. Doppelte Kennungen und Adressen entfallen.
     */
    fun parseStations(json: String, allowHttp: Boolean): List<Station> {
        val list = try {
            MiniJson.parse(json).asArray()
        } catch (ignored: MiniJson.JsonException) {
            null
        } ?: return emptyList()
        val ids = HashSet<String>()
        val urls = HashSet<String>()
        return list.mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val id = o["stationuuid"].asString()?.trim().orEmpty()
            if (id.isEmpty() || id.length > 64) return@mapNotNull null
            if (o["lastcheckok"].asDouble() == 0.0) return@mapNotNull null
            val url = playableUrl(o["url_resolved"].asString()?.takeIf { it.isNotBlank() } ?: o["url"].asString(), allowHttp)
                ?: return@mapNotNull null
            if (url.startsWith("https://", ignoreCase = true) && (o["ssl_error"].asDouble() ?: 0.0) != 0.0) return@mapNotNull null
            val name = o["name"].asString()?.trim()?.take(MusicLogic.MAX_NAME).orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            if (!ids.add(id) || !urls.add(url.lowercase())) return@mapNotNull null
            Station(
                id = id,
                name = name,
                streamUrl = url,
                homepage = o["homepage"].asString()?.let { playableUrl(it, allowHttp = true) },
                logoUrl = logoUrl(o["favicon"].asString()),
                country = o["country"].asString()?.trim().orEmpty().take(60),
                tags = o["tags"].asString().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ").take(80),
                codec = o["codec"].asString()?.trim().orEmpty().take(12),
                bitrate = (o["bitrate"].asDouble()?.toInt() ?: 0).coerceIn(0, 2000),
            )
        }.take(LIMIT)
    }
}
