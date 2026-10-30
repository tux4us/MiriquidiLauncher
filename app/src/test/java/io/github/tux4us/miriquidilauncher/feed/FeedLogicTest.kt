// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLogicTest {
    private fun item(id: Long, pub: Long = id * 10, unread: Boolean = true, title: String = "T$id") =
        FeedItem(id, 1, title, null, null, "", pub, unread, false)

    @Test
    fun mergeSortsNewestFirstAndFirstListWins() {
        val fetchedUnread = listOf(item(3, unread = true))
        val fetchedAll = listOf(item(3, unread = false), item(2, unread = false), item(4))
        val merged = FeedLogic.merge(emptySet(), fetchedUnread, fetchedAll)
        assertEquals(listOf(4L, 3L, 2L), merged.map { it.id })
        assertTrue(merged.first { it.id == 3L }.unread)
    }

    @Test
    fun mergeKeepsPendingReadAsRead() {
        val merged = FeedLogic.merge(setOf(5L), listOf(item(5), item(6)))
        assertEquals(false, merged.first { it.id == 5L }.unread)
        assertEquals(true, merged.first { it.id == 6L }.unread)
    }

    @Test
    fun mergeUsesIdAsTieBreakerAndLimits() {
        val same = FeedLogic.merge(emptySet(), listOf(item(1, pub = 5), item(2, pub = 5)))
        assertEquals(listOf(2L, 1L), same.map { it.id })
        val many = (1L..400L).map { item(it) }
        val limited = FeedLogic.merge(emptySet(), many)
        assertEquals(FeedLogic.MAX_ITEMS, limited.size)
        assertEquals(400L, limited.first().id)
    }

    @Test
    fun appendOlderKeepsCurrentState() {
        val current = listOf(item(10, unread = false), item(9))
        val older = listOf(item(10), item(8), item(7))
        val result = FeedLogic.appendOlder(current, older, emptySet())
        assertEquals(listOf(10L, 9L, 8L, 7L), result.map { it.id })
        assertEquals(false, result.first { it.id == 10L }.unread)
    }

    @Test
    fun filterAndOldestAndMarkRead() {
        val items = listOf(item(3, unread = false), item(2), item(1, unread = false))
        assertEquals(listOf(2L), FeedLogic.filter(items, unreadOnly = true).map { it.id })
        assertEquals(3, FeedLogic.filter(items, unreadOnly = false).size)
        assertEquals(1L, FeedLogic.oldestId(items))
        assertEquals(0L, FeedLogic.oldestId(emptyList()))
        assertEquals(false, FeedLogic.markRead(items, 2).first { it.id == 2L }.unread)
    }

    @Test
    fun cacheRoundTripKeepsSpecialCharacters() {
        val body = "<p>\"Zitat\" \\ Rückstrich\n\tTab \u0001 Steuerzeichen 😀 Ünïcode</p>"
        val snapshot = FeedSnapshot(
            feeds = listOf(FeedInfo(3, "Blog \"A\"", null), FeedInfo(4, "B", "https://b.example")),
            items = listOf(
                FeedItem(9, 3, "Titel", "Anna", "https://a.example/p", body, 1759650000, true, true),
                FeedItem(8, 4, "Ohne", null, null, "", 0, false, false),
            ),
            fetchedAtMillis = 1_759_650_000_123,
            pendingRead = setOf(8L, 2L),
        )
        assertEquals(snapshot, FeedCacheCodec.decode(FeedCacheCodec.encode(snapshot)))
    }

    @Test
    fun cacheDecodeRejectsDamagedOrForeignData() {
        assertNull(FeedCacheCodec.decode(""))
        assertNull(FeedCacheCodec.decode("{\"v\":1,\"items\":["))
        assertNull(FeedCacheCodec.decode("{\"v\":99}"))
        assertNull(FeedCacheCodec.decode("[]"))
        assertNotNull(FeedCacheCodec.decode("{\"v\":1}"))
    }

    private fun feedItem(id: Long, feed: Long, unread: Boolean = true) =
        FeedItem(id, feed, "T$id", null, null, "", id * 10, unread, false)

    @Test
    fun filterByFeedAndUnread() {
        val items = listOf(feedItem(1, 1), feedItem(2, 2), feedItem(3, 2, unread = false))
        assertEquals(listOf(1L, 2L, 3L), FeedLogic.filter(items, false).map { it.id })
        assertEquals(listOf(2L, 3L), FeedLogic.filter(items, false, feedId = 2).map { it.id })
        assertEquals(listOf(2L), FeedLogic.filter(items, true, feedId = 2).map { it.id })
        assertEquals(emptyList<Long>(), FeedLogic.filter(items, true, feedId = 9).map { it.id })
    }

    @Test
    fun unreadCountsPerFeed() {
        val items = listOf(feedItem(1, 1), feedItem(2, 2), feedItem(3, 2), feedItem(4, 3, unread = false))
        assertEquals(mapOf(1L to 1, 2L to 2), FeedLogic.unreadCounts(items))
    }

    @Test
    fun mergeKeepingRetainsOlderItemsOfSelectedFeed() {
        val max = FeedLogic.MAX_ITEMS
        val newest = (1..max).map { feedItem(1000L + it, 1) }
        val older = (1..10).map { feedItem(it.toLong(), 2) }
        val plain = FeedLogic.merge(emptySet(), newest, older)
        assertEquals(max, plain.size)
        assertTrue(plain.none { it.feedId == 2L })
        val kept = FeedLogic.mergeKeeping(emptySet(), 2L, newest, older)
        assertEquals(max + 10, kept.size)
        assertEquals(10, kept.count { it.feedId == 2L })
        assertEquals(max + 1000L, kept.first().id)
    }
}
