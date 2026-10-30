// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    @Test
    fun catalogIsConsistent() {
        assertEquals(CryptoCatalog.selectable.size, CryptoCatalog.selectable.map { it.id }.toSet().size)
        assertTrue(CryptoCatalog.stablecoins.all { it.peg == "USD" || it.peg == "EUR" })
        assertTrue(CryptoCatalog.fetched.none { it.id == "bitcoin" })
        assertEquals("BTC", CryptoCatalog.byId("bitcoin")!!.symbol)
        assertNull(CryptoCatalog.byId("nope"))
    }

    @Test
    fun parsesPrices() {
        val json = """{"ethereum":{"eur":3000.5,"eur_market_cap":3.6e11,"eur_24h_change":-1.25,"usd":3400.0,"usd_market_cap":4e11,"usd_24h_change":-1.0},
            "tether":{"eur":0.92,"usd":1.0004},"broken":{"eur":1.0},"zero":{"eur":0,"usd":0}}"""
        val q = CoinGeckoParser.parsePrices(json)
        assertEquals(listOf("ethereum", "tether"), q.map { it.id })
        assertEquals(3000.5, q[0].eur, 0.0)
        assertEquals(-1.25, q[0].change24h!!, 0.0)
        assertEquals(3.6e11, q[0].marketCapEur!!, 1.0)
        assertNull(q[1].change24h)
        assertNull(q[1].marketCapEur)
    }

    @Test
    fun statusObjectIsAnError() {
        try {
            CoinGeckoParser.parsePrices("""{"status":{"error_code":429,"error_message":"limit"}}""")
            fail()
        } catch (e: CoinGeckoParser.ParseException) {
            // erwartet
        }
        try {
            CoinGeckoParser.parsePrices("kein json")
            fail()
        } catch (e: CoinGeckoParser.ParseException) {
            // erwartet
        }
    }

    @Test
    fun pegDeviation() {
        val usdt = CryptoCatalog.byId("tether")!!
        val eurc = CryptoCatalog.byId("euro-coin")!!
        val q = CryptoQuote("tether", 0.92, 1.0004, null, null)
        assertEquals(0.04, q.pegDeviationPercent(usdt)!!, 1e-9)
        assertEquals(-8.0, q.pegDeviationPercent(eurc)!!, 1e-9)
        assertNull(q.pegDeviationPercent(CryptoCatalog.ethereum))
    }

    @Test
    fun parsesChart() {
        val s = CoinGeckoParser.parseChart("""{"prices":[[1700000000000,100.0],[1700000300000,101.5],[1700000600000,null],["x",3]],"market_caps":[]}""")
        assertEquals(listOf(1_700_000_000L, 1_700_000_300L), s.times)
        assertEquals(listOf(100.0, 101.5), s.values)
        assertEquals("EUR", s.currency)
        try {
            CoinGeckoParser.parseChart("""{"prices":[[1,2]]}""")
            fail()
        } catch (e: CoinGeckoParser.ParseException) {
            // erwartet
        }
    }

    @Test
    fun urlsAreBuilt() {
        assertEquals(
            "https://api.coingecko.com/api/v3/simple/price?ids=ethereum,tether&vs_currencies=eur,usd&include_market_cap=true&include_24hr_change=true",
            CoinGeckoParser.pricesUrl(listOf("ethereum", "tether")),
        )
        assertEquals("https://api.coingecko.com/api/v3/coins/ethereum/market_chart?vs_currency=eur&days=7", CoinGeckoParser.chartUrl("ethereum", 7))
        assertEquals(CryptoRange.Week, CryptoRange.fromKey("zzz"))
    }

    @Test
    fun cryptoDataMerges() {
        val old = CryptoData(listOf(CryptoQuote("a", 1.0, 1.0, null, null), CryptoQuote("b", 2.0, 2.0, null, null)), 1L)
        val fresh = CryptoData(listOf(CryptoQuote("a", 5.0, 5.0, null, null)), 2L).filledFrom(old)
        assertEquals(5.0, fresh.byId("a")!!.eur, 0.0)
        assertEquals(2.0, fresh.byId("b")!!.eur, 0.0)
    }
}
