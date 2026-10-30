// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListOrderTest {
    private val list = listOf("a", "b", "c", "d")

    @Test fun moveForwardAndBack() {
        assertEquals(listOf("b", "c", "a", "d"), ListOrder.move(list, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), ListOrder.move(list, 3, 1))
    }

    @Test fun outOfRangeIsClampedOrIgnored() {
        assertEquals(list, ListOrder.move(list, 9, 0))
        assertEquals(list, ListOrder.move(list, -1, 0))
        assertEquals(listOf("b", "c", "d", "a"), ListOrder.move(list, 0, 99))
        assertEquals(list, ListOrder.move(emptyList<String>() + list, 2, 2))
    }

    @Test fun moveByDelta() {
        assertEquals(listOf("b", "a", "c", "d"), ListOrder.moveBy(list, 1) { it == "a" })
        assertEquals(list, ListOrder.moveBy(list, -1) { it == "a" })
        assertEquals(list, ListOrder.moveBy(list, 1) { it == "d" })
        assertEquals(list, ListOrder.moveBy(list, 1) { it == "zzz" })
    }
}
