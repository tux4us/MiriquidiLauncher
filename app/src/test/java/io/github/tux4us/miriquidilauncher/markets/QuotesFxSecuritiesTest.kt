// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuotesFxSecuritiesTest {
    // region FX

    private val rates = mapOf("USD" to 1.10, "GBP" to 0.85, "JPY" to 160.0)

    @Test
    fun convertsThroughEuro() {
        assertEquals(110.0, FxLogic.convert(100.0, "EUR", "USD", rates)!!, 1e-9)
        assertEquals(100.0, FxLogic.convert(110.0, "USD", "EUR", rates)!!, 1e-9)
        assertEquals(100.0 * 0.85 / 1.10, FxLogic.convert(100.0, "USD", "GBP", rates)!!, 1e-9)
        assertEquals(5.0, FxLogic.convert(5.0, "CHF", "CHF", rates)!!, 0.0)
        assertNull(FxLogic.convert(1.0, "EUR", "CHF", rates))
    }

    @Test
    fun swapIsInverse() {
        val there = FxLogic.convert(250.0, "USD", "JPY", rates)!!
        assertEquals(250.0, FxLogic.convert(there, "JPY", "USD", rates)!!, 1e-9)
    }

    @Test
    fun normalizesSubunits() {
        assertEquals("GBP" to 12.5, FxLogic.normalize("GBp", 1250.0))
        assertEquals("USD" to 10.0, FxLogic.normalize("USD", 10.0))
        assertEquals("USD" to 5.5, FxLogic.normalize("USX", 550.0))
        assertEquals("ZAR" to 2.0, FxLogic.normalize("ZAc", 200.0))
    }

    @Test
    fun currencyListIsConsistent() {
        assertEquals(FxLogic.currencies.size, FxLogic.currencies.toSet().size)
        assertTrue(FxLogic.currencies.all { it.length == 3 && it == it.uppercase() })
        assertEquals("https://api.frankfurter.dev/v2/rate/eur/usd", FxLogic.rateUrl("USD"))
        assertEquals(12.5, FxLogic.parseAmount("12,5")!!, 0.0)
        assertNull(FxLogic.parseAmount("abc"))
    }

    // endregion

    // region WKN / ISIN

    @Test
    fun parsesIsinAndWkn() {
        assertEquals(SecurityIdKind.Isin, SecurityIds.parse("us0378331005")!!.kind)
        assertEquals(SecurityIdKind.Isin, SecurityIds.parse(" DE0007164600 ")!!.kind)
        assertEquals(SecurityIdKind.Isin, SecurityIds.parse("IE00B4L5Y983")!!.kind)
        assertEquals(SecurityIdKind.Wkn, SecurityIds.parse("716460")!!.kind)
        assertEquals(SecurityIdKind.Wkn, SecurityIds.parse("a0rpwh")!!.kind)
    }

    @Test
    fun rejectsBadIdentifiers() {
        assertNull(SecurityIds.parse("US0378331006")) // falsche Prüfziffer
        assertNull(SecurityIds.parse("12345"))
        assertNull(SecurityIds.parse("SAP.DE"))
        assertNull(SecurityIds.parse(""))
    }

    @Test
    fun wknGetsGermanIsinCandidate() {
        val id = SecurityIds.parse("716460")!!
        assertEquals(listOf("716460", "DE0007164600"), id.searchTerms())
        assertEquals(listOf("US0378331005"), SecurityIds.parse("US0378331005")!!.searchTerms())
    }

    // endregion

    // region Yahoo

    @Test
    fun parsesYahooSearch() {
        val json = """{"quotes":[
            {"symbol":"SAP.DE","shortname":"SAP SE","longname":"SAP SE","exchDisp":"XETRA","quoteType":"EQUITY"},
            {"symbol":"SAPGF","shortname":"SAP","quoteType":"EQUITY"},
            {"symbol":"^GDAXI","shortname":"DAX","quoteType":"INDEX"},
            {"quoteType":"EQUITY"}]}"""
        val hits = YahooParser.parseSearch(json)
        assertEquals(listOf("SAP.DE", "SAPGF"), hits.map { it.symbol })
        assertEquals("XETRA", hits[0].exchange)
        assertTrue(YahooParser.parseSearch("""{"quotes":[]}""").isEmpty())
    }

    @Test
    fun parsesYahooChart() {
        val json = """{"chart":{"result":[{"meta":{"currency":"EUR","symbol":"SAP.DE","regularMarketPrice":201.35,"longName":"SAP SE"}}],"error":null}}"""
        val q = YahooParser.parseChart(json)
        assertEquals("SAP.DE", q.symbol)
        assertEquals(201.35, q.price, 0.0)
        assertEquals("EUR", q.currency)
        try {
            YahooParser.parseChart("""{"chart":{"result":null,"error":{"code":"Not Found"}}}""")
            fail()
        } catch (e: YahooParser.ParseException) {
            // erwartet
        }
        try {
            YahooParser.parseChart("kein json")
            fail()
        } catch (e: YahooParser.ParseException) {
            // erwartet
        }
    }

    // endregion
}
