// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioHistoryTest {
    private val prices = AssetPrices(mapOf("USD" to 1.25), emptyList(), null, emptyMap())

    private fun series(times: List<Long>, values: List<Double>, currency: String?) = Series(times, values, null, currency, 0L)

    private val btc = Position("bitcoin", "Bitcoin", "BTC", 2.0, PositionKind.Crypto)
    private val gold = Position("gold", "Gold", "", 10.0, PositionKind.Commodity)
    private val stock = Position("SAP.DE", "SAP", "716460", 5.0, PositionKind.Stock)

    @Test
    fun sumsPositionsInEuroWithQuantities() {
        val result = PortfolioHistory.combine(
            listOf(btc, stock),
            mapOf(
                PortfolioHistory.seriesKey(btc) to series(listOf(0, 10, 20), listOf(100.0, 110.0, 120.0), "EUR"),
                PortfolioHistory.seriesKey(stock) to series(listOf(0, 10, 20), listOf(10.0, 10.0, 20.0), "EUR"),
            ),
            prices,
        )
        assertNotNull(result)
        assertEquals(listOf(0L, 10L, 20L), result!!.times)
        assertEquals(listOf(250.0, 270.0, 340.0), result.valuesEur)
        assertEquals(0, result.missing)
        assertEquals(2, result.total)
        assertTrue(result.isUsable)
    }

    @Test
    fun convertsForeignCurrencyWithTodaysRate() {
        // 1,25 USD je Euro: 10 Stück zu 12,5 USD sind 100 EUR.
        val result = PortfolioHistory.combine(
            listOf(gold),
            mapOf(PortfolioHistory.seriesKey(gold) to series(listOf(0, 10), listOf(12.5, 25.0), "USD")),
            prices,
        )!!
        assertEquals(listOf(100.0, 200.0), result.valuesEur)
    }

    @Test
    fun forwardFillsBetweenQuotesAndStartsWhereEverySeriesHasData() {
        val result = PortfolioHistory.combine(
            listOf(btc, stock),
            mapOf(
                PortfolioHistory.seriesKey(btc) to series(listOf(0, 5, 10, 15, 20), listOf(1.0, 2.0, 3.0, 4.0, 5.0), "EUR"),
                // Die Aktie hat erst ab Zeit 10 Kurse und nur zwei davon.
                PortfolioHistory.seriesKey(stock) to series(listOf(10, 20), listOf(100.0, 200.0), "EUR"),
            ),
            prices,
        )!!
        assertEquals(listOf(10L, 15L, 20L), result.times)
        // btc: 2 Stück; Aktie: 5 Stück, zwischen 10 und 20 gilt 100.
        assertEquals(listOf(2 * 3.0 + 500.0, 2 * 4.0 + 500.0, 2 * 5.0 + 1000.0), result.valuesEur)
    }

    @Test
    fun countsPositionsWithoutUsableSeriesOrRate() {
        val noRate = Position("X", "X", "", 1.0, PositionKind.Stock)
        val result = PortfolioHistory.combine(
            listOf(btc, gold, noRate),
            mapOf(
                PortfolioHistory.seriesKey(btc) to series(listOf(0, 10), listOf(1.0, 2.0), "EUR"),
                PortfolioHistory.seriesKey(noRate) to series(listOf(0, 10), listOf(1.0, 2.0), "ZZZ"),
            ),
            prices,
        )!!
        assertEquals(2, result.missing)
        assertEquals(3, result.total)
        assertEquals(listOf(2.0, 4.0), result.valuesEur)
    }

    @Test
    fun noUsableSeriesGivesNull() {
        assertNull(PortfolioHistory.combine(listOf(btc), emptyMap(), prices))
        assertNull(PortfolioHistory.combine(emptyList(), emptyMap(), prices))
        assertNull(
            PortfolioHistory.combine(
                listOf(btc),
                mapOf(PortfolioHistory.seriesKey(btc) to series(listOf(5), listOf(1.0), "EUR")),
                prices,
            ),
        )
    }

    @Test
    fun cryptoSeriesAreAlwaysEuro() {
        val result = PortfolioHistory.combine(
            listOf(btc),
            mapOf(PortfolioHistory.seriesKey(btc) to series(listOf(0, 10), listOf(1.0, 2.0), null)),
            prices,
        )!!
        assertEquals(listOf(2.0, 4.0), result.valuesEur)
    }

    @Test
    fun unsortedTimesAreHandled() {
        val result = PortfolioHistory.combine(
            listOf(btc),
            mapOf(PortfolioHistory.seriesKey(btc) to series(listOf(20, 0, 10), listOf(3.0, 1.0, 2.0), "EUR")),
            prices,
        )!!
        assertEquals(listOf(0L, 10L, 20L), result.times)
        assertEquals(listOf(2.0, 4.0, 6.0), result.valuesEur)
    }

    @Test
    fun thinsToMaxPointsKeepingFirstAndLast() {
        val times = (0L until 1000L).toList()
        val result = PortfolioHistory.combine(
            listOf(btc),
            mapOf(PortfolioHistory.seriesKey(btc) to series(times, times.map { it + 1.0 }, "EUR")),
            prices,
            maxPoints = 100,
        )!!
        assertTrue(result.times.size <= 100)
        assertEquals(0L, result.times.first())
        assertEquals(999L, result.times.last())
        assertEquals(result.times.size, result.valuesEur.size)
        assertEquals((999L + 1.0) * 2.0, result.valuesEur.last(), 1e-9)
    }

    @Test
    fun changePercent() {
        val s = PortfolioSeries(listOf(0, 1), listOf(200.0, 250.0), 0, 1)
        assertEquals(25.0, s.changePercent()!!, 1e-9)
        assertNull(PortfolioSeries(listOf(0, 1), listOf(0.0, 250.0), 0, 1).changePercent())
    }

    @Test
    fun cryptoRangeIsCappedAtOneYear() {
        assertEquals(CryptoRange.Year, PortfolioHistory.cryptoRange(SeriesRange.FiveYears))
        assertEquals(CryptoRange.Day, PortfolioHistory.cryptoRange(SeriesRange.Day))
        assertEquals(CryptoRange.Week, PortfolioHistory.cryptoRange(SeriesRange.Week))
        assertEquals(CryptoRange.Month, PortfolioHistory.cryptoRange(SeriesRange.Month))
    }
}
