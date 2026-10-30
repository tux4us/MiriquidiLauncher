// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalCamera2Interop::class)

package io.github.tux4us.miriquidilauncher.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Alle Aufrufe, die von der genauen CameraX-Version oder von Camera2-Interop abhängen, stehen in dieser Datei. Sie
 * wurde ohne Android-SDK geschrieben und ist bis zum ersten Gradle-Build ungeprüft. Sollte eine Version einen
 * Namen anders führen (z. B. die Ausgabeformate für Ultra HDR und RAW, die erst ab CameraX 1.4 bzw. 1.5 vorhanden
 * sind), genügt es, sie hier anzupassen.
 */

/** Wartet auf das Ergebnis eines `ListenableFuture`, ohne zusätzliche Bibliotheken. */
internal suspend fun <T : Any> ListenableFuture<T>.awaitResult(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error.cause ?: error)
                }
            },
            executor,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }

internal object CameraXCompat {

    // --- Kamerakennung und Auswahl -------------------------------------------------------------------------

    fun cameraId(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    fun <T> characteristic(info: CameraInfo, key: CameraCharacteristics.Key<T>): T? =
        Camera2CameraInfo.from(info).getCameraCharacteristic(key)

    /** Wählt genau die Kamera mit dieser Camera2-Kennung. */
    fun selectorFor(id: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { runCatching { cameraId(it) }.getOrNull() == id } }
            .build()

    // --- Fähigkeiten -----------------------------------------------------------------------------------------

    /** Foto-Ausgabeformate, die die Kamera meldet. JPEG gibt es immer. */
    fun supportedPhotoFormats(info: CameraInfo): Set<PhotoFormat> {
        val formats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }
            .getOrDefault(emptySet())
        val result = linkedSetOf(PhotoFormat.Jpeg)
        if (Build.VERSION.SDK_INT >= 34 && ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats) {
            result += PhotoFormat.UltraHdr
        }
        if (ImageCapture.OUTPUT_FORMAT_RAW in formats) result += PhotoFormat.Raw
        return result
    }

    fun applyPhotoFormat(builder: ImageCapture.Builder, format: PhotoFormat) {
        when (format) {
            PhotoFormat.Jpeg -> Unit
            PhotoFormat.UltraHdr -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            PhotoFormat.Raw -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
        }
    }

    fun supportedVideoQualities(info: CameraInfo): List<VideoQualitySetting> {
        val supported = runCatching { QualitySelector.getSupportedQualities(info) }.getOrDefault(emptyList())
        return listOf(
            Quality.UHD to VideoQualitySetting.Uhd,
            Quality.FHD to VideoQualitySetting.Fhd,
            Quality.HD to VideoQualitySetting.Hd,
            Quality.SD to VideoQualitySetting.Sd,
        ).filter { (quality, _) -> quality in supported }.map { it.second }
    }

    fun qualityOf(setting: VideoQualitySetting): Quality = when (setting) {
        VideoQualitySetting.Highest -> Quality.HIGHEST
        VideoQualitySetting.Uhd -> Quality.UHD
        VideoQualitySetting.Fhd -> Quality.FHD
        VideoQualitySetting.Hd -> Quality.HD
        VideoQualitySetting.Sd -> Quality.SD
    }

    /** Bildraten (30 und 60), die in einem der gemeldeten Bereiche liegen. */
    fun supportedFrameRates(info: CameraInfo): Set<Int> {
        val ranges = runCatching { info.supportedFrameRateRanges }.getOrDefault(emptySet())
        return listOf(30, 60).filter { fps -> ranges.any { fps >= it.lower && fps <= it.upper } }.toSet()
    }

    fun stabilizationSupported(info: CameraInfo): Boolean {
        val modes = characteristic(info, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        return modes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
    }

    fun enableStabilization(builder: VideoCapture.Builder<Recorder>) {
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        )
    }

    /** Zoombereich nach den Kameradaten; nach dem Binden kommt der genaue Bereich von CameraX. */
    fun zoomRange(info: CameraInfo): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            characteristic(info, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower to it.upper }
        }
        val digital = characteristic(info, CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to digital
    }

    /** Indexbereich und Schrittweite (EV je Schritt) der Belichtungskorrektur. */
    fun exposureCompensation(info: CameraInfo): Triple<Int, Int, Float> {
        val range = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (range == null || step == null) return Triple(0, 0, 0f)
        return Triple(range.lower, range.upper, step.toFloat())
    }

    fun manualCaps(info: CameraInfo): ManualCaps? {
        val capabilities = characteristic(info, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) return null
        val iso = characteristic(info, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val exposure = characteristic(info, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val focus = characteristic(info, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return ManualCaps(iso.lower, iso.upper, exposure.lower, exposure.upper, focus)
    }

    fun whiteBalances(info: CameraInfo): Set<WhiteBalanceSetting> {
        val modes = characteristic(info, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return setOf(WhiteBalanceSetting.Auto)
        val result = linkedSetOf(WhiteBalanceSetting.Auto)
        for (setting in WhiteBalanceSetting.entries) {
            if (setting != WhiteBalanceSetting.Auto && awbMode(setting) in modes) result += setting
        }
        return result
    }

    private fun awbMode(setting: WhiteBalanceSetting): Int = when (setting) {
        WhiteBalanceSetting.Auto -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WhiteBalanceSetting.Incandescent -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalanceSetting.Fluorescent -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalanceSetting.Daylight -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalanceSetting.Cloudy -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalanceSetting.Shade -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    // --- Manuelle Steuerung ----------------------------------------------------------------------------------

    /** Setzt die manuellen Werte; `null` gibt die Automatik wieder frei. */
    fun applyManual(camera: Camera, options: ManualOptions?) {
        val control = Camera2CameraControl.from(camera.cameraControl)
        if (options == null) {
            control.clearCaptureRequestOptions()
            return
        }
        val builder = CaptureRequestOptions.Builder()
        if (options.autoExposureOff) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, options.iso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, options.exposureNs)
            options.frameDurationNs?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, it) }
        }
        if (options.autoFocusOff) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, options.focusDiopters)
        }
        if (options.whiteBalance != WhiteBalanceSetting.Auto) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode(options.whiteBalance))
        }
        control.setCaptureRequestOptions(builder.build())
    }
}
