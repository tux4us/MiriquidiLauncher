// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketMathTest {

    @Test fun subsidyHalvesEvery210000Blocks() {
        assertEquals(5_000_000_000L, BitcoinMath.subsidySats(0))
        assertEquals(5_000_000_000L, BitcoinMath.subsidySats(209_999))
        assertEquals(2_500_000_000L, BitcoinMath.subsidySats(210_000))
        assertEquals(312_500_000L, BitcoinMath.subsidySats(840_000))
        assertEquals(312_500_000L, BitcoinMath.subsidySats(1_049_999))
        assertEquals(156_250_000L, BitcoinMath.subsidySats(1_050_000))
        assertEquals(0L, BitcoinMath.subsidySats(64 * 210_000))
        assertEquals(0L, BitcoinMath.subsidySats(-1))
    }

    @Test fun halvingCountdown() {
        assertEquals(1_050_000, BitcoinMath.nextHalvingHeight(972_036))
        assertEquals(77_964, BitcoinMath.blocksUntilHalving(972_036))
        // Der Block mit Höhe 1 049 999 ist der letzte vor der Halbierung: noch ein Block bis dahin.
        assertEquals(1, BitcoinMath.blocksUntilHalving(1_049_999))
        assertEquals(210_000, BitcoinMath.blocksUntilHalving(1_050_000))
        assertEquals(0.62874, BitcoinMath.epochProgress(972_036), 1e-4)
        assertEquals(0.0, BitcoinMath.epochProgress(840_000), 0.0)
        val now = 1_000_000_000_000L
        assertEquals(now + 77_964L * 600_000L, BitcoinMath.estimatedHalvingMillis(972_036, now))
    }

    @Test fun supplyIsSumOfSubsidies() {
        assertEquals(5_000_000_000L, BitcoinMath.supplySats(0))
        assertEquals(210_000L * 5_000_000_000L, BitcoinMath.supplySats(209_999))
        assertEquals(210_000L * 5_000_000_000L + 2_500_000_000L, BitcoinMath.supplySats(210_000))
        assertEquals(2_010_011_562_500_000L, BitcoinMath.supplySats(972_036))
        assertEquals(0L, BitcoinMath.supplySats(-1))
        // Obergrenze: nie mehr als 21 Millionen BTC
        assertTrue(BitcoinMath.supplySats(10_000_000) <= 21_000_000L * BitcoinMath.SATS_PER_BTC)
    }

    @Test fun fiatHelpers() {
        assertEquals(1376.5, BitcoinMath.satsPerUnit(72_646.0), 0.1)
        assertEquals(72_646.0, BitcoinMath.fiatValue(1e8, 72_646.0), 1e-6)
        // 140 vB bei 10 sat/vB = 1400 sat
        assertEquals(1400.0 / 1e8 * 80_000.0, BitcoinMath.feeFiat(10.0, 140, 80_000.0), 1e-9)
        // 20 Millionen BTC zu 80 000 = 1,6 Billionen
        assertEquals(1.6e12, BitcoinMath.marketCap(2_000_000_000_000_000L, 80_000.0), 1.0)
    }

    @Test fun changePercent() {
        assertEquals(10.0, PriceSeries.changePercent(110.0, 100.0)!!, 1e-9)
        assertEquals(-50.0, PriceSeries.changePercent(50.0, 100.0)!!, 1e-9)
        assertNull(PriceSeries.changePercent(50.0, 0.0))
    }

    private fun hourly(count: Int, startSec: Long = 1_700_000_000L): List<PricePoint> =
        (0 until count).map { PricePoint(startSec + it * 3600L, 80_000.0 + it, 70_000.0 + it) }

    @Test fun closestPointRespectsMaxGap() {
        val points = hourly(48)
        val target = points[10].timeSec + 600
        assertEquals(points[10], PriceSeries.closest(points, target))
        assertNull(PriceSeries.closest(points, points.last().timeSec + 5 * 3600L))
        assertNull(PriceSeries.closest(emptyList(), 5L))
    }

    @Test fun changeOverRange() {
        val points = hourly(24 * 8)
        val now = points.last().timeSec
        // vor 24 h war der Euro-Kurs 70 000 + (n-24) ; aktuell 80 000 → Vergleich in Euro
        val past = points.first { it.timeSec == now - 24 * 3600L }
        val change = PriceSeries.changeOver(points, 75_000.0, now, 24 * 3600L, ChartCurrency.Eur)!!
        assertEquals((75_000.0 - past.eur) / past.eur * 100.0, change, 1e-9)
        // kein Verlauf für 30 Tage vorhanden → null
        assertNull(PriceSeries.changeOver(points, 75_000.0, now, 30 * 24 * 3600L, ChartCurrency.Eur))
    }

    @Test fun keepLastTrimsToTheNewestPeriod() {
        val points = hourly(24 * 40)
        val kept = PriceSeries.keepLast(points, 31L * 24L * 3600L)
        assertEquals(31 * 24 + 1, kept.size)
        assertEquals(points.last(), kept.last())
        assertTrue(PriceSeries.keepLast(emptyList(), 5L).isEmpty())
    }

    @Test fun windowFiltersAndThins() {
        val points = hourly(24 * 40)
        val now = points.last().timeSec
        val day = PriceSeries.window(points, ChartRange.Day, now)
        assertEquals(25, day.size)
        assertEquals(now, day.last().timeSec)
        val month = PriceSeries.window(points, ChartRange.Month, now, maxPoints = 100)
        assertTrue(month.size <= 101)
        assertEquals(now, month.last().timeSec)
        assertTrue(month.zipWithNext().all { (a, b) -> a.timeSec < b.timeSec })
        assertTrue(PriceSeries.window(emptyList(), ChartRange.Week, 5L).isEmpty())
    }

    @Test fun metals() {
        assertEquals(133.94, MetalMath.perGram(4166.1), 0.01)
        assertEquals(3709.4, MetalMath.usdToEur(4166.1, 1.1231), 0.1)
        assertEquals(72.94, MetalMath.goldSilverRatio(4166.1, 57.116)!!, 0.01)
        assertNull(MetalMath.goldSilverRatio(0.0, 5.0))
        assertEquals(19.59, MetalMath.bitcoinInGoldOunces(81_623.0, 4166.1)!!, 0.01)
        assertNull(MetalMath.bitcoinInGoldOunces(1.0, 0.0))
        assertNotNull(ChartRange.fromKey("7d"))
        assertEquals(ChartRange.Day, ChartRange.fromKey("quatsch"))
        assertEquals(ChartCurrency.Eur, ChartCurrency.fromKey(null))
    }
}
