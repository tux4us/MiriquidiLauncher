// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/** Ein Artikel aus der Nextcloud-News-App. [publishedSeconds] ist 0, wenn der Feed kein Datum liefert. */
data class FeedItem(
    val id: Long,
    val feedId: Long,
    val title: String,
    val author: String?,
    val url: String?,
    /** Artikeltext als HTML, wie ihn der Feed liefert (gekürzt auf [NewsApi.MAX_BODY_CHARS] Zeichen). */
    val body: String,
    val publishedSeconds: Long,
    val unread: Boolean,
    val starred: Boolean,
)

/** Ein abonnierter Feed der News-App. */
data class FeedInfo(val id: Long, val title: String, val link: String?)

/**
 * Lokal gespeicherter Stand für die Offline-Anzeige.
 *
 * @property pendingRead Artikel, die lokal als gelesen markiert wurden, deren Status der Server aber noch
 *   nicht bestätigt hat (z. B. weil das Gerät offline war)
 */
data class FeedSnapshot(
    val feeds: List<FeedInfo>,
    val items: List<FeedItem>,
    val fetchedAtMillis: Long,
    val pendingRead: Set<Long>,
)
