// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldingsTest {
    private val perEur = mapOf("USD" to 1.25, "GBP" to 0.8)
    private val sap = Position("SAP.DE", "SAP SE", "DE0007164600", 10.0)
    private val apple = Position("AAPL", "Apple \"Inc\"", "US0378331005", 2.5)
    private val gold = Position("gold", "Gold", "", 2.0, PositionKind.Commodity)
    private val btc = Position("bitcoin", "Bitcoin", "", 0.5, PositionKind.Crypto)

    private val prices = AssetPrices(
        perEur = perEur,
        stocks = listOf(PositionQuote("SAP.DE", 200.0, "EUR"), PositionQuote("AAPL", 220.0, "USD")),
        commodities = QuotesData(listOf(Quote("gold", 4000.0, null, "USD", emptyList(), QuoteSource.YAHOO)), 1L),
        cryptoEur = mapOf("bitcoin" to 90_000.0),
    )

    @Test
    fun parseAcceptsCommaAndDot() {
        assertEquals(HoldingsLogic.Parsed.Ok(1.5), HoldingsLogic.parseAmount("1,5"))
        assertEquals(HoldingsLogic.Parsed.Ok(0.25), HoldingsLogic.parseAmount(" 0.25 "))
        assertEquals(HoldingsLogic.Parsed.Ok(0.0), HoldingsLogic.parseAmount("0"))
        assertEquals(HoldingsLogic.Parsed.Ok(10.0), HoldingsLogic.parseAmount("10"))
    }

    @Test
    fun parseRejectsBadInput() {
        assertEquals(HoldingsLogic.Parsed.Empty, HoldingsLogic.parseAmount("  "))
        for (bad in listOf("-1", "1,2,3", "1.2.3", "abc", "1e5", ".", "1 000", "+1", "99999999999")) {
            assertEquals(bad, HoldingsLogic.Parsed.Invalid, HoldingsLogic.parseAmount(bad))
        }
    }

    @Test
    fun legacyFormatIsStillReadable() {
        assertEquals(Holdings(btc = 0.125, goldOz = null, silverOz = 20.5), HoldingsLogic.decode("0.125||20.5"))
        assertEquals(Holdings(), HoldingsLogic.decode(null))
        assertEquals(Holdings(), HoldingsLogic.decode("1|2"))
        assertEquals(Holdings(btc = null, goldOz = 2.0, silverOz = null), HoldingsLogic.decode("x|2|-3"))
        assertEquals(Holdings(), HoldingsLogic.decode("NaN|Infinity|1e12"))
        assertTrue(Holdings().isEmpty)
    }

    @Test
    fun migrationCreatesPositionsOnce() {
        val migrated = PositionsLogic.migrate(listOf(sap), Holdings(btc = 0.5, goldOz = 2.0, silverOz = null))
        assertEquals(listOf(sap, btc, gold), migrated)
        assertEquals(listOf(sap), PositionsLogic.migrate(listOf(sap), Holdings()))
        assertEquals(listOf(sap), PositionsLogic.migrate(listOf(sap), Holdings(btc = 0.0)))
    }

    @Test
    fun positionsRoundTripWithKinds() {
        val all = listOf(sap, apple, gold, btc)
        assertEquals(all, PositionsLogic.decode(PositionsLogic.encode(all)))
        assertTrue(PositionsLogic.decode(null).isEmpty())
        assertTrue(PositionsLogic.decode("[kaputt").isEmpty())
        assertTrue(PositionsLogic.decode("""[{"s":"X","q":-1}]""").isEmpty())
        // Einträge ohne Art (früherer Stand) sind Aktien
        assertEquals(listOf(sap), PositionsLogic.decode("""[{"s":"SAP.DE","n":"SAP SE","i":"DE0007164600","q":10.0}]"""))
    }

    @Test
    fun addMergesSameSymbolAndKindOnly() {
        val merged = PositionsLogic.add(listOf(sap), sap.copy(quantity = 5.0))!!
        assertEquals(1, merged.size)
        assertEquals(15.0, merged[0].quantity, 0.0)
        // gleiche Kennung, andere Art: eigener Eintrag
        assertEquals(2, PositionsLogic.add(listOf(sap), sap.copy(kind = PositionKind.Commodity))!!.size)
        val full = (1..PositionsLogic.MAX_POSITIONS).map { Position("S$it", "n", "", 1.0) }
        assertNull(PositionsLogic.add(full, sap))
    }

    @Test
    fun quantityZeroRemoves() {
        assertEquals(listOf(apple), PositionsLogic.setQuantity(listOf(sap, apple), PositionKind.Stock, "SAP.DE", 0.0))
        assertEquals(20.0, PositionsLogic.setQuantity(listOf(sap), PositionKind.Stock, "SAP.DE", 20.0)[0].quantity, 0.0)
        assertEquals(listOf(sap), PositionsLogic.remove(listOf(sap, apple), PositionKind.Stock, "AAPL"))
        assertEquals(listOf(sap, apple), PositionsLogic.remove(listOf(sap, apple), PositionKind.Crypto, "AAPL"))
    }

    @Test
    fun valuesAllKindsInEuro() {
        val v = PositionsLogic.value(listOf(sap, apple, gold, btc), prices)
        assertTrue(v.complete)
        assertEquals(2000.0, v.items[0].valueEur!!, 1e-9)
        assertEquals(2.5 * 220.0 / 1.25, v.items[1].valueEur!!, 1e-9)
        assertEquals(2.0 * 4000.0 / 1.25, v.items[2].valueEur!!, 1e-9)
        assertEquals(45_000.0, v.items[3].valueEur!!, 1e-9)
        assertEquals(2000.0 + 440.0 + 6400.0 + 45_000.0, v.totalEur!!, 1e-9)
    }

    @Test
    fun commoditiesInSubunitsAreNormalized() {
        val wheat = Position("wheat", "Wheat", "", 10.0, PositionKind.Commodity)
        val p = prices.copy(commodities = QuotesData(listOf(Quote("wheat", 5.5, null, "USD", emptyList(), QuoteSource.YAHOO)), 1L))
        assertEquals(10.0 * 5.5 / 1.25, PositionsLogic.value(listOf(wheat), p).totalEur!!, 1e-9)
    }

    @Test
    fun missingQuoteOrRateMakesIncomplete() {
        val noQuote = PositionsLogic.value(listOf(sap, apple), prices.copy(stocks = listOf(PositionQuote("SAP.DE", 200.0, "EUR"))))
        assertFalse(noQuote.complete)
        assertNull(noQuote.totalEur)
        assertEquals(2000.0, noQuote.items[0].valueEur!!, 1e-9)
        val noRate = PositionsLogic.value(listOf(apple), prices.copy(perEur = emptyMap()))
        assertNull(noRate.items[0].valueEur)
        assertNull(PositionsLogic.value(listOf(btc), prices.copy(cryptoEur = emptyMap())).items[0].price)
        assertTrue(PositionsLogic.value(emptyList(), prices).items.isEmpty())
    }

    @Test
    fun currenciesNeededListsForeignCurrencies() {
        assertEquals(setOf("USD"), PositionsLogic.currenciesNeeded(listOf(sap, apple, gold, btc), prices))
    }

    @Test
    fun snapshotBuildsAssetPrices() {
        val snap = MarketSnapshot(
            fast = BitcoinFast(BitcoinPrice(100_000.0, 80_000.0, 1L), null, null, null, 1L),
            crypto = CryptoData(listOf(CryptoQuote("ethereum", 3000.0, 3500.0, null, null)), 1L),
            fx = FxData(mapOf("GBP" to 0.85), 1L),
            securities = SecuritiesData(listOf(PositionQuote("SAP.DE", 200.0, "EUR")), 1L),
        )
        val p = snap.assetPrices()
        assertEquals(80_000.0, p.cryptoEur["bitcoin"]!!, 0.0)
        assertEquals(3000.0, p.cryptoEur["ethereum"]!!, 0.0)
        // USD fehlt in den Wechselkursen und wird aus dem Bitcoin-Preis abgeleitet
        assertEquals(1.25, p.perEur["USD"]!!, 1e-9)
        assertEquals(0.85, p.perEur["GBP"]!!, 0.0)
        assertEquals(1, p.stocks.size)
        assertEquals(1.25, snap.eurUsd()!!, 1e-9)
        assertNull(MarketSnapshot().eurUsd())
        // gespeicherter Kurs hat Vorrang vor der Ableitung
        assertEquals(1.1, snap.copy(fx = FxData(mapOf("USD" to 1.1), 1L)).eurUsd()!!, 0.0)
    }
}
