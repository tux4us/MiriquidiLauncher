// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCodecTest {
    private val full = MarketSnapshot(
        fast = BitcoinFast(
            price = BitcoinPrice(81_623.0, 72_646.0, 1_792_302_606L),
            tip = TipBlock(972_036, 1_792_347_814L, 5191),
            fees = FeeLevels(4.0, 3.0, 2.0, 1.0, 0.1),
            mempool = MempoolStats(74_057L, 39_513_286L, 7_269_766L),
            atMillis = 1_792_302_700_000L,
        ),
        slow = BitcoinSlow(
            difficulty = DifficultyInfo(10.96, -1.25, 1_793_383_537_645L, 1795, 973_728, 5.92),
            hashrate = HashrateInfo(9.432431119226117E20, 140_577_680_051_919.6),
            lightning = LightningInfo(39_627L, 17_334L, 445_087_713_469L),
            atMillis = 1_792_302_800_000L,
        ),
        history = BitcoinHistory(
            listOf(PricePoint(1_792_328_400L, 81_872.0, 72_892.0), PricePoint(1_792_332_000L, 81_438.5, 72_501.25)),
            1_792_302_900_000L,
        ),
        quotes = QuotesData(
            listOf(
                Quote("dax", 24250.75, 24100.5, "EUR", listOf(24000.0, 24100.5, 24250.75), QuoteSource.YAHOO),
                Quote("brent", 80.5, null, null, listOf(80.5), QuoteSource.STOOQ),
            ),
            1_792_303_000_000L,
        ),
        crypto = CryptoData(
            listOf(CryptoQuote("ethereum", 3000.0, 3400.0, -1.5, 3.6e11), CryptoQuote("tether", 0.92, 1.0, null, null)),
            1_792_303_100_000L,
        ),
        securities = SecuritiesData(listOf(PositionQuote("SAP.DE", 200.0, "EUR")), 6L),
        fx = FxData(mapOf("USD" to 1.1, "GBP" to 0.85), 7L),
    )

    @Test fun roundTrip() {
        val decoded = MarketCodec.decode(MarketCodec.encode(full))
        assertEquals(full, decoded)
    }

    @Test fun roundTripWithMissingParts() {
        val sparse = MarketSnapshot(
            fast = BitcoinFast(null, TipBlock(5, null, null), null, null, 7L),
            slow = BitcoinSlow(DifficultyInfo(1.0, 2.0, 3L, 4, 5, null), null, null, 8L),
            quotes = QuotesData(listOf(Quote("gold", 4000.0, null, "USD", emptyList(), QuoteSource.YAHOO)), 9L),
        )
        assertEquals(sparse, MarketCodec.decode(MarketCodec.encode(sparse)))
        assertEquals(MarketSnapshot(), MarketCodec.decode(MarketCodec.encode(MarketSnapshot())))
    }

    @Test fun corruptOrForeignTextGivesNull() {
        assertNull(MarketCodec.decode(null))
        assertNull(MarketCodec.decode(""))
        assertNull(MarketCodec.decode("kein json"))
        assertNull(MarketCodec.decode("[1,2,3]"))
        assertNull(MarketCodec.decode("""{"v":99}"""))
        assertNull(MarketCodec.decode("""{"v":1}"""))
        assertNull(MarketCodec.decode(MarketCodec.encode(full).dropLast(7)))
        // Falsche Typen innerhalb: kein Absturz
        assertNotNull(MarketCodec.decode("""{"v":2,"fast":{"price":"x","fees":[1,2],"tip":{"h":"a"}},"hist":{"p":[["a"],[1]]}}"""))
    }

    @Test fun nonFiniteNumbersAreWrittenAsZero() {
        val s = MarketSnapshot(fast = BitcoinFast(BitcoinPrice(Double.NaN, Double.POSITIVE_INFINITY, 1L), null, null, null, 1L))
        val decoded = MarketCodec.decode(MarketCodec.encode(s))!!
        assertEquals(0.0, decoded.fast!!.price!!.usd, 0.0)
        assertTrue(decoded.fast!!.price!!.eur == 0.0)
    }

    @Test fun mergeKeepsOlderPartsWhenNewOnesAreMissing() {
        val old = full.fast!!
        val fresh = BitcoinFast(BitcoinPrice(1.0, 2.0, 3L), null, null, null, 99L).filledFrom(old)
        assertEquals(1.0, fresh.price!!.usd, 0.0)
        assertEquals(old.tip, fresh.tip)
        assertEquals(old.fees, fresh.fees)
        assertEquals(99L, fresh.atMillis)
        val quotes = QuotesData(listOf(Quote("gold", 4100.0, null, "USD", emptyList(), QuoteSource.YAHOO)), 50L).filledFrom(full.quotes)
        assertEquals(4100.0, quotes.byId("gold")!!.price, 0.0)
        assertEquals(24250.75, quotes.byId("dax")!!.price, 0.0)
        assertEquals(50L, quotes.atMillis)
        val crypto = CryptoData(listOf(CryptoQuote("ethereum", 1.0, 2.0, null, null)), 5L).filledFrom(full.crypto)
        assertEquals(1.0, crypto.byId("ethereum")!!.eur, 0.0)
        assertEquals(0.92, crypto.byId("tether")!!.eur, 0.0)
    }
}
