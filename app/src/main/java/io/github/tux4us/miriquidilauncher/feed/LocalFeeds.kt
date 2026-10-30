// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/**
 * Ein lokal abonnierter Feed (ohne Nextcloud). [url] ist die Adresse des Feeds. Feeds aus einer importierten
 * Datei ohne eigene Adresse tragen eine künstliche Adresse mit dem Präfix [LocalFeeds.STATIC_PREFIX]; sie
 * werden nicht abgerufen, ihre Artikel bleiben gespeichert.
 */
data class LocalFeed(val url: String, val title: String) {
    val id: Long get() = LocalFeeds.feedId(url)
    val isStatic: Boolean get() = url.startsWith(LocalFeeds.STATIC_PREFIX)
}

/** Ergebnis von [LocalFeeds.addFeeds]. */
data class AddFeedsResult(val feeds: List<LocalFeed>, val added: Int, val duplicates: Int, val overLimit: Int)

/**
 * Logik für lokale Abos: stabile Kennungen, Zusammenführen der Abrufe mit dem gespeicherten Stand und
 * Verwalten der Feed-Liste. Reine Funktionen ohne Android-Bezug.
 */
object LocalFeeds {
    const val STATIC_PREFIX = "import:"

    /** Höchstzahl der Abos; jeder Abruf besteht aus einer Anfrage pro Feed. */
    const val MAX_FEEDS = 100

    /** Pro Feed werden höchstens so viele Artikel übernommen. */
    const val MAX_ITEMS_PER_FEED = 50

    // Kennungen bleiben unter 2^52, weil der Zwischenspeicher Zahlen als Double (53 Bit Genauigkeit) liest.
    private const val ID_MASK = (1L shl 52) - 1

    fun feedId(url: String): Long = hash(url)

    fun itemId(feedUrl: String, key: String): Long = hash("$feedUrl\n$key")

    /** Künstliche Adresse für einen importierten Feed ohne eigene Adresse; gleiche Datei ergibt gleiche Adresse. */
    fun staticUrl(feed: ParsedFeed): String =
        STATIC_PREFIX + java.lang.Long.toHexString(hash(feed.title + "\n" + feed.items.take(5).joinToString("\n") { it.key }))

    private fun hash(text: String): Long {
        // FNV-1a, 64 Bit
        var h = 0xcbf29ce484222325uL.toLong()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        val v = (h xor (h ushr 52)) and ID_MASK
        return if (v == 0L) 1L else v
    }

    /** Fügt [incoming] an [existing] an; bereits vorhandene Adressen und Überschreitungen von [MAX_FEEDS] werden gezählt. */
    fun addFeeds(existing: List<LocalFeed>, incoming: List<LocalFeed>): AddFeedsResult {
        val result = ArrayList(existing)
        val known = existing.mapTo(HashSet()) { it.url.lowercase() }
        var added = 0
        var duplicates = 0
        var overLimit = 0
        for (feed in incoming) {
            when {
                !known.add(feed.url.lowercase()) -> duplicates++
                result.size >= MAX_FEEDS -> overLimit++
                else -> {
                    result.add(feed)
                    added++
                }
            }
        }
        return AddFeedsResult(result, added, duplicates, overLimit)
    }

    /** Anzeigename eines Feeds: Titel, sonst der Host der Adresse. */
    fun displayTitle(feed: LocalFeed): String =
        feed.title.ifBlank { feed.url.substringAfter("://").substringBefore('/') }

    /** Artikel eines Feeds in der Form der Anzeige. Ohne Datum gilt der Zeitpunkt, an dem der Artikel zuerst gesehen wurde. */
    fun items(
        feed: LocalFeed,
        parsed: ParsedFeed,
        known: Map<Long, FeedItem>,
        readIds: Set<Long>,
        nowSeconds: Long,
    ): List<FeedItem> {
        val feedId = feed.id
        val result = LinkedHashMap<Long, FeedItem>()
        for (entry in parsed.items) {
            val id = itemId(feed.url, entry.key)
            if (id in result) continue
            val published = if (entry.publishedSeconds > 0L) entry.publishedSeconds else known[id]?.publishedSeconds ?: nowSeconds
            result[id] = FeedItem(
                id = id,
                feedId = feedId,
                title = entry.title,
                author = entry.author,
                url = entry.url,
                body = entry.body,
                // Ein Feed mit Datum in der Zukunft würde sonst dauerhaft oben stehen.
                publishedSeconds = minOf(published, nowSeconds),
                unread = id !in readIds,
                starred = false,
            )
        }
        return result.values.sortedByDescending { it.publishedSeconds }.take(MAX_ITEMS_PER_FEED)
    }

    /**
     * Neue Artikelliste aus den Abrufen ([fetched], Schlüssel = Adresse des Feeds) und dem bisherigen Stand
     * [current]. Artikel entfernter Feeds fallen weg; Artikel von Feeds, die nicht geladen werden konnten
     * oder nicht mehr im Feed stehen, bleiben erhalten. Neueste zuerst, begrenzt auf [FeedLogic.MAX_ITEMS].
     */
    fun combine(
        feeds: List<LocalFeed>,
        fetched: Map<String, ParsedFeed>,
        current: List<FeedItem>,
        readIds: Set<Long>,
        nowSeconds: Long,
    ): List<FeedItem> {
        val subscribed = feeds.mapTo(HashSet()) { it.id }
        val kept = current.filter { it.feedId in subscribed }
        val known = kept.associateBy { it.id }
        val fresh = feeds.flatMap { feed ->
            val parsed = fetched[feed.url] ?: return@flatMap emptyList()
            items(feed, parsed, known, readIds, nowSeconds)
        }
        return FeedLogic.merge(readIds, fresh, kept)
    }

    /** Kennungen aller Artikel in [fetched]; nur Gelesen-Markierungen dazu (oder bereits sichtbarer Artikel) bleiben erhalten. */
    fun liveIds(feeds: List<LocalFeed>, fetched: Map<String, ParsedFeed>): Set<Long> {
        val ids = HashSet<Long>()
        for (feed in feeds) {
            val parsed = fetched[feed.url] ?: continue
            for (entry in parsed.items) ids.add(itemId(feed.url, entry.key))
        }
        return ids
    }
}
