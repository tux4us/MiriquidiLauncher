// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Das Menü öffnet nur durch Tippen auf den Button; eine Auswahl durch Gleiten des Fingers gibt es nicht. */
class QuickMenuMachineTest {
    private val layout = QuickMenuLayout.compute(
        anchor = Bounds(100f, 700f, 260f, 752f),
        containerWidth = 360f,
        topInset = 24f,
        itemCount = 3,
        metrics = QuickMenuMetrics.fromDensity(1f),
    )

    private val many = QuickMenuLayout.compute(
        anchor = Bounds(100f, 700f, 260f, 752f),
        containerWidth = 360f,
        topInset = 24f,
        itemCount = 30,
        metrics = QuickMenuMetrics.fromDensity(1f),
    )

    private fun step(state: QuickMenuState, event: QuickMenuEvent, on: QuickMenuLayout = layout) =
        QuickMenuMachine.reduce(state, event, on)

    /** Führt mehrere Ereignisse nacheinander aus und gibt den letzten Übergang zurück. */
    private fun run(vararg events: QuickMenuEvent, on: QuickMenuLayout = layout): QuickMenuTransition {
        var transition = QuickMenuTransition(QuickMenuState.Closed)
        for (event in events) transition = step(transition.state, event, on)
        return transition
    }

    @Test
    fun tappingButtonOpensMenu() {
        val t = run(QuickMenuEvent.ShortPress)
        assertEquals(QuickMenuState.Open, t.state)
        assertNull(t.effect)
    }

    @Test
    fun openStateReportsOpen() {
        assertTrue(QuickMenuState.Open.isOpen)
        assertFalse(QuickMenuState.Closed.isOpen)
    }

    @Test
    fun tappingItemLaunchesAndCloses() {
        val t = run(QuickMenuEvent.ShortPress, QuickMenuEvent.ItemTapped(1))
        assertEquals(QuickMenuState.Closed, t.state)
        assertEquals(QuickMenuEffect.Launch(1), t.effect)
    }

    @Test
    fun tappingInvalidItemKeepsMenuOpen() {
        for (index in listOf(3, 5, -1)) {
            val t = run(QuickMenuEvent.ShortPress, QuickMenuEvent.ItemTapped(index))
            assertEquals("Index $index", QuickMenuState.Open, t.state)
            assertNull(t.effect)
        }
    }

    @Test
    fun dismissClosesMenu() {
        val t = run(QuickMenuEvent.ShortPress, QuickMenuEvent.Dismiss)
        assertEquals(QuickMenuState.Closed, t.state)
        assertNull(t.effect)
    }

    @Test
    fun tappingButtonAgainClosesMenu() {
        val t = run(QuickMenuEvent.ShortPress, QuickMenuEvent.ShortPress)
        assertEquals(QuickMenuState.Closed, t.state)
        assertNull(t.effect)
    }

    @Test
    fun closedMenuIgnoresItemTapsAndDismiss() {
        for (event in listOf(QuickMenuEvent.ItemTapped(0), QuickMenuEvent.Dismiss)) {
            val t = step(QuickMenuState.Closed, event)
            assertEquals("Ereignis $event", QuickMenuState.Closed, t.state)
            assertNull(t.effect)
        }
    }

    @Test
    fun menuWithManyRowsAcceptsEveryValidIndex() {
        for (index in listOf(0, 15, 29)) {
            val t = run(QuickMenuEvent.ShortPress, QuickMenuEvent.ItemTapped(index), on = many)
            assertEquals(QuickMenuState.Closed, t.state)
            assertEquals(QuickMenuEffect.Launch(index), t.effect)
        }
        val invalid = run(QuickMenuEvent.ShortPress, QuickMenuEvent.ItemTapped(30), on = many)
        assertEquals(QuickMenuState.Open, invalid.state)
        assertNull(invalid.effect)
    }

    @Test
    fun openingTwiceInARowNeverLaunchesAnything() {
        val first = run(QuickMenuEvent.ShortPress)
        val second = step(first.state, QuickMenuEvent.ShortPress)
        val third = step(second.state, QuickMenuEvent.ShortPress)
        assertEquals(QuickMenuState.Open, third.state)
        assertNull(first.effect)
        assertNull(second.effect)
        assertNull(third.effect)
    }
}
