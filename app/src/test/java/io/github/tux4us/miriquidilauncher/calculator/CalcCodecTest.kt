// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcCodecTest {
    @Test fun roundTrip() {
        val state = CalcPersisted(
            angle = AngleMode.Grad,
            memory = -2.5,
            ans = 1.0E-7,
            history = listOf(HistoryEntry("sin(30)", 0.5), HistoryEntry("2×(3+4)", 14.0), HistoryEntry("7,5 mod 2", 1.5)),
        )
        assertEquals(state, CalcCodec.decode(CalcCodec.encode(state)))
    }

    @Test fun emptyAndForeignInputGivesDefaults() {
        assertEquals(CalcPersisted(), CalcCodec.decode(null))
        assertEquals(CalcPersisted(), CalcCodec.decode(""))
        assertEquals(CalcPersisted(), CalcCodec.decode("v2\nangle=rad"))
        assertEquals(CalcPersisted(), CalcCodec.decode("Quatsch"))
    }

    @Test fun damagedLinesAreSkipped() {
        val text = "v1\nangle=rad\nmem=abc\nans=NaN\nh=nur Text\nh=\t5\nh=1+1\t2.0\nh=2+2\tx\nh=3+3\tInfinity\nh=ok\t4.0\n"
        val decoded = CalcCodec.decode(text)
        assertEquals(AngleMode.Rad, decoded.angle)
        assertNull(decoded.memory)
        assertEquals(0.0, decoded.ans, 0.0)
        assertEquals(listOf(HistoryEntry("1+1", 2.0), HistoryEntry("ok", 4.0)), decoded.history)
    }

    @Test fun tabsAndNewlinesInExpressionsAreNeutralised() {
        val encoded = CalcCodec.encode(CalcPersisted(history = listOf(HistoryEntry("1\t+\n1", 2.0))))
        assertEquals(listOf(HistoryEntry("1 + 1", 2.0)), CalcCodec.decode(encoded).history)
    }

    @Test fun sessionConversion() {
        val session = CalcSession(angle = AngleMode.Rad, memory = 3.0, ans = 4.0, history = listOf(HistoryEntry("1+1", 2.0)))
        assertEquals(session, session.persisted().toSession())
    }
}
