// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketFormatTest {
    private val de = Locale.GERMANY
    private val us = Locale.US

    /** Leerzeichen-Varianten (geschütztes, schmales) vereinheitlichen, damit der Test nicht von der JDK-Version abhängt. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    @Test fun money() {
        assertEquals("81.623,00 $", MarketFormat.money(81_623.0, "USD", de).plain())
        assertEquals("72.646 €", MarketFormat.money(72_646.0, "EUR", de, decimals = 0).plain())
        assertEquals("$81,623.00", MarketFormat.money(81_623.0, "USD", us))
        assertEquals("3.709,40 €", MarketFormat.money(3709.4, "EUR", de).plain())
    }

    @Test fun numbers() {
        assertEquals("1.376", MarketFormat.number(1376.4, de))
        assertEquals("133,94", MarketFormat.number(133.94, de, 2))
        assertEquals("0,00137", MarketFormat.trimmed(0.00137, de, 8))
        assertEquals("0,5", MarketFormat.trimmed(0.5, de, 8))
        assertEquals("1,234", MarketFormat.trimmed(1.234, de, 3))
    }

    @Test fun signedPercent() {
        assertEquals("+1,2 %", MarketFormat.signedPercent(1.23, de))
        assertEquals("−0,4 %", MarketFormat.signedPercent(-0.44, de))
        assertEquals("0,0 %", MarketFormat.signedPercent(0.01, de))
        assertEquals("+1.2 %", MarketFormat.signedPercent(1.23, us))
    }

    @Test fun compactUsesGivenSuffixes() {
        val suffixes = listOf("Tsd.", "Mio.", "Mrd.", "Bio.")
        assertEquals("1,64 Bio.", MarketFormat.compact(1.64e12, de, suffixes))
        assertEquals("445,09 Mrd.", MarketFormat.compact(445_087_713_469.0, de, suffixes))
        assertEquals("12,50 Mio.", MarketFormat.compact(12_500_000.0, de, suffixes))
        assertEquals("999", MarketFormat.compact(999.0, de, suffixes))
    }

    @Test fun hashrateAndBytes() {
        assertEquals("943,2 EH/s", MarketFormat.hashrate(9.432431119226117E20, de))
        assertEquals("512 H/s", MarketFormat.hashrate(512.0, de))
        assertEquals("39,5 MB", MarketFormat.bytes(39_513_286L, de))
        assertEquals("900 B", MarketFormat.bytes(900L, de))
    }

    @Test fun btc() {
        assertEquals("20.100.115,63 BTC", MarketFormat.btc(2_010_011_562_500_000L, de))
        assertEquals("0,00000001 BTC", MarketFormat.btc(1L, de, decimals = 8))
    }

    @Test fun priceFallsBackForUnknownCurrency() {
        val de = java.util.Locale.GERMANY
        assertEquals("24.250,75", MarketFormat.price(24250.75, null, de, 2))
        assertEquals("1,50 XYZ", MarketFormat.price(1.5, "XYZ", de, 2))
        assertTrue(MarketFormat.price(80.5, "USD", de, 2).contains("80,50"))
        assertEquals(0, MarketFormat.autoDecimals(24250.0))
        assertEquals(2, MarketFormat.autoDecimals(80.5))
        assertEquals(4, MarketFormat.autoDecimals(0.05))
    }
}
