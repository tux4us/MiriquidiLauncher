// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import io.github.tux4us.miriquidilauncher.webapps.PinnedShortcut
import io.github.tux4us.miriquidilauncher.webapps.WebApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppSearchProviderTest {
    private val nextcloud = WebApp(id = "a1", name = "Nextcloud", url = "https://cloud.example.org")
    private val mail = WebApp(id = "b2", name = "Webmail Büro", url = "https://mail.example.org")
    private val pin = WebApp(id = "c3", name = "Wiki", url = "", pin = PinnedShortcut("com.example.browser", "wiki", 0L))

    private fun search(raw: String, apps: List<WebApp>): List<SearchResult> = runBlocking {
        WebAppSearchProvider { apps }.search(SearchQuery(raw, TextNormalizer.normalize(raw)))
    }

    @Test
    fun findsByNamePrefixAndIgnoresCase() {
        val hits = search("next", listOf(nextcloud, mail))
        assertEquals(listOf("webapp:a1"), hits.map { it.id })
        assertEquals("Nextcloud", hits.single().title)
    }

    @Test
    fun findsByWordPrefixAndUmlautFolding() {
        assertEquals(listOf("webapp:b2"), search("buro", listOf(nextcloud, mail)).map { it.id })
    }

    @Test
    fun doesNotSearchTheUrl() {
        assertTrue(search("example", listOf(nextcloud, mail)).isEmpty())
    }

    @Test
    fun includesPinnedShortcutsAndCarriesTheApp() {
        val hit = search("wiki", listOf(pin)).single() as WebAppResult
        assertEquals(pin, hit.app)
    }

    @Test
    fun emptyListGivesNoHits() {
        assertTrue(search("x", emptyList()).isEmpty())
    }

    @Test
    fun worksTogetherWithTheEngine() = runBlocking {
        val engine = SearchEngine(listOf(WebAppSearchProvider { listOf(nextcloud, mail) }))
        assertEquals(listOf("webapp:a1"), engine.search("Nextcloud").map { it.id })
    }
}
