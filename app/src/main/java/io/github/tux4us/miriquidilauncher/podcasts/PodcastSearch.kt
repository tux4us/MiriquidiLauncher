// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.net.URLEncoder

/** Adressen und Antwort-Leser der Podcast-Verzeichnisse. Beide Dienste brauchen weder Konto noch Schlüssel. */
object PodcastSearch {
    const val MAX_QUERY = 100
    const val MAX_HITS = 30

    fun url(provider: SearchProvider, query: String): String? {
        val term = query.trim().take(MAX_QUERY)
        if (term.isEmpty()) return null
        val encoded = URLEncoder.encode(term, "UTF-8")
        return when (provider) {
            SearchProvider.ITUNES -> "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=$MAX_HITS&term=$encoded"
            SearchProvider.FYYD -> "https://api.fyyd.de/0.2/search/podcast?title=$encoded&count=$MAX_HITS"
        }
    }

    /** Ein ungültiger Text ergibt eine leere Liste; Treffer ohne HTTPS-Feed-Adresse entfallen. */
    fun parse(provider: SearchProvider, json: String): List<PodcastHit> {
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (ignored: MiniJson.JsonException) {
            null
        } ?: return emptyList()
        val list = when (provider) {
            SearchProvider.ITUNES -> root["results"].asArray()
            SearchProvider.FYYD -> root["data"].asArray()
        } ?: return emptyList()
        val seen = HashSet<String>()
        return list.mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val hit = when (provider) {
                SearchProvider.ITUNES -> hit(o["feedUrl"].asString(), o["collectionName"].asString(), o["artistName"].asString(), o["artworkUrl600"].asString() ?: o["artworkUrl100"].asString())
                SearchProvider.FYYD -> hit(o["xmlURL"].asString(), o["title"].asString(), o["author"].asString(), o["imgURL"].asString())
            }
            hit?.takeIf { seen.add(it.feedUrl.lowercase()) }
        }.take(MAX_HITS)
    }

    private fun hit(feed: String?, title: String?, author: String?, image: String?): PodcastHit? {
        val url = PodcastUrls.https(feed) ?: return null
        return PodcastHit(url, title?.trim().takeUnless { it.isNullOrEmpty() } ?: url, author?.trim().orEmpty(), PodcastUrls.https(image))
    }
}
