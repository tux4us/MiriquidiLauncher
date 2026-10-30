// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFormatTest {
    private val sec = 1_000_000_000L

    @Test
    fun durationIsMinutesAndSeconds() {
        assertEquals("00:00", CameraFormat.duration(0))
        assertEquals("00:07", CameraFormat.duration(7 * sec + 900_000_000L))
        assertEquals("01:05", CameraFormat.duration(65 * sec))
        assertEquals("59:59", CameraFormat.duration(3599 * sec))
    }

    @Test
    fun durationShowsHoursAndIgnoresNegativeValues() {
        assertEquals("1:00:00", CameraFormat.duration(3600 * sec))
        assertEquals("2:03:04", CameraFormat.duration((2 * 3600 + 3 * 60 + 4) * sec))
        assertEquals("00:00", CameraFormat.duration(-5 * sec))
    }

    @Test
    fun shutterBelowOneSecondIsAFraction() {
        assertEquals("1/8000", CameraFormat.shutter(125_000L))
        assertEquals("1/250", CameraFormat.shutter(4_000_000L))
        assertEquals("1/60", CameraFormat.shutter(16_666_667L))
        assertEquals("1/30", CameraFormat.shutter(33_333_333L))
        assertEquals("1/2", CameraFormat.shutter(500_000_000L))
    }

    @Test
    fun shutterFromOneSecondIsInSeconds() {
        assertEquals("1 s", CameraFormat.shutter(sec))
        assertEquals("2 s", CameraFormat.shutter(2 * sec))
        assertEquals("1.5 s", CameraFormat.shutter(1_500_000_000L))
        assertEquals("–", CameraFormat.shutter(0))
    }

    @Test
    fun zoomShowsTenthsOnlyWhenNeeded() {
        assertEquals("1×", CameraFormat.zoom(1f))
        assertEquals("2×", CameraFormat.zoom(2.0f))
        assertEquals("2.5×", CameraFormat.zoom(2.46f))
        assertEquals("0.5×", CameraFormat.zoom(0.5f))
        assertEquals("10×", CameraFormat.zoom(9.98f))
    }

    @Test
    fun focusShowsInfinityMetersAndCentimeters() {
        assertEquals("∞", CameraFormat.focus(0f))
        assertEquals("∞", CameraFormat.focus(0.02f))
        assertEquals("2.0 m", CameraFormat.focus(0.5f))
        assertEquals("1.0 m", CameraFormat.focus(1f))
        assertEquals("50 cm", CameraFormat.focus(2f))
        assertEquals("10 cm", CameraFormat.focus(10f))
    }

    @Test
    fun isoLabel() {
        assertEquals("ISO 400", CameraFormat.iso(400))
    }

    @Test
    fun namesUseLocalTime() {
        // 2026-10-21 12:33:05 UTC = 14:33:05 in Berlin (Sommerzeit endet erst am 25.10.)
        val millis = java.time.ZonedDateTime.of(2026, 10, 21, 12, 33, 5, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("IMG_20261021_143305", CameraFormat.photoName(millis, ZoneId.of("Europe/Berlin")))
        assertEquals("VID_20261021_123305", CameraFormat.videoName(millis, ZoneId.of("UTC")))
    }

    @Test
    fun mimeTypesFollowTheFormat() {
        assertEquals("image/jpeg", CameraFormat.mimeType(PhotoFormat.Jpeg))
        assertEquals("image/jpeg", CameraFormat.mimeType(PhotoFormat.UltraHdr))
        assertEquals("image/x-adobe-dng", CameraFormat.mimeType(PhotoFormat.Raw))
    }
}
