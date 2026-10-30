// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraChoicesTest {
    // Camera2 LENS_FACING: 0 = vorne, 1 = hinten, 2 = extern
    private val phone = CameraChoices.build(listOf("1" to 0, "0" to 1, "2" to 1, "3" to 1, "10" to 1))

    @Test
    fun orderIsBackThenFrontThenExternalAndNumeric() {
        assertEquals(listOf("0", "2", "3", "10", "1"), phone.map { it.id })
        val mixed = CameraChoices.build(listOf("usb" to 2, "1" to 0, "0" to 1))
        assertEquals(listOf("0", "1", "usb"), mixed.map { it.id })
        assertEquals(LensFacing.External, mixed.last().facing)
    }

    @Test
    fun ordinalsCountPerFacing() {
        val back = phone.filter { it.facing == LensFacing.Back }
        assertEquals(listOf(1, 2, 3, 4), back.map { it.ordinal })
        assertEquals(1, phone.first { it.facing == LensFacing.Front }.ordinal)
    }

    @Test
    fun duplicateIdsCountOnce() {
        assertEquals(2, CameraChoices.build(listOf("0" to 1, "0" to 1, "1" to 0)).size)
    }

    @Test
    fun initialPrefersSavedThenFirstBack() {
        assertEquals("3", CameraChoices.initial(phone, "3")?.id)
        assertEquals("0", CameraChoices.initial(phone, "99")?.id)
        assertEquals("0", CameraChoices.initial(phone, null)?.id)
        val frontOnly = CameraChoices.build(listOf("1" to 0))
        assertEquals("1", CameraChoices.initial(frontOnly, null)?.id)
        assertNull(CameraChoices.initial(emptyList(), null))
    }

    @Test
    fun flipGoesFrontAndBackToTheLastBackCamera() {
        assertEquals("1", CameraChoices.flipTarget(phone, "0", null)?.id)
        assertEquals("1", CameraChoices.flipTarget(phone, "3", "3")?.id)
        assertEquals("3", CameraChoices.flipTarget(phone, "1", "3")?.id)
        assertEquals("0", CameraChoices.flipTarget(phone, "1", null)?.id)
        assertEquals("0", CameraChoices.flipTarget(phone, "1", "1")?.id)
    }

    @Test
    fun flipWithoutOppositeSideCyclesThroughTheList() {
        val backs = CameraChoices.build(listOf("0" to 1, "2" to 1))
        assertEquals("2", CameraChoices.flipTarget(backs, "0", null)?.id)
        assertEquals("0", CameraChoices.flipTarget(backs, "2", null)?.id)
        assertNull(CameraChoices.flipTarget(CameraChoices.build(listOf("0" to 1)), "0", null))
        assertNull(CameraChoices.flipTarget(emptyList(), null, null))
    }

    @Test
    fun flipFromUnknownCameraPicksTheFirst() {
        assertEquals("0", CameraChoices.flipTarget(phone, "nope", null)?.id)
    }

    @Test
    fun multipleBackCamerasAreDetected() {
        assertTrue(CameraChoices.hasMultipleBack(phone))
        assertFalse(CameraChoices.hasMultipleBack(CameraChoices.build(listOf("0" to 1, "1" to 0))))
    }
}
