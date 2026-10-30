// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/** Zusammenführen und Filtern der Artikelliste; reine Funktionen ohne Android-Bezug. */
object FeedLogic {
    /** Höchstens so viele Artikel werden gehalten und gespeichert. */
    const val MAX_ITEMS = 300

    private val ORDER = compareByDescending<FeedItem> { it.publishedSeconds }.thenByDescending { it.id }

    /**
     * Vereinigt mehrere Abrufe. Bei gleicher Id gilt der zuerst genannte Artikel. Artikel, die lokal als
     * gelesen markiert wurden und deren Status der Server noch nicht kennt ([pendingRead]), bleiben gelesen.
     * Neueste zuerst, begrenzt auf [MAX_ITEMS].
     */
    fun merge(pendingRead: Set<Long>, vararg lists: List<FeedItem>): List<FeedItem> =
        mergeKeeping(pendingRead, null, *lists)

    /**
     * Wie [merge]. Zusätzlich bleiben bis zu [MAX_ITEMS] Artikel des Abonnements [keepFeedId] erhalten, auch
     * wenn sie älter sind als die [MAX_ITEMS] neuesten Artikel insgesamt. So lässt sich die Liste eines
     * einzelnen Abonnements weiter nach hinten laden.
     */
    fun mergeKeeping(pendingRead: Set<Long>, keepFeedId: Long?, vararg lists: List<FeedItem>): List<FeedItem> {
        val byId = LinkedHashMap<Long, FeedItem>()
        for (list in lists) for (item in list) byId.putIfAbsent(item.id, item)
        val sorted = byId.values
            .map { if (it.id in pendingRead && it.unread) it.copy(unread = false) else it }
            .sortedWith(ORDER)
        if (keepFeedId == null || sorted.size <= MAX_ITEMS) return sorted.take(MAX_ITEMS)
        val head = sorted.take(MAX_ITEMS)
        val extra = sorted.drop(MAX_ITEMS).filter { it.feedId == keepFeedId }
        val kept = head.count { it.feedId == keepFeedId }
        return head + extra.take((MAX_ITEMS - kept).coerceAtLeast(0))
    }

    /** Hängt eine ältere Seite an die bestehende Liste an (bestehende Einträge bleiben maßgeblich). */
    fun appendOlder(
        current: List<FeedItem>,
        older: List<FeedItem>,
        pendingRead: Set<Long>,
        keepFeedId: Long? = null,
    ): List<FeedItem> = mergeKeeping(pendingRead, keepFeedId, current, older)

    /** Filtert nach Gelesen-Status und, wenn [feedId] gesetzt ist, nach Abonnement. */
    fun filter(items: List<FeedItem>, unreadOnly: Boolean, feedId: Long? = null): List<FeedItem> =
        items.filter { (!unreadOnly || it.unread) && (feedId == null || it.feedId == feedId) }

    /** Anzahl ungelesener Artikel je Abonnement, bezogen auf die geladenen Artikel. */
    fun unreadCounts(items: List<FeedItem>): Map<Long, Int> =
        items.filter { it.unread }.groupingBy { it.feedId }.eachCount()

    /** Kleinste Id der Liste: Ausgangspunkt für "Mehr laden". 0, wenn die Liste leer ist. */
    fun oldestId(items: List<FeedItem>): Long = items.minOfOrNull { it.id } ?: 0L

    fun markRead(items: List<FeedItem>, id: Long): List<FeedItem> =
        items.map { if (it.id == id && it.unread) it.copy(unread = false) else it }
}
