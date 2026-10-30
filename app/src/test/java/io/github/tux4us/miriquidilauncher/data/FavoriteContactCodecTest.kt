// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteContactCodecTest {
    private val anna = FavoriteContact("Anna Schmidt", "+49 170 1234567")
    private val bob = FavoriteContact("Bob", "030 / 555-0100")

    @Test
    fun roundTripKeepsOrderNamesAndNumbers() {
        val raw = FavoriteContactCodec.encode(listOf(anna, bob))
        assertEquals(listOf(anna, bob), FavoriteContactCodec.decode(raw))
    }

    @Test
    fun emptyAndMissingInputDecodeToEmptyList() {
        assertTrue(FavoriteContactCodec.decode(null).isEmpty())
        assertTrue(FavoriteContactCodec.decode("").isEmpty())
        assertEquals("", FavoriteContactCodec.encode(emptyList()))
    }

    @Test
    fun separatorsInNamesDoNotBreakDecoding() {
        val tricky = FavoriteContact("A\u001EB\u001FC", "123")
        val decoded = FavoriteContactCodec.decode(FavoriteContactCodec.encode(listOf(tricky, bob)))
        assertEquals(listOf(FavoriteContact("ABC", "123"), bob), decoded)
    }

    @Test
    fun malformedRecordsAreSkipped() {
        val raw = "nur ein Feld\u001E" + FavoriteContactCodec.encode(listOf(bob)) + "\u001EName\u001F  "
        assertEquals(listOf(bob), FavoriteContactCodec.decode(raw))
    }

    @Test
    fun addIgnoresDuplicatesByNormalizedNumber() {
        val same = FavoriteContact("Anna privat", "+491701234567")
        assertEquals(listOf(anna), FavoriteContactCodec.add(listOf(anna), same))
        assertEquals(listOf(anna, bob), FavoriteContactCodec.add(listOf(anna), bob))
    }

    @Test
    fun addIgnoresBlankNumbers() {
        assertEquals(listOf(anna), FavoriteContactCodec.add(listOf(anna), FavoriteContact("X", "  ")))
    }

    @Test
    fun removeMatchesByNormalizedNumber() {
        val result = FavoriteContactCodec.remove(listOf(anna, bob), FavoriteContact("egal", "+491701234567"))
        assertEquals(listOf(bob), result)
    }
}
