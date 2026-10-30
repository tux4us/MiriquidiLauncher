// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referenzaufbau (Dichte 1, ohne Beschriftung): Bildschirm 360 breit, Statusleiste 24, Button von
 * (100, 700) bis (260, 752), Mitte also bei x = 180. Zelle 72 x 68, Kopfzeile 40, Polster 12, Lücke 12.
 *
 * Bei 3 Einträgen (3 Spalten, 1 Zeile): Panel (60, 568)–(300, 688).
 *
 * Bei 30 Einträgen (4 Spalten, 8 Zeilen, 4 sichtbar): Panel (24, 364)–(336, 688).
 */
class QuickMenuLayoutTest {
    private val metrics = QuickMenuMetrics.fromDensity(1f)
    private val anchor = Bounds(100f, 700f, 260f, 752f)

    private fun layout(itemCount: Int, anchor: Bounds = this.anchor, width: Float = 360f) =
        QuickMenuLayout.compute(anchor, containerWidth = width, topInset = 24f, itemCount = itemCount, metrics = metrics)

    @Test
    fun panelSitsAboveAnchorAndCentersOnButton() {
        val panel = layout(3).panel
        assertEquals(60f, panel.left, 0.001f)
        assertEquals(568f, panel.top, 0.001f)
        assertEquals(300f, panel.right, 0.001f)
        assertEquals(688f, panel.bottom, 0.001f)
    }

    @Test
    fun fewItemsGiveNarrowPanel() {
        assertEquals(1, layout(1).columns)
        assertEquals(2, layout(2).columns)
        assertEquals(3, layout(3).columns)
        assertEquals(4, layout(4).columns)
        assertEquals(4, layout(9).columns)
    }

    @Test
    fun itemsFillRowsOfUpToFourColumns() {
        val seven = layout(7)
        assertEquals(4, seven.columns)
        assertEquals(2, seven.totalRows)
        assertEquals(2, seven.visibleRows)
        assertEquals(7, seven.itemCount)
        assertEquals(1, layout(3).totalRows)
        assertEquals(3, layout(11).totalRows)
    }

    @Test
    fun visibleRowsAreLimitedByMaxRows() {
        assertEquals(4, layout(30).visibleRows)
        assertEquals(8, layout(30).totalRows)
    }

    @Test
    fun visibleRowsAreLimitedBySpaceAboveButton() {
        // panelBottom 288, verfügbar 288 - (24 + 16) = 248, (248 - 40 - 12) / 68 = 2,88 -> 2
        val high = Bounds(100f, 300f, 260f, 352f)
        assertEquals(2, layout(30, high).visibleRows)
    }

    @Test
    fun noSpaceYieldsNoItems() {
        val veryHigh = Bounds(100f, 100f, 260f, 152f)
        val l = layout(5, veryHigh)
        assertEquals(0, l.visibleRows)
    }

    @Test
    fun emptyListShowsHintPanelWithoutItems() {
        val l = layout(0)
        assertTrue(l.showsEmptyHint)
        assertEquals(0, l.visibleRows)
        assertEquals(120f, l.panel.height, 0.001f)
        assertEquals(312f, l.panel.width, 0.001f)
    }

    @Test
    fun withItemsNoHintIsShown() {
        assertFalse(layout(3).showsEmptyHint)
    }

    @Test
    fun panelIsClampedToLeftEdge() {
        val left = Bounds(0f, 700f, 80f, 752f)
        val panel = layout(30, left).panel
        assertEquals(16f, panel.left, 0.001f)
        assertEquals(328f, panel.right, 0.001f)
    }

    @Test
    fun panelIsClampedToRightEdge() {
        val right = Bounds(280f, 700f, 360f, 752f)
        val panel = layout(30, right).panel
        assertEquals(32f, panel.left, 0.001f)
        assertEquals(344f, panel.right, 0.001f)
    }

    @Test
    fun columnsShrinkOnNarrowScreens() {
        // (200 - 32 - 24) / 72 = 2
        val l = layout(5, Bounds(60f, 700f, 140f, 752f), width = 200f)
        assertEquals(2, l.columns)
        assertEquals(3, l.totalRows)
        assertEquals(16f, l.panel.left, 0.001f)
        assertEquals(184f, l.panel.right, 0.001f)
    }

    @Test
    fun labelledCellsAreHigher() {
        val labelled = QuickMenuMetrics.fromDensity(1f, showLabels = true)
        assertTrue(labelled.cellHeight > metrics.cellHeight)
    }

    @Test
    fun widerCellsWidenThePanel() {
        val wide = QuickMenuMetrics.fromDensity(1f, showLabels = true, cellWidthDp = 96f)
        // (360 - 32 - 24) / 96 = 3 Spalten möglich, 2 Einträge ergeben 2 Spalten: 2 * 96 + 24 = 216
        val l = QuickMenuLayout.compute(anchor, 360f, 24f, 2, wide)
        assertEquals(2, l.columns)
        assertEquals(216f, l.panel.width, 0.001f)
        assertEquals(96f, l.cellWidth, 0.001f)
    }

}
