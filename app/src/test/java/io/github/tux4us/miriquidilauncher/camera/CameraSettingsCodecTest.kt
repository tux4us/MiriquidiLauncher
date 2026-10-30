// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSettingsCodecTest {
    private val custom = CameraSettings(
        cameraId = "3",
        flash = FlashSetting.Auto,
        ratio = RatioSetting.R16x9,
        timer = TimerSetting.S10,
        grid = GridSetting.Thirds,
        photoFormat = PhotoFormat.Raw,
        extension = ExtensionSetting.Night,
        priority = PriorityMode.Speed,
        jpegQuality = JpegQuality.Maximum,
        videoQuality = VideoQualitySetting.Fhd,
        frameRate = FrameRateSetting.Fps60,
        stabilization = false,
        audio = false,
        mirrorFront = false,
        lastUri = "content://media/external/images/media/1234",
        lastIsVideo = true,
    )

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(custom, CameraSettingsCodec.decode(CameraSettingsCodec.encode(custom)))
    }

    @Test
    fun defaultsRoundTrip() {
        val defaults = CameraSettings()
        assertEquals(defaults, CameraSettingsCodec.decode(CameraSettingsCodec.encode(defaults)))
    }

    @Test
    fun missingOrEmptyTextGivesDefaults() {
        assertEquals(CameraSettings(), CameraSettingsCodec.decode(null))
        assertEquals(CameraSettings(), CameraSettingsCodec.decode(""))
        assertEquals(CameraSettings(), CameraSettingsCodec.decode("   "))
    }

    @Test
    fun unknownHeaderGivesDefaults() {
        assertEquals(CameraSettings(), CameraSettingsCodec.decode("v9\nflash=On\n"))
        assertEquals(CameraSettings(), CameraSettingsCodec.decode("flash=On\n"))
    }

    @Test
    fun invalidValuesFallBackPerField() {
        val text = "v1\nflash=Blitz\nratio=R16x9\ntimer=\naudio=maybe\nmirror=false\nnonsense\n=x\nfuture=1\n"
        val decoded = CameraSettingsCodec.decode(text)
        assertEquals(FlashSetting.Off, decoded.flash)
        assertEquals(RatioSetting.R16x9, decoded.ratio)
        assertEquals(TimerSetting.Off, decoded.timer)
        assertEquals(true, decoded.audio)
        assertEquals(false, decoded.mirrorFront)
    }

    @Test
    fun addressMayContainEqualsSigns() {
        val s = CameraSettings(lastUri = "content://media/x?id=5&a=b")
        assertEquals("content://media/x?id=5&a=b", CameraSettingsCodec.decode(CameraSettingsCodec.encode(s)).lastUri)
    }

    @Test
    fun lineBreaksInTextValuesCannotInjectSettings() {
        val s = CameraSettings(cameraId = "0\nflash=On", lastUri = "u\r\nvquality=Sd")
        val decoded = CameraSettingsCodec.decode(CameraSettingsCodec.encode(s))
        assertEquals(FlashSetting.Off, decoded.flash)
        assertEquals(VideoQualitySetting.Highest, decoded.videoQuality)
    }

    @Test
    fun blankIdsAreDropped() {
        val decoded = CameraSettingsCodec.decode(CameraSettingsCodec.encode(CameraSettings(cameraId = " ", lastUri = "")))
        assertNull(decoded.cameraId)
        assertNull(decoded.lastUri)
    }
}
