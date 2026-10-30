// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Der Server hat mit dem Statuscode [code] geantwortet, der keinen Erfolg bedeutet. */
class NewsHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Zugriff auf die Nextcloud-News-Schnittstelle (v1.3) mit Basic-Auth (Benutzer und App-Passwort). Es werden
 * nur Anfragen an die eigene Nextcloud gestellt, ausschließlich über HTTPS. Weiterleitungen werden nur
 * innerhalb desselben Hosts befolgt, damit die Zugangsdaten nirgendwohin sonst gesendet werden.
 */
class FeedRepository(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
) {
    suspend fun fetchFeeds(): List<FeedInfo> = NewsApi.parseFeeds(request("GET", "feeds"))

    /** Eine Seite Artikel, neueste zuerst. [offset] = Id des ältesten bereits bekannten Artikels (0 = ganz vorn). */
    suspend fun fetchItems(includeRead: Boolean, offset: Long = 0, feedId: Long? = null): List<FeedItem> =
        NewsApi.parseItems(request("GET", NewsApi.itemsPath(includeRead, PAGE_SIZE, offset, feedId)))

    suspend fun markRead(itemId: Long) {
        request("PUT", "items/$itemId/read")
    }

    private suspend fun request(method: String, path: String): String = withContext(Dispatchers.IO) {
        var target = URL(NewsApi.url(baseUrl, path))
        val host = target.host
        for (hop in 0..MAX_REDIRECTS) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", NewsApi.basicAuth(user, password))
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (method == "PUT") {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(0)
                    connection.outputStream.close()
                }
                val code = connection.responseCode
                if (code in REDIRECTS && hop < MAX_REDIRECTS) {
                    val next = connection.getHeaderField("Location")
                        ?.let { runCatching { URI(target.toString()).resolve(it).toURL() }.getOrNull() }
                    if (next != null && next.protocol == "https" && next.host.equals(host, ignoreCase = true)) {
                        target = next
                        continue
                    }
                }
                if (code !in 200..299) throw NewsHttpException(code)
                return@withContext readBody(connection)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    private fun readBody(connection: HttpURLConnection): String =
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("Antwort zu groß")
            }
            out.toString(Charsets.UTF_8.name())
        }

    companion object {
        /** Artikel pro Abruf. Klein gehalten, weil jeder Artikel den vollen Text enthält. */
        const val PAGE_SIZE = 60
        private const val TIMEOUT_MS = 20_000
        private const val MAX_BYTES = 24 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"
    }
}
