// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.feed.FeedFetcher
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Netzwerkzugriffe der Podcast-Scope: Feeds, Verzeichnissuche und Downloads. Ausschließlich HTTPS,
 * Weiterleitungen nur zu HTTPS, keine Cookies, keine Zugangsdaten, keine Kennungen.
 */
object PodcastRepository {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_DOWNLOAD_BYTES = 1L * 1024 * 1024 * 1024
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"

    /** @throws io.github.tux4us.miriquidilauncher.feed.FeedParseException wenn kein Podcast-Feed */
    suspend fun loadFeed(feedUrl: String): ParsedPodcast {
        val page = FeedFetcher.fetch(feedUrl)
        return PodcastParser.parse(page.text, feedUrl)
    }

    suspend fun search(provider: SearchProvider, query: String): List<PodcastHit> {
        val url = PodcastSearch.url(provider, query) ?: return emptyList()
        return PodcastSearch.parse(provider, FeedFetcher.fetch(url).text)
    }

    /**
     * Lädt die Audiodatei in den App-Speicher. [onProgress] erhält 0–100, oder -1 wenn die Größe unbekannt ist.
     * Bei Abbruch oder Fehler wird die Teildatei gelöscht.
     */
    suspend fun download(episode: Episode, target: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            var url = URL(episode.audioUrl)
            for (hop in 0..MAX_REDIRECTS) {
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    val code = connection.responseCode
                    if (code in REDIRECTS && hop < MAX_REDIRECTS) {
                        val next = connection.getHeaderField("Location")
                            ?.let { runCatching { URI(url.toString()).resolve(it).toURL() }.getOrNull() }
                        if (next != null && next.protocol == "https") {
                            url = next
                            continue
                        }
                    }
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val total = connection.contentLengthLong
                    if (total > MAX_DOWNLOAD_BYTES) throw IOException("Datei zu groß")
                    var done = 0L
                    var lastPercent = -2
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (done > MAX_DOWNLOAD_BYTES) throw IOException("Datei zu groß")
                                val percent = if (total > 0) (done * 100 / total).toInt() else -1
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("Datei nicht speicherbar")
                    return@withContext
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Zu viele Weiterleitungen")
        } finally {
            if (part.exists()) part.delete()
        }
    }
}
