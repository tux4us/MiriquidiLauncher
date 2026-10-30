// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlsTest {
    private val caps = ManualCaps(isoMin = 50, isoMax = 3200, exposureMinNs = 125_000L, exposureMaxNs = 1_000_000_000L, minFocusDiopters = 10f)

    @Test
    fun isoStopsStayInsideTheRange() {
        assertEquals(listOf(50, 100, 200, 400, 800, 1600, 3200), ManualControls.isoStops(caps))
        assertEquals(listOf(100, 200), ManualControls.isoStops(caps.copy(isoMin = 100, isoMax = 200)))
    }

    @Test
    fun isoStopsFallBackToTheLimitsWhenNoStandardValueFits() {
        assertEquals(listOf(110, 190), ManualControls.isoStops(caps.copy(isoMin = 110, isoMax = 190)))
        assertEquals(listOf(110), ManualControls.isoStops(caps.copy(isoMin = 110, isoMax = 110)))
    }

    @Test
    fun shutterStopsStayInsideTheRangeAndEndAtOneSecond() {
        val stops = ManualControls.shutterStops(caps)
        assertEquals(125_000L, stops.first())
        assertEquals(1_000_000_000L, stops.last())
        assertEquals(14, stops.size)
        val short = ManualControls.shutterStops(caps.copy(exposureMinNs = 1_000_000L, exposureMaxNs = 33_333_333L))
        assertEquals(listOf(1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L), short)
    }

    @Test
    fun shutterStopsFallBackToTheLimits() {
        assertEquals(
            listOf(300_000L, 330_000L),
            ManualControls.shutterStops(caps.copy(exposureMinNs = 300_000L, exposureMaxNs = 330_000L)),
        )
    }

    @Test
    fun nearestIndexPicksTheClosestValue() {
        val stops = ManualControls.shutterStops(caps)
        assertEquals(stops.indexOf(8_000_000L), ManualControls.nearestIndex(stops, 9_000_000L))
        assertEquals(0, ManualControls.nearestIndex(stops, 1L))
        assertEquals(stops.lastIndex, ManualControls.nearestIndex(stops, Long.MAX_VALUE / 2))
        assertEquals(-1, ManualControls.nearestIndex(emptyList(), 5L))
        assertEquals(2, ManualControls.nearestIsoIndex(listOf(50, 100, 200), 180))
        assertEquals(-1, ManualControls.nearestIsoIndex(emptyList(), 100))
    }

    @Test
    fun focusFractionMapsToDiopters() {
        assertEquals(0f, ManualControls.focusDiopters(0f, caps), 0.0001f)
        assertEquals(5f, ManualControls.focusDiopters(0.5f, caps), 0.0001f)
        assertEquals(10f, ManualControls.focusDiopters(1f, caps), 0.0001f)
        assertEquals(10f, ManualControls.focusDiopters(7f, caps), 0.0001f)
        assertEquals(0f, ManualControls.focusDiopters(-1f, caps), 0.0001f)
    }

    @Test
    fun normalizeSnapsToTheCameraStops() {
        val state = ManualState(exposureEnabled = true, iso = 120, exposureNs = 9_000_000L, focusEnabled = true, focusFraction = 3f)
        val fixed = ManualControls.normalize(state, caps)
        assertEquals(100, fixed.iso)
        assertEquals(8_000_000L, fixed.exposureNs)
        assertEquals(1f, fixed.focusFraction, 0.0001f)
        assertTrue(fixed.focusEnabled)
    }

    @Test
    fun normalizeDisablesFocusForFixedFocusCameras() {
        val fixedFocus = caps.copy(minFocusDiopters = 0f)
        assertFalse(fixedFocus.hasManualFocus)
        val state = ManualState(focusEnabled = true)
        assertFalse(ManualControls.normalize(state, fixedFocus).focusEnabled)
    }

    @Test
    fun normalizeWithoutManualSupportResetsEverything() {
        val state = ManualState(exposureEnabled = true, whiteBalance = WhiteBalanceSetting.Cloudy)
        assertEquals(ManualState(), ManualControls.normalize(state, null))
    }

    @Test
    fun optionsCarryTheEnabledParts() {
        val state = ManualState(
            exposureEnabled = true, iso = 400, exposureNs = 4_000_000L,
            focusEnabled = true, focusFraction = 0.5f, whiteBalance = WhiteBalanceSetting.Daylight,
        )
        val o = ManualControls.options(state, caps)
        assertTrue(o.autoExposureOff)
        assertEquals(400, o.iso)
        assertEquals(4_000_000L, o.exposureNs)
        assertNull(o.frameDurationNs)
        assertTrue(o.autoFocusOff)
        assertEquals(5f, o.focusDiopters, 0.0001f)
        assertEquals(WhiteBalanceSetting.Daylight, o.whiteBalance)
    }

    @Test
    fun longExposuresExtendTheFrameDuration() {
        val slow = ManualControls.options(ManualState(exposureEnabled = true, exposureNs = 500_000_000L), caps)
        assertEquals(500_000_000L, slow.frameDurationNs)
        val limit = ManualControls.options(ManualState(exposureEnabled = true, exposureNs = 33_333_333L), caps)
        assertNull(limit.frameDurationNs)
    }

    @Test
    fun stateIsActiveWhenAnyAutomaticIsReplaced() {
        assertFalse(ManualState().active)
        assertTrue(ManualState(exposureEnabled = true).active)
        assertTrue(ManualState(focusEnabled = true).active)
        assertTrue(ManualState(whiteBalance = WhiteBalanceSetting.Shade).active)
    }
}
