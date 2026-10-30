// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcSessionTest {
    private fun CalcSession.keys(vararg tokens: String): CalcSession = tokens.fold(this) { s, t -> s.type(t) }
    private fun CalcSession.text() = editor.text
    private fun CalcSession.result(): Double = (outcome as CalcOutcome.Value).value

    @Test fun typingAndEvaluating() {
        val s = CalcSession().keys("1", "2", "×", "7").evaluate()
        assertEquals("12×7", s.text())
        assertEquals(84.0, s.result(), 0.0)
        assertTrue(s.evaluated)
        assertEquals(listOf(HistoryEntry("12×7", 84.0)), s.history)
        assertEquals(84.0, s.ans, 0.0)
    }

    @Test fun operatorAfterResultContinuesWithAns() {
        val s = CalcSession().keys("6", "×", "7").evaluate().keys("+", "8")
        assertEquals("Ans+8", s.text())
        assertEquals(50.0, s.evaluate().result(), 0.0)
    }

    @Test fun digitAfterResultStartsNewCalculation() {
        val s = CalcSession().keys("6", "×", "7").evaluate().keys("5")
        assertEquals("5", s.text())
        assertNull(s.outcome)
        assertTrue(!s.evaluated)
    }

    @Test fun operatorReplacesPreviousOperator() {
        assertEquals("5×3", CalcSession().keys("5", "+", "×", "3").text())
        assertEquals("5×−3", CalcSession().keys("5", "×", "−", "3").text())
        assertEquals("", CalcSession().keys("×").text())
        assertEquals("−3", CalcSession().keys("−", "3").text())
    }

    @Test fun decimalSeparatorOncePerNumber() {
        assertEquals("1,5", CalcSession().keys("1", ",", "5", ",").text())
        assertEquals("1,5+2,5", CalcSession().keys("1", ",", "5", "+", "2", ",", "5").text())
        assertEquals("1.5", CalcSession().type("1", '.').type(",", '.').type("5", '.').text())
        assertEquals("1.5", CalcSession().type("1", '.').type(".", '.').type("5", '.').type(".", '.').text())
    }

    @Test fun closingParenthesisNeedsOpening() {
        assertEquals("(2)", CalcSession().keys(")", "(", "2", ")", ")").text())
    }

    @Test fun semicolonAndExponentOnlyWhereTheyMakeSense() {
        assertEquals("ncr(5;2)", CalcSession().keys("ncr(", "5", ";", "2", ")").text())
        assertEquals("5", CalcSession().keys("5", ";").text())
        assertEquals("2E3", CalcSession().keys("2", "E", "3").text())
        assertEquals("", CalcSession().keys("E").text())
        assertEquals("(", CalcSession().keys("(", "E").text())
    }

    @Test fun backspaceRemovesWholeFunctionName() {
        assertEquals("2", CalcSession().keys("2", "sin(").backspace().text())
        assertEquals("", CalcSession().keys("√(").backspace().text())
        assertEquals("2+", CalcSession().keys("2", "+", "3").backspace().text())
        assertEquals("", CalcSession().backspace().text())
    }

    @Test fun backspaceAfterResultEditsExpression() {
        val t = CalcSession().keys("1", "+", "2").evaluate().backspace()
        assertEquals("1+", t.text())
        assertTrue(!t.evaluated)
        assertNull(t.outcome)
        assertEquals(3.0, t.ans, 0.0)
    }

    @Test fun cursorEditing() {
        var s = CalcSession().keys("1", "3")
        s = s.moveLeft().type("2")
        assertEquals("123", s.text())
        assertEquals(2, s.editor.cursor)
        s = s.moveLeft().moveLeft().moveLeft().moveLeft()
        assertEquals(0, s.editor.cursor)
        s = s.moveRight().moveRight().moveRight().moveRight()
        assertEquals(3, s.editor.cursor)
    }

    @Test fun errorsKeepTheExpression() {
        val s = CalcSession().keys("1", "÷", "0").evaluate()
        assertEquals(CalcOutcome.Error(CalcError.DivideByZero), s.outcome)
        assertEquals("1÷0", s.text())
        assertTrue(!s.evaluated)
        assertTrue(s.history.isEmpty())
        assertEquals("1÷", s.backspace().text())
    }

    @Test fun evaluationClosesParentheses() {
        val s = CalcSession().keys("2", "×", "(", "3", "+", "4").evaluate()
        assertEquals(14.0, s.result(), 0.0)
    }

    @Test fun previewShowsOnlyForRealCalculations() {
        assertNull(CalcSession().keys("1", "2").preview())
        assertNull(CalcSession().keys("1", "+").preview())
        assertEquals(5.0, CalcSession().keys("2", "+", "3").preview()!!, 0.0)
        assertNull(CalcSession().keys("2", "+", "3").evaluate().preview())
    }

    @Test fun historyIsLimitedAndDeduplicated() {
        var s = CalcSession()
        repeat(60) { i -> s = s.clear().keys("$i", "+", "1").evaluate() }
        assertEquals(CalcSession.MAX_HISTORY, s.history.size)
        assertEquals("59+1", s.history.first().expression)
        val again = s.clear().keys("5", "9", "+", "1").evaluate()
        assertEquals(s.history, again.history)
        assertTrue(again.clearHistory().history.isEmpty())
    }

    @Test fun memory() {
        var s = CalcSession().keys("5", "×", "4").memoryAdd()
        assertEquals(20.0, s.memory!!, 0.0)
        s = s.clear().keys("8").memoryAdd(subtract = true)
        assertEquals(12.0, s.memory!!, 0.0)
        s = s.clear().memoryRecall()
        assertEquals("12", s.text())
        s = s.keys("+", "1").evaluate()
        assertEquals(13.0, s.result(), 0.0)
        assertNull(s.memoryClear().memory)
        // negativer Speicher wird geklammert
        val neg = CalcSession().keys("5").memoryAdd(subtract = true).clear().memoryRecall()
        assertEquals("(-5)", neg.text())
        // leere Eingabe verändert den Speicher nicht
        assertNull(CalcSession().memoryAdd().memory)
        assertNull(CalcSession().memoryRecall().memory)
    }

    @Test fun memoryAddUsesShownResult() {
        val s = CalcSession().keys("2", "+", "3").evaluate().memoryAdd()
        assertEquals(5.0, s.memory!!, 0.0)
    }

    @Test fun angleModeIsUsedInEvaluation() {
        val deg = CalcSession().keys("sin(", "9", "0").evaluate()
        assertEquals(1.0, deg.result(), 0.0)
        val rad = CalcSession().toggleAngle().keys("sin(", "0").evaluate()
        assertEquals(0.0, rad.result(), 0.0)
        assertEquals(AngleMode.Rad, rad.angle)
    }

    @Test fun ansKeyword() {
        val s = CalcSession().keys("3", "×", "3").evaluate().clear().keys("Ans", "+", "1").evaluate()
        assertEquals(10.0, s.result(), 0.0)
    }

    @Test fun loadExpressionAndHistoryValue() {
        val s = CalcSession().loadExpression("  12*7 ")
        assertEquals("12*7", s.text())
        assertEquals(84.0, s.evaluate().result(), 0.0)
        val v = CalcSession().insertValue(-2.5)
        assertEquals("(-2,5)", v.text())
        assertEquals(200, CalcSession().loadExpression("1".repeat(500)).text().length)
    }

    @Test fun lengthLimit() {
        var s = CalcSession()
        repeat(250) { s = s.type("1") }
        assertEquals(CalcEngine.MAX_LENGTH, s.text().length)
    }
}
