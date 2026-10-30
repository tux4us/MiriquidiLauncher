// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.quickmenu

import kotlin.math.ceil
import kotlin.math.floor

/** Rechteck in Bildschirmkoordinaten (Pixel). Plattformunabhängig, damit die Logik auf der JVM testbar bleibt. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}

/**
 * Maße des Schnellmenüs in Pixeln.
 *
 * @property cellWidth Breite einer Zelle im Grid
 * @property cellHeight Höhe einer Zelle (mit Beschriftung höher als ohne)
 * @property headerHeight Höhe der Kopfzeile oberhalb des Grids (Titel des Menüs)
 * @property maxColumns höchstens so viele Spalten
 * @property maxRows höchstens so viele sichtbare Zeilen
 */
data class QuickMenuMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
    val headerHeight: Float,
    val gap: Float,
    val edgeMargin: Float,
    val padding: Float,
    val maxColumns: Int,
    val maxRows: Int,
) {
    companion object {
        /**
         * @param showLabels `true`, wenn unter dem Symbol ein Name steht (höhere Zellen)
         * @param cellWidthDp Breite einer Zelle in dp; breitere Zellen für längere Namen
         */
        fun fromDensity(density: Float, showLabels: Boolean = false, cellWidthDp: Float = 72f): QuickMenuMetrics =
            QuickMenuMetrics(
                cellWidth = cellWidthDp * density,
                cellHeight = (if (showLabels) 88f else 68f) * density,
                headerHeight = 40f * density,
                gap = 12f * density,
                edgeMargin = 16f * density,
                padding = 12f * density,
                maxColumns = DEFAULT_MAX_COLUMNS,
                maxRows = DEFAULT_MAX_ROWS,
            )

        const val DEFAULT_MAX_COLUMNS = 4
        const val DEFAULT_MAX_ROWS = 4
    }
}

/**
 * Geometrie des geöffneten Schnellmenüs als Raster.
 *
 * Das Menü klappt oberhalb des Buttons auf und ist waagerecht auf dem Button zentriert (am Bildschirmrand
 * begrenzt). Oben im Panel liegt eine Kopfzeile, darunter das Raster. Eintrag 0 liegt links in der
 * untersten Zeile, direkt über dem Button; weitere Einträge folgen nach rechts und dann zeilenweise nach
 * oben.
 *
 * Das Menü zeigt höchstens [visibleRows] Zeilen, begrenzt durch [QuickMenuMetrics.maxRows] und den Platz
 * oberhalb des Buttons. Gibt es mehr Zeilen ([totalRows]), scrollt die Oberfläche mit dem Finger; die Geometrie
 * hängt davon nicht ab.
 *
 * Indizes in der Schnittstelle sind immer Indizes in der Eintragsliste (0 = erster Eintrag), Spalten zählen
 * von links ab 0.
 */
class QuickMenuLayout private constructor(
    val panel: Bounds,
    val columns: Int,
    val visibleRows: Int,
    val showsEmptyHint: Boolean,
    val cellWidth: Float,
    val cellHeight: Float,
    val headerHeight: Float,
    val padding: Float,
    /** Anzahl aller Einträge (auch der nicht sichtbaren). */
    val itemCount: Int,
) {
    /** Anzahl aller Zeilen bei [columns] Spalten. */
    val totalRows: Int get() = if (columns <= 0) 0 else ceil(itemCount.toDouble() / columns).toInt()

    companion object {
        /**
         * @param anchor Bereich des Buttons in Bildschirmkoordinaten
         * @param containerWidth Breite des Bildschirms
         * @param topInset Höhe der Statusleiste; das Menü ragt nicht darüber hinaus
         * @param itemCount Anzahl der verfügbaren Einträge
         */
        fun compute(
            anchor: Bounds,
            containerWidth: Float,
            topInset: Float,
            itemCount: Int,
            metrics: QuickMenuMetrics,
        ): QuickMenuLayout {
            val total = itemCount.coerceAtLeast(0)
            val showsEmptyHint = total <= 0

            val columnsFit = floor((containerWidth - 2 * metrics.edgeMargin - 2 * metrics.padding) / metrics.cellWidth)
                .toInt()
                .coerceAtLeast(1)
            val maxColumns = minOf(metrics.maxColumns, columnsFit)
            // Wenige Einträge ergeben ein schmales Panel; der Hinweistext braucht die volle Breite.
            val columns = if (showsEmptyHint) maxColumns else minOf(maxColumns, total)

            val totalRows = if (showsEmptyHint) 0 else ceil(total.toDouble() / columns).toInt()
            val panelBottom = anchor.top - metrics.gap
            val availableHeight = panelBottom - (topInset + metrics.edgeMargin)
            val fittingRows = floor((availableHeight - metrics.headerHeight - metrics.padding) / metrics.cellHeight)
                .toInt()
                .coerceAtLeast(0)
            val visibleRows = minOf(totalRows, metrics.maxRows, fittingRows)

            val shownRows = if (showsEmptyHint) 1 else visibleRows
            val panelHeight = metrics.headerHeight + shownRows * metrics.cellHeight + metrics.padding
            val panelWidth = columns * metrics.cellWidth + 2 * metrics.padding

            val maxLeft = (containerWidth - metrics.edgeMargin - panelWidth).coerceAtLeast(metrics.edgeMargin)
            val left = (anchor.centerX - panelWidth / 2f).coerceIn(metrics.edgeMargin, maxLeft)

            return QuickMenuLayout(
                panel = Bounds(left, panelBottom - panelHeight, left + panelWidth, panelBottom),
                columns = columns,
                visibleRows = visibleRows,
                showsEmptyHint = showsEmptyHint,
                cellWidth = metrics.cellWidth,
                cellHeight = metrics.cellHeight,
                headerHeight = metrics.headerHeight,
                padding = metrics.padding,
                itemCount = total,
            )
        }
    }
}
