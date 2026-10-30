// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scopes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopePagesTest {
    private val order = listOf("A", "B", "C", "Settings", "Camera")
    private val pinned = setOf("Settings")

    @Test
    fun pagesStartWithTheLauncher() {
        val pages = ScopePages(order)
        assertEquals(6, pages.count)
        assertNull(pages.idAt(ScopePages.LAUNCHER))
        assertEquals("A", pages.idAt(1))
        assertEquals("Camera", pages.idAt(5))
        assertNull(pages.idAt(6))
        assertNull(pages.idAt(-1))
        assertEquals(1, pages.pageOf("A"))
        assertEquals(4, pages.pageOf("Settings"))
        assertNull(pages.pageOf("Unknown"))
    }

    @Test
    fun hiddenScopesDisappearAndPagesShift() {
        val visible = ScopeVisibility.visible(order, setOf("A", "C"), pinned)
        val pages = ScopePages(visible)
        assertEquals(listOf("B", "Settings", "Camera"), visible)
        assertNull(pages.pageOf("A"))
        assertEquals(1, pages.pageOf("B"))
        assertEquals(2, pages.pageOf("Settings"))
        assertEquals(4, pages.count)
    }

    @Test
    fun pinnedScopeCannotBeHidden() {
        assertEquals(order, ScopeVisibility.visible(order, setOf("Settings"), pinned))
        assertEquals(emptySet<String>(), ScopeVisibility.withVisible(emptySet(), "Settings", visible = false, pinned = pinned))
    }

    @Test
    fun togglingChangesTheHiddenSet() {
        var hidden = emptySet<String>()
        hidden = ScopeVisibility.withVisible(hidden, "B", visible = false, pinned = pinned)
        hidden = ScopeVisibility.withVisible(hidden, "A", visible = false, pinned = pinned)
        assertEquals(setOf("A", "B"), hidden)
        hidden = ScopeVisibility.withVisible(hidden, "B", visible = true, pinned = pinned)
        assertEquals(setOf("A"), hidden)
    }

    @Test
    fun hiddenSetRoundTripsAndDropsUnknownNames() {
        val known = order.toSet()
        assertEquals("A,C", ScopeVisibility.encode(setOf("C", "A")))
        assertEquals(setOf("A", "C"), ScopeVisibility.decode("A,C", known))
        assertEquals(setOf("A"), ScopeVisibility.decode(" A ,Gone,", known))
        assertEquals(emptySet<String>(), ScopeVisibility.decode(null, known))
        assertEquals(emptySet<String>(), ScopeVisibility.decode("", known))
    }
}
