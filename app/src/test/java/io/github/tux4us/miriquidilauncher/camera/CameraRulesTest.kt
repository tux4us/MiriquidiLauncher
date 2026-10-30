// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRulesTest {
    private val fullCaps = CameraCaps(
        hasFlash = true,
        zoomMin = 0.5f,
        zoomMax = 8f,
        evMin = -12,
        evMax = 12,
        evStep = 1f / 6f,
        photoFormats = setOf(PhotoFormat.Jpeg, PhotoFormat.UltraHdr, PhotoFormat.Raw),
        extensions = setOf(ExtensionSetting.Hdr, ExtensionSetting.Night),
        videoQualities = listOf(VideoQualitySetting.Uhd, VideoQualitySetting.Fhd, VideoQualitySetting.Hd),
        frameRates = setOf(30, 60),
        stabilization = true,
        manual = ManualCaps(50, 3200, 125_000L, 1_000_000_000L, 10f),
    )
    private val basicCaps = CameraCaps()

    private fun request(
        mode: CaptureMode = CaptureMode.Photo,
        format: PhotoFormat = PhotoFormat.Jpeg,
        extension: ExtensionSetting = ExtensionSetting.None,
        videoQuality: VideoQualitySetting = VideoQualitySetting.Highest,
        frameRate: FrameRateSetting = FrameRateSetting.Auto,
        stabilization: Boolean = false,
        manualActive: Boolean = false,
    ) = BindRequest(
        mode = mode,
        cameraId = "0",
        ratio = RatioSetting.R4x3,
        photoFormat = format,
        extension = extension,
        priority = PriorityMode.Quality,
        jpegQuality = JpegQuality.High,
        videoQuality = videoQuality,
        frameRate = frameRate,
        stabilization = stabilization,
        mirrorFront = true,
        manualActive = manualActive,
    )

    @Test
    fun supportedPhotoFormatIsKept() {
        assertEquals(PhotoFormat.Raw, CameraRules.resolve(request(format = PhotoFormat.Raw), fullCaps).photoFormat)
        assertEquals(PhotoFormat.UltraHdr, CameraRules.resolve(request(format = PhotoFormat.UltraHdr), fullCaps).photoFormat)
    }

    @Test
    fun unsupportedPhotoFormatFallsBackToJpeg() {
        assertEquals(PhotoFormat.Jpeg, CameraRules.resolve(request(format = PhotoFormat.Raw), basicCaps).photoFormat)
        assertEquals(PhotoFormat.Jpeg, CameraRules.resolve(request(format = PhotoFormat.UltraHdr), basicCaps).photoFormat)
    }

    @Test
    fun extensionNeedsAvailabilityAndPhotoMode() {
        assertEquals(ExtensionSetting.Hdr, CameraRules.resolve(request(extension = ExtensionSetting.Hdr), fullCaps).extension)
        assertEquals(ExtensionSetting.None, CameraRules.resolve(request(extension = ExtensionSetting.Bokeh), fullCaps).extension)
        assertEquals(ExtensionSetting.None, CameraRules.resolve(request(extension = ExtensionSetting.Hdr), basicCaps).extension)
        assertEquals(
            ExtensionSetting.None,
            CameraRules.resolve(request(mode = CaptureMode.Video, extension = ExtensionSetting.Hdr), fullCaps).extension,
        )
    }

    @Test
    fun extensionForcesJpegAndYieldsToManualValues() {
        val withExtension = CameraRules.resolve(request(format = PhotoFormat.Raw, extension = ExtensionSetting.Night), fullCaps)
        assertEquals(ExtensionSetting.Night, withExtension.extension)
        assertEquals(PhotoFormat.Jpeg, withExtension.photoFormat)
        val manual = CameraRules.resolve(
            request(format = PhotoFormat.Raw, extension = ExtensionSetting.Night, manualActive = true),
            fullCaps,
        )
        assertEquals(ExtensionSetting.None, manual.extension)
        assertEquals(PhotoFormat.Raw, manual.photoFormat)
    }

    @Test
    fun videoAndPhotoOnlyFieldsAreNeutralizedInTheOtherMode() {
        val photo = CameraRules.resolve(
            request(videoQuality = VideoQualitySetting.Fhd, frameRate = FrameRateSetting.Fps60, stabilization = true),
            fullCaps,
        )
        assertEquals(VideoQualitySetting.Highest, photo.videoQuality)
        assertEquals(FrameRateSetting.Auto, photo.frameRate)
        assertFalse(photo.stabilization)
        val video = CameraRules.resolve(request(mode = CaptureMode.Video, format = PhotoFormat.Raw), fullCaps)
        assertEquals(PhotoFormat.Jpeg, video.photoFormat)
        assertEquals(PriorityMode.Quality, video.priority)
    }

    @Test
    fun changingVideoOnlySettingsInPhotoModeDoesNotChangeTheBinding() {
        val a = CameraRules.resolve(request(videoQuality = VideoQualitySetting.Hd, frameRate = FrameRateSetting.Fps30), fullCaps)
        val b = CameraRules.resolve(request(videoQuality = VideoQualitySetting.Uhd, frameRate = FrameRateSetting.Fps60), fullCaps)
        assertEquals(a, b)
    }

    @Test
    fun rawIgnoresJpegQuality() {
        val high = CameraRules.resolve(request(format = PhotoFormat.Raw).copy(jpegQuality = JpegQuality.Maximum), fullCaps)
        assertEquals(JpegQuality.High, high.jpegQuality)
    }

    @Test
    fun videoQualityFallsBackToBestSupportedBelow() {
        assertEquals(VideoQualitySetting.Fhd, CameraRules.effectiveVideoQuality(VideoQualitySetting.Fhd, fullCaps))
        val noUhd = fullCaps.copy(videoQualities = listOf(VideoQualitySetting.Hd, VideoQualitySetting.Sd))
        assertEquals(VideoQualitySetting.Hd, CameraRules.effectiveVideoQuality(VideoQualitySetting.Uhd, noUhd))
        assertEquals(VideoQualitySetting.Sd, CameraRules.effectiveVideoQuality(VideoQualitySetting.Sd, noUhd))
        assertEquals(VideoQualitySetting.Highest, CameraRules.effectiveVideoQuality(VideoQualitySetting.Fhd, basicCaps))
        assertEquals(VideoQualitySetting.Highest, CameraRules.effectiveVideoQuality(VideoQualitySetting.Highest, fullCaps))
    }

    @Test
    fun videoQualityAboveAllSupportedValuesBecomesHighest() {
        val onlyHigh = fullCaps.copy(videoQualities = listOf(VideoQualitySetting.Uhd))
        assertEquals(VideoQualitySetting.Highest, CameraRules.effectiveVideoQuality(VideoQualitySetting.Hd, onlyHigh))
    }

    @Test
    fun frameRateAndStabilizationNeedSupport() {
        val video = request(mode = CaptureMode.Video, frameRate = FrameRateSetting.Fps60, stabilization = true)
        val ok = CameraRules.resolve(video, fullCaps)
        assertEquals(FrameRateSetting.Fps60, ok.frameRate)
        assertTrue(ok.stabilization)
        val no = CameraRules.resolve(video, basicCaps)
        assertEquals(FrameRateSetting.Auto, no.frameRate)
        assertFalse(no.stabilization)
        assertEquals(FrameRateSetting.Auto, CameraRules.effectiveFrameRate(FrameRateSetting.Auto, fullCaps))
        val only30 = fullCaps.copy(frameRates = setOf(30))
        assertEquals(FrameRateSetting.Auto, CameraRules.effectiveFrameRate(FrameRateSetting.Fps60, only30))
    }

    @Test
    fun flashNeedsFlashUnitAndAutomaticExposure() {
        assertEquals(
            listOf(FlashSetting.Off, FlashSetting.Auto, FlashSetting.On),
            CameraRules.flashChoices(fullCaps, manualExposure = false),
        )
        assertEquals(listOf(FlashSetting.Off), CameraRules.flashChoices(basicCaps, manualExposure = false))
        assertEquals(listOf(FlashSetting.Off), CameraRules.flashChoices(fullCaps, manualExposure = true))
        assertEquals(FlashSetting.Off, CameraRules.effectiveFlash(FlashSetting.On, basicCaps, false))
        assertEquals(FlashSetting.Auto, CameraRules.effectiveFlash(FlashSetting.Auto, fullCaps, false))
        assertEquals(FlashSetting.Off, CameraRules.effectiveFlash(FlashSetting.Auto, fullCaps, true))
    }

    @Test
    fun nextCyclesAndStartsOverForUnknownValues() {
        val flash = listOf(FlashSetting.Off, FlashSetting.Auto, FlashSetting.On)
        assertEquals(FlashSetting.Auto, CameraRules.next(flash, FlashSetting.Off))
        assertEquals(FlashSetting.Off, CameraRules.next(flash, FlashSetting.On))
        assertEquals(FlashSetting.Off, CameraRules.next(listOf(FlashSetting.Off), FlashSetting.On))
        assertEquals(FlashSetting.On, CameraRules.next(emptyList(), FlashSetting.On))
        assertEquals(TimerSetting.S3, CameraRules.next(TimerSetting.entries, TimerSetting.Off))
        assertEquals(TimerSetting.Off, CameraRules.next(TimerSetting.entries, TimerSetting.S10))
    }

    @Test
    fun zoomIsClampedToTheCameraRange() {
        assertEquals(0.5f, CameraRules.clampZoom(0.1f, fullCaps), 0.0001f)
        assertEquals(8f, CameraRules.clampZoom(20f, fullCaps), 0.0001f)
        assertEquals(2f, CameraRules.clampZoom(2f, fullCaps), 0.0001f)
    }

    @Test
    fun pinchMultipliesAndIgnoresInvalidScales() {
        assertEquals(3f, CameraRules.pinchZoom(2f, 1.5f, fullCaps), 0.0001f)
        assertEquals(8f, CameraRules.pinchZoom(6f, 2f, fullCaps), 0.0001f)
        assertEquals(2f, CameraRules.pinchZoom(2f, 0f, fullCaps), 0.0001f)
        assertEquals(2f, CameraRules.pinchZoom(2f, Float.NaN, fullCaps), 0.0001f)
        assertEquals(2f, CameraRules.pinchZoom(2f, -1f, fullCaps), 0.0001f)
    }

    @Test
    fun zoomPresetsIncludeUltraWideAndStopsInRange() {
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f), CameraRules.zoomPresets(fullCaps))
        assertEquals(listOf(0.6f, 1f, 2f), CameraRules.zoomPresets(fullCaps.copy(zoomMin = 0.6f, zoomMax = 2.4f)))
        assertEquals(listOf(1f, 2f, 3f), CameraRules.zoomPresets(fullCaps.copy(zoomMin = 1f, zoomMax = 3.9f)))
        assertEquals(emptyList<Float>(), CameraRules.zoomPresets(basicCaps))
    }

    @Test
    fun exposureCompensationNeedsRangeAndStep() {
        assertTrue(fullCaps.hasExposureCompensation)
        assertFalse(basicCaps.hasExposureCompensation)
        assertEquals(12, CameraRules.clampEv(40, fullCaps))
        assertEquals(-12, CameraRules.clampEv(-40, fullCaps))
        assertEquals(0, CameraRules.clampEv(7, basicCaps))
    }

    @Test
    fun exposureTextShowsSignedEv() {
        assertEquals("0 EV", CameraRules.evText(0, 0.5f))
        assertEquals("+1.0 EV", CameraRules.evText(2, 0.5f))
        assertEquals("-1.5 EV", CameraRules.evText(-3, 0.5f))
        assertEquals("0 EV", CameraRules.evText(3, 0f))
        assertEquals("+0.3 EV", CameraRules.evText(2, 1f / 6f))
    }

    @Test
    fun surfaceRotationFollowsTheDeviceTilt() {
        assertEquals(0, CameraRules.surfaceRotation(0))
        assertEquals(0, CameraRules.surfaceRotation(44))
        assertEquals(3, CameraRules.surfaceRotation(45))
        assertEquals(3, CameraRules.surfaceRotation(90))
        assertEquals(2, CameraRules.surfaceRotation(180))
        assertEquals(1, CameraRules.surfaceRotation(270))
        assertEquals(1, CameraRules.surfaceRotation(314))
        assertEquals(0, CameraRules.surfaceRotation(315))
        assertEquals(0, CameraRules.surfaceRotation(359))
    }

    @Test
    fun surfaceRotationIgnoresUnknownTilt() {
        assertEquals(null, CameraRules.surfaceRotation(-1))
        assertEquals(null, CameraRules.surfaceRotation(360))
        assertEquals(null, CameraRules.surfaceRotation(1000))
    }
}
