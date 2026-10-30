// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Die Beispiele sind gekürzte echte Antworten von mempool.space und Frankfurter (2026-10-18). */
class MarketParserTest {

    @Test fun prices() {
        val p = MarketParser.parsePrices("""{"time":1792302606,"USD":81623,"EUR":72646,"GBP":61281,"CAD":115300}""")
        assertEquals(81623.0, p.usd, 0.0)
        assertEquals(72646.0, p.eur, 0.0)
        assertEquals(1792302606L, p.timeSec)
    }

    @Test fun pricesWithoutEuroAreRejected() {
        expectParseError { MarketParser.parsePrices("""{"time":1,"USD":81623}""") }
        expectParseError { MarketParser.parsePrices("""{"time":1,"USD":0,"EUR":5}""") }
        expectParseError { MarketParser.parsePrices("[]") }
        expectParseError { MarketParser.parsePrices("<html>") }
    }

    @Test fun fees() {
        val f = MarketParser.parseFees("""{"fastestFee":4,"halfHourFee":3,"hourFee":2,"economyFee":1,"minimumFee":1}""")
        assertEquals(4.0, f.fastest, 0.0)
        assertEquals(1.0, f.minimum, 0.0)
        expectParseError { MarketParser.parseFees("""{"fastestFee":4}""") }
    }

    @Test fun preciseFeesWithDecimals() {
        val f = MarketParser.parseFees("""{"fastestFee":1.412,"halfHourFee":1.2,"hourFee":1.1,"economyFee":1,"minimumFee":0.1}""")
        assertEquals(0.1, f.minimum, 1e-9)
    }

    @Test fun mempool() {
        val m = MarketParser.parseMempool("""{"count":74057,"vsize":39513286,"total_fee":7269766,"fee_histogram":[[4.019802,50190],[3.05,51488]]}""")
        assertEquals(74057L, m.txCount)
        assertEquals(39513286L, m.vsize)
        assertEquals(7269766L, m.totalFeeSats)
    }

    @Test fun tipHeightAndHash() {
        assertEquals(972036, MarketParser.parseTipHeight("972036\n"))
        expectParseError { MarketParser.parseTipHeight("abc") }
        expectParseError { MarketParser.parseTipHeight("-4") }
        val hash = "0000000000000000000195c3c38f012514f274f503660996932dc74cec9ab57b"
        assertEquals(hash, MarketParser.parseTipHash(" $hash "))
        expectParseError { MarketParser.parseTipHash("xyz") }
        expectParseError { MarketParser.parseTipHash("<html>" + hash) }
    }

    @Test fun block() {
        val b = MarketParser.parseBlock("""{"id":"00","height":972036,"version":1,"timestamp":1792347814,"tx_count":5191,"size":1877515}""")
        assertEquals(972036, b.height)
        assertEquals(1792347814L, b.timeSec)
        assertEquals(5191, b.txCount)
        val onlyHeight = MarketParser.parseBlock("""{"height":5}""")
        assertNull(onlyHeight.timeSec)
        assertNull(onlyHeight.txCount)
    }

    @Test fun difficulty() {
        val d = MarketParser.parseDifficulty(
            """{"progressPercent":10.962301587301587,"difficultyChange":-1.2546660473229942,"estimatedRetargetDate":1793383537645,
            |"remainingBlocks":1795,"remainingTime":1092851645,"previousRetarget":5.923684832227039,"nextRetargetHeight":973728}""".trimMargin(),
        )
        assertEquals(10.96, d.progressPercent, 0.01)
        assertEquals(-1.2547, d.expectedChangePercent, 1e-3)
        assertEquals(1793383537645L, d.estimatedRetargetMillis)
        assertEquals(1795, d.remainingBlocks)
        assertEquals(973728, d.nextRetargetHeight)
        assertEquals(5.92, d.previousChangePercent!!, 0.01)
        expectParseError { MarketParser.parseDifficulty("""{"progressPercent":1}""") }
    }

    @Test fun hashrate() {
        val h = MarketParser.parseHashrate(
            """{"hashrates":[{"timestamp":1792281600,"avgHashrate":9.432431119226117E20}],
            |"difficulty":[{"time":1792154251,"height":971712,"difficulty":140577680051919.6,"adjustment":1.059}],
            |"currentHashrate":9.432431119226117E20,"currentDifficulty":140577680051919.6}""".trimMargin(),
        )
        assertEquals(9.432431119226117E20, h.hashrate, 1e6)
        assertEquals(140577680051919.6, h.difficulty, 1.0)
    }

    @Test fun lightning() {
        val l = MarketParser.parseLightning(
            """{"latest":{"id":157258,"added":"2026-07-13T00:00:00.000Z","channel_count":39627,"node_count":17334,"total_capacity":445087713469,"tor_nodes":8839}}""",
        )
        assertEquals(39627L, l.channels)
        assertEquals(17334L, l.nodes)
        assertEquals(445087713469L, l.capacitySats)
        expectParseError { MarketParser.parseLightning("""{"previous":{}}""") }
    }

    @Test fun historyFromTruncatedText() {
        val text = """{"prices":[{"time":1792335600,"EUR":72117,"USD":81009},{"time":1792332000,"EUR":72501,"USD":81438},""" +
            """{"time":1792328400,"EUR":72892,"USD":81872},{"ti"""
        val points = MarketParser.parseHistory(text)
        assertEquals(3, points.size)
        // aufsteigend nach Zeit
        assertEquals(1792328400L, points.first().timeSec)
        assertEquals(1792335600L, points.last().timeSec)
        assertEquals(72117.0, points.last().eur, 0.0)
        assertEquals(81009.0, points.last().usd, 0.0)
    }

    @Test fun historyIgnoresOtherObjectsAndKeyOrder() {
        val text = """{"prices":[{"USD":81000,"time":1700000000,"EUR":72000}],"exchangeRates":{"USDEUR":0.89,"USDGBP":0.75}}"""
        val points = MarketParser.parseHistory(text)
        assertEquals(1, points.size)
        assertEquals(72000.0, points[0].eur, 0.0)
    }

    @Test fun historyRemovesDuplicatesAndLimitsLength() {
        val sb = StringBuilder("""{"prices":[""")
        for (i in 0 until 2500) {
            if (i > 0) sb.append(',')
            sb.append("""{"time":${1_700_000_000L + i * 3600L},"EUR":70000,"USD":80000}""")
        }
        sb.append(""",{"time":1700000000,"EUR":1,"USD":1}]}""")
        val points = MarketParser.parseHistory(sb.toString())
        assertEquals(MarketParser.MAX_HISTORY_POINTS, points.size)
        // die neuesten Punkte bleiben erhalten
        assertEquals(1_700_000_000L + 2499 * 3600L, points.last().timeSec)
        assertTrue(points.zipWithNext().all { (a, b) -> a.timeSec < b.timeSec })
        assertTrue(MarketParser.parseHistory("kein JSON").isEmpty())
    }

    @Test fun euroRate() {
        val r = MarketParser.parseEuroRate("""{"date":"2026-10-18","base":"EUR","quote":"USD","rate":1.1231}""")
        assertEquals(1.1231, r.usdPerEur, 1e-9)
        assertEquals("2026-10-18", r.date)
        expectParseError { MarketParser.parseEuroRate("""{"date":"2026-10-18"}""") }
        assertNotNull(r)
    }

    private fun expectParseError(block: () -> Unit) {
        try {
            block()
        } catch (e: MarketParser.ParseException) {
            return
        }
        fail("ParseException erwartet")
    }
}
