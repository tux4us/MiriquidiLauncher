// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tux4us.miriquidilauncher.data.AppEntry

data class AppResult(val entry: AppEntry, override val score: Int) : SearchResult {
    override val id: String = "app:${entry.key}"
    override val title: String = entry.label
}

/** Durchsucht die installierten Apps. [apps] liefert jeweils den aktuellen Bestand. */
class AppSearchProvider(private val apps: () -> List<AppEntry>) : SearchProvider {
    override suspend fun search(query: SearchQuery): List<SearchResult> =
        withContext(Dispatchers.Default) {
            apps().mapNotNull { entry ->
                val score = Matcher.score(query.normalized, entry.searchKey)
                if (score > Matcher.NO_MATCH) AppResult(entry, score) else null
            }
        }
}
