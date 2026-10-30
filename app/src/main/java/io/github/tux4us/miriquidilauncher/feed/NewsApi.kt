// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/** Ergebnis der Prüfung einer eingegebenen Server-Adresse. */
sealed interface ServerUrl {
    /** [baseUrl] ohne abschließenden Schrägstrich, z. B. `https://cloud.example.org/nextcloud`. */
    data class Ok(val baseUrl: String) : ServerUrl
    data object Empty : ServerUrl

    /** Die Adresse beginnt mit `http://`; Zugangsdaten werden nur über HTTPS gesendet. */
    data object NotHttps : ServerUrl
    data object Invalid : ServerUrl
}

/**
 * Adressen und Antworten der Nextcloud-News-Schnittstelle (Version 1.3). Plattformunabhängig, damit die
 * Auswertung ohne Android auf der JVM testbar ist.
 */
object NewsApi {
    const val MAX_BODY_CHARS = 200_000

    /**
     * Prüft die Eingabe und liefert die Basisadresse der Nextcloud. Ohne Schema wird `https://` ergänzt.
     * Wurde die Adresse der News-App eingefügt (`…/index.php/apps/news`), wird der Teil entfernt. Eine Adresse
     * mit Benutzerangaben (`user:pass@host`) wird abgelehnt, die Zugangsdaten gehören in die eigenen Felder.
     */
    fun parseServerUrl(input: String): ServerUrl {
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
        if (host.isNullOrBlank() || uri.userInfo != null) return ServerUrl.Invalid
        val path = (uri.rawPath ?: "").substringBefore("/index.php").substringBefore("/apps/").trimEnd('/')
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return ServerUrl.Ok("https://${host.lowercase()}$port$path")
    }

    fun url(baseUrl: String, path: String): String = "$baseUrl/index.php/apps/news/api/v1-3/$path"

    fun basicAuth(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    /**
     * Abfrage einer Seite Artikel. [offset] ist die Id des ältesten bereits geladenen Artikels, 0 = neueste.
     * Mit [feedId] liefert der Server nur die Artikel dieses Abonnements (`type=0`), sonst alle (`type=3`).
     */
    fun itemsPath(includeRead: Boolean, batchSize: Int, offset: Long = 0, feedId: Long? = null): String =
        (if (feedId != null) "items?type=0&id=$feedId" else "items?type=3") +
            "&getRead=$includeRead&batchSize=$batchSize&oldestFirst=false" +
            if (offset > 0) "&offset=$offset" else ""

    fun parseFeeds(json: String): List<FeedInfo> {
        val feeds = MiniJson.parse(json).asObject()?.get("feeds").asArray() ?: return emptyList()
        return feeds.mapNotNull { entry ->
            val obj = entry.asObject() ?: return@mapNotNull null
            val id = obj["id"].asDouble()?.toLong() ?: return@mapNotNull null
            FeedInfo(id, obj["title"].asString().orEmpty().ifBlank { obj["link"].asString().orEmpty() }, obj["link"].asString())
        }
    }

    fun parseItems(json: String): List<FeedItem> {
        val items = MiniJson.parse(json).asObject()?.get("items").asArray() ?: return emptyList()
        return items.mapNotNull { entry ->
            val obj = entry.asObject() ?: return@mapNotNull null
            val id = obj["id"].asDouble()?.toLong() ?: return@mapNotNull null
            FeedItem(
                id = id,
                feedId = obj["feedId"].asDouble()?.toLong() ?: 0L,
                title = obj["title"].asString().orEmpty().trim(),
                author = obj["author"].asString()?.trim()?.takeIf { it.isNotEmpty() },
                url = obj["url"].asString()?.trim()?.takeIf { it.isNotEmpty() },
                body = obj["body"].asString().orEmpty().take(MAX_BODY_CHARS),
                publishedSeconds = obj["pubDate"].asDouble()?.toLong() ?: 0L,
                unread = obj["unread"] as? Boolean ?: false,
                starred = obj["starred"] as? Boolean ?: false,
            )
        }
    }
}
