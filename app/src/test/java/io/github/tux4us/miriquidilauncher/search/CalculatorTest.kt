// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {
    private fun eval(s: String) = Calculator.evaluate(s)

    @Test fun basicOperations() {
        assertEquals(84.0, eval("12*7")!!, 1e-9)
        assertEquals(14.0, eval("2+3*4")!!, 1e-9)
        assertEquals(20.0, eval("(2+3)*4")!!, 1e-9)
        assertEquals(2.5, eval("5/2")!!, 1e-9)
        assertEquals(8.0, eval("2^3")!!, 1e-9)
    }

    @Test fun powerIsRightAssociative() {
        assertEquals(512.0, eval("2^3^2")!!, 1e-9)
    }

    @Test fun alternativeOperatorsAndSpaces() {
        assertEquals(84.0, eval("12 x 7")!!, 1e-9)
        assertEquals(84.0, eval("12 × 7")!!, 1e-9)
        assertEquals(6.0, eval("12 ÷ 2")!!, 1e-9)
        assertEquals(6.0, eval("12 : 2")!!, 1e-9)
    }

    @Test fun decimalCommaAndPoint() {
        assertEquals(3.75, eval("1,5*2,5")!!, 1e-9)
        assertEquals(3.75, eval("1.5*2.5")!!, 1e-9)
    }

    @Test fun unaryAndPercent() {
        assertEquals(-5.0, eval("-2-3")!!, 1e-9)
        assertEquals(0.5, eval("50%")!!, 1e-9)
        assertEquals(15.0, eval("150*10%")!!, 1e-9)
    }

    @Test fun sqrt() {
        assertEquals(4.0, eval("sqrt(16)")!!, 1e-9)
        assertNull(eval("sqrt(-4)"))
    }

    @Test fun singleNumberIsNoCalculation() {
        assertNull(eval("42"))
        assertNull(eval("3,14"))
    }

    @Test fun invalidInput() {
        assertNull(eval("2+"))
        assertNull(eval("(2+3"))
        assertNull(eval("2 3"))
        assertNull(eval("abc"))
        assertNull(eval("1/0"))
        assertNull(eval(""))
        assertNull(eval("1".repeat(200) + "+1"))
    }

    @Test fun deepNestingIsRejected() {
        assertNull(eval("(".repeat(100) + "1" + ")".repeat(100)))
    }

    @Test fun formatting() {
        assertEquals("84", Calculator.format(84.0))
        assertEquals("2,5", Calculator.format(2.5))
        assertEquals("2.5", Calculator.format(2.5, '.'))
        assertEquals("0,3333333333", Calculator.format(1.0 / 3.0))
        assertEquals("0,3", Calculator.format(0.1 + 0.2))
        assertEquals("-5", Calculator.format(-5.0))
        assertEquals("0", Calculator.format(0.0))
        assertEquals("1000000", Calculator.format(1_000_000.0))
    }

    @Test fun scientificFunctionsInSearch() {
        assertEquals(0.5, eval("sin(30)")!!, 1e-12)
        assertEquals(120.0, eval("5!")!!, 0.0)
        assertEquals(6.283185307, eval("2*pi")!!, 1e-8)
        assertEquals(1000.0, eval("10^3")!!, 0.0)
        assertEquals(110.0, eval("100+10%")!!, 1e-12)
        assertEquals(0.3, eval("0,1+0,2")!!, 0.0)
    }

    @Test fun wordsAndNamesAreNoCalculation() {
        assertNull(eval("sky-fi"))
        assertNull(eval("e-mail"))
        assertNull(eval("pi-hole"))
        assertNull(eval("e"))
        assertNull(eval("pi"))
        assertNull(eval("e-e"))
        assertNull(eval("wow!"))
        assertNull(eval("sin"))
        assertNull(eval("12 apples"))
    }
}
