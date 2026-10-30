// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcEngineTest {
    private fun value(s: String, angle: AngleMode = AngleMode.Deg, ans: Double = 0.0): Double {
        val r = CalcEngine.evaluate(s, angle, ans)
        assertTrue("$s -> $r", r is CalcOutcome.Value)
        return (r as CalcOutcome.Value).value
    }

    private fun error(s: String, angle: AngleMode = AngleMode.Deg): CalcError {
        val r = CalcEngine.evaluate(s, angle)
        assertTrue("$s -> $r", r is CalcOutcome.Error)
        return (r as CalcOutcome.Error).error
    }

    @Test fun basics() {
        assertEquals(14.0, value("2+3*4"), 0.0)
        assertEquals(20.0, value("(2+3)*4"), 0.0)
        assertEquals(2.5, value("5/2"), 0.0)
        assertEquals(84.0, value("12×7"), 0.0)
        assertEquals(6.0, value("12÷2"), 0.0)
        assertEquals(3.75, value("1,5*2,5"), 0.0)
        assertEquals(-5.0, value("-2-3"), 0.0)
    }

    @Test fun powerAndUnaryMinus() {
        assertEquals(512.0, value("2^3^2"), 0.0)
        assertEquals(-4.0, value("-2^2"), 0.0)
        assertEquals(0.125, value("2^-3"), 0.0)
        assertEquals(1.0, value("0^0"), 0.0)
        assertEquals(CalcError.DivideByZero, error("0^-1"))
        assertEquals(CalcError.Domain, error("(-8)^0,5"))
    }

    @Test fun floatingPointResidueIsRemoved() {
        assertEquals(0.3, value("0,1+0,2"), 0.0)
        assertEquals(0.0, value("(0,1+0,2)-0,3"), 0.0)
        assertEquals(1.1, value("0,7+0,4"), 0.0)
    }

    @Test fun percentLikeAPocketCalculator() {
        assertEquals(0.5, value("50%"), 0.0)
        assertEquals(110.0, value("100+10%"), 1e-12)
        assertEquals(90.0, value("100-10%"), 1e-12)
        assertEquals(20.0, value("200*10%"), 1e-12)
        assertEquals(15.0, value("150*10%"), 1e-12)
        assertEquals(115.5, value("100+10%+5%"), 1e-12)
        assertEquals(100.2, value("100+10%*2"), 1e-12)
        assertEquals(50.0, value("(50+50)*50%"), 1e-12)
    }

    @Test fun constantsAndImplicitMultiplication() {
        assertEquals(Math.PI * 2, value("2pi"), 1e-12)
        assertEquals(Math.PI, value("π"), 1e-12)
        assertEquals(Math.E, value("e"), 1e-12)
        assertEquals(14.0, value("2(3+4)"), 0.0)
        assertEquals(12.0, value("(2)(6)"), 0.0)
        assertEquals(10.0, value("ans*2", ans = 5.0), 0.0)
    }

    @Test fun degreeTrigonometryIsExact() {
        assertEquals(0.0, value("sin(180)"), 0.0)
        assertEquals(1.0, value("sin(90)"), 0.0)
        assertEquals(-1.0, value("sin(270)"), 0.0)
        assertEquals(0.0, value("cos(90)"), 0.0)
        assertEquals(-1.0, value("cos(180)"), 0.0)
        assertEquals(0.5, value("sin(30)"), 1e-14)
        assertEquals(1.0, value("tan(45)"), 1e-14)
        assertEquals(0.0, value("tan(180)"), 0.0)
        assertEquals(CalcError.Domain, error("tan(90)"))
        assertEquals(CalcError.Domain, error("tan(270)"))
        assertEquals(-1.0, value("sin(-90)"), 0.0)
    }

    @Test fun radianAndGradModes() {
        assertEquals(0.0, value("sin(pi)", AngleMode.Rad), 0.0)
        assertEquals(1.0, value("sin(pi/2)", AngleMode.Rad), 0.0)
        assertEquals(CalcError.Domain, error("tan(pi/2)", AngleMode.Rad))
        assertEquals(1.0, value("sin(100)", AngleMode.Grad), 0.0)
        assertEquals(0.5, value("sin(pi/6)", AngleMode.Rad), 1e-14)
    }

    @Test fun inverseTrigonometry() {
        assertEquals(30.0, value("asin(0,5)"), 1e-12)
        assertEquals(60.0, value("acos(0,5)"), 1e-12)
        assertEquals(45.0, value("atan(1)"), 1e-12)
        assertEquals(Math.PI / 2, value("asin(1)", AngleMode.Rad), 1e-12)
        assertEquals(CalcError.Domain, error("asin(2)"))
        assertEquals(CalcError.Domain, error("acos(-1,5)"))
    }

    @Test fun hyperbolic() {
        assertEquals(0.0, value("sinh(0)"), 0.0)
        assertEquals(1.0, value("cosh(0)"), 0.0)
        assertEquals(Math.tanh(0.5), value("tanh(0,5)"), 1e-12)
        assertEquals(1.0, value("asinh(sinh(1))"), 1e-12)
        assertEquals(2.0, value("acosh(cosh(2))"), 1e-12)
        assertEquals(0.5, value("atanh(tanh(0,5))"), 1e-12)
        assertEquals(-1.0, value("asinh(sinh(-1))"), 1e-12)
        assertEquals(CalcError.Domain, error("acosh(0,5)"))
        assertEquals(CalcError.Domain, error("atanh(1)"))
    }

    @Test fun logarithmsAndExponentials() {
        assertEquals(1.0, value("ln(e)"), 1e-12)
        assertEquals(3.0, value("log(1000)"), 0.0)
        assertEquals(3.0, value("lg(1000)"), 0.0)
        assertEquals(10.0, value("lb(1024)"), 0.0)
        assertEquals(3.0, value("log(8;2)"), 1e-12)
        assertEquals(Math.E, value("exp(1)"), 1e-12)
        assertEquals(CalcError.Domain, error("ln(0)"))
        assertEquals(CalcError.Domain, error("log(-1)"))
        assertEquals(CalcError.Domain, error("log(8;1)"))
        assertEquals(CalcError.Overflow, error("exp(1000)"))
    }

    @Test fun rootsAndAbsolute() {
        assertEquals(4.0, value("sqrt(16)"), 0.0)
        assertEquals(4.0, value("√16"), 0.0)
        assertEquals(4.0, value("√(16)"), 0.0)
        assertEquals(3.0, value("cbrt(27)"), 1e-12)
        assertEquals(-3.0, value("cbrt(-27)"), 1e-12)
        assertEquals(3.0, value("root(27;3)"), 1e-12)
        assertEquals(-2.0, value("root(-8;3)"), 1e-12)
        assertEquals(CalcError.Domain, error("root(-4;2)"))
        assertEquals(CalcError.Domain, error("sqrt(-4)"))
        assertEquals(7.0, value("abs(-7)"), 0.0)
    }

    @Test fun roundingFunctions() {
        assertEquals(3.0, value("floor(3,9)"), 0.0)
        assertEquals(4.0, value("ceil(3,1)"), 0.0)
        assertEquals(3.0, value("round(2,5)"), 0.0)
        assertEquals(-3.0, value("round(-2,5)"), 0.0)
        assertEquals(3.14, value("round(3,14159;2)"), 0.0)
        assertEquals(1200.0, value("round(1234;-2)"), 0.0)
        assertEquals(-1.0, value("sign(-4)"), 0.0)
    }

    @Test fun factorialAndCombinatorics() {
        assertEquals(120.0, value("5!"), 0.0)
        assertEquals(1.0, value("0!"), 0.0)
        assertEquals(720.0, value("fact(6)"), 0.0)
        assertEquals(6.0, value("3!"), 0.0)
        assertEquals(10.0, value("ncr(5;2)"), 0.0)
        assertEquals(20.0, value("npr(5;2)"), 0.0)
        assertEquals(1.0, value("ncr(7;0)"), 0.0)
        assertEquals(1.0, value("ncr(7;7)"), 0.0)
        assertEquals(CalcError.Domain, error("(-1)!"))
        assertEquals(CalcError.Domain, error("2,5!"))
        assertEquals(CalcError.Domain, error("171!"))
        assertEquals(CalcError.Domain, error("ncr(3;5)"))
        assertEquals(7.25741561530799e306, value("170!"), 1e293)
    }

    @Test fun modulo() {
        assertEquals(1.0, value("10mod3"), 0.0)
        assertEquals(2.0, value("-10 mod 3"), 0.0)
        assertEquals(1.5, value("7,5 mod 2"), 0.0)
        assertEquals(CalcError.DivideByZero, error("5mod0"))
    }

    @Test fun scientificNotation() {
        assertEquals(1500.0, value("1,5E3"), 0.0)
        assertEquals(0.002, value("2E-3"), 1e-18)
        assertEquals(2000.0, value("2E+3"), 0.0)
    }

    @Test fun errors() {
        assertEquals(CalcError.DivideByZero, error("1/0"))
        assertEquals(CalcError.Syntax, error("2+"))
        assertEquals(CalcError.Syntax, error("(2+3"))
        assertEquals(CalcError.Syntax, error("sin()"))
        assertEquals(CalcError.Syntax, error("sin(1;2)"))
        assertEquals(CalcError.Syntax, error("foo(2)"))
        assertEquals(CalcError.Syntax, error(""))
        assertEquals(CalcError.Syntax, error("1".repeat(300)))
        assertEquals(CalcError.Overflow, error("10^400"))
        assertEquals(CalcError.Syntax, error("(".repeat(100) + "1" + ")".repeat(100)))
    }

    @Test fun autoCloseAddsMissingParentheses() {
        val r = CalcEngine.evaluate("2*(3+4", autoClose = true)
        assertEquals(14.0, (r as CalcOutcome.Value).value, 0.0)
        val s = CalcEngine.evaluate("sin(30", autoClose = true)
        assertEquals(0.5, (s as CalcOutcome.Value).value, 1e-14)
        assertTrue(CalcEngine.evaluate("2*(3+4") is CalcOutcome.Error)
    }

    @Test fun formatting() {
        assertEquals("84", CalcFormat.format(84.0))
        assertEquals("2,5", CalcFormat.format(2.5))
        assertEquals("2.5", CalcFormat.format(2.5, '.'))
        assertEquals("0,333333333333", CalcFormat.format(1.0 / 3.0))
        assertEquals("0,3333333333", CalcFormat.format(1.0 / 3.0, ',', 10))
        assertEquals("0", CalcFormat.format(0.0))
        assertEquals("-5", CalcFormat.format(-5.0))
        assertEquals("1000000", CalcFormat.format(1_000_000.0))
        assertEquals("1E15", CalcFormat.format(1e15))
        assertEquals("1,5E20", CalcFormat.format(1.5e20))
        assertEquals("-1,5E-7", CalcFormat.format(-1.5e-7))
        assertEquals("0,00001", CalcFormat.format(0.00001))
        assertEquals("7,25741561531E306", CalcFormat.format(7.25741561530799e306))
    }

    @Test fun angleModeCycles() {
        assertEquals(AngleMode.Rad, AngleMode.Deg.next())
        assertEquals(AngleMode.Grad, AngleMode.Rad.next())
        assertEquals(AngleMode.Deg, AngleMode.Grad.next())
        assertEquals(AngleMode.Deg, AngleMode.fromKey("quatsch"))
        assertEquals(AngleMode.Grad, AngleMode.fromKey("grad"))
    }
}
