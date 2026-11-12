// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import io.github.tux4us.miriquidilauncher.feed.FeedFetcher
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Netzwerkzugriff der Radio-Suche (radio-browser.info), nur nach Zustimmung des Nutzers. Ausschließlich HTTPS, keine Cookies,
 * keine Zugangsdaten, keine Kennungen. Gesendet werden der Suchbegriff bzw. beim Start der Ländercode der Geräteeinstellung.
 * Das Abrufen eines Senders zählt der Verzeichnisdienst nicht mit (kein „Klick“-Aufruf).
 */
object RadioRepository {
    private const val MAX_TRIES = 3

    @Volatile
    private var servers: List<String>? = null

    /**
     * Sucht Sender. Es werden nacheinander bis zu drei Server des Verzeichnisses versucht.
     * @throws IOException wenn kein Server antwortet
     */
    suspend fun search(mode: RadioSearch, query: String, allowHttp: Boolean, countryCode: String?): List<Station> {
        var failure: IOException? = null
        for (host in hosts().take(MAX_TRIES)) {
            val url = RadioBrowser.searchUrl(host, mode, query, allowHttp, countryCode) ?: return emptyList()
            try {
                return RadioBrowser.parseStations(FeedFetcher.fetch(url).text, allowHttp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        servers = null // beim nächsten Versuch die Serverliste neu abrufen
        throw failure ?: IOException("Kein Server erreichbar")
    }

    private suspend fun hosts(): List<String> {
        servers?.let { return it }
        val fetched = try {
            RadioBrowser.parseServers(FeedFetcher.fetch(RadioBrowser.SERVERS_URL).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emptyList()
        }
        val list = (fetched.ifEmpty { RadioBrowser.FALLBACK_HOSTS }).shuffled()
        servers = list
        return list
    }
}
