// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Führt alle [SearchProvider] parallel aus und führt die Treffer zusammen.
 *
 * Stabilitätsregeln:
 * - Jeder Provider hat ein eigenes Zeitlimit; ein langsamer Provider verzögert die übrigen nicht.
 * - Ausnahmen eines Providers (außer Abbruch) führen zu einer leeren Trefferliste dieses
 *   Providers, nicht zum Abbruch der Suche.
 * - Treffer mit identischer [SearchResult.id] werden dedupliziert (höchster Score gewinnt).
 */
class SearchEngine(
    private val providers: List<SearchProvider>,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
) {
    suspend fun search(rawQuery: String): List<SearchResult> {
        val query = SearchQuery(rawQuery.trim(), TextNormalizer.normalize(rawQuery))
        if (query.normalized.isEmpty()) return emptyList()

        val all = coroutineScope {
            providers
                .map { provider -> async { runProvider(provider, query) } }
                .awaitAll()
                .flatten()
        }

        return all
            .groupBy { it.id }
            .values
            .map { duplicates -> duplicates.maxBy { it.score } }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.title }.thenBy { it.id })
            .take(maxResults)
    }

    private suspend fun runProvider(provider: SearchProvider, query: SearchQuery): List<SearchResult> =
        try {
            withTimeoutOrNull(providerTimeoutMs) { provider.search(query) } ?: emptyList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            emptyList()
        }

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 400L
        const val DEFAULT_MAX_RESULTS = 30
    }
}
