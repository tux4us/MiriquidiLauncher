// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import io.github.tux4us.miriquidilauncher.webapps.WebApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebAppResult(val app: WebApp, override val score: Int) : SearchResult {
    override val id: String = "webapp:${app.id}"
    override val title: String = app.name
}

/**
 * Durchsucht die Webapps des Webapps-Bereichs nach dem Namen. [apps] liefert jeweils den aktuellen Bestand.
 * Es findet kein Netzwerkzugriff statt; die Adresse wird nicht durchsucht.
 */
class WebAppSearchProvider(private val apps: suspend () -> List<WebApp>) : SearchProvider {
    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val list = apps()
        return withContext(Dispatchers.Default) {
            list.mapNotNull { app ->
                val score = Matcher.score(query.normalized, TextNormalizer.normalize(app.name))
                if (score > Matcher.NO_MATCH) WebAppResult(app, score) else null
            }
        }
    }
}
