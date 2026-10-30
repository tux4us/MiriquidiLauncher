// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun removesDiacriticsAndUmlauts() {
        assertEquals("cafe muller", TextNormalizer.normalize("Café Müller"))
    }

    @Test
    fun replacesEszett() {
        assertEquals("strasse", TextNormalizer.normalize("Straße"))
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("google maps", TextNormalizer.normalize("  Google \t  Maps  "))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", TextNormalizer.normalize(""))
        assertEquals("", TextNormalizer.normalize("   "))
    }
}

class MatcherTest {
    private fun score(query: String, candidate: String) =
        Matcher.score(TextNormalizer.normalize(query), TextNormalizer.normalize(candidate))

    @Test
    fun noMatchReturnsZero() {
        assertEquals(Matcher.NO_MATCH, score("xyz", "Firefox"))
        assertEquals(Matcher.NO_MATCH, score("", "Firefox"))
        assertEquals(Matcher.NO_MATCH, score("fox", ""))
    }

    @Test
    fun classOrderIsExactPrefixWordPrefixInitialsSubstring() {
        val exact = score("maps", "Maps")
        val prefix = score("map", "Maps")
        val wordPrefix = score("maps", "Google Maps")
        val initials = score("gm", "Google Maps")
        val substring = score("oog", "Google Maps")

        assertTrue("exact > prefix", exact > prefix)
        assertTrue("prefix > wordPrefix", prefix > wordPrefix)
        assertTrue("wordPrefix > initials", wordPrefix > initials)
        assertTrue("initials > substring", initials > substring)
        assertTrue("substring > 0", substring > Matcher.NO_MATCH)
    }

    @Test
    fun shorterCandidateWinsWithinSameClass() {
        assertTrue(score("sig", "Signal") > score("sig", "Signal Private Messenger"))
    }

    @Test
    fun umlautsMatchBaseLetters() {
        assertTrue(score("muller", "Müller") > Matcher.NO_MATCH)
        assertTrue(score("mül", "Muller") > Matcher.NO_MATCH)
    }

    @Test
    fun multiTokenQueryRequiresEveryToken() {
        assertTrue(score("google ma", "Google Maps") > Matcher.NO_MATCH)
        assertTrue(score("maps goo", "Google Maps") > Matcher.NO_MATCH)
        assertEquals(Matcher.NO_MATCH, score("google mail", "Google Maps"))
    }

    @Test
    fun initialsMatchInOrderOnly() {
        assertTrue(score("hg", "Hello Group") > Matcher.NO_MATCH)
        assertEquals(Matcher.NO_MATCH, score("gh", "Hello Group"))
    }
}
