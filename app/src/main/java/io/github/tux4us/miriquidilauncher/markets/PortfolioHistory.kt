// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

/**
 * Verlauf des Gesamtwerts von „Eigene Werte“ in Euro. [times] sind Unix-Sekunden (aufsteigend), [valuesEur] gleich lang.
 * [missing] ist die Zahl der Einträge, die nicht eingerechnet werden konnten (kein Verlauf oder kein Wechselkurs).
 */
data class PortfolioSeries(val times: List<Long>, val valuesEur: List<Double>, val missing: Int, val total: Int) {
    val isUsable: Boolean get() = valuesEur.size >= 2 && valuesEur.size == times.size

    /** Veränderung vom ersten zum letzten Punkt in Prozent; `null`, wenn der Anfangswert nicht positiv ist. */
    fun changePercent(): Double? {
        val first = valuesEur.firstOrNull() ?: return null
        val last = valuesEur.lastOrNull() ?: return null
        return if (first > 0.0) (last - first) / first * 100.0 else null
    }
}

/**
 * Berechnet den Verlauf des Gesamtwerts aus den Kursverläufen der einzelnen Einträge. Grundlage sind die heutigen
 * Mengen: Der Verlauf zeigt, was der aktuelle Bestand im gewählten Zeitraum wert gewesen wäre. Fremdwährungen werden mit
 * dem heutigen Wechselkurs in Euro umgerechnet (historische Wechselkurse werden nicht abgerufen).
 */
object PortfolioHistory {
    /** Höchstzahl der Punkte des Diagramms. */
    const val MAX_POINTS = 200

    /** Schlüssel eines Eintrags in der Menge der abgerufenen Verläufe (Art und Kennung). */
    fun seriesKey(position: Position): String = "${position.kind.key}:${position.symbol}"

    /** Krypto-Zeitraum zu einem Zeitraum der Kurs-Diagramme; CoinGecko liefert ohne Schlüssel höchstens 365 Tage. */
    fun cryptoRange(range: SeriesRange): CryptoRange = when (range) {
        SeriesRange.Day -> CryptoRange.Day
        SeriesRange.Week -> CryptoRange.Week
        SeriesRange.Month -> CryptoRange.Month
        SeriesRange.Year, SeriesRange.FiveYears -> CryptoRange.Year
    }

    /** Zeiträume, die für den Gesamtwert angeboten werden. */
    val ranges: List<SeriesRange> = listOf(SeriesRange.Day, SeriesRange.Week, SeriesRange.Month, SeriesRange.Year)

    private class Component(val quantity: Double, val times: List<Long>, val eurPerUnit: List<Double>)

    /**
     * Gesamtwert über die Zeit. Der Zeitraum beginnt beim spätesten Anfang der Einzelverläufe, damit jeder Eintrag von
     * Anfang an einen Wert hat; zwischen zwei Kursen gilt der letzte bekannte Kurs. `null`, wenn kein Eintrag einen
     * brauchbaren Verlauf hat.
     */
    fun combine(
        positions: List<Position>,
        seriesByKey: Map<String, Series>,
        prices: AssetPrices,
        maxPoints: Int = MAX_POINTS,
    ): PortfolioSeries? {
        val components = positions.mapNotNull { component(it, seriesByKey[seriesKey(it)], prices) }
        if (components.isEmpty()) return null
        val start = components.maxOf { it.times.first() }
        val end = components.maxOf { it.times.last() }
        val grid = components.flatMap { it.times }.filter { it in start..end }.distinct().sorted()
        if (grid.size < 2) return null
        val thinned = thin(grid, maxPoints)
        val pointer = IntArray(components.size)
        val values = ArrayList<Double>(thinned.size)
        for (t in thinned) {
            var sum = 0.0
            components.forEachIndexed { i, c ->
                var p = pointer[i]
                while (p + 1 < c.times.size && c.times[p + 1] <= t) p++
                pointer[i] = p
                sum += c.quantity * c.eurPerUnit[p]
            }
            values += sum
        }
        return PortfolioSeries(thinned, values, positions.size - components.size, positions.size)
    }

    private fun component(position: Position, series: Series?, prices: AssetPrices): Component? {
        if (series == null || !series.isUsable) return null
        val currency = when (position.kind) {
            PositionKind.Crypto -> "EUR"
            else -> series.currency ?: PositionsLogic.priceOf(position, prices)?.second
        } ?: return null
        val rate = FxLogic.convert(1.0, currency, "EUR", prices.perEur) ?: return null
        // Die Zeiten werden aufsteigend verarbeitet, auch wenn eine Quelle sie anders liefert.
        val sorted = series.times.indices.sortedBy { series.times[it] }
        return Component(
            position.quantity,
            sorted.map { series.times[it] },
            sorted.map { series.values[it] * rate },
        )
    }

    /** Verdünnt gleichmäßig auf höchstens [max] Punkte; erster und letzter bleiben erhalten. */
    internal fun thin(times: List<Long>, max: Int): List<Long> {
        if (times.size <= max || max < 2) return times
        val out = ArrayList<Long>(max)
        val step = (times.size - 1).toDouble() / (max - 1)
        var last = -1
        for (i in 0 until max) {
            val index = if (i == max - 1) times.lastIndex else (i * step).toInt()
            if (index != last) out += times[index]
            last = index
        }
        return out
    }
}
