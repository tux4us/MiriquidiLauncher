// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

/** Suchanfrage: Originaltext und normalisierte Form (siehe [TextNormalizer]). */
data class SearchQuery(val raw: String, val normalized: String)

/** Ein Treffer eines [SearchProvider]. Höherer [score] bedeutet besserer Treffer. */
interface SearchResult {
    /** Stabiler, eindeutiger Schlüssel (für Listen-Keys und Deduplizierung). */
    val id: String
    val title: String
    val score: Int
}

/**
 * Quelle für Suchtreffer. Implementierungen müssen abbrechbar sein (Coroutine-Cancellation)
 * und dürfen blockierende Arbeit nur auf einem geeigneten Dispatcher ausführen.
 */
interface SearchProvider {
    suspend fun search(query: SearchQuery): List<SearchResult>
}
