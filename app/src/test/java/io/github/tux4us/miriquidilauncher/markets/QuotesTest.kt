// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuotesTest {
    private val yahooMonth = """{"chart":{"result":[{"meta":{"currency":"EUR","symbol":"^GDAXI","regularMarketPrice":24250.75,"chartPreviousClose":23900.0},
        "timestamp":[1,2,3,4],"indicators":{"quote":[{"close":[24000.0,null,24100.5,24250.75]}]}}],"error":null}}"""

    @Test
    fun catalogHasUniqueIdsAndSymbols() {
        assertEquals(QuoteCatalog.all.size, QuoteCatalog.all.map { it.id }.toSet().size)
        assertEquals(QuoteCatalog.all.size, QuoteCatalog.all.map { it.yahoo }.toSet().size)
        assertEquals(QuoteCatalog.all.mapNotNull { it.stooq }.size, QuoteCatalog.all.mapNotNull { it.stooq }.toSet().size)
        assertTrue(QuoteCatalog.indices.all { it.group == QuoteGroup.Index })
        assertTrue(QuoteCatalog.commodities.all { it.group == QuoteGroup.Commodity })
        assertTrue(QuoteCatalog.byId("dax") != null && QuoteCatalog.byId("gold") != null && QuoteCatalog.byId("palladium") != null)
        assertNull(QuoteCatalog.byId("nope"))
    }

    @Test
    fun yahooQuoteUsesPreviousSessionAsReference() {
        val q = YahooParser.parseQuote("dax", yahooMonth)
        assertEquals(24250.75, q.price, 0.0)
        assertEquals(24100.5, q.prevClose!!, 0.0)
        assertEquals("EUR", q.currency)
        assertEquals(listOf(24000.0, 24100.5, 24250.75), q.closes)
        assertEquals((24250.75 - 24100.5) / 24100.5 * 100.0, q.changePercent!!, 1e-9)
        assertEquals(QuoteSource.YAHOO, q.source)
    }

    @Test
    fun yahooQuoteFallsBackToMetaPreviousClose() {
        val json = """{"chart":{"result":[{"meta":{"currency":"USD","regularMarketPrice":80.0,"previousClose":79.0},
            "timestamp":[1],"indicators":{"quote":[{"close":[80.0]}]}}]}}"""
        assertEquals(79.0, YahooParser.parseQuote("brent", json).prevClose!!, 0.0)
    }

    @Test
    fun yahooQuoteNormalizesCents() {
        val json = """{"chart":{"result":[{"meta":{"currency":"USX","regularMarketPrice":550.0},
            "timestamp":[1,2],"indicators":{"quote":[{"close":[540.0,550.0]}]}}]}}"""
        val q = YahooParser.parseQuote("wheat", json)
        assertEquals("USD", q.currency)
        assertEquals(5.5, q.price, 1e-9)
        assertEquals(5.4, q.prevClose!!, 1e-9)
    }

    @Test
    fun yahooErrorsAreParseExceptions() {
        for (bad in listOf("kein json", """{"chart":{"result":null,"error":{"code":"Not Found"}}}""", """{"chart":{"result":[{"meta":{}}]}}""")) {
            try {
                YahooParser.parseQuote("x", bad)
                fail(bad)
            } catch (e: YahooParser.ParseException) {
                // erwartet
            }
        }
    }

    @Test
    fun yahooSeriesSkipsNullsAndKeepsPreviousClose() {
        val s = YahooParser.parseSeries(yahooMonth)
        assertEquals(listOf(1L, 3L, 4L), s.times)
        assertEquals(listOf(24000.0, 24100.5, 24250.75), s.values)
        assertEquals(23900.0, s.prevClose!!, 0.0)
        assertTrue(s.isUsable)
    }

    @Test
    fun yahooSeriesThinsLongData() {
        val n = 1000
        val times = (1..n).joinToString(",")
        val closes = (1..n).joinToString(",") { (100 + it).toString() }
        val json = """{"chart":{"result":[{"meta":{"currency":"USD"},"timestamp":[$times],"indicators":{"quote":[{"close":[$closes]}]}}]}}"""
        val s = YahooParser.parseSeries(json)
        assertTrue(s.values.size <= YahooParser.MAX_SERIES_POINTS + 1)
        assertEquals(1100.0, s.values.last(), 0.0)
        assertEquals(s.times.size, s.values.size)
    }

    @Test
    fun yahooSeriesNeedsTwoPoints() {
        try {
            YahooParser.parseSeries("""{"chart":{"result":[{"meta":{},"timestamp":[1],"indicators":{"quote":[{"close":[5.0]}]}}]}}""")
            fail()
        } catch (e: YahooParser.ParseException) {
            // erwartet
        }
    }

    @Test
    fun chartUrlCarriesRangeAndEncodesSymbol() {
        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/%5EGDAXI?range=5d&interval=30m",
            YahooParser.chartUrl("^GDAXI", "5d", "30m"),
        )
    }

    @Test
    fun stooqHistoryIsParsedAscending() {
        val csv = "Date,Open,High,Low,Close,Volume\n2026-10-19,1,2,0.5,1.5,100\n2026-10-20,1,2,0.5,1.8,100\nkaputt\n2026-10-21,1,2,0.5,0,100\n"
        val s = StooqParser.parseSeries(csv)
        assertEquals(listOf(1.5, 1.8), s.values)
        assertTrue(s.times[0] < s.times[1])
        val q = StooqParser.toQuote("x", s)
        assertEquals(1.8, q.price, 0.0)
        assertEquals(1.5, q.prevClose!!, 0.0)
        assertEquals(QuoteSource.STOOQ, q.source)
    }

    @Test
    fun stooqKeyErrorsAreDetected() {
        for (body in listOf("", "Get your apikey: open https://stooq.com/q/d/?s=cl.f&get_apikey", "<html>Exceeded the daily hits limit</html>")) {
            try {
                StooqParser.parseSeries(body)
                fail(body)
            } catch (e: ApiKeyException) {
                // erwartet
            }
        }
        try {
            StooqParser.parseSeries("Date,Open,High,Low,Close,Volume\n")
            fail()
        } catch (e: StooqParser.ParseException) {
            // erwartet
        }
    }

    @Test
    fun stooqUrlEncodesSymbolAndKey() {
        assertEquals(
            "https://stooq.com/q/d/l/?s=%5Edax&d1=20260901&d2=20261021&i=d&apikey=a%2Bb",
            StooqParser.historyUrl("^dax", "20260901", "20261021", "d", "a+b"),
        )
    }

    @Test
    fun quotesMergeKeepsOldMissing() {
        fun q(id: String, p: Double) = Quote(id, p, null, null, emptyList(), QuoteSource.YAHOO)
        val old = QuotesData(listOf(q("dax", 1.0), q("cac", 2.0)), 1L)
        val fresh = QuotesData(listOf(q("dax", 3.0)), 2L).filledFrom(old)
        assertEquals(3.0, fresh.byId("dax")!!.price, 0.0)
        assertEquals(2.0, fresh.byId("cac")!!.price, 0.0)
        assertEquals(2L, fresh.atMillis)
    }

    @Test
    fun changePercentNeedsPositiveReference() {
        assertNull(Quote("x", 5.0, 0.0, null, emptyList(), QuoteSource.YAHOO).changePercent)
        assertNull(Quote("x", 5.0, null, null, emptyList(), QuoteSource.YAHOO).changePercent)
        assertEquals(25.0, Quote("x", 5.0, 4.0, null, emptyList(), QuoteSource.YAHOO).changePercent!!, 1e-9)
    }

    @Test
    fun rangesMapToProviders() {
        assertFalse(SeriesRange.Day.stooqSupported)
        assertEquals("w", SeriesRange.FiveYears.stooqInterval)
        assertEquals("d", SeriesRange.Year.stooqInterval)
        assertEquals(SeriesRange.Month, SeriesRange.fromKey("unbekannt"))
        assertEquals(SeriesRange.Year, SeriesRange.fromKey("1y"))
    }
}
