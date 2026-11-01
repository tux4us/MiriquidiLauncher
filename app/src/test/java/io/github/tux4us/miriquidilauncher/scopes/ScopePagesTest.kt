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

    @Test
    fun orderFollowsSavedListAndKeepsEveryScopeOnce() {
        val known = listOf("A", "B", "C", "D")
        assertEquals(listOf("C", "A", "B", "D"), ScopeVisibility.orderOf(listOf("C", "A", "B", "D"), known))
        // Unbekannte und doppelte Kennungen entfallen.
        assertEquals(listOf("C", "A", "B", "D"), ScopeVisibility.orderOf(listOf("C", "X", "A", "C", "B", "D"), known))
        // Ohne gespeicherte Reihenfolge gilt die Standardreihenfolge.
        assertEquals(known, ScopeVisibility.orderOf(emptyList(), known))
    }

    @Test
    fun newScopesAreInsertedBehindTheirPredecessor() {
        // "B2" kam in einer neueren Version zwischen "B" und "C" hinzu, "Z" ganz an den Anfang.
        val known = listOf("Z", "A", "B", "B2", "C")
        assertEquals(listOf("Z", "C", "B", "B2", "A"), ScopeVisibility.orderOf(listOf("C", "B", "A"), known))
    }

    @Test
    fun movingChangesOneStepAndStopsAtTheEdges() {
        val list = listOf("A", "B", "C")
        assertEquals(listOf("B", "A", "C"), ScopeVisibility.move(list, "B", -1))
        assertEquals(listOf("A", "C", "B"), ScopeVisibility.move(list, "B", 1))
        assertEquals(list, ScopeVisibility.move(list, "A", -1))
        assertEquals(list, ScopeVisibility.move(list, "C", 1))
        assertEquals(list, ScopeVisibility.move(list, "Unknown", 1))
    }

    @Test
    fun orderRoundTripsAndFallsBackToDefault() {
        val known = listOf("A", "B", "C")
        assertEquals("C,A,B", ScopeVisibility.encodeOrder(listOf("C", "A", "B")))
        assertEquals(listOf("C", "A", "B"), ScopeVisibility.decodeOrder("C,A,B", known))
        // "C" fehlt in der gespeicherten Liste und kommt hinter seinen Vorgänger "B".
        assertEquals(listOf("B", "C", "A"), ScopeVisibility.decodeOrder(" B , Gone,A", known))
        assertEquals(known, ScopeVisibility.decodeOrder(null, known))
        assertEquals(known, ScopeVisibility.decodeOrder("", known))
    }

    @Test
    fun hiddenScopesStayHiddenInTheCustomOrder() {
        val custom = ScopeVisibility.orderOf(listOf("Camera", "C", "A", "Settings", "B"), order)
        val visible = ScopeVisibility.visible(custom, setOf("A"), pinned)
        assertEquals(listOf("Camera", "C", "Settings", "B"), visible)
        assertEquals(1, ScopePages(visible).pageOf("Camera"))
    }
}
