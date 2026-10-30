// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private data class Hit(
        override val id: String,
        override val title: String,
        override val score: Int,
    ) : SearchResult

    private fun provider(block: suspend (SearchQuery) -> List<SearchResult>) = object : SearchProvider {
        override suspend fun search(query: SearchQuery): List<SearchResult> = block(query)
    }

    @Test
    fun blankQueryReturnsNothingAndSkipsProviders() = runBlocking {
        var called = false
        val engine = SearchEngine(listOf(provider { called = true; emptyList() }))

        assertTrue(engine.search("   ").isEmpty())
        assertTrue(!called)
    }

    @Test
    fun mergesAndSortsByScoreThenTitle() = runBlocking {
        val engine = SearchEngine(
            listOf(
                provider { listOf(Hit("a", "Beta", 500), Hit("b", "Alpha", 500)) },
                provider { listOf(Hit("c", "Zulu", 900)) },
            ),
        )

        assertEquals(listOf("c", "b", "a"), engine.search("x").map { it.id })
    }

    @Test
    fun deduplicatesByIdKeepingHighestScore() = runBlocking {
        val engine = SearchEngine(
            listOf(
                provider { listOf(Hit("same", "Low", 100)) },
                provider { listOf(Hit("same", "High", 800)) },
            ),
        )

        val results = engine.search("x")
        assertEquals(1, results.size)
        assertEquals("High", results.single().title)
    }

    @Test
    fun failingProviderDoesNotAffectOthers() = runBlocking {
        val engine = SearchEngine(
            listOf(
                provider { throw IllegalStateException("boom") },
                provider { listOf(Hit("ok", "Ok", 500)) },
            ),
        )

        assertEquals(listOf("ok"), engine.search("x").map { it.id })
    }

    @Test
    fun slowProviderIsCutOffByTimeout() = runBlocking {
        val engine = SearchEngine(
            providers = listOf(
                provider { delay(5_000); listOf(Hit("slow", "Slow", 999)) },
                provider { listOf(Hit("fast", "Fast", 500)) },
            ),
            providerTimeoutMs = 50,
        )

        val started = System.nanoTime()
        val results = engine.search("x")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("fast"), results.map { it.id })
        assertTrue("took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun limitsNumberOfResults() = runBlocking {
        val engine = SearchEngine(
            providers = listOf(provider { (1..100).map { Hit("id$it", "T$it", it) } }),
            maxResults = 10,
        )

        val results = engine.search("x")
        assertEquals(10, results.size)
        assertEquals("id100", results.first().id)
    }

    @Test
    fun cancellationIsPropagated() {
        val engine = SearchEngine(listOf(provider { throw CancellationException("cancelled") }))

        val outcome = runCatching { runBlocking { engine.search("x") } }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }
}
