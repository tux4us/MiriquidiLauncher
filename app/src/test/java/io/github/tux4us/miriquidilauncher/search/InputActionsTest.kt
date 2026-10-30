// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputActionsTest {
    private fun kinds(s: String) = InputActions.detect(s).map { it.kind }

    @Test fun shortInputGivesNothing() {
        assertTrue(InputActions.detect("a").isEmpty())
        assertTrue(InputActions.detect("  ").isEmpty())
    }

    @Test fun plainWordOnlyOffersWebSearch() {
        assertEquals(listOf(ActionKind.WebSearch), kinds("wetter"))
    }

    @Test fun calculation() {
        val result = InputActions.detect("12*7")
        assertEquals(ActionKind.Calculation, result.first().kind)
        assertEquals("12*7 = 84", result.first().title)
        assertEquals("84", result.first().payload)
        assertEquals(ActionKind.WebSearch, result.last().kind)
    }

    @Test fun calculationKeepsTheExpressionForTheCalculator() {
        val calculation = InputActions.detect("12*7").first()
        assertEquals("12*7", calculation.expression)
        assertNull(InputActions.detect("20 c nach f").first().expression)
        assertNull(InputActions.detect("wetter").first().expression)
    }

    @Test fun scientificCalculation() {
        val result = InputActions.detect("sin(30)+1")
        assertEquals(ActionKind.Calculation, result.first().kind)
        assertEquals("sin(30)+1 = 1,5", result.first().title)
    }

    @Test fun conversion() {
        val first = InputActions.detect("20 c nach f").first()
        assertEquals(ActionKind.Conversion, first.kind)
        assertEquals("20 °C = 68 °F", first.title)
    }

    @Test fun decimalSeparatorIsConfigurable() {
        assertEquals("1.5*3 = 4.5", InputActions.detect("1.5*3", '.').first().title)
    }

    @Test fun phoneNumbers() {
        assertEquals(listOf(ActionKind.Call, ActionKind.Sms, ActionKind.WebSearch), kinds("0170 1234567"))
        assertEquals("+4930123456", InputActions.phoneNumber("+49 30 123456"))
        assertEquals("030123456", InputActions.phoneNumber("030/123456"))
        assertNull(InputActions.phoneNumber("1234"))
        assertNull(InputActions.phoneNumber("12345678901234567"))
        assertNull(InputActions.phoneNumber("abc12345"))
    }

    @Test fun emailAddress() {
        assertEquals(ActionKind.Email, kinds("max@example.org").first())
        assertTrue(ActionKind.Email !in kinds("max@example"))
    }

    @Test fun urls() {
        assertEquals("https://example.org", InputActions.url("example.org"))
        assertEquals("https://www.wildnislehrer.com/kurse", InputActions.url("www.wildnislehrer.com/kurse"))
        assertEquals("http://example.org/a", InputActions.url("http://example.org/a"))
        assertEquals("https://example.de:8080/x?y=1", InputActions.url("example.de:8080/x?y=1"))
        assertNull(InputActions.url("bild.png"))
        assertNull(InputActions.url("3.14"))
        assertNull(InputActions.url("hallo welt.de"))
        assertNull(InputActions.url("z.B"))
        assertEquals(ActionKind.OpenUrl, kinds("example.org").first())
    }

    @Test fun ambiguousDateShowsCalculationAndPhone() {
        val k = kinds("2024-10-08")
        assertTrue(ActionKind.Calculation in k)
        assertTrue(ActionKind.Call in k)
    }
}
