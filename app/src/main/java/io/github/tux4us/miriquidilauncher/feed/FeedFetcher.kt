// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ruft Feeds beliebiger Quellen ab (lokale Abos ohne Nextcloud). Ausschließlich HTTPS; Weiterleitungen
 * werden nur zu HTTPS-Adressen befolgt. Es werden weder Zugangsdaten noch Cookies gesendet, die Antwort ist
 * auf [MAX_BYTES] begrenzt.
 */
object FeedFetcher {
    /** Eine geladene Seite: [text] ist dekodiert, [finalUrl] die Adresse nach allen Weiterleitungen. */
    class Page(val text: String, val finalUrl: String)

    /** @throws NewsHttpException bei einem Fehlerstatus, [IOException] bei Netzwerkfehlern, [FeedParseException] wenn kein Feed */
    suspend fun loadFeed(url: String): ParsedFeed = FeedParser.parse(fetch(url).text)

    /**
     * Lädt [url] als Feed. Ist die Antwort eine Webseite, wird deren Feed-Verweis (`<link rel="alternate">`)
     * gesucht und einmal gefolgt. Ergebnis: die tatsächliche Feed-Adresse und der Feed.
     */
    suspend fun loadOrDiscover(url: String): Pair<String, ParsedFeed> {
        val page = fetch(url)
        try {
            return url to FeedParser.parse(page.text)
        } catch (first: FeedParseException) {
            val discovered = FeedDiscovery.find(page.text, page.finalUrl) ?: throw first
            return discovered to loadFeed(discovered)
        }
    }

    suspend fun fetch(url: String): Page = withContext(Dispatchers.IO) {
        var target = URL(url)
        for (hop in 0..MAX_REDIRECTS) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", ACCEPT)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in REDIRECTS && hop < MAX_REDIRECTS) {
                    val next = connection.getHeaderField("Location")
                        ?.let { runCatching { URI(target.toString()).resolve(it).toURL() }.getOrNull() }
                    if (next != null && next.protocol == "https") {
                        target = next
                        continue
                    }
                }
                if (code !in 200..299) throw NewsHttpException(code)
                val bytes = readBody(connection)
                return@withContext Page(FeedParser.decode(bytes, connection.contentType), target.toString())
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    private fun readBody(connection: HttpURLConnection): ByteArray =
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("Antwort zu groß")
            }
            out.toByteArray()
        }

    private const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val ACCEPT =
        "application/rss+xml, application/atom+xml, application/feed+json, application/json, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.5, */*;q=0.1"
    private const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"
}
