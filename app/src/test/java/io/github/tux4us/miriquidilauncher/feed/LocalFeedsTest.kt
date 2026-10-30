// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeedsTest {
    private val a = LocalFeed("https://a.example/feed", "A")
    private val b = LocalFeed("https://b.example/feed", "B")

    private fun parsed(vararg items: ParsedItem) = ParsedFeed("T", null, null, items.toList())
    private fun entry(key: String, pub: Long = 100, title: String = "T-$key") = ParsedItem(key, title, null, null, "body", pub)

    @Test
    fun idsAreStableDistinctAndSafeForDoubleStorage() {
        assertEquals(LocalFeeds.feedId(a.url), LocalFeeds.feedId(a.url))
        assertTrue(LocalFeeds.feedId(a.url) != LocalFeeds.feedId(b.url))
        assertTrue(LocalFeeds.itemId(a.url, "1") != LocalFeeds.itemId(b.url, "1"))
        val limit = 1L shl 53
        for (i in 0 until 2000) {
            val id = LocalFeeds.itemId(a.url, "key-$i")
            assertTrue(id in 1 until limit)
            assertEquals(id, id.toDouble().toLong())
        }
    }

    @Test
    fun addFeedsSkipsDuplicatesCaseInsensitivelyAndLimits() {
        val first = LocalFeeds.addFeeds(emptyList(), listOf(a, b, LocalFeed("HTTPS://A.EXAMPLE/feed", "A2")))
        assertEquals(2, first.added)
        assertEquals(1, first.duplicates)
        val many = (1..LocalFeeds.MAX_FEEDS).map { LocalFeed("https://h$it.example/f", "H$it") }
        val limited = LocalFeeds.addFeeds(first.feeds, many)
        assertEquals(LocalFeeds.MAX_FEEDS, limited.feeds.size)
        assertEquals(LocalFeeds.MAX_FEEDS - 2, limited.added)
        assertEquals(2, limited.overLimit)
    }

    @Test
    fun itemsMarkReadAndFallBackToFirstSeenTimeForUndatedEntries() {
        val readId = LocalFeeds.itemId(a.url, "1")
        val first = LocalFeeds.items(a, parsed(entry("1"), entry("2", pub = 0)), emptyMap(), setOf(readId), nowSeconds = 5000)
        assertFalse(first.first { it.id == readId }.unread)
        val undated = first.first { it.id == LocalFeeds.itemId(a.url, "2") }
        assertTrue(undated.unread)
        assertEquals(5000L, undated.publishedSeconds)
        // beim nächsten Abruf bleibt die erste Sichtung maßgeblich
        val second = LocalFeeds.items(a, parsed(entry("2", pub = 0)), first.associateBy { it.id }, emptySet(), nowSeconds = 9000)
        assertEquals(5000L, second[0].publishedSeconds)
    }

    @Test
    fun itemsClampFutureDatesDeduplicateAndLimit() {
        val future = LocalFeeds.items(a, parsed(entry("1", pub = 99_999)), emptyMap(), emptySet(), nowSeconds = 1000)
        assertEquals(1000L, future[0].publishedSeconds)
        val dup = LocalFeeds.items(a, parsed(entry("1", title = "x"), entry("1", title = "y")), emptyMap(), emptySet(), 1000)
        assertEquals(1, dup.size)
        assertEquals("x", dup[0].title)
        val many = parsed(*(1..80).map { entry("k$it", pub = it.toLong()) }.toTypedArray())
        val limited = LocalFeeds.items(a, many, emptyMap(), emptySet(), 1000)
        assertEquals(LocalFeeds.MAX_ITEMS_PER_FEED, limited.size)
        assertEquals(80L, limited.first().publishedSeconds)
    }

    @Test
    fun combineKeepsItemsOfFailedFeedsAndDropsRemovedFeeds() {
        val old = LocalFeeds.items(a, parsed(entry("old", pub = 10)), emptyMap(), emptySet(), 1000) +
            LocalFeeds.items(b, parsed(entry("b1", pub = 20)), emptyMap(), emptySet(), 1000)
        // Feed a wurde nicht geladen, Feed b ist nicht mehr abonniert
        val result = LocalFeeds.combine(listOf(a), emptyMap(), old, emptySet(), 1000)
        assertEquals(listOf(LocalFeeds.itemId(a.url, "old")), result.map { it.id })
    }

    @Test
    fun combineMergesFreshAndKnownSortedNewestFirstAndKeepsReadState() {
        val knownItem = LocalFeeds.items(a, parsed(entry("1", pub = 10)), emptyMap(), emptySet(), 1000)
        val readIds = setOf(LocalFeeds.itemId(a.url, "1"))
        val fetched = mapOf(a.url to parsed(entry("1", pub = 10), entry("2", pub = 50)))
        val result = LocalFeeds.combine(listOf(a), fetched, knownItem, readIds, 1000)
        assertEquals(listOf("2", "1").map { LocalFeeds.itemId(a.url, it) }, result.map { it.id })
        assertTrue(result[0].unread)
        assertFalse(result[1].unread)
    }

    @Test
    fun liveIdsListAllFetchedItems() {
        val fetched = mapOf(a.url to parsed(entry("1"), entry("2")))
        assertEquals(setOf("1", "2").map { LocalFeeds.itemId(a.url, it) }.toSet(), LocalFeeds.liveIds(listOf(a, b), fetched))
    }

    @Test
    fun displayTitleFallsBackToHost() {
        assertEquals("A", LocalFeeds.displayTitle(a))
        assertEquals("c.example", LocalFeeds.displayTitle(LocalFeed("https://c.example/x/y", "")))
    }
}
